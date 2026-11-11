package app.parallaxwall

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import java.util.Calendar

/**
 * 《鐘點戰》手臂時鐘風格：螢光綠的分段式數字，多層半透明描邊疊出光暈。
 * 格式：2026 - 11 - 11 - 三 - 10 - 15 - 26（年 - 月 - 日 - 星期 - 時 - 分 - 秒）。
 *
 * 光暈不用 setShadowLayer / BlurMaskFilter：硬體加速畫布（lockHardwareCanvas）
 * 不支援非文字的陰影，所以改用由寬到窄的多層描邊，軟硬體畫布結果一致。
 * 非執行緒安全：每個使用端（主執行緒、GL 執行緒）各自建立一個實例。
 */
class ClockPainter {

    private class Layer(val widthMul: Float, val color: Int)

    private val layers = listOf(
        Layer(5.0f, 0x1039FF14),
        Layer(3.4f, 0x2039FF14),
        Layer(2.3f, 0x4239FF14),
        Layer(1.5f, 0x8839FF14.toInt()),
        Layer(1.0f, 0xFF4CFF3A.toInt()),
        Layer(0.42f, 0xFFE6FFE0.toInt()),   // 發白的亮芯
    )

    private val segPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val glyphPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL_AND_STROKE
        strokeJoin = Paint.Join.ROUND
        typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        textAlign = Paint.Align.CENTER
    }
    private val path = Path()

    // 7 段：a 上、b 右上、c 右下、d 下、e 左下、f 左上、g 中
    private val digitSegs = intArrayOf(
        0b0111111, // 0  abcdef
        0b0000110, // 1  bc
        0b1011011, // 2  abdeg
        0b1001111, // 3  abcdg
        0b1100110, // 4  bcfg
        0b1101101, // 5  acdfg
        0b1111101, // 6  acdefg
        0b0000111, // 7  abc
        0b1111111, // 8
        0b1101111, // 9  abcdfg
    )

    /** 在寬度 [w] 的畫布上，以垂直中心 [cy] 畫出時鐘。 */
    fun draw(c: Canvas, w: Float, cy: Float, scale: Float) {
        val cal = Calendar.getInstance()
        val dow = "日一二三四五六"[cal.get(Calendar.DAY_OF_WEEK) - 1].toString()
        val groups = listOf(
            "%04d".format(cal.get(Calendar.YEAR)),
            "%02d".format(cal.get(Calendar.MONTH) + 1),
            "%02d".format(cal.get(Calendar.DAY_OF_MONTH)),
            dow,
            "%02d".format(cal.get(Calendar.HOUR_OF_DAY)),
            "%02d".format(cal.get(Calendar.MINUTE)),
            "%02d".format(cal.get(Calendar.SECOND)),
        )

        // 以「數字寬 d」為單位排版
        val gapIn = 0.34f      // 同組數字之間
        val dashW = 0.50f      // 分隔短橫線長度
        val gapDash = 0.62f    // 短橫線兩側留白
        val weekW = 1.15f      // 星期字寬
        var units = 0f
        for ((i, g) in groups.withIndex()) {
            units += if (i == 3) weekW else g.length + gapIn * (g.length - 1)
            if (i < groups.size - 1) units += gapDash * 2 + dashW
        }

        var d = 38.5f * (w / 1080f) * scale
        val maxW = w * 0.94f
        if (d * units > maxW) d = maxW / units
        val h = d * 1.9f
        val t = d * 0.17f
        val top = cy - h / 2f
        var x = (w - d * units) / 2f

        // 一次建好所有分段的路徑，再用各光暈層重複描邊
        path.reset()
        val weekX = floatArrayOf(0f)
        for ((i, g) in groups.withIndex()) {
            if (i == 3) {
                weekX[0] = x + d * weekW / 2f
                x += d * weekW
            } else {
                for ((k, ch) in g.withIndex()) {
                    addDigit(path, ch - '0', x, top, d, h, t)
                    x += d + if (k < g.length - 1) d * gapIn else 0f
                }
            }
            if (i < groups.size - 1) {
                x += d * gapDash
                path.moveTo(x, top + h / 2f)
                path.lineTo(x + d * dashW, top + h / 2f)
                x += d * dashW + d * gapDash
            }
        }

        glyphPaint.textSize = h * 0.95f
        val baseY = cy - (glyphPaint.ascent() + glyphPaint.descent()) / 2f

        for (l in layers) {
            segPaint.color = l.color
            segPaint.strokeWidth = t * l.widthMul
            c.drawPath(path, segPaint)

            glyphPaint.color = l.color
            glyphPaint.strokeWidth = t * l.widthMul * 0.55f
            c.drawText(dow, weekX[0], baseY, glyphPaint)
        }
    }

    private fun addDigit(p: Path, n: Int, x0: Float, y0: Float, d: Float, h: Float, t: Float) {
        val m = digitSegs[n.coerceIn(0, 9)]
        val xl = x0 + t / 2f
        val xr = x0 + d - t / 2f
        val yt = y0 + t / 2f
        val ym = y0 + h / 2f
        val yb = y0 + h - t / 2f
        val g = t * 0.55f // 段與段之間的縫

        if (m and 1 != 0) { p.moveTo(xl + g, yt); p.lineTo(xr - g, yt) }          // a
        if (m and 2 != 0) { p.moveTo(xr, yt + g); p.lineTo(xr, ym - g) }          // b
        if (m and 4 != 0) { p.moveTo(xr, ym + g); p.lineTo(xr, yb - g) }          // c
        if (m and 8 != 0) { p.moveTo(xl + g, yb); p.lineTo(xr - g, yb) }          // d
        if (m and 16 != 0) { p.moveTo(xl, ym + g); p.lineTo(xl, yb - g) }         // e
        if (m and 32 != 0) { p.moveTo(xl, yt + g); p.lineTo(xl, ym - g) }         // f
        if (m and 64 != 0) { p.moveTo(xl + g, ym); p.lineTo(xr - g, ym) }         // g
    }
}
