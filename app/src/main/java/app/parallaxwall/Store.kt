package app.parallaxwall

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class Item(
    val id: String,
    val name: String,
    val kind: String, // "IMAGE" 或 "VIDEO"
    val ext: String,
    val fx: Float = 0.5f, // 構圖焦點（0..1），預設置中
    val fy: Float = 0.5f,
    val w: Int = 0,
    val h: Int = 0,
)

data class Settings(
    val parallax: Float = 0.6f,   // 整體視差強度
    val depth: Float = 0.7f,      // 立體深度（前後景位移差）
    val glow: Float = 0.5f,       // 光暈強度
    val pulse: Boolean = true,    // 光暈呼吸
    val light: Float = 0.4f,      // 光影掃過
    val dim: Float = 0f,          // 暗化
    val invertDepth: Boolean = false,
    val reverseDir: Boolean = false,
    val muteVideo: Boolean = true,
    val rotateMin: Int = 0,       // 輪播間隔（分鐘），0 = 關閉
)

class Store(ctx: Context) {
    private val app = ctx.applicationContext
    private val sp: SharedPreferences = app.getSharedPreferences("wall", Context.MODE_PRIVATE)
    val dir: File = File(app.filesDir, "library").apply { mkdirs() }

    fun register(l: SharedPreferences.OnSharedPreferenceChangeListener) =
        sp.registerOnSharedPreferenceChangeListener(l)

    fun unregister(l: SharedPreferences.OnSharedPreferenceChangeListener) =
        sp.unregisterOnSharedPreferenceChangeListener(l)

    fun settings(): Settings {
        val d = Settings()
        return Settings(
            parallax = sp.getFloat("parallax", d.parallax),
            depth = sp.getFloat("depth", d.depth),
            glow = sp.getFloat("glow", d.glow),
            pulse = sp.getBoolean("pulse", d.pulse),
            light = sp.getFloat("light", d.light),
            dim = sp.getFloat("dim", d.dim),
            invertDepth = sp.getBoolean("invert", d.invertDepth),
            reverseDir = sp.getBoolean("reverse", d.reverseDir),
            muteVideo = sp.getBoolean("mute", d.muteVideo),
            rotateMin = sp.getInt("rotate", d.rotateMin),
        )
    }

    fun save(s: Settings) {
        sp.edit()
            .putFloat("parallax", s.parallax)
            .putFloat("depth", s.depth)
            .putFloat("glow", s.glow)
            .putBoolean("pulse", s.pulse)
            .putFloat("light", s.light)
            .putFloat("dim", s.dim)
            .putBoolean("invert", s.invertDepth)
            .putBoolean("reverse", s.reverseDir)
            .putBoolean("mute", s.muteVideo)
            .putInt("rotate", s.rotateMin)
            .apply()
    }

    fun items(): List<Item> {
        val raw = sp.getString("items", "[]") ?: "[]"
        val arr = try {
            JSONArray(raw)
        } catch (e: Exception) {
            JSONArray()
        }
        val out = ArrayList<Item>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out.add(
                Item(
                    id = o.optString("id"),
                    name = o.optString("name", "素材"),
                    kind = o.optString("kind", "IMAGE"),
                    ext = o.optString("ext", "jpg"),
                    fx = o.optDouble("fx", 0.5).toFloat(),
                    fy = o.optDouble("fy", 0.5).toFloat(),
                    w = o.optInt("w"),
                    h = o.optInt("h"),
                )
            )
        }
        return out
    }

    private fun toJson(list: List<Item>): String {
        val arr = JSONArray()
        for (it in list) {
            arr.put(
                JSONObject()
                    .put("id", it.id)
                    .put("name", it.name)
                    .put("kind", it.kind)
                    .put("ext", it.ext)
                    .put("fx", it.fx.toDouble())
                    .put("fy", it.fy.toDouble())
                    .put("w", it.w)
                    .put("h", it.h)
            )
        }
        return arr.toString()
    }

    fun currentId(): String? = sp.getString("current", null)

    fun setCurrent(id: String) {
        sp.edit().putString("current", id).apply()
    }

    fun add(item: Item) {
        sp.edit()
            .putString("items", toJson(items() + item))
            .putString("current", item.id)
            .apply()
    }

    fun remove(id: String) {
        val list = items()
        val target = list.firstOrNull { it.id == id } ?: return
        fileOf(target).delete()
        thumbOf(target).delete()
        val rest = list.filter { it.id != id }
        val ed = sp.edit().putString("items", toJson(rest))
        if (currentId() == id) {
            if (rest.isEmpty()) ed.remove("current") else ed.putString("current", rest.first().id)
        }
        ed.apply()
    }

    fun updateFocus(id: String, fx: Float, fy: Float) {
        val list = items().map { if (it.id == id) it.copy(fx = fx, fy = fy) else it }
        sp.edit().putString("items", toJson(list)).apply()
    }

    fun lastRotate(): Long = sp.getLong("lastRotate", 0L)

    fun markRotated() {
        sp.edit().putLong("lastRotate", System.currentTimeMillis()).apply()
    }

    fun fileOf(item: Item): File = File(dir, "${item.id}.${item.ext}")

    fun thumbOf(item: Item): File = File(dir, "${item.id}.thumb.jpg")
}
