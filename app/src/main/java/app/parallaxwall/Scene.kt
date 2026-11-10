package app.parallaxwall

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import java.io.File
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** 一張靜態圖準備好的繪製資料：裁切後的圖、每個網格頂點的深度、光暈圖層。 */
class SceneData(val bmp: Bitmap, val depth: FloatArray, val glow: Bitmap) {
    fun recycle() {
        if (!bmp.isRecycled) bmp.recycle()
        if (!glow.isRecycled) glow.recycle()
    }
}

object Scene {
    /** 四周預留的放大邊界（相對螢幕寬/高），讓視差位移時不會露出黑邊。 */
    const val MARGIN = 0.07f
    const val COLS = 24
    const val ROWS = 48

    fun build(file: File, surfW: Int, surfH: Int, fx: Float, fy: Float): SceneData {
        val rw = max(2, (surfW * (1f + 2f * MARGIN)).roundToInt())
        val rh = max(2, (surfH * (1f + 2f * MARGIN)).roundToInt())

        // 1) 直接解碼成接近目標尺寸，避免大圖吃記憶體
        val decoded = ImageDecoder.decodeBitmap(
            ImageDecoder.createSource(file),
            ImageDecoder.OnHeaderDecodedListener { dec, info, _ ->
                val sw0 = info.size.width
                val sh0 = info.size.height
                dec.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val sc = max(rw.toFloat() / sw0, rh.toFloat() / sh0)
                if (sc < 1f) {
                    dec.setTargetSize(
                        max(1, (sw0 * sc).roundToInt()),
                        max(1, (sh0 * sc).roundToInt())
                    )
                }
            }
        )

        // 2) 依焦點裁成與目標相同的長寬比（比例不符時 = 置中填滿裁切）
        val a = rw.toFloat() / rh
        val bw = decoded.width
        val bh = decoded.height
        val cw: Int
        val ch: Int
        if (bw.toFloat() / bh > a) {
            ch = bh
            cw = (bh * a).roundToInt().coerceIn(1, bw)
        } else {
            cw = bw
            ch = (bw / a).roundToInt().coerceIn(1, bh)
        }
        val cx = ((bw - cw) * fx.coerceIn(0f, 1f)).roundToInt()
        val cy = ((bh - ch) * fy.coerceIn(0f, 1f)).roundToInt()
        var crop = if (cw == bw && ch == bh) {
            decoded
        } else {
            val c = Bitmap.createBitmap(decoded, cx, cy, cw, ch)
            if (c !== decoded) decoded.recycle()
            c
        }
        if (crop.config != Bitmap.Config.ARGB_8888) {
            val c = crop.copy(Bitmap.Config.ARGB_8888, false)
            crop.recycle()
            crop = c
        }

        // 3) 縮小分析：亮度 -> 偽深度圖、光暈
        val sw = 120
        val sh = max(2, (sw.toFloat() * crop.height / crop.width).roundToInt())
        val small = Bitmap.createScaledBitmap(crop, sw, sh, true)
        val px = IntArray(sw * sh)
        small.getPixels(px, 0, sw, 0, 0, sw, sh)
        if (small !== crop) small.recycle()

        val n = sw * sh
        val rr = FloatArray(n)
        val gg = FloatArray(n)
        val bb = FloatArray(n)
        val luma = FloatArray(n)
        for (i in 0 until n) {
            val c = px[i]
            rr[i] = ((c shr 16) and 255) / 255f
            gg[i] = ((c shr 8) and 255) / 255f
            bb[i] = (c and 255) / 255f
            luma[i] = 0.299f * rr[i] + 0.587f * gg[i] + 0.114f * bb[i]
        }

        // 偽深度：越亮、越靠近畫面中心 → 越「近」
        val lb = boxBlur(boxBlur(luma, sw, sh, 5), sw, sh, 5)
        var mn = 1f
        var mx = 0f
        for (v in lb) {
            if (v < mn) mn = v
            if (v > mx) mx = v
        }
        val rng = max(1e-3f, mx - mn)
        val dm = FloatArray(n)
        for (y in 0 until sh) {
            for (x in 0 until sw) {
                val dx = (x / sw.toFloat() - 0.5f) / 0.38f
                val dy = (y / sh.toFloat() - 0.45f) / 0.42f
                val cb = exp(-(dx * dx + dy * dy))
                dm[y * sw + x] = 0.55f * ((lb[y * sw + x] - mn) / rng) + 0.45f * cb
            }
        }

        // 每個網格頂點取雙線性插值的深度
        val depth = FloatArray((COLS + 1) * (ROWS + 1))
        for (j in 0..ROWS) {
            val fyp = j / ROWS.toFloat() * (sh - 1)
            val y0 = fyp.toInt()
            val y1 = min(y0 + 1, sh - 1)
            val ty = fyp - y0
            for (i in 0..COLS) {
                val fxp = i / COLS.toFloat() * (sw - 1)
                val x0 = fxp.toInt()
                val x1 = min(x0 + 1, sw - 1)
                val tx = fxp - x0
                val top = dm[y0 * sw + x0] * (1 - tx) + dm[y0 * sw + x1] * tx
                val bot = dm[y1 * sw + x0] * (1 - tx) + dm[y1 * sw + x1] * tx
                depth[j * (COLS + 1) + i] = (top * (1 - ty) + bot * ty).coerceIn(0f, 1f)
            }
        }

        // 光暈：只取亮部 → 模糊 → 之後用「濾色」疊回去
        val gr = FloatArray(n)
        val gg2 = FloatArray(n)
        val gb = FloatArray(n)
        for (i in 0 until n) {
            val k = sstep((luma[i] - 0.55f) / 0.40f)
            gr[i] = rr[i] * k
            gg2[i] = gg[i] * k
            gb[i] = bb[i] * k
        }
        val br = boxBlur(boxBlur(gr, sw, sh, 4), sw, sh, 4)
        val bg = boxBlur(boxBlur(gg2, sw, sh, 4), sw, sh, 4)
        val bbl = boxBlur(boxBlur(gb, sw, sh, 4), sw, sh, 4)
        val gain = 1.6f
        val gp = IntArray(n)
        for (i in 0 until n) {
            val r = (min(1f, br[i] * gain) * 255f).toInt()
            val g = (min(1f, bg[i] * gain) * 255f).toInt()
            val b = (min(1f, bbl[i] * gain) * 255f).toInt()
            gp[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        val glow = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888)
        glow.setPixels(gp, 0, sw, 0, 0, sw, sh)

        return SceneData(crop, depth, glow)
    }

    private fun sstep(t: Float): Float {
        val x = t.coerceIn(0f, 1f)
        return x * x * (3f - 2f * x)
    }

    /** 可分離盒狀模糊（邊緣夾住），用滑動視窗 O(n)。 */
    private fun boxBlur(src: FloatArray, w: Int, h: Int, r: Int): FloatArray {
        val tmp = FloatArray(src.size)
        val out = FloatArray(src.size)
        val k = (2 * r + 1).toFloat()
        for (y in 0 until h) {
            var sum = 0f
            for (i in -r..r) sum += src[y * w + i.coerceIn(0, w - 1)]
            for (x in 0 until w) {
                tmp[y * w + x] = sum / k
                val add = min(x + r + 1, w - 1)
                val sub = max(x - r, 0)
                sum += src[y * w + add] - src[y * w + sub]
            }
        }
        for (x in 0 until w) {
            var sum = 0f
            for (i in -r..r) sum += tmp[i.coerceIn(0, h - 1) * w + x]
            for (y in 0 until h) {
                out[y * w + x] = sum / k
                val add = min(y + r + 1, h - 1)
                val sub = max(y - r, 0)
                sum += tmp[add * w + x] - tmp[sub * w + x]
            }
        }
        return out
    }
}
