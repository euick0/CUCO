package pt.cuco.scanner

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import pt.cuco.scanner.databinding.ActivityScanBinding
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class ScanActivity : AppCompatActivity() {

    private lateinit var binding: ActivityScanBinding
    private lateinit var imageExecutor: ExecutorService
    private var qualityIssues: Set<CucoImagePreprocessor.ImageQualityIssue> = emptySet()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityScanBinding.inflate(layoutInflater)
        setContentView(binding.root)
        imageExecutor = Executors.newSingleThreadExecutor()

        val uri: Uri? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_IMAGE_URI, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_IMAGE_URI)
        }

        if (uri == null) {
            Toast.makeText(this, R.string.toast_no_image, Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        binding.preview.setImageURI(uri)
        runOcr(uri)
    }

    override fun onDestroy() {
        super.onDestroy()
        imageExecutor.shutdown()
    }

    private fun runOcr(uri: Uri) {
        binding.progressLabel.setText(R.string.scan_in_progress)
        imageExecutor.execute {
            val preparation = try {
                CucoImagePreprocessor.prepare(this, uri)
            } catch (e: Exception) {
                Log.w(TAG, "Image preparation failed", e)
                CucoImagePreprocessor.Preparation(
                    variants = emptyList(),
                    issues = setOf(CucoImagePreprocessor.ImageQualityIssue.SCREEN_NOT_FOUND),
                )
            }
            runOnUiThread {
                qualityIssues = preparation.issues
                if (preparation.variants.isEmpty()) {
                    failAndReturn()
                } else {
                    processOcrVariants(preparation.variants)
                }
            }
        }
    }

    private fun processOcrVariants(variants: List<CucoImagePreprocessor.OcrImageVariant>) {
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val attempts = mutableListOf<OcrAttempt>()

        fun processAt(index: Int) {
            if (index >= variants.size) {
                recognizer.close()
                val best = chooseBestAttempt(attempts)
                if (best == null) {
                    failAndReturn()
                } else {
                    Log.d(TAG, "Best OCR result from ${best.source}: ${best.fields}")
                    openWebView(best.fields)
                }
                return
            }

            val variant = variants[index]
            binding.progressLabel.text = getString(
                R.string.scan_in_progress_step,
                index + 1,
                variants.size,
            )
            val image = InputImage.fromBitmap(variant.bitmap, 0)
            recognizer.process(image)
            .addOnSuccessListener { result ->
                Log.d(TAG, "OCR ${variant.name} text:\n${result.text}")
                parseResult(result, variant)?.let { fields ->
                    attempts += OcrAttempt(
                        fields = fields,
                        source = variant.name,
                        score = score(fields, variant),
                    )
                }
            }
            .addOnFailureListener { e ->
                Log.w(TAG, "OCR failed for ${variant.name}", e)
            }
            .addOnCompleteListener {
                variant.bitmap.recycle()
                processAt(index + 1)
            }
        }

        processAt(0)
    }

    private fun parseResult(
        result: Text,
        variant: CucoImagePreprocessor.OcrImageVariant,
    ): CucoOcrParser.CucoFields? {
        val ocrLines = result.textBlocks.flatMap { block ->
            block.lines.map { line ->
                val box = line.boundingBox
                CucoOcrParser.OcrLine(
                    text = line.text,
                    left = box?.left,
                    top = box?.top,
                    right = box?.right,
                    bottom = box?.bottom,
                )
            }
        }
        return when (variant.role) {
            CucoImagePreprocessor.VariantRole.VALUE_ROWS ->
                CucoOcrParser.parseValueRows(ocrLines, result.text)
                    ?: CucoOcrParser.parse(ocrLines, result.text)
            CucoImagePreprocessor.VariantRole.STRUCTURED_TEXT ->
                CucoOcrParser.parse(ocrLines, result.text)
        }
    }

    private fun score(
        fields: CucoOcrParser.CucoFields,
        variant: CucoImagePreprocessor.OcrImageVariant,
    ): Int {
        val roleBonus = when (variant.role) {
            CucoImagePreprocessor.VariantRole.VALUE_ROWS -> 8
            CucoImagePreprocessor.VariantRole.STRUCTURED_TEXT -> 4
        }
        val cropBonus = when {
            "field-band" in variant.name -> 6
            "screen" in variant.name -> 4
            "source" in variant.name -> 1
            else -> 0
        }
        return CucoOcrParser.confidenceScore(fields) + roleBonus + cropBonus
    }

    private fun chooseBestAttempt(attempts: List<OcrAttempt>): OcrAttempt? {
        if (attempts.isEmpty()) return null
        val consensus = consensusAttempt(attempts)
        return (attempts + listOfNotNull(consensus)).maxByOrNull { it.score }
    }

    private fun consensusAttempt(attempts: List<OcrAttempt>): OcrAttempt? {
        val serial = charLevelVote(attempts) { it.fields.serial } ?: return null
        val certified = charLevelVote(attempts) { it.fields.certifiedTime } ?: return null
        val usage = charLevelVote(attempts) { it.fields.usageCounter } ?: return null
        val fields = CucoOcrParser.CucoFields(serial, certified, usage)
        val score = CucoOcrParser.confidenceScore(fields)
        if (score == 0) return null
        return OcrAttempt(fields, source = "consensus", score = score + 12)
    }

    private fun charLevelVote(
        attempts: List<OcrAttempt>,
        selector: (OcrAttempt) -> String,
    ): String? {
        if (attempts.isEmpty()) return null
        val pairs = attempts.map { selector(it) to it.score }
        val majorityLen = pairs.groupingBy { it.first.length }.eachCount()
            .maxByOrNull { it.value }?.key ?: return null
        val matching = pairs.filter { it.first.length == majorityLen }
        if (matching.size < 2) return matching.firstOrNull()?.first
        return buildString(majorityLen) {
            for (i in 0 until majorityLen) {
                val charScores = mutableMapOf<Char, Int>()
                for ((value, score) in matching) {
                    charScores[value[i]] = (charScores[value[i]] ?: 0) + score
                }
                append(charScores.maxByOrNull { it.value }!!.key)
            }
        }
    }

    private fun openWebView(fields: CucoOcrParser.CucoFields) {
        val intent = Intent(this, WebViewActivity::class.java).apply {
            putExtra(WebViewActivity.EXTRA_SERIAL, fields.serial)
            putExtra(WebViewActivity.EXTRA_CTIME, fields.certifiedTime)
            putExtra(WebViewActivity.EXTRA_USAGE, fields.usageCounterTrimmed())
        }
        startActivity(intent)
        finish()
    }

    private fun failAndReturn() {
        val message = when {
            CucoImagePreprocessor.ImageQualityIssue.SCREEN_NOT_FOUND in qualityIssues ||
                CucoImagePreprocessor.ImageQualityIssue.MOVE_CLOSER in qualityIssues ->
                R.string.toast_ocr_failed_screen
            CucoImagePreprocessor.ImageQualityIssue.POSSIBLE_GLARE in qualityIssues ->
                R.string.toast_ocr_failed_glare
            CucoImagePreprocessor.ImageQualityIssue.POSSIBLE_BLUR in qualityIssues ->
                R.string.toast_ocr_failed_blur
            else -> R.string.toast_ocr_failed
        }
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        finish()
    }

    private data class OcrAttempt(
        val fields: CucoOcrParser.CucoFields,
        val source: String,
        val score: Int,
    )

    companion object {
        const val EXTRA_IMAGE_URI = "image_uri"
        private const val TAG = "ScanActivity"
    }
}
