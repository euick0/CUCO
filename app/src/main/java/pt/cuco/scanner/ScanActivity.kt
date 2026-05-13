package pt.cuco.scanner

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import pt.cuco.scanner.databinding.ActivityScanBinding

class ScanActivity : AppCompatActivity() {

    private lateinit var binding: ActivityScanBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityScanBinding.inflate(layoutInflater)
        setContentView(binding.root)

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

    private fun runOcr(uri: Uri) {
        val image = try {
            InputImage.fromFilePath(this, uri)
        } catch (e: Exception) {
            Log.w(TAG, "InputImage.fromFilePath failed", e)
            failAndReturn(R.string.toast_no_image)
            return
        }

        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        recognizer.process(image)
            .addOnSuccessListener { result ->
                val text = result.text.orEmpty()
                Log.d(TAG, "OCR text:\n$text")

                if (text.length < 60 || result.textBlocks.size < 3) {
                    failAndReturn(R.string.toast_bad_photo)
                    return@addOnSuccessListener
                }

                if (!CucoOcrParser.looksLikeCucoScreen(text)) {
                    failAndReturn(R.string.toast_not_cuco_screen)
                    return@addOnSuccessListener
                }

                val parsed = CucoOcrParser.parse(text)
                if (parsed == null) {
                    failAndReturn(R.string.toast_ocr_failed)
                } else {
                    openWebView(parsed)
                }
            }
            .addOnFailureListener { e ->
                Log.w(TAG, "OCR failed", e)
                failAndReturn(R.string.toast_bad_photo)
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

    private fun failAndReturn(messageRes: Int) {
        Toast.makeText(this, messageRes, Toast.LENGTH_LONG).show()
        finish()
    }

    companion object {
        const val EXTRA_IMAGE_URI = "image_uri"
        private const val TAG = "ScanActivity"
    }
}
