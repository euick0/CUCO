package pt.cuco.scanner

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.min

class CucoCameraGuideView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private val shadePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(120, 0, 0, 0)
        style = Paint.Style.FILL
    }
    private val framePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = resources.displayMetrics.density * 2.5f
    }
    private val rowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(210, 255, 255, 255)
        style = Paint.Style.STROKE
        strokeWidth = resources.displayMetrics.density
        pathEffect = android.graphics.DashPathEffect(floatArrayOf(18f, 14f), 0f)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val frame = guideFrame()
        canvas.drawRect(0f, 0f, width.toFloat(), frame.top, shadePaint)
        canvas.drawRect(0f, frame.bottom, width.toFloat(), height.toFloat(), shadePaint)
        canvas.drawRect(0f, frame.top, frame.left, frame.bottom, shadePaint)
        canvas.drawRect(frame.right, frame.top, width.toFloat(), frame.bottom, shadePaint)
        canvas.drawRoundRect(frame, CORNER_RADIUS, CORNER_RADIUS, framePaint)

        val rowStart = frame.top + frame.height() * 0.34f
        val rowGap = frame.height() * 0.08f
        repeat(3) { index ->
            val y = rowStart + rowGap * index
            canvas.drawLine(frame.left + 28f, y, frame.right - 28f, y, rowPaint)
        }
    }

    private fun guideFrame(): RectF {
        val horizontalMargin = width * 0.045f
        val frameWidth = width - horizontalMargin * 2
        val frameHeight = min(frameWidth * 0.55f, height * 0.58f)
        val top = height * 0.20f
        return RectF(
            horizontalMargin,
            top,
            horizontalMargin + frameWidth,
            top + frameHeight,
        )
    }

    companion object {
        private const val CORNER_RADIUS = 18f
    }
}
