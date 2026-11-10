package app.parallaxwall

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.service.wallpaper.WallpaperService
import android.view.Choreographer
import android.view.SurfaceHolder
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

class ParallaxWallpaperService : WallpaperService() {

    override fun onCreateEngine(): Engine = ParallaxEngine()

    private enum class Mode { NONE, STILL, ANIM, VIDEO }

    inner class ParallaxEngine : Engine(), SensorEventListener,
        SharedPreferences.OnSharedPreferenceChangeListener {

        private val svc = this@ParallaxWallpaperService
        private val store = Store(svc)
        private var st = store.settings()
        private val main = Handler(Looper.getMainLooper())
        private val exec = Executors.newSingleThreadExecutor()
        private val sm = svc.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        private val pm = svc.getSystemService(Context.POWER_SERVICE) as PowerManager

        private var destroyed = false
        private var visible = false
        private var surfW = 0
        private var surfH = 0
        private var mode = Mode.NONE
        private var generation = 0

        private var scene: SceneData? = null
        private var anim: AnimatedImageDrawable? = null
        private var player: MediaPlayer? = null
        private var playerReady = false
        private var swFallback = false
        private var animFx = 0.5f
        private var animFy = 0.5f

        // 感測器
        private var sensorOn = false
        private var sensorIsAccel = false
        private val rot = FloatArray(9)
        private var baseX = 0f
        private var baseY = 0f
        private var haveBase = false
        private var lastSensorNs = 0L

        // 傾斜（目標值與平滑後的值，範圍 -1..1）
        private var tgtX = 0f
        private var tgtY = 0f
        private var curX = 0f
        private var curY = 0f
        private var pageTgt = 0f
        private var pageCur = 0f

        // 繪製
        private var looping = false
        private var lastNs = 0L
        private var t = 0f
        private val verts = FloatArray((Scene.COLS + 1) * (Scene.ROWS + 1) * 2)
        private val glowRect = RectF()
        private val meshPaint = Paint().apply { isFilterBitmap = true }
        private val glowPaint = Paint().apply {
            isFilterBitmap = true
            xfermode = PorterDuffXfermode(PorterDuff.Mode.SCREEN)
        }
        private val lightShader = RadialGradient(
            0f, 0f, 1f,
            intArrayOf(0xFFFFF4DC.toInt(), 0x00FFF4DC),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP
        )
        private val lightMatrix = Matrix()
        private val lightPaint = Paint().apply {
            shader = lightShader
            xfermode = PorterDuffXfermode(PorterDuff.Mode.SCREEN)
        }
        private val noopCb = object : Drawable.Callback {
            override fun invalidateDrawable(who: Drawable) {}
            override fun scheduleDrawable(who: Drawable, what: Runnable, `when`: Long) {}
            override fun unscheduleDrawable(who: Drawable, what: Runnable) {}
        }

        private val frame = Choreographer.FrameCallback { ns ->
            looping = false
            onFrame(ns)
        }
        private val reloadRun = Runnable { reloadNow() }

        // ───────────── 生命週期 ─────────────

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            setTouchEventsEnabled(false)
            store.register(this)
        }

        override fun onDestroy() {
            destroyed = true
            main.removeCallbacksAndMessages(null)
            Choreographer.getInstance().removeFrameCallback(frame)
            store.unregister(this)
            if (sensorOn) sm.unregisterListener(this)
            sensorOn = false
            releasePlayer()
            exec.shutdownNow()
            scene?.recycle()
            scene = null
            super.onDestroy()
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            val changed = width != surfW || height != surfH
            surfW = width
            surfH = height
            if (changed || mode == Mode.NONE) {
                main.removeCallbacks(reloadRun)
                reloadNow()
            } else {
                kick()
            }
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            releasePlayer()
            if (mode == Mode.VIDEO) mode = Mode.NONE
            super.onSurfaceDestroyed(holder)
        }

        override fun onVisibilityChanged(v: Boolean) {
            visible = v
            if (v) {
                haveBase = false
                tgtX = 0f
                tgtY = 0f
                maybeRotate()
                if (playerReady) {
                    try {
                        player?.start()
                    } catch (_: Exception) {
                    }
                }
                kick()
            } else {
                try {
                    val p = player
                    if (p != null && p.isPlaying) p.pause()
                } catch (_: Exception) {
                }
            }
            updateSensor()
        }

        override fun onOffsetsChanged(
            xOffset: Float, yOffset: Float, xStep: Float, yStep: Float, xPixels: Int, yPixels: Int
        ) {
            pageTgt = if (xStep > 0f) (xOffset - 0.5f) * 2f else 0f
            kick()
        }

        // ───────────── 設定 / 素材切換 ─────────────

        override fun onSharedPreferenceChanged(sp: SharedPreferences?, key: String?) {
            when (key) {
                "items", "current", null -> {
                    main.removeCallbacks(reloadRun)
                    main.postDelayed(reloadRun, 150)
                }
                "lastRotate" -> {}
                else -> {
                    st = store.settings()
                    applyVolume()
                    updateSensor()
                    kick()
                }
            }
        }

        private fun maybeRotate() {
            val mins = st.rotateMin
            if (mins <= 0) return
            val list = store.items()
            if (list.size < 2) return
            val now = System.currentTimeMillis()
            if (now - store.lastRotate() < mins * 60_000L) return
            val idx = list.indexOfFirst { it.id == store.currentId() }
            val next = list[(idx + 1).mod(list.size)]
            store.markRotated()
            store.setCurrent(next.id)
        }

        private fun reloadNow() {
            if (destroyed) return
            generation++
            val gen = generation
            releasePlayer()
            anim?.let {
                try {
                    it.stop()
                } catch (_: Exception) {
                }
            }
            anim = null
            val old = scene
            scene = null
            old?.recycle()
            mode = Mode.NONE

            val list = store.items()
            val cur = list.firstOrNull { x -> x.id == store.currentId() } ?: list.firstOrNull()
            if (cur == null || surfW == 0 || surfH == 0) {
                updateSensor()
                kick()
                return
            }
            animFx = cur.fx
            animFy = cur.fy
            val file = store.fileOf(cur)
            if (!file.exists()) {
                updateSensor()
                kick()
                return
            }
            if (cur.kind == "VIDEO") {
                startVideo(file)
                updateSensor()
                return
            }
            val w = surfW
            val h = surfH
            val ext = cur.ext
            val fx = cur.fx
            val fy = cur.fy
            try {
                exec.execute {
                    try {
                        var a: AnimatedImageDrawable? = null
                        if (ext == "gif" || ext == "webp") {
                            try {
                                val d = ImageDecoder.decodeDrawable(ImageDecoder.createSource(file))
                                if (d is AnimatedImageDrawable) a = d
                            } catch (_: Throwable) {
                            }
                        }
                        if (a != null) {
                            val ad: AnimatedImageDrawable = a
                            main.post {
                                if (!destroyed && gen == generation) {
                                    anim = ad
                                    ad.callback = noopCb
                                    ad.repeatCount = AnimatedImageDrawable.REPEAT_INFINITE
                                    ad.start()
                                    mode = Mode.ANIM
                                    updateSensor()
                                    kick()
                                }
                            }
                        } else {
                            val sd = Scene.build(file, w, h, fx, fy)
                            main.post {
                                if (!destroyed && gen == generation) {
                                    scene = sd
                                    mode = Mode.STILL
                                    updateSensor()
                                    kick()
                                } else {
                                    sd.recycle()
                                }
                            }
                        }
                    } catch (e: Throwable) {
                        main.post { kick() }
                    }
                }
            } catch (_: Exception) {
            }
        }

        // ───────────── 影片 ─────────────

        private fun startVideo(file: File) {
            try {
                val mp = MediaPlayer()
                mp.setDataSource(file.absolutePath)
                mp.setSurface(surfaceHolder.surface)
                mp.isLooping = true
                val v = if (st.muteVideo) 0f else 1f
                mp.setVolume(v, v)
                mp.setVideoScalingMode(MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING)
                mp.setOnPreparedListener { p ->
                    playerReady = true
                    if (visible) {
                        try {
                            p.start()
                        } catch (_: Exception) {
                        }
                    }
                }
                mp.setOnErrorListener { _, _, _ -> true }
                playerReady = false
                player = mp
                mode = Mode.VIDEO
                mp.prepareAsync()
            } catch (e: Exception) {
                releasePlayer()
                mode = Mode.NONE
                kick()
            }
        }

        private fun applyVolume() {
            val v = if (st.muteVideo) 0f else 1f
            try {
                player?.setVolume(v, v)
            } catch (_: Exception) {
            }
        }

        private fun releasePlayer() {
            val p = player ?: return
            player = null
            playerReady = false
            try {
                p.release()
            } catch (_: Exception) {
            }
        }

        // ───────────── 感測器 ─────────────

        private fun updateSensor() {
            val need = visible && (mode == Mode.STILL || mode == Mode.ANIM) &&
                (st.parallax > 0f || st.depth > 0f || st.light > 0f)
            if (need && !sensorOn) {
                val s = sm.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
                    ?: sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
                if (s != null) {
                    sensorIsAccel = s.type == Sensor.TYPE_ACCELEROMETER
                    lastSensorNs = 0L
                    sm.registerListener(this, s, SensorManager.SENSOR_DELAY_GAME)
                    sensorOn = true
                }
            } else if (!need && sensorOn) {
                sm.unregisterListener(this)
                sensorOn = false
            }
        }

        override fun onSensorChanged(e: SensorEvent) {
            // 取「世界正上方」在手機座標系的分量：不受萬向鎖影響，直立握持也穩定
            val ux: Float
            val uz: Float
            if (sensorIsAccel) {
                val x = e.values[0]
                val y = e.values[1]
                val z = e.values[2]
                val n = sqrt(x * x + y * y + z * z)
                if (n < 1e-3f) return
                ux = x / n
                uz = z / n
            } else {
                SensorManager.getRotationMatrixFromVector(rot, e.values)
                ux = rot[6]
                uz = rot[8]
            }
            val dtS = if (lastSensorNs == 0L) 0.02f
            else ((e.timestamp - lastSensorNs) / 1e9f).coerceIn(0f, 0.2f)
            lastSensorNs = e.timestamp
            if (!haveBase) {
                baseX = ux
                baseY = uz
                haveBase = true
            } else {
                // 基準點緩慢跟隨（約 4 秒），所以任何握持角度都會自動「歸零」
                val k = 1f - exp(-dtS / 4f)
                baseX += (ux - baseX) * k
                baseY += (uz - baseY) * k
            }
            tgtX = ((ux - baseX) / 0.30f).coerceIn(-1f, 1f)
            tgtY = ((uz - baseY) / 0.30f).coerceIn(-1f, 1f)
            kick()
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

        // ───────────── 繪製迴圈 ─────────────

        private fun kick() {
            if (destroyed || !visible || looping) return
            looping = true
            Choreographer.getInstance().postFrameCallback(frame)
        }

        private fun onFrame(ns: Long) {
            if (destroyed || !visible) {
                lastNs = 0L
                return
            }
            val dt = if (lastNs == 0L) 0.016f else ((ns - lastNs) / 1e9f).coerceIn(0.001f, 0.1f)
            lastNs = ns
            val k = 1f - exp(-dt * 10f)
            curX += (tgtX - curX) * k
            curY += (tgtY - curY) * k
            pageCur += (pageTgt - pageCur) * k
            t += dt

            val moving = abs(tgtX - curX) > 0.002f || abs(tgtY - curY) > 0.002f ||
                abs(pageTgt - pageCur) > 0.002f
            draw()

            val continuous = mode == Mode.ANIM ||
                (mode == Mode.STILL && st.glow > 0f && st.pulse && !pm.isPowerSaveMode)
            if (moving || continuous) kick() else lastNs = 0L
        }

        private fun draw() {
            if (surfW == 0 || surfH == 0 || mode == Mode.VIDEO) return
            val holder = surfaceHolder
            var c: Canvas? = null
            try {
                c = if (!swFallback) holder.surface.lockHardwareCanvas() else holder.lockCanvas()
                if (c != null) render(c)
            } catch (e: Exception) {
                swFallback = true
            } finally {
                if (c != null) {
                    try {
                        holder.surface.unlockCanvasAndPost(c)
                    } catch (_: Exception) {
                    }
                }
            }
        }

        private fun render(c: Canvas) {
            val w = surfW.toFloat()
            val sign = if (st.reverseDir) -1f else 1f
            val ex = ((curX + 0.7f * pageCur) * sign).coerceIn(-1f, 1f)
            val ey = (curY * sign).coerceIn(-1f, 1f)
            val amp = st.parallax * 0.03f * w
            val dep = st.depth * 0.03f * w

            c.drawColor(Color.BLACK)
            when (mode) {
                Mode.STILL -> drawStill(c, ex, ey, amp, dep)
                Mode.ANIM -> drawAnim(c, ex, ey, amp)
                else -> c.drawColor(0xFF0B1220.toInt())
            }
            if (mode == Mode.STILL || mode == Mode.ANIM) drawLight(c, ex, ey)
            if (st.dim > 0f) c.drawColor(Color.argb((st.dim * 200f).toInt(), 0, 0, 0))
        }

        private fun drawStill(c: Canvas, ex: Float, ey: Float, amp: Float, dep: Float) {
            val sc = scene ?: return
            val w = surfW.toFloat()
            val h = surfH.toFloat()
            val m = Scene.MARGIN
            val rw = w * (1f + 2f * m)
            val rh = h * (1f + 2f * m)
            val left = -w * m
            val top = -h * m
            val cols = Scene.COLS
            val rows = Scene.ROWS
            val invert = st.invertDepth

            var idx = 0
            for (j in 0..rows) {
                val by = top + rh * j / rows
                for (i in 0..cols) {
                    val bx = left + rw * i / cols
                    var d = sc.depth[j * (cols + 1) + i]
                    if (invert) d = 1f - d
                    val s = amp + dep * (d - 0.5f) * 2f
                    verts[idx++] = bx + ex * s
                    verts[idx++] = by + ey * s * 0.6f
                }
            }
            c.drawBitmapMesh(sc.bmp, cols, rows, verts, 0, null, 0, meshPaint)

            if (st.glow > 0f) {
                val pulse = if (st.pulse && !pm.isPowerSaveMode) 0.65f + 0.35f * sin(t * 1.4f) else 1f
                val a = (st.glow * pulse * 255f).toInt().coerceIn(0, 255)
                val gs = amp * 1.5f + dep * 0.5f
                glowRect.set(
                    left + ex * gs, top + ey * gs * 0.6f,
                    left + rw + ex * gs, top + rh + ey * gs * 0.6f
                )
                glowPaint.alpha = a
                c.drawBitmap(sc.glow, null, glowRect, glowPaint)
                // 外圈柔光，讓亮部有「溢出」的感覺
                val grow = w * 0.03f
                glowRect.inset(-grow, -grow * h / w)
                glowPaint.alpha = (a * 0.55f).toInt()
                c.drawBitmap(sc.glow, null, glowRect, glowPaint)
            }
        }

        private fun drawAnim(c: Canvas, ex: Float, ey: Float, amp: Float) {
            val d = anim ?: return
            val fx = animFx
            val fy = animFy
            val w = surfW.toFloat()
            val h = surfH.toFloat()
            val m = Scene.MARGIN
            val rw = w * (1f + 2f * m)
            val rh = h * (1f + 2f * m)
            val iw = max(1, d.intrinsicWidth).toFloat()
            val ih = max(1, d.intrinsicHeight).toFloat()
            val sc = max(rw / iw, rh / ih)
            val dw = iw * sc
            val dh = ih * sc
            val left = -w * m - (dw - rw) * fx + ex * amp
            val top = -h * m - (dh - rh) * fy + ey * amp * 0.6f
            d.setBounds(left.toInt(), top.toInt(), (left + dw).toInt(), (top + dh).toInt())
            d.draw(c)
        }

        private fun drawLight(c: Canvas, ex: Float, ey: Float) {
            if (st.light <= 0f) return
            val w = surfW.toFloat()
            val h = surfH.toFloat()
            val cx = w * (0.5f + ex * 0.4f)
            val cy = h * (0.30f + ey * 0.18f)
            val r = w * 0.95f
            lightMatrix.reset()
            lightMatrix.setScale(r, r)
            lightMatrix.postTranslate(cx, cy)
            lightShader.setLocalMatrix(lightMatrix)
            lightPaint.alpha = (st.light * 0.55f * 255f).toInt().coerceIn(0, 255)
            c.drawRect(0f, 0f, w, h, lightPaint)
        }
    }
}
