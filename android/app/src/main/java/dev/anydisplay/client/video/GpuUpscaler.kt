package dev.anydisplay.client.video

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Process
import android.view.Surface
import dev.anydisplay.client.Elog
import dev.anydisplay.client.data.Upscaler
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * GPU upscaling of the decoded video onto the SurfaceView.
 *
 * MediaCodec renders into [inputSurface], a Surface backed by a SurfaceTexture / GL_TEXTURE_EXTERNAL_OES texture.
 * A dedicated GL thread with its own EGL context draws the latest decoded frame:
 * FSR = EASU pass into an offscreen texture, then an RCAS pass to the window; nearest = a single GL_NEAREST pass.
 * Only the newest frame is ever drawn and nothing is read back to the CPU.
 *
 * The caller hands the same window Surface to either this renderer or the direct MediaCodec path, so failing to
 * start here (or a runtime GL/EGL error) is reported through [onFailure] and the caller falls back to direct output.
 * [onFailure] runs on the GL thread; it must only hand the message to another thread (as StreamActivity does), not
 * block on this renderer. The second argument is true for a permanent cause (missing extension, shader compile,
 * framebuffer unsupported) and false for a transient surface/EGL error that may succeed on a new surface.
 */
class GpuUpscaler(
    private val mode: Upscaler,
    sharpnessPercent: Int,
    private val srcWidth: Int,
    private val srcHeight: Int,
    private val onFailure: (String, Boolean) -> Unit,
) {
    /** MediaCodec output surface; set once [start] has succeeded. */
    @Volatile var inputSurface: Surface? = null
        private set

    private val thread = HandlerThread("anydisplay-gl", Process.THREAD_PRIORITY_DISPLAY).apply { start() }
    private val handler = Handler(thread.looper)
    private val startedLatch = CountDownLatch(1)
    private val frameLock = Any()

    @Volatile private var ready = false
    @Volatile private var released = false
    @Volatile private var closed = false
    private var failed = false
    private var framePending = false
    /** True when the last failure cannot be fixed by retrying on another surface (bad shader/extension/FBO). */
    @Volatile var lastFailurePermanent = false
        private set

    // RCAS sharpness as FsrRcasCon() wants it: exp2(-stops), 0% -> 2 stops (least), 100% -> 0 stops (maximum).
    private val rcasSharpness = Math.pow(2.0, -2.0 * (100 - sharpnessPercent.coerceIn(0, 100)) / 100.0).toFloat()

    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE

    private var surfaceTexture: SurfaceTexture? = null
    private var oesTex = 0
    private var fbo = 0
    private var fboTex = 0
    private var targetW = 0
    private var targetH = 0
    private var viewportX = 0
    private var viewportY = 0

    private var programEasu = 0
    private var programRcas = 0
    private var programNearest = 0
    private var attribEasu = -1
    private var attribRcas = -1
    private var attribNearest = -1
    private var uEasuSrc = -1
    private var uEasuMatrix = -1
    private var uEasuCon0 = -1
    private var uEasuCon1 = -1
    private var uRcasSrc = -1
    private var uRcasInvSize = -1
    private var uRcasSharp = -1
    private var uNearestSrc = -1
    private var uNearestMatrix = -1
    private var uNearestSize = -1

    private val texMatrix = FloatArray(16)
    private val con0 = FloatArray(4)
    private val con1 = FloatArray(4)
    private val surfaceSize = IntArray(1)
    private var quad: FloatBuffer? = null

    private val frameRunnable = Runnable {
        synchronized(frameLock) { framePending = false }
        drawFrame()
    }

    /**
     * Creates the EGL context and the decoder surface. Blocks until EGL is ready or failed; shaders are compiled
     * lazily on the first frame, so a slow cold-GPU FSR compile cannot fail the start.
     */
    fun start(window: Surface): Boolean {
        handler.post { init(window) }
        if (!startedLatch.await(5, TimeUnit.SECONDS)) {
            lastFailurePermanent = false
            Elog.w("upscaler ${mode.key}: EGL init did not finish within 5s (GL thread still starting)")
            release()
            return false
        }
        if (!ready) release()
        return ready
    }

    /** Tears the GL thread down. Safe to call more than once. */
    fun release() {
        synchronized(frameLock) {
            if (closed) return
            closed = true
        }
        if (Looper.myLooper() == thread.looper) {
            // Called from the GL thread (a failure callback): tear down here instead of waiting on ourselves.
            released = true
            releaseGl()
            thread.quitSafely()
            return
        }
        val done = CountDownLatch(1)
        handler.post {
            released = true
            releaseGl()
            done.countDown()
        }
        if (!done.await(2, TimeUnit.SECONDS)) Elog.w("upscaler: GL release timed out")
        thread.quitSafely()
    }

    // ------------------------------------------------------------ GL thread

    private fun init(window: Surface) {
        try {
            if (released) return
            eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            if (eglDisplay == EGL14.EGL_NO_DISPLAY) throw IllegalStateException("eglGetDisplay failed")
            val version = IntArray(2)
            if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) {
                throw IllegalStateException("eglInitialize failed: 0x" + Integer.toHexString(EGL14.eglGetError()))
            }
            // GLES2 first, like the known-working reference implementation; the shaders are GLSL ES 1.00.
            // Only fall back to an ES3 context when the driver refuses an ES2 one.
            var config = chooseConfig(false)
            var context = if (config != null) createContext(config, 2) else EGL14.EGL_NO_CONTEXT
            if (context == EGL14.EGL_NO_CONTEXT) {
                config = chooseConfig(true) ?: throw IllegalStateException("no EGL config (ES2/ES3)")
                context = createContext(config, 3)
                if (context == EGL14.EGL_NO_CONTEXT) {
                    throw IllegalStateException("eglCreateContext failed: 0x" + Integer.toHexString(EGL14.eglGetError()))
                }
                Elog.i("upscaler ${mode.key}: ES2 context unavailable, using ES3")
            }
            eglContext = context
            eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, config, window, intArrayOf(EGL14.EGL_NONE), 0)
            if (eglSurface == EGL14.EGL_NO_SURFACE) {
                throw IllegalStateException("eglCreateWindowSurface failed: 0x" + Integer.toHexString(EGL14.eglGetError()))
            }
            if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
                throw IllegalStateException("eglMakeCurrent failed: 0x" + Integer.toHexString(EGL14.eglGetError()))
            }

            // Shaders compile lazily on the first frame (ensurePrograms): a cold GPU must not turn a slow
            // FSR compile into a failed start, and NEAREST never compiles or links any FSR program.
            logGlInfo()
            val extensions = GLES20.glGetString(GLES20.GL_EXTENSIONS)
            if (extensions == null) {
                // Some drivers expose no extension string until the context is fully current; retry later.
                lastFailurePermanent = false
                throw IllegalStateException("GL_EXTENSIONS is unavailable")
            }
            if (extensions.split(' ').none { it == "GL_OES_EGL_image_external" }) {
                lastFailurePermanent = true
                throw IllegalStateException("GL_OES_EGL_image_external is not available")
            }
            val range = IntArray(2)
            val prec = IntArray(2)
            GLES20.glGetShaderPrecisionFormat(GLES20.GL_FRAGMENT_SHADER, GLES20.GL_HIGH_FLOAT, range, 0, prec, 0)
            if (prec[0] == 0) {
                lastFailurePermanent = true
                throw IllegalStateException("fragment highp is not available")
            }

            // The decoded frame texture. GL_NEAREST keeps the twelve EASU taps at exact texel centers.
            val ids = IntArray(2)
            GLES20.glGenTextures(1, ids, 0)
            oesTex = ids[0]
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTex)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

            val st = SurfaceTexture(oesTex)
            st.setDefaultBufferSize(srcWidth, srcHeight)
            st.setOnFrameAvailableListener { scheduleFrame() }
            surfaceTexture = st
            inputSurface = Surface(st)

            // Two triangles as a strip: four vertices of two floats.
            quad = ByteBuffer.allocateDirect(4 * 2 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
                put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))
                position(0)
            }

            GLES20.glDisable(GLES20.GL_DEPTH_TEST)
            GLES20.glDisable(GLES20.GL_BLEND)
            GLES20.glDisable(GLES20.GL_CULL_FACE)
            GLES20.glDisable(GLES20.GL_SCISSOR_TEST)
            GLES20.glClearColor(0f, 0f, 0f, 1f)

            ready = true
            Elog.i("upscaler ${mode.key} ready: ${srcWidth}x${srcHeight} frame -> window")
        } catch (t: Throwable) {
            Elog.w("upscaler: init failed: " + (t.message ?: t.javaClass.simpleName))
            releaseGl()
        } finally {
            startedLatch.countDown()
        }
    }

    private fun chooseConfig(es3: Boolean): EGLConfig? {
        val attribs = intArrayOf(
            EGL14.EGL_RENDERABLE_TYPE, if (es3) EGLExt.EGL_OPENGL_ES3_BIT_KHR else EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        return if (EGL14.eglChooseConfig(eglDisplay, attribs, 0, configs, 0, 1, count, 0) && count[0] > 0) configs[0] else null
    }

    private fun createContext(config: EGLConfig, version: Int): EGLContext =
        EGL14.eglCreateContext(
            eglDisplay, config, EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, version, EGL14.EGL_NONE), 0,
        )

    private fun compile(type: Int, source: String, label: String): Int {
        val shader = GLES20.glCreateShader(type)
        if (shader == 0) throw IllegalStateException("glCreateShader($label) failed")
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] != GLES20.GL_TRUE) {
            val log = GLES20.glGetShaderInfoLog(shader)
            GLES20.glDeleteShader(shader)
            throw IllegalStateException("shader $label compile failed: " + log)
        }
        return shader
    }

    private fun buildProgram(vertex: String, fragment: String, label: String): Int {
        val vs = compile(GLES20.GL_VERTEX_SHADER, vertex, "$label vertex")
        val fs = compile(GLES20.GL_FRAGMENT_SHADER, fragment, "$label fragment")
        val program = GLES20.glCreateProgram()
        if (program == 0) throw IllegalStateException("glCreateProgram($label) failed")
        GLES20.glAttachShader(program, vs)
        GLES20.glAttachShader(program, fs)
        GLES20.glLinkProgram(program)
        val status = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0)
        if (status[0] != GLES20.GL_TRUE) {
            val log = GLES20.glGetProgramInfoLog(program)
            GLES20.glDeleteProgram(program)
            GLES20.glDeleteShader(vs)
            GLES20.glDeleteShader(fs)
            throw IllegalStateException("program $label link failed: " + log)
        }
        GLES20.glDeleteShader(vs)
        GLES20.glDeleteShader(fs)
        return program
    }

    private fun logGlInfo() {
        val vendor = GLES20.glGetString(GLES20.GL_VENDOR) ?: "?"
        val renderer = GLES20.glGetString(GLES20.GL_RENDERER) ?: "?"
        val version = GLES20.glGetString(GLES20.GL_VERSION) ?: "?"
        Elog.i("upscaler ${mode.key}: GL vendor=$vendor renderer=$renderer version=$version frame=${srcWidth}x${srcHeight}")
    }

    /** Compiles only the program(s) the selected mode needs; a mode change creates a new renderer. */
    private fun ensurePrograms() {
        try {
            if (mode == Upscaler.NEAREST) {
                if (programNearest == 0) {
                    programNearest = buildProgram(FsrShaders.VERTEX.trimStart(), FsrShaders.NEAREST_FRAGMENT.trimStart(), "nearest")
                    attribNearest = GLES20.glGetAttribLocation(programNearest, "aPos")
                    uNearestSrc = GLES20.glGetUniformLocation(programNearest, "uSrc")
                    uNearestMatrix = GLES20.glGetUniformLocation(programNearest, "uTexMatrix")
                    uNearestSize = GLES20.glGetUniformLocation(programNearest, "uVideoSize")
                    Elog.i("upscaler nearest: program ready")
                }
                return
            }
            if (programEasu == 0) {
                programEasu = buildProgram(FsrShaders.VERTEX.trimStart(), FsrShaders.EASU_FRAGMENT.trimStart(), "EASU")
                attribEasu = GLES20.glGetAttribLocation(programEasu, "aPos")
                uEasuSrc = GLES20.glGetUniformLocation(programEasu, "uSrc")
                uEasuMatrix = GLES20.glGetUniformLocation(programEasu, "uTexMatrix")
                uEasuCon0 = GLES20.glGetUniformLocation(programEasu, "uCon0")
                uEasuCon1 = GLES20.glGetUniformLocation(programEasu, "uCon1")
                Elog.i("upscaler EASU: program ready")
            }
            if (programRcas == 0) {
                programRcas = buildProgram(FsrShaders.VERTEX.trimStart(), FsrShaders.RCAS_FRAGMENT.trimStart(), "RCAS")
                attribRcas = GLES20.glGetAttribLocation(programRcas, "aPos")
                uRcasSrc = GLES20.glGetUniformLocation(programRcas, "uSrc")
                uRcasInvSize = GLES20.glGetUniformLocation(programRcas, "uInvSize")
                uRcasSharp = GLES20.glGetUniformLocation(programRcas, "uSharp")
                Elog.i("upscaler RCAS: program ready")
            }
        } catch (t: Throwable) {
            fail("program init: " + (t.message ?: t.javaClass.simpleName), true)
        }
    }

    /** Coalesces frame callbacks: at most one draw is queued, and it always shows the newest frame. */
    private fun scheduleFrame() {
        synchronized(frameLock) {
            if (framePending || released) return
            framePending = true
        }
        handler.post(frameRunnable)
    }

    private fun drawFrame() {
        if (!ready || released || failed) return
        val st = surfaceTexture ?: return
        val display = eglDisplay
        val surface = eglSurface
        if (display == EGL14.EGL_NO_DISPLAY || surface == EGL14.EGL_NO_SURFACE || eglContext == EGL14.EGL_NO_CONTEXT) return
        var stage = "eglMakeCurrent"
        try {
            if (!EGL14.eglMakeCurrent(display, surface, surface, eglContext)) {
                fail(stage + ": 0x" + Integer.toHexString(EGL14.eglGetError()), false)
                return
            }
            // Shader compilation happens here on the GL thread, only for the selected mode, on the first frame.
            // Drop errors left over from context/surface creation so they are not blamed on this frame.
            drainGlErrors()
            stage = "programs"
            ensurePrograms()
            if (failed) return
            stage = "updateTexImage"
            st.updateTexImage()
            st.getTransformMatrix(texMatrix)
            // The window buffer is sized asynchronously by SurfaceView, so read its real size each frame
            // (two cheap EGL queries) instead of assuming the requested layout size.
            if (!EGL14.eglQuerySurface(display, surface, EGL14.EGL_WIDTH, surfaceSize, 0)) return
            val surfaceW = surfaceSize[0]
            if (!EGL14.eglQuerySurface(display, surface, EGL14.EGL_HEIGHT, surfaceSize, 0)) return
            val surfaceH = surfaceSize[0]
            if (surfaceW <= 0 || surfaceH <= 0) return
            stage = "framebuffer"
            try {
                updateTarget(surfaceW, surfaceH)
            } catch (t: Throwable) {
                fail(stage + ": " + (t.message ?: t.javaClass.simpleName), true)
                return
            }
            stage = "draw"
            if (mode == Upscaler.FSR) drawFsr(surfaceW, surfaceH) else drawNearest(surfaceW, surfaceH)
            if (drawGlError(stage)) {
                fail(stage + " glGetError", false)
                return
            }
            stage = "eglSwapBuffers"
            if (!EGL14.eglSwapBuffers(display, surface)) {
                val error = EGL14.eglGetError()
                if (error != EGL14.EGL_SUCCESS) fail(stage + ": 0x" + Integer.toHexString(error), false)
            }
        } catch (t: Throwable) {
            // Shader/mode errors are reported permanently by ensurePrograms; anything else here is transient.
            if (!failed) fail(stage + ": " + (t.message ?: t.javaClass.simpleName), false)
        }
    }

    /** Letterboxed destination inside the window surface plus the EASU constants for that size. */
    private fun updateTarget(surfaceW: Int, surfaceH: Int) {
        val scale = minOf(surfaceW.toFloat() / srcWidth, surfaceH.toFloat() / srcHeight)
        val w = Math.max(1, Math.round(srcWidth * scale))
        val h = Math.max(1, Math.round(srcHeight * scale))
        viewportX = (surfaceW - w) / 2
        viewportY = (surfaceH - h) / 2
        if (w == targetW && h == targetH && (mode != Upscaler.FSR || fbo != 0)) return
        targetW = w
        targetH = h
        // NEAREST renders straight to the window: no EASU texture/FBO is created for it.
        if (mode == Upscaler.FSR) ensureTarget(w, h)
        // FsrEasuCon() with the whole frame as viewport, input and output sizes.
        con0[0] = srcWidth.toFloat() / w
        con0[1] = srcHeight.toFloat() / h
        con0[2] = 0.5f * srcWidth / w - 0.5f
        con0[3] = 0.5f * srcHeight / h - 0.5f
        con1[0] = 1f / srcWidth
        con1[1] = 1f / srcHeight
        con1[2] = srcWidth - 1f
        con1[3] = srcHeight - 1f
    }

    /** EASU output texture, reallocated only when the destination size changes. */
    private fun ensureTarget(w: Int, h: Int) {
        val maxSize = IntArray(1)
        GLES20.glGetIntegerv(GLES20.GL_MAX_TEXTURE_SIZE, maxSize, 0)
        if (w > maxSize[0] || h > maxSize[0]) {
            throw IllegalStateException("target " + w + "x" + h + " exceeds GL_MAX_TEXTURE_SIZE " + maxSize[0])
        }
        if (fbo == 0) {
            // Separate arrays: glGenTextures writes only element 0, reading ids[1] silently produced texture 0
            // and made glCheckFramebufferStatus fail for every mode (including Nearest, which also called this).
            val ids = IntArray(1)
            GLES20.glGenFramebuffers(1, ids, 0)
            fbo = ids[0]
            GLES20.glGenTextures(1, ids, 0)
            fboTex = ids[0]
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fboTex)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo)
            GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, fboTex, 0)
        }
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fboTex)
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, w, h, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo)
        val status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        if (status != GLES20.GL_FRAMEBUFFER_COMPLETE) {
            throw IllegalStateException("framebuffer incomplete: 0x" + Integer.toHexString(status))
        }
    }

    private fun drawFsr(surfaceW: Int, surfaceH: Int) {
        // EASU: decoded frame -> offscreen texture at the destination resolution.
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo)
        GLES20.glViewport(0, 0, targetW, targetH)
        GLES20.glUseProgram(programEasu)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTex)
        GLES20.glUniform1i(uEasuSrc, 0)
        GLES20.glUniformMatrix4fv(uEasuMatrix, 1, false, texMatrix, 0)
        GLES20.glUniform4fv(uEasuCon0, 1, con0, 0)
        GLES20.glUniform4fv(uEasuCon1, 1, con1, 0)
        drawQuad(attribEasu)

        // RCAS: sharpen the EASU output onto the window surface.
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glViewport(0, 0, surfaceW, surfaceH)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glViewport(viewportX, viewportY, targetW, targetH)
        GLES20.glUseProgram(programRcas)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fboTex)
        GLES20.glUniform1i(uRcasSrc, 0)
        GLES20.glUniform2f(uRcasInvSize, 1f / targetW, 1f / targetH)
        GLES20.glUniform1f(uRcasSharp, rcasSharpness)
        drawQuad(attribRcas)
    }

    private fun drawNearest(surfaceW: Int, surfaceH: Int) {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glViewport(0, 0, surfaceW, surfaceH)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glViewport(viewportX, viewportY, targetW, targetH)
        GLES20.glUseProgram(programNearest)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTex)
        GLES20.glUniform1i(uNearestSrc, 0)
        GLES20.glUniformMatrix4fv(uNearestMatrix, 1, false, texMatrix, 0)
        GLES20.glUniform2f(uNearestSize, srcWidth.toFloat(), srcHeight.toFloat())
        drawQuad(attribNearest)
    }

    private fun drawQuad(attrib: Int) {
        val buffer = quad ?: return
        if (attrib < 0) return
        buffer.position(0)
        GLES20.glEnableVertexAttribArray(attrib)
        GLES20.glVertexAttribPointer(attrib, 2, GLES20.GL_FLOAT, false, 0, buffer)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(attrib)
    }

    /** Clears stale errors left by context/surface churn before attributing an error to this frame. */
    private fun drainGlErrors() {
        var i = 0
        while (GLES20.glGetError() != GLES20.GL_NO_ERROR && i++ < 16) { }
    }

    private fun drawGlError(stage: String): Boolean {
        val error = GLES20.glGetError()
        if (error == GLES20.GL_NO_ERROR) return false
        Elog.w("upscaler ${mode.key}: " + stage + " glGetError=0x" + Integer.toHexString(error))
        return true
    }

    private fun fail(message: String, permanent: Boolean) {
        if (failed || closed) return
        failed = true
        released = true
        lastFailurePermanent = permanent
        Elog.w("upscaler ${mode.key}: " + message + " (" + (if (permanent) "unsupported" else "transient") + "); falling back to direct output")
        releaseGl()
        onFailure(message, permanent)
    }

    /** Deletes GL objects and the EGL objects. Runs on the GL thread. */
    private fun releaseGl() {
        ready = false
        val ids = IntArray(1)
        if (eglDisplay != EGL14.EGL_NO_DISPLAY && eglContext != EGL14.EGL_NO_CONTEXT) {
            if (eglSurface != EGL14.EGL_NO_SURFACE) {
                EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)
            }
            if (fbo != 0) {
                ids[0] = fbo
                GLES20.glDeleteFramebuffers(1, ids, 0)
                fbo = 0
            }
            if (fboTex != 0) {
                ids[0] = fboTex
                GLES20.glDeleteTextures(1, ids, 0)
                fboTex = 0
            }
            if (oesTex != 0) {
                ids[0] = oesTex
                GLES20.glDeleteTextures(1, ids, 0)
                oesTex = 0
            }
            if (programEasu != 0) { GLES20.glDeleteProgram(programEasu); programEasu = 0 }
            if (programRcas != 0) { GLES20.glDeleteProgram(programRcas); programRcas = 0 }
            if (programNearest != 0) { GLES20.glDeleteProgram(programNearest); programNearest = 0 }
        }
        val st = surfaceTexture
        surfaceTexture = null
        try { inputSurface?.release() } catch (_: Exception) {}
        inputSurface = null
        if (st != null) {
            try { st.release() } catch (_: Exception) {}
        }
        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            if (eglSurface != EGL14.EGL_NO_SURFACE) {
                EGL14.eglDestroySurface(eglDisplay, eglSurface)
                eglSurface = EGL14.EGL_NO_SURFACE
            }
            if (eglContext != EGL14.EGL_NO_CONTEXT) {
                EGL14.eglDestroyContext(eglDisplay, eglContext)
                eglContext = EGL14.EGL_NO_CONTEXT
            }
            EGL14.eglTerminate(eglDisplay)
            eglDisplay = EGL14.EGL_NO_DISPLAY
        }
        targetW = 0
        targetH = 0
    }
}
