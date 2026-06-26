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
import kotlin.math.sqrt

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

    private data class ScreenCorners(
        val tlX: Float, val tlY: Float,
        val trX: Float, val trY: Float,
        val blX: Float, val blY: Float,
        val brX: Float, val brY: Float,
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
            val corners = detectScreenCorners(source, screenRect)
            if (corners != null) {
                perspectiveCorrect(source, corners) ?: crop(source, screenRect)
            } else {
                crop(source, screenRect)
            }
        } else {
            cropRelative(source, 0.04f, 0.14f, 0.96f, 0.82f)
        }

        if (screen != null) {
            add("screen", screen, VariantRole.STRUCTURED_TEXT)
            add("screen-threshold", highContrastThreshold(screen), VariantRole.STRUCTURED_TEXT)

            val sharpScreen = unsharpMask(screen)
            add("screen-sharp-threshold", highContrastThreshold(sharpScreen), VariantRole.STRUCTURED_TEXT)

            val bluesup = blueSuppressionGrayscale(screen)
            add("screen-bluesup", bluesup, VariantRole.STRUCTURED_TEXT)
            add("screen-bluesup-threshold", highContrastThreshold(bluesup), VariantRole.STRUCTURED_TEXT)

            // Keep the full right edge: a long serial (or its wrapped second
            // line) can reach the screen border, and cropping it short was
            // truncating the machine serial number mid-code.
            val fieldBand = cropRelative(screen, 0.00f, 0.22f, 0.98f, 0.66f)
            add("field-band", fieldBand, VariantRole.STRUCTURED_TEXT)
            add("field-band-threshold", fieldBand?.let(::highContrastThreshold), VariantRole.STRUCTURED_TEXT)

            val valueColumn = cropRelative(screen, 0.34f, 0.22f, 0.98f, 0.66f)
            add("value-column", valueColumn, VariantRole.VALUE_ROWS)
            add("value-column-threshold", valueColumn?.let(::highContrastThreshold), VariantRole.VALUE_ROWS)
            add("value-column-sharp-threshold", valueColumn?.let { highContrastThreshold(unsharpMask(it)) }, VariantRole.VALUE_ROWS)
            add("value-column-bluesup-threshold", valueColumn?.let { highContrastThreshold(blueSuppressionGrayscale(it)) }, VariantRole.VALUE_ROWS)
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

    private fun detectScreenCorners(bitmap: Bitmap, rect: Rect): ScreenCorners? {
        val bandSize = max(5, rect.height() / 15)
        val step = max(1, rect.width() / 300)

        fun scanBand(yStart: Int, yEnd: Int): Pair<Float, Float>? {
            var sumLeft = 0f
            var sumRight = 0f
            var count = 0
            for (y in yStart until min(yEnd, bitmap.height)) {
                if (y < 0) continue
                var rowLeft = -1
                var rowRight = -1
                var x = max(0, rect.left)
                while (x < min(rect.right, bitmap.width)) {
                    if (isCucoBlue(bitmap.getPixel(x, y))) {
                        if (rowLeft < 0) rowLeft = x
                        rowRight = x
                    }
                    x += step
                }
                if (rowLeft >= 0) {
                    sumLeft += rowLeft
                    sumRight += rowRight
                    count++
                }
            }
            if (count == 0) return null
            return (sumLeft / count) to (sumRight / count)
        }

        val (topL, topR) = scanBand(rect.top, rect.top + bandSize) ?: return null
        val (botL, botR) = scanBand(rect.bottom - bandSize, rect.bottom) ?: return null

        val leftSkew = abs(topL - botL)
        val rightSkew = abs(topR - botR)
        if (leftSkew < rect.width() * 0.02f && rightSkew < rect.width() * 0.02f) return null

        return ScreenCorners(
            tlX = topL, tlY = rect.top.toFloat(),
            trX = topR, trY = rect.top.toFloat(),
            blX = botL, blY = rect.bottom.toFloat(),
            brX = botR, brY = rect.bottom.toFloat(),
        )
    }

    private fun perspectiveCorrect(bitmap: Bitmap, corners: ScreenCorners): Bitmap? {
        val topWidth = dist(corners.tlX, corners.tlY, corners.trX, corners.trY)
        val bottomWidth = dist(corners.blX, corners.blY, corners.brX, corners.brY)
        val leftHeight = dist(corners.tlX, corners.tlY, corners.blX, corners.blY)
        val rightHeight = dist(corners.trX, corners.trY, corners.brX, corners.brY)
        val outW = max(topWidth, bottomWidth).roundToInt()
        val outH = max(leftHeight, rightHeight).roundToInt()
        if (outW < MIN_OCR_SIDE || outH < MIN_OCR_SIDE) return null

        val srcPixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(srcPixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val outPixels = IntArray(outW * outH)
        val maxU = max(1, outW - 1).toFloat()
        val maxV = max(1, outH - 1).toFloat()

        for (dy in 0 until outH) {
            val v = dy / maxV
            val v1 = 1f - v
            for (dx in 0 until outW) {
                val u = dx / maxU
                val u1 = 1f - u
                val srcX = (v1 * (u1 * corners.tlX + u * corners.trX) +
                    v * (u1 * corners.blX + u * corners.brX)).roundToInt()
                val srcY = (v1 * (u1 * corners.tlY + u * corners.trY) +
                    v * (u1 * corners.blY + u * corners.brY)).roundToInt()
                if (srcX in 0 until bitmap.width && srcY in 0 until bitmap.height) {
                    outPixels[dy * outW + dx] = srcPixels[srcY * bitmap.width + srcX]
                }
            }
        }

        return Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888).apply {
            setPixels(outPixels, 0, outW, 0, 0, outW, outH)
        }
    }

    private fun dist(x1: Float, y1: Float, x2: Float, y2: Float): Float =
        sqrt((x2 - x1) * (x2 - x1) + (y2 - y1) * (y2 - y1))

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

    private fun unsharpMask(bitmap: Bitmap, strength: Float = 0.6f): Bitmap {
        val w = bitmap.width
        val h = bitmap.height
        val src = IntArray(w * h)
        bitmap.getPixels(src, 0, w, 0, 0, w, h)
        val lum = IntArray(src.size) { luminance(src[it]) }
        val out = IntArray(src.size)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val idx = y * w + x
                val center = lum[idx]
                val left = if (x > 0) lum[idx - 1] else center
                val right = if (x < w - 1) lum[idx + 1] else center
                val above = if (y > 0) lum[idx - w] else center
                val below = if (y < h - 1) lum[idx + w] else center
                val neighbours = (left + right + above + below) / 4
                val sharp = (center + (center - neighbours) * strength)
                    .roundToInt().coerceIn(0, 255)
                out[idx] = Color.rgb(sharp, sharp, sharp)
            }
        }
        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply {
            setPixels(out, 0, w, 0, 0, w, h)
        }
    }

    private fun blueSuppressionGrayscale(bitmap: Bitmap): Bitmap {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        val out = IntArray(pixels.size)
        for (i in pixels.indices) {
            val r = Color.red(pixels[i])
            val g = Color.green(pixels[i])
            val gray = (r * 0.3f + g * 0.7f).roundToInt().coerceIn(0, 255)
            out[i] = Color.rgb(gray, gray, gray)
        }
        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply {
            setPixels(out, 0, w, 0, 0, w, h)
        }
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
