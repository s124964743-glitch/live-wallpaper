package app.parallaxwall

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** 把使用者選的圖片/影片複製進 App 私有資料夾（之後不依賴外部 URI 權限），並產生縮圖。 */
object Importer {

    fun ingest(ctx: Context, uri: Uri): Item {
        val app = ctx.applicationContext
        val store = Store(app)
        val d = Sniffer.detect(app, uri)
        if (d.kind == Kind.UNKNOWN) throw IllegalArgumentException("無法辨識的檔案格式")

        val id = java.lang.Long.toString(System.currentTimeMillis(), 36) +
            java.lang.Integer.toString((0..1295).random(), 36)
        val dest = File(store.dir, "$id.${d.ext}")
        val thumb = File(store.dir, "$id.thumb.jpg")

        try {
            val input = app.contentResolver.openInputStream(uri)
                ?: throw IllegalStateException("無法開啟檔案")
            input.use { ins -> dest.outputStream().use { out -> ins.copyTo(out) } }

            var w = 0
            var h = 0
            if (d.kind == Kind.VIDEO) {
                val r = MediaMetadataRetriever()
                try {
                    r.setDataSource(dest.absolutePath)
                    w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                    h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                    val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                    if (rot == 90 || rot == 270) {
                        val t = w; w = h; h = t
                    }
                    val frame = r.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    if (frame != null) saveThumb(frame, thumb)
                } finally {
                    r.release()
                }
                if (w <= 0 || h <= 0) throw IllegalStateException("無法讀取影片")
            } else {
                var bw = 0
                var bh = 0
                val bmp = ImageDecoder.decodeBitmap(
                    ImageDecoder.createSource(dest),
                    ImageDecoder.OnHeaderDecodedListener { dec, info, _ ->
                        bw = info.size.width
                        bh = info.size.height
                        dec.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                        val sc = min(1f, 480f / max(bw, bh))
                        dec.setTargetSize(
                            max(1, (bw * sc).roundToInt()),
                            max(1, (bh * sc).roundToInt())
                        )
                    }
                )
                w = bw
                h = bh
                saveThumb(bmp, thumb)
            }

            val item = Item(id, displayName(app, uri), d.kind.name, d.ext, 0.5f, 0.5f, w, h)
            store.add(item)
            return item
        } catch (e: Exception) {
            dest.delete()
            thumb.delete()
            throw e
        }
    }

    private fun saveThumb(bmp: Bitmap, file: File) {
        FileOutputStream(file).use { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }
    }

    private fun displayName(ctx: Context, uri: Uri): String {
        try {
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val n = c.getString(0)
                    if (!n.isNullOrBlank()) return n.substringBeforeLast('.')
                }
            }
        } catch (_: Exception) {
        }
        return "素材"
    }
}
