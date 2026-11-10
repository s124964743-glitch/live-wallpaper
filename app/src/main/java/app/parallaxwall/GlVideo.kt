package app.parallaxwall

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLUtils
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * 影片桌布的 OpenGL 渲染器：MediaPlayer → SurfaceTexture → 填滿裁切 → 疊加暗化與時鐘。
 * 這樣影片桌布也能顯示時鐘（MediaPlayer 直接輸出到桌布 Surface 時無法再疊字）。
 *
 * 全部 GL 操作都在自己的 HandlerThread 上執行。[onReady]、[onFail] 也在該執行緒呼叫，
 * 呼叫端需自行切回主執行緒。
 */
class GlVideo(
    private val target: Surface,
    private val width: Int,
    private val height: Int,
    private val focusX: Float,
    private val focusY: Float,
    private val onReady: (Surface) -> Unit,
    private val onFail: () -> Unit,
) {
    private val thread = HandlerThread("glvideo").also { it.start() }
    private val h = Handler(thread.looper)

    @Volatile private var released = false

    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE

    private var progVideo = 0
    private var progOverlay = 0
    private var oesTex = 0
    private var clockTex = 0
    private var blackTex = 0
    private var surfaceTexture: SurfaceTexture? = null
    private var videoSurface: Surface? = null

    private val texMatrix = FloatArray(16)
    private var hasFrame = false
    private var vidW = 0
    private var vidH = 0

    private val videoVerts: FloatBuffer = newBuffer()
    private val quadVerts: FloatBuffer = newBuffer()

    // 由主執行緒設定
    @Volatile private var showClock = false
    @Volatile private var clockY = 0.865f
    @Volatile private var clockScale = 1.0f
    @Volatile private var dim = 0f

    private val clockPainter = ClockPainter()
    private val stripH = (260f * width / 1080f).toInt().coerceAtLeast(64)
    private var clockBmp: Bitmap? = null
    private var clockSec = -1L

    // ───────────── 對外介面 ─────────────

    fun start() {
        h.post {
            try {
                init()
                onReady(videoSurface!!)
            } catch (e: Throwable) {
                cleanup()
                onFail()
            }
        }
    }

    fun setVideoSize(w: Int, hh: Int) {
        h.post {
            if (released) return@post
            vidW = w
            vidH = hh
            rebuildVideoVerts()
            drawFrame(false)
        }
    }

    fun setClock(show: Boolean, yFrac: Float, scale: Float) {
        showClock = show
        clockY = yFrac
        clockScale = scale
        clockSec = -1L
        h.post { drawFrame(false) }
    }

    fun setDim(d: Float) {
        dim = d
        h.post { drawFrame(false) }
    }

    fun release() {
        if (released) return
        released = true
        h.post {
            cleanup()
            thread.quitSafely()
        }
    }

    // ───────────── 初始化 ─────────────

    private fun init() {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(eglDisplay != EGL14.EGL_NO_DISPLAY) { "no egl display" }
        val ver = IntArray(2)
        check(EGL14.eglInitialize(eglDisplay, ver, 0, ver, 1)) { "egl init" }

        val cfgAttr = intArrayOf(
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_NONE
        )
        val cfgs = arrayOfNulls<EGLConfig>(1)
        val n = IntArray(1)
        check(EGL14.eglChooseConfig(eglDisplay, cfgAttr, 0, cfgs, 0, 1, n, 0) && n[0] > 0) { "egl config" }
        val cfg = cfgs[0]!!

        eglContext = EGL14.eglCreateContext(
            eglDisplay, cfg, EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0
        )
        check(eglContext != EGL14.EGL_NO_CONTEXT) { "egl context" }
        eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, cfg, target, intArrayOf(EGL14.EGL_NONE), 0)
        check(eglSurface != EGL14.EGL_NO_SURFACE) { "egl surface" }
        check(EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) { "egl current" }

        progVideo = buildProgram(VIDEO_VS, VIDEO_FS)
        progOverlay = buildProgram(OVERLAY_VS, OVERLAY_FS)

        val t = IntArray(3)
        GLES20.glGenTextures(3, t, 0)
        oesTex = t[0]
        clockTex = t[1]
        blackTex = t[2]

        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTex)
        texParams(GLES11Ext.GL_TEXTURE_EXTERNAL_OES)

        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, clockTex)
        texParams(GLES20.GL_TEXTURE_2D)

        val black = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        black.eraseColor(Color.BLACK)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, blackTex)
        texParams(GLES20.GL_TEXTURE_2D)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, black, 0)
        black.recycle()

        val st = SurfaceTexture(oesTex)
        st.setOnFrameAvailableListener({ drawFrame(true) }, h)
        surfaceTexture = st
        videoSurface = Surface(st)

        rebuildVideoVerts()
    }

    private fun texParams(target: Int) {
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
    }

    /** 依影片長寬比做「填滿裁切」，焦點來自素材的構圖設定。 */
    private fun rebuildVideoVerts() {
        var u0 = 0f
        var u1 = 1f
        var v0 = 0f
        var v1 = 1f
        if (vidW > 0 && vidH > 0) {
            val va = vidW.toFloat() / vidH
            val a = width.toFloat() / height
            if (va > a) {
                val frac = a / va
                u0 = (1f - frac) * focusX.coerceIn(0f, 1f)
                u1 = u0 + frac
            } else {
                val frac = va / a
                v1 = 1f - (1f - frac) * focusY.coerceIn(0f, 1f)
                v0 = v1 - frac
            }
        }
        fillBuffer(videoVerts, -1f, -1f, 1f, 1f, u0, v0, u1, v1)
    }

    // ───────────── 繪製 ─────────────

    private fun drawFrame(newFrame: Boolean) {
        if (released || eglSurface == EGL14.EGL_NO_SURFACE) return
        try {
            val st = surfaceTexture
            if (newFrame && st != null) {
                st.updateTexImage()
                st.getTransformMatrix(texMatrix)
                hasFrame = true
            }
            GLES20.glViewport(0, 0, width, height)
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

            if (hasFrame) drawVideo()

            GLES20.glEnable(GLES20.GL_BLEND)
            GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)

            val d = dim
            if (d > 0f) {
                fillBuffer(quadVerts, -1f, -1f, 1f, 1f, 0f, 0f, 1f, 1f)
                drawOverlay(blackTex, d * 0.78f)
            }
            if (showClock) drawClock()

            GLES20.glDisable(GLES20.GL_BLEND)
            EGL14.eglSwapBuffers(eglDisplay, eglSurface)
        } catch (e: Throwable) {
            // 單一畫格失敗不致命，下一格再試
        }
    }

    private fun drawVideo() {
        GLES20.glUseProgram(progVideo)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTex)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(progVideo, "sTex"), 0)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(progVideo, "uTexMatrix"), 1, false, texMatrix, 0)
        bindAndDraw(progVideo, videoVerts)
    }

    private fun drawOverlay(tex: Int, alpha: Float) {
        GLES20.glUseProgram(progOverlay)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(progOverlay, "sTex"), 0)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(progOverlay, "uAlpha"), alpha)
        bindAndDraw(progOverlay, quadVerts)
    }

    private fun bindAndDraw(prog: Int, buf: FloatBuffer) {
        val pos = GLES20.glGetAttribLocation(prog, "aPos")
        val tex = GLES20.glGetAttribLocation(prog, "aTex")
        buf.position(0)
        GLES20.glVertexAttribPointer(pos, 2, GLES20.GL_FLOAT, false, 16, buf)
        GLES20.glEnableVertexAttribArray(pos)
        buf.position(2)
        GLES20.glVertexAttribPointer(tex, 2, GLES20.GL_FLOAT, false, 16, buf)
        GLES20.glEnableVertexAttribArray(tex)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(pos)
        GLES20.glDisableVertexAttribArray(tex)
    }

    private fun drawClock() {
        val sec = System.currentTimeMillis() / 1000L
        if (sec != clockSec || clockBmp == null) {
            var bmp = clockBmp
            if (bmp == null) {
                bmp = Bitmap.createBitmap(width, stripH, Bitmap.Config.ARGB_8888)
                clockBmp = bmp
            }
            bmp.eraseColor(Color.TRANSPARENT)
            clockPainter.draw(Canvas(bmp), width.toFloat(), stripH / 2f, clockScale)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, clockTex)
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)
            clockSec = sec
        }
        val topPx = clockY * height - stripH / 2f
        val ndcTop = 1f - 2f * topPx / height
        val ndcBottom = 1f - 2f * (topPx + stripH) / height
        // 點陣圖第 0 列在最上方 → 頂邊 v = 0
        fillBuffer(quadVerts, -1f, ndcBottom, 1f, ndcTop, 0f, 1f, 1f, 0f)
        drawOverlay(clockTex, 1f)
    }

    // ───────────── 收尾 ─────────────

    private fun cleanup() {
        released = true
        try {
            surfaceTexture?.setOnFrameAvailableListener(null)
            surfaceTexture?.release()
            videoSurface?.release()
        } catch (_: Throwable) {
        }
        surfaceTexture = null
        videoSurface = null
        clockBmp?.recycle()
        clockBmp = null
        try {
            if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(
                    eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT
                )
                if (eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, eglSurface)
                if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(eglDisplay, eglContext)
                EGL14.eglTerminate(eglDisplay)
            }
        } catch (_: Throwable) {
        }
        eglDisplay = EGL14.EGL_NO_DISPLAY
        eglContext = EGL14.EGL_NO_CONTEXT
        eglSurface = EGL14.EGL_NO_SURFACE
    }

    // ───────────── 小工具 ─────────────

    private fun newBuffer(): FloatBuffer =
        ByteBuffer.allocateDirect(16 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()

    /** 三角形帶的四個頂點：左下、右下、左上、右上；每點 (x, y, u, v)。 */
    private fun fillBuffer(
        b: FloatBuffer, x0: Float, y0: Float, x1: Float, y1: Float,
        u0: Float, v0: Float, u1: Float, v1: Float
    ) {
        b.position(0)
        b.put(floatArrayOf(
            x0, y0, u0, v0,
            x1, y0, u1, v0,
            x0, y1, u0, v1,
            x1, y1, u1, v1,
        ))
        b.position(0)
    }

    private fun buildProgram(vs: String, fs: String): Int {
        val v = compile(GLES20.GL_VERTEX_SHADER, vs)
        val f = compile(GLES20.GL_FRAGMENT_SHADER, fs)
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, v)
        GLES20.glAttachShader(p, f)
        GLES20.glLinkProgram(p)
        val ok = IntArray(1)
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0)
        check(ok[0] != 0) { "link: " + GLES20.glGetProgramInfoLog(p) }
        return p
    }

    private fun compile(type: Int, src: String): Int {
        val s = GLES20.glCreateShader(type)
        GLES20.glShaderSource(s, src)
        GLES20.glCompileShader(s)
        val ok = IntArray(1)
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
        check(ok[0] != 0) { "shader: " + GLES20.glGetShaderInfoLog(s) }
        return s
    }

    companion object {
        private const val VIDEO_VS = """
            uniform mat4 uTexMatrix;
            attribute vec4 aPos;
            attribute vec2 aTex;
            varying vec2 vTex;
            void main() {
                gl_Position = aPos;
                vTex = (uTexMatrix * vec4(aTex, 0.0, 1.0)).xy;
            }
        """
        private const val VIDEO_FS = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTex;
            uniform samplerExternalOES sTex;
            void main() {
                gl_FragColor = texture2D(sTex, vTex);
            }
        """
        private const val OVERLAY_VS = """
            attribute vec4 aPos;
            attribute vec2 aTex;
            varying vec2 vTex;
            void main() {
                gl_Position = aPos;
                vTex = aTex;
            }
        """
        private const val OVERLAY_FS = """
            precision mediump float;
            varying vec2 vTex;
            uniform sampler2D sTex;
            uniform float uAlpha;
            void main() {
                gl_FragColor = texture2D(sTex, vTex) * uAlpha;
            }
        """
    }
}
