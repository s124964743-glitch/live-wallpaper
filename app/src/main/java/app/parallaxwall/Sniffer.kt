package app.parallaxwall

import android.content.Context
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.webkit.MimeTypeMap

enum class Kind { IMAGE, VIDEO, UNKNOWN }

data class Detected(val kind: Kind, val ext: String, val label: String)

/**
 * 依檔案開頭的魔術位元組辨識格式（不依賴副檔名或 MIME，
 * 因為相簿、LINE、雲端硬碟給的檔名常常是錯的或沒有副檔名）。
 * 失敗時才退回 MIME，最後再實際探測。
 */
object Sniffer {

    fun detect(ctx: Context, uri: Uri): Detected {
        val head = ByteArray(32)
        var n = 0
        try {
            ctx.contentResolver.openInputStream(uri)?.use { ins ->
                while (n < head.size) {
                    val r = ins.read(head, n, head.size - n)
                    if (r <= 0) break
                    n += r
                }
            }
        } catch (_: Exception) {
        }
        fromMagic(head, n)?.let { return it }
        fromMime(ctx.contentResolver.getType(uri) ?: "")?.let { return it }
        return probe(ctx, uri)
    }

    private fun u(b: ByteArray, i: Int) = b[i].toInt() and 0xFF

    private fun fromMagic(b: ByteArray, n: Int): Detected? {
        fun t(off: Int, s: String): Boolean =
            off + s.length <= n && s.indices.all { b[off + it].toInt() == s[it].code }

        if (n >= 3 && u(b, 0) == 0xFF && u(b, 1) == 0xD8 && u(b, 2) == 0xFF)
            return Detected(Kind.IMAGE, "jpg", "JPEG 圖片")
        if (n >= 8 && u(b, 0) == 0x89 && t(1, "PNG"))
            return Detected(Kind.IMAGE, "png", "PNG 圖片")
        if (t(0, "GIF8")) return Detected(Kind.IMAGE, "gif", "GIF 動圖")
        if (t(0, "RIFF") && t(8, "WEBP")) return Detected(Kind.IMAGE, "webp", "WebP 圖片")
        if (t(0, "RIFF") && t(8, "AVI ")) return Detected(Kind.VIDEO, "avi", "AVI 影片")
        if (n >= 26 && t(0, "BM")) return Detected(Kind.IMAGE, "bmp", "BMP 圖片")
        if (n >= 4 && u(b, 0) == 0x1A && u(b, 1) == 0x45 && u(b, 2) == 0xDF && u(b, 3) == 0xA3)
            return Detected(Kind.VIDEO, "webm", "WebM/MKV 影片")
        if (t(4, "ftyp") && n >= 12) {
            val brand = String(b, 8, 4, Charsets.ISO_8859_1)
            return when {
                brand in setOf("heic", "heix", "hevc", "hevx", "heim", "heis", "mif1", "msf1") ->
                    Detected(Kind.IMAGE, "heic", "HEIC 圖片")
                brand == "avif" || brand == "avis" -> Detected(Kind.IMAGE, "avif", "AVIF 圖片")
                brand.startsWith("3gp") -> Detected(Kind.VIDEO, "3gp", "3GP 影片")
                brand == "qt  " -> Detected(Kind.VIDEO, "mov", "MOV 影片")
                else -> Detected(Kind.VIDEO, "mp4", "MP4 影片")
            }
        }
        return null
    }

    private fun fromMime(m: String): Detected? {
        val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(m) ?: "bin"
        return when {
            m.startsWith("video/") -> Detected(Kind.VIDEO, ext, "影片")
            m.startsWith("image/") -> Detected(Kind.IMAGE, ext, "圖片")
            else -> null
        }
    }

    private fun probe(ctx: Context, uri: Uri): Detected {
        try {
            val r = MediaMetadataRetriever()
            try {
                r.setDataSource(ctx, uri)
                if (r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) == "yes")
                    return Detected(Kind.VIDEO, "mp4", "影片")
            } finally {
                r.release()
            }
        } catch (_: Exception) {
        }
        try {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o) }
            if (o.outWidth > 0) return Detected(Kind.IMAGE, "jpg", "圖片")
        } catch (_: Exception) {
        }
        return Detected(Kind.UNKNOWN, "bin", "未知")
    }
}
