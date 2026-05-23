package pt.cuco.scanner

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Rect
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

object CucoImagePreprocessor {

    enum class VariantRole {
        STRUCTURED_TEXT,
        VALUE_ROWS,
    }

    enum class ImageQualityIssue {
        SCREEN_NOT_FOUND,
        MOVE_CLOSER,
        POSSIBLE_GLARE,
        POSSIBLE_BLUR,
    }

    data class OcrImageVariant(
        val name: String,
        val bitmap: Bitmap,
        val role: VariantRole,
    )

    data class Preparation(
        val variants: List<OcrImageVariant>,
        val issues: Set<ImageQualityIssue>,
    )

    fun prepare(context: Context, uri: Uri): Preparation {
        val source = decodeOrientedBitmap(context, uri) ?: return Preparation(
            variants = emptyList(),
            issues = setOf(ImageQualityIssue.SCREEN_NOT_FOUND),
        )
        val screenRect = detectBlueScreenBounds(source)
        val issues = assessQuality(source, screenRect)
        val variants = mutableListOf<OcrImageVariant>()

        fun add(name: String, bitmap: Bitmap?, role: VariantRole) {
            if (bitmap == null || bitmap.width < MIN_OCR_SIDE || bitmap.height < MIN_OCR_SIDE) return
            variants += OcrImageVariant(name, scaleForOcr(bitmap), role)
        }

        add("source", source, VariantRole.STRUCTURED_TEXT)
        add("source-contrast", contrastGrayscale(source), VariantRole.STRUCTURED_TEXT)

        val screen = if (screenRect != null) {
            crop(source, screenRect)
        } else {
            cropRelative(source, 0.04f, 0.14f, 0.96f, 0.82f)
        }

        if (screen != null) {
            add("screen", screen, VariantRole.STRUCTURED_TEXT)
            add("screen-threshold", highContrastThreshold(screen), VariantRole.STRUCTURED_TEXT)

            val fieldBand = cropRelative(screen, 0.00f, 0.24f, 0.92f, 0.60f)
            add("field-band", fieldBand, VariantRole.STRUCTURED_TEXT)
            add("field-band-threshold", fieldBand?.let(::highContrastThreshold), VariantRole.STRUCTURED_TEXT)

            val valueColumn = cropRelative(screen, 0.38f, 0.24f, 0.88f, 0.60f)
            add("value-column", valueColumn, VariantRole.VALUE_ROWS)
            add("value-column-threshold", valueColumn?.let(::highContrastThreshold), VariantRole.VALUE_ROWS)
        }

        return Preparation(variants = variants, issues = issues)
    }

    private fun decodeOrientedBitmap(context: Context, uri: Uri): Bitmap? {
        val boundsOptions = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, boundsOptions)
        }
        if (boundsOptions.outWidth <= 0 || boundsOptions.outHeight <= 0) return null

        val decodeOptions = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inSampleSize = calculateInSampleSize(boundsOptions.outWidth, boundsOptions.outHeight)
        }
        val decoded = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, decodeOptions)
        } ?: return null

        val rotation = readExifRotation(context, uri)
        if (rotation == 0) return decoded

        val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
        return Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true).also {
            if (it !== decoded) decoded.recycle()
        }
    }

    private fun readExifRotation(context: Context, uri: Uri): Int =
        try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                when (
                    ExifInterface(stream).getAttributeInt(
                        ExifInterface.TAG_ORIENTATION,
                        ExifInterface.ORIENTATION_NORMAL,
                    )
                ) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270
                    else -> 0
                }
            } ?: 0
        } catch (_: Exception) {
            0
        }

    private fun calculateInSampleSize(width: Int, height: Int): Int {
        var sampleSize = 1
        var sampledWidth = width
        var sampledHeight = height
        while (max(sampledWidth, sampledHeight) > MAX_DECODE_SIDE) {
            sampleSize *= 2
            sampledWidth /= 2
            sampledHeight /= 2
        }
        return sampleSize
    }

    private fun detectBlueScreenBounds(bitmap: Bitmap): Rect? {
        val step = max(2, max(bitmap.width, bitmap.height) / 700)
        var minX = bitmap.width
        var minY = bitmap.height
        var maxX = 0
        var maxY = 0
        var blueCount = 0
        var total = 0

        var y = 0
        while (y < bitmap.height) {
            var x = 0
            while (x < bitmap.width) {
                total += 1
                val color = bitmap.getPixel(x, y)
                if (isCucoBlue(color)) {
                    blueCount += 1
                    minX = min(minX, x)
                    minY = min(minY, y)
                    maxX = max(maxX, x)
                    maxY = max(maxY, y)
                }
                x += step
            }
            y += step
        }

        if (total == 0 || blueCount < total * MIN_BLUE_SCREEN_RATIO) return null
        val padX = (bitmap.width * 0.015f).roundToInt()
        val padY = (bitmap.height * 0.025f).roundToInt()
        val rect = Rect(
            (minX - padX).coerceAtLeast(0),
            (minY - padY).coerceAtLeast(0),
            (maxX + padX).coerceAtMost(bitmap.width),
            (maxY + padY).coerceAtMost(bitmap.height),
        )
        if (rect.width() < bitmap.width * 0.35f || rect.height() < bitmap.height * 0.25f) return null
        return rect
    }

    private fun isCucoBlue(color: Int): Boolean {
        val red = Color.red(color)
        val green = Color.green(color)
        val blue = Color.blue(color)
        return blue > 70 &&
            green > 25 &&
            blue > red * 1.35f &&
            blue > green * 1.08f &&
            red < 150
    }

    private fun assessQuality(bitmap: Bitmap, screenRect: Rect?): Set<ImageQualityIssue> {
        val issues = mutableSetOf<ImageQualityIssue>()
        if (screenRect == null) {
            issues += ImageQualityIssue.SCREEN_NOT_FOUND
        } else {
            if (screenRect.width() < bitmap.width * 0.58f || screenRect.height() < bitmap.height * 0.42f) {
                issues += ImageQualityIssue.MOVE_CLOSER
            }
            if (estimateGlareRatio(bitmap, screenRect) > MAX_GLARE_RATIO) {
                issues += ImageQualityIssue.POSSIBLE_GLARE
            }
        }
        if (estimateSharpness(bitmap) < MIN_SHARPNESS_SCORE) {
            issues += ImageQualityIssue.POSSIBLE_BLUR
        }
        return issues
    }

    private fun estimateGlareRatio(bitmap: Bitmap, rect: Rect): Float {
        val step = max(3, max(rect.width(), rect.height()) / 250)
        var brightLowSaturation = 0
        var total = 0
        var y = rect.top
        while (y < rect.bottom) {
            var x = rect.left
            while (x < rect.right) {
                total += 1
                val color = bitmap.getPixel(x, y)
                val red = Color.red(color)
                val green = Color.green(color)
                val blue = Color.blue(color)
                val brightness = max(red, max(green, blue))
                val saturation = brightness - min(red, min(green, blue))
                if (brightness > 210 && saturation < 65) brightLowSaturation += 1
                x += step
            }
            y += step
        }
        return if (total == 0) 0f else brightLowSaturation.toFloat() / total
    }

    private fun estimateSharpness(bitmap: Bitmap): Float {
        val step = max(4, max(bitmap.width, bitmap.height) / 350)
        var totalDelta = 0L
        var total = 0
        var y = 0
        while (y < bitmap.height - step) {
            var x = 0
            while (x < bitmap.width - step) {
                val current = luminance(bitmap.getPixel(x, y))
                val right = luminance(bitmap.getPixel(x + step, y))
                val bottom = luminance(bitmap.getPixel(x, y + step))
                totalDelta += abs(current - right) + abs(current - bottom)
                total += 2
                x += step
            }
            y += step
        }
        return if (total == 0) 0f else totalDelta.toFloat() / total
    }

    private fun crop(bitmap: Bitmap, rect: Rect): Bitmap? {
        if (rect.width() < MIN_OCR_SIDE || rect.height() < MIN_OCR_SIDE) return null
        return Bitmap.createBitmap(bitmap, rect.left, rect.top, rect.width(), rect.height())
    }

    private fun cropRelative(
        bitmap: Bitmap,
        leftFraction: Float,
        topFraction: Float,
        rightFraction: Float,
        bottomFraction: Float,
    ): Bitmap? {
        val left = (bitmap.width * leftFraction).roundToInt().coerceIn(0, bitmap.width - 1)
        val top = (bitmap.height * topFraction).roundToInt().coerceIn(0, bitmap.height - 1)
        val right = (bitmap.width * rightFraction).roundToInt().coerceIn(left + 1, bitmap.width)
        val bottom = (bitmap.height * bottomFraction).roundToInt().coerceIn(top + 1, bitmap.height)
        return crop(bitmap, Rect(left, top, right, bottom))
    }

    private fun scaleForOcr(bitmap: Bitmap): Bitmap {
        val largestSide = max(bitmap.width, bitmap.height)
        val scale = when {
            largestSide > MAX_OCR_SIDE -> MAX_OCR_SIDE.toFloat() / largestSide
            largestSide < MIN_TARGET_OCR_SIDE -> MIN_TARGET_OCR_SIDE.toFloat() / largestSide
            else -> 1f
        }
        if (scale == 1f) return bitmap
        val width = max(1, (bitmap.width * scale).roundToInt())
        val height = max(1, (bitmap.height * scale).roundToInt())
        return Bitmap.createScaledBitmap(bitmap, width, height, true)
    }

    private fun contrastGrayscale(bitmap: Bitmap): Bitmap {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val histogram = IntArray(256)
        pixels.forEach { histogram[luminance(it)] += 1 }
        val low = percentile(histogram, pixels.size, 0.03f)
        val high = percentile(histogram, pixels.size, 0.985f).coerceAtLeast(low + 12)
        val output = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        val stretched = IntArray(pixels.size)
        for (i in pixels.indices) {
            val gray = stretch(luminance(pixels[i]), low, high)
            stretched[i] = Color.rgb(gray, gray, gray)
        }
        output.setPixels(stretched, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return output
    }

    private fun highContrastThreshold(bitmap: Bitmap): Bitmap {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val histogram = IntArray(256)
        val luminanceValues = IntArray(pixels.size)
        for (i in pixels.indices) {
            val lum = luminance(pixels[i])
            luminanceValues[i] = lum
            histogram[lum] += 1
        }
        val threshold = otsuThreshold(histogram, pixels.size)
        val outputPixels = IntArray(pixels.size)
        for (i in luminanceValues.indices) {
            val gray = if (luminanceValues[i] > threshold) 0 else 255
            outputPixels[i] = Color.rgb(gray, gray, gray)
        }
        return Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888).apply {
            setPixels(outputPixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        }
    }

    private fun luminance(color: Int): Int {
        val red = Color.red(color)
        val green = Color.green(color)
        val blue = Color.blue(color)
        return (red * 0.2126f + green * 0.7152f + blue * 0.0722f).roundToInt().coerceIn(0, 255)
    }

    private fun stretch(value: Int, low: Int, high: Int): Int =
        (((value - low).coerceAtLeast(0)) * 255f / (high - low).coerceAtLeast(1))
            .roundToInt()
            .coerceIn(0, 255)

    private fun percentile(histogram: IntArray, total: Int, percentile: Float): Int {
        val target = (total * percentile).roundToInt()
        var seen = 0
        for (i in histogram.indices) {
            seen += histogram[i]
            if (seen >= target) return i
        }
        return histogram.lastIndex
    }

    private fun otsuThreshold(histogram: IntArray, total: Int): Int {
        var sum = 0L
        for (i in histogram.indices) sum += i * histogram[i].toLong()

        var backgroundWeight = 0
        var backgroundSum = 0L
        var bestVariance = 0.0
        var threshold = 128

        for (i in histogram.indices) {
            backgroundWeight += histogram[i]
            if (backgroundWeight == 0) continue
            val foregroundWeight = total - backgroundWeight
            if (foregroundWeight == 0) break

            backgroundSum += i * histogram[i].toLong()
            val backgroundMean = backgroundSum.toDouble() / backgroundWeight
            val foregroundMean = (sum - backgroundSum).toDouble() / foregroundWeight
            val variance = backgroundWeight.toDouble() *
                foregroundWeight.toDouble() *
                (backgroundMean - foregroundMean) *
                (backgroundMean - foregroundMean)

            if (variance > bestVariance) {
                bestVariance = variance
                threshold = i
            }
        }
        return threshold
    }

    private const val MAX_DECODE_SIDE = 2400
    private const val MAX_OCR_SIDE = 2200
    private const val MIN_TARGET_OCR_SIDE = 1300
    private const val MIN_OCR_SIDE = 80
    private const val MIN_BLUE_SCREEN_RATIO = 0.08f
    private const val MAX_GLARE_RATIO = 0.08f
    private const val MIN_SHARPNESS_SCORE = 9.0f
}
