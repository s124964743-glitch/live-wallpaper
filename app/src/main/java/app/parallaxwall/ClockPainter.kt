package app.parallaxwall

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import java.util.Calendar
import java.util.Locale

/**
 * 駭客任務風格時鐘：磷光綠 #00FF41 + 發光，單行「2026/11/11 週三    02：24：56」。
 * 非執行緒安全：每個使用端（主執行緒、GL 執行緒）各自建立一個實例。
 */
class ClockPainter {
    private val face = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)

    private fun paint(style: Paint.Style) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = face
        textAlign = Paint.Align.CENTER
        this.style = style
    }

    private val outline = paint(Paint.Style.STROKE).apply {
        color = 0xB3000000.toInt()
        strokeJoin = Paint.Join.ROUND
    }
    private val fill = paint(Paint.Style.FILL).apply { color = GREEN }

    fun text(): String {
        val cal = Calendar.getInstance()
        val dow = "日一二三四五六"[cal.get(Calendar.DAY_OF_WEEK) - 1]
        return String.format(
            Locale.US, "%04d/%02d/%02d 週%c    %02d：%02d：%02d",
            cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH), dow,
            cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), cal.get(Calendar.SECOND)
        )
    }

    /** 在寬度 [w] 的畫布上，以垂直中心 [cy] 畫出時鐘。 */
    fun draw(c: Canvas, w: Float, cy: Float, scale: Float) {
        val s = text()
        var size = 50f * (w / 1080f) * scale
        fill.textSize = size
        val tw = fill.measureText(s)
        val maxW = w * 0.94f
        if (tw > maxW) {
            size *= maxW / tw
            fill.textSize = size
        }
        outline.textSize = size
        outline.strokeWidth = size * 0.14f

        val x = w / 2f
        val y = cy - (fill.ascent() + fill.descent()) / 2f

        c.drawText(s, x, y, outline)                       // 暗色描邊，亮背景也看得清楚
        fill.setShadowLayer(size * 0.6f, 0f, 0f, GREEN)    // 發光
        c.drawText(s, x, y, fill)
        fill.clearShadowLayer()
        c.drawText(s, x, y, fill)                          // 清晰字芯
    }

    companion object {
        const val GREEN = 0xFF00FF41.toInt()
    }
}
