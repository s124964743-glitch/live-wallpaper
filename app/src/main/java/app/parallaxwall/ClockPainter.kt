package app.parallaxwall

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import java.util.Calendar

/**
 * 《鐘點戰》手臂時鐘風格：螢光綠的分段式數字，多層半透明描邊疊出光暈。
 * 格式：2026 • 11 • 11 • 三 • 10 • 15 • 26（年 • 月 • 日 • 星期 • 時 • 分 • 秒）。
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
    private val path = Path()
    private val dotPath = Path()

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
        val dashW = 0.30f      // 分隔圓點「•」所佔寬度
        val gapDash = 0.50f    // 圓點兩側各留一個半形空格的寬度
        val weekW = 1.0f       // 星期字寬：與數字同寬，並用同樣的線條畫，風格才一致
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
        dotPath.reset()
        for ((i, g) in groups.withIndex()) {
            if (i == 3) {
                addWeek(path, dow[0], x, top, d, h, t)
                x += d * weekW
            } else {
                for ((k, ch) in g.withIndex()) {
                    addDigit(path, ch - '0', x, top, d, h, t)
                    x += d + if (k < g.length - 1) d * gapIn else 0f
                }
            }
            if (i < groups.size - 1) {
                x += d * gapDash
                // 圓點 = 長度幾乎為零、圓頭的線
                val dotX = x + d * dashW / 2f
                dotPath.moveTo(dotX, top + h / 2f)
                dotPath.lineTo(dotX + 0.01f, top + h / 2f)
                x += d * dashW + d * gapDash
            }
        }

        for (l in layers) {
            segPaint.color = l.color
            segPaint.strokeWidth = t * l.widthMul
            c.drawPath(path, segPaint)

            // 圓點比數字線條粗一點，光暈範圍與數字相近
            segPaint.strokeWidth = t * (l.widthMul + 0.7f)
            c.drawPath(dotPath, segPaint)
        }
    }

    /** 日一二三四五六：用和數字相同的直線／斜線拼出，佔一個數字格。 */
    private fun addWeek(p: Path, ch: Char, x0: Float, y0: Float, d: Float, h: Float, t: Float) {
        val xl = x0 + t / 2f
        val w = d - t
        val yt = y0 + t / 2f
        val hh = h - t

        // 水平線：高度比例 y，左右範圍比例 a..b
        fun hl(y: Float, a: Float, b: Float) {
            p.moveTo(xl + w * a, yt + hh * y)
            p.lineTo(xl + w * b, yt + hh * y)
        }
        // 垂直線：水平位置比例 x，上下範圍比例 a..b
        fun vl(x: Float, a: Float, b: Float) {
            p.moveTo(xl + w * x, yt + hh * a)
            p.lineTo(xl + w * x, yt + hh * b)
        }

        when (ch) {
            '一' -> hl(0.5f, 0f, 1f)
            '二' -> {
                hl(0.30f, 0.15f, 0.85f)
                hl(0.72f, 0f, 1f)
            }
            '三' -> {
                hl(0.10f, 0.15f, 0.85f)
                hl(0.50f, 0.28f, 0.72f)
                hl(0.90f, 0f, 1f)
            }
            '四' -> {
                hl(0.06f, 0f, 1f)
                hl(0.94f, 0f, 1f)
                vl(0f, 0.06f, 0.94f)
                vl(1f, 0.06f, 0.94f)
                vl(0.34f, 0.40f, 0.94f)
                vl(0.66f, 0.40f, 0.94f)
            }
            '五' -> {
                hl(0.06f, 0.05f, 0.95f)
                vl(0.38f, 0.06f, 0.94f)
                hl(0.50f, 0.38f, 0.85f)
                vl(0.85f, 0.50f, 0.94f)
                hl(0.94f, 0f, 1f)
            }
            '六' -> {
                vl(0.5f, 0f, 0.16f)
                hl(0.34f, 0f, 1f)
                p.moveTo(xl + w * 0.45f, yt + hh * 0.34f)
                p.lineTo(xl + w * 0.05f, yt + hh)
                p.moveTo(xl + w * 0.55f, yt + hh * 0.34f)
                p.lineTo(xl + w * 0.95f, yt + hh)
            }
            else -> { // 日
                hl(0.06f, 0.1f, 0.9f)
                hl(0.50f, 0.1f, 0.9f)
                hl(0.94f, 0.1f, 0.9f)
                vl(0.1f, 0.06f, 0.94f)
                vl(0.9f, 0.06f, 0.94f)
            }
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

        if (n == 1) {
            // 「1」置中成一條直線，不然會貼在格子右側，讓前後間距看起來不一致
            p.moveTo(x0 + d / 2f, yt)
            p.lineTo(x0 + d / 2f, yb)
            return
        }

        if (m and 1 != 0) { p.moveTo(xl + g, yt); p.lineTo(xr - g, yt) }          // a
        if (m and 2 != 0) { p.moveTo(xr, yt + g); p.lineTo(xr, ym - g) }          // b
        if (m and 4 != 0) { p.moveTo(xr, ym + g); p.lineTo(xr, yb - g) }          // c
        if (m and 8 != 0) { p.moveTo(xl + g, yb); p.lineTo(xr - g, yb) }          // d
        if (m and 16 != 0) { p.moveTo(xl, ym + g); p.lineTo(xl, yb - g) }         // e
        if (m and 32 != 0) { p.moveTo(xl, yt + g); p.lineTo(xl, ym - g) }         // f
        if (m and 64 != 0) { p.moveTo(xl + g, ym); p.lineTo(xr - g, ym) }         // g
    }
}
