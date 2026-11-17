package com.yinu.app.records

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.opengl.GLUtils
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.view.Choreographer
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.ceil
import kotlin.math.sqrt

private const val DustSeconds = 0.78f
internal val LocalParticleDust = staticCompositionLocalOf<ParticleDustHost?> { null }

@Composable
internal fun rememberParticleDust(topPadding: Dp): ParticleDustHost {
    val view = LocalView.current
    val top = with(LocalDensity.current) { topPadding.toPx() }
    val host = remember(view) { ParticleDustHost(view.rootView as? ViewGroup) }
    SideEffect { host.clipTop = top }
    DisposableEffect(host) { onDispose { host.dispose() } }
    return host
}

private data class DustPacket(val id: String, val bitmap: Bitmap, @Volatile var bounds: Rect)

/** A purely visual ViewGroupOverlay never participates in Compose hit testing. */
internal class ParticleDustHost(private val root: ViewGroup?) {
    private val main = Handler(Looper.getMainLooper())
    private val context = root?.context
    private val manager = context?.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
    private val capable = root != null && manager?.isLowRamDevice != true &&
        manager?.deviceConfigurationInfo?.reqGlEsVersion?.let { it >= 0x20000 } == true
    var failed by mutableStateOf(false)
        private set
    val supported get() = capable && !failed
    var clipTop = 0f
    private val tracked = mutableStateMapOf<String, Boolean>()
    private val packets = mutableMapOf<String, DustPacket>()
    private val pending = mutableListOf<DustPacket>()
    private var texture: TextureView? = null
    private var engine: DustEngine? = null
    private var disposed = false
    private val rootLocation = IntArray(2)
    private val resize = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
        texture?.layout(0, 0, root?.width ?: 0, root?.height ?: 0)
    }

    fun isDrawing(id: String) = tracked[id] == true
    fun release(id: String) { tracked.remove(id) }
    fun cancel(id: String) {
        pending.removeAll { if (it.id == id) { it.bitmap.recycle(); true } else false }
        packets.remove(id)
        tracked.remove(id)
        engine?.cancel(id)
    }
    fun update(id: String, bounds: Rect) {
        val packet = packets[id] ?: return
        root?.getLocationInWindow(rootLocation)
        packet.bounds = bounds.translate(-rootLocation[0].toFloat(), -rootLocation[1].toFloat())
    }

    // Ownership of the bitmap passes to the renderer only when true is returned.
    fun submit(id: String, bitmap: Bitmap, bounds: Rect): Boolean {
        if (!supported || disposed || packets.size >= 8 || root?.isAttachedToWindow != true || bounds.isEmpty) return false
        root.getLocationInWindow(rootLocation)
        val packet = DustPacket(id, bitmap, bounds.translate(-rootLocation[0].toFloat(), -rootLocation[1].toFloat()))
        packets[id] = packet
        tracked[id] = false
        val running = engine
        if (running != null) running.add(packet) else pending += packet
        if (texture == null) {
            val surface = TextureView(root.context).apply {
                isOpaque = false
                isClickable = false
                isFocusable = false
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            }
            surface.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(source: SurfaceTexture, width: Int, height: Int) {
                    if (disposed) { source.release(); return }
                    val renderer = DustEngine(source, width, height, root.resources.displayMetrics.density,
                        top = { clipTop },
                        started = { id -> main.post { if (tracked.containsKey(id)) tracked[id] = true } },
                        done = { id -> main.post { packets.remove(id) } },
                        idle = { main.post { if (packets.isEmpty()) detach() } },
                        failure = { error -> main.post {
                            android.util.Log.w("YinuDust", "GPU effect unavailable; using fade", error)
                            failed = true
                            detach()
                        } },
                    )
                    engine = renderer
                    pending.toList().forEach(renderer::add)
                    pending.clear()
                }
                override fun onSurfaceTextureSizeChanged(source: SurfaceTexture, width: Int, height: Int) { engine?.resize(width, height) }
                override fun onSurfaceTextureUpdated(source: SurfaceTexture) = Unit
                override fun onSurfaceTextureDestroyed(source: SurfaceTexture): Boolean {
                    if (engine == null) source.release() else engine?.close()
                    engine = null
                    return false // The EGL thread releases the SurfaceTexture after its surface.
                }
            }
            texture = surface
            surface.layout(0, 0, root.width, root.height)
            root.addOnLayoutChangeListener(resize)
            root.overlay.add(surface)
        }
        return true
    }

    private fun detach() {
        val view = texture
        texture = null
        root?.removeOnLayoutChangeListener(resize)
        if (view != null) root?.overlay?.remove(view)
        engine?.close()
        engine = null
        pending.forEach { if (!it.bitmap.isRecycled) it.bitmap.recycle() }
        pending.clear()
        packets.clear()
        // Completed rows remain hidden until their database deletion/rollback.
    }

    fun dispose() { disposed = true; detach(); tracked.clear() }
}

/** One EGL context and one GPU point batch per visible message; no per-frame bitmaps. */
private class DustEngine(
    private val source: SurfaceTexture,
    private var width: Int,
    private var height: Int,
    private val density: Float,
    private val top: () -> Float,
    private val started: (String) -> Unit,
    private val done: (String) -> Unit,
    private val idle: () -> Unit,
    private val failure: (Throwable) -> Unit,
) : Choreographer.FrameCallback {
    private val thread = HandlerThread("YinuParticles").apply { start() }
    private val handler = Handler(thread.looper)
    @Volatile private var closed = false
    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var surface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var program = 0
    private var intactProgram = 0
    private var quadBuffer = 0
    private var choreographer: Choreographer? = null
    private var frameScheduled = false
    private val jobs = mutableListOf<Particles>()
    private data class Particles(val packet: DustPacket, val texture: Int, val vertices: Int, val count: Int,
        val columns: Int, val rows: Int, var start: Long = 0)

    init {
        handler.post {
            try { initialize(); choreographer = Choreographer.getInstance() }
            catch (error: Throwable) { failure(error); shutdown() }
        }
    }

    fun add(packet: DustPacket) {
        if (closed) { packet.bitmap.recycle(); return }
        handler.post {
            if (closed) { if (!packet.bitmap.isRecycled) packet.bitmap.recycle(); return@post }
            try {
                val remaining = (52_000 - jobs.sumOf { it.count }).coerceAtLeast(400)
                val area = packet.bounds.width * packet.bounds.height
                val count = (area / (density * density * 0.7f)).toInt().coerceIn(32, minOf(14_000, remaining))
                val aspect = (packet.bounds.width / packet.bounds.height).coerceIn(0.05f, 40f)
                val columns = ceil(sqrt(count * aspect)).toInt().coerceAtLeast(1)
                val rows = ceil(count.toFloat() / columns).toInt().coerceAtLeast(1)
                val actualCount = columns * rows
                val points = ByteBuffer.allocateDirect(actualCount * 8).order(ByteOrder.nativeOrder()).asFloatBuffer()
                for (y in 0 until rows) for (x in 0 until columns) {
                    points.put((x + 0.5f) / columns).put((y + 0.5f) / rows)
                }
                points.position(0)
                val buffers = IntArray(1)
                GLES20.glGenBuffers(1, buffers, 0)
                GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, buffers[0])
                GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, actualCount * 8, points, GLES20.GL_STATIC_DRAW)
                val textures = IntArray(1)
                GLES20.glGenTextures(1, textures, 0)
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textures[0])
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
                GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, packet.bitmap, 0)
                check(GLES20.glGetError() == GLES20.GL_NO_ERROR) { "Cannot allocate particle buffers" }
                jobs += Particles(packet, textures[0], buffers[0], actualCount, columns, rows)
                schedule()
            } catch (error: Throwable) { failure(error); shutdown() }
            finally { if (!packet.bitmap.isRecycled) packet.bitmap.recycle() }
        }
    }

    fun resize(w: Int, h: Int) { if (!closed) handler.post { width = w; height = h } }
    fun cancel(id: String) {
        if (!closed) handler.post {
            val iterator = jobs.iterator()
            while (iterator.hasNext()) {
                val job = iterator.next()
                if (job.packet.id == id) { delete(job); iterator.remove(); done(id) }
            }
            if (jobs.isEmpty()) idle()
        }
    }
    fun close() { if (!closed) handler.post { shutdown() } }
    private fun schedule() {
        if (!closed && !frameScheduled) { frameScheduled = true; choreographer?.postFrameCallback(this) }
    }

    override fun doFrame(frameTimeNanos: Long) {
        frameScheduled = false
        if (closed) return
        try {
            GLES20.glViewport(0, 0, width, height)
            GLES20.glClearColor(0f, 0f, 0f, 0f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            GLES20.glEnable(GLES20.GL_BLEND)
            GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            val firstFrames = mutableListOf<String>()
            val iterator = jobs.iterator()
            while (iterator.hasNext()) {
                val job = iterator.next()
                if (job.start == 0L) { job.start = frameTimeNanos; firstFrames += job.packet.id }
                val time = (frameTimeNanos - job.start) / 1_000_000_000f
                if (time > DustSeconds) {
                    delete(job); iterator.remove(); done(job.packet.id); continue
                }
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, job.texture)
                // Preserve the exact original pixels until each cell detaches.
                bind(intactProgram, job, time)
                GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, quadBuffer)
                val quadAttribute = GLES20.glGetAttribLocation(intactProgram, "aUv")
                GLES20.glEnableVertexAttribArray(quadAttribute)
                GLES20.glVertexAttribPointer(quadAttribute, 2, GLES20.GL_FLOAT, false, 8, 0)
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
                bind(program, job, time)
                GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, job.vertices)
                val attribute = GLES20.glGetAttribLocation(program, "aUv")
                GLES20.glEnableVertexAttribArray(attribute)
                GLES20.glVertexAttribPointer(attribute, 2, GLES20.GL_FLOAT, false, 8, 0)
                GLES20.glDrawArrays(GLES20.GL_POINTS, 0, job.count)
            }
            check(EGL14.eglSwapBuffers(display, surface)) { "Cannot present particle frame" }
            firstFrames.forEach(started)
            if (jobs.isNotEmpty()) schedule() else idle()
        } catch (error: Throwable) { failure(error); shutdown() }
    }

    private fun bind(program: Int, job: Particles, time: Float) {
        GLES20.glUseProgram(program)
        val bounds = job.packet.bounds
        GLES20.glUniform2f(GLES20.glGetUniformLocation(program, "uViewport"), width.toFloat(), height.toFloat())
        GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uDensity"), density)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uClipTop"), top())
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uTexture"), 0)
        GLES20.glUniform4f(GLES20.glGetUniformLocation(program, "uRect"), bounds.left, bounds.top, bounds.width, bounds.height)
        GLES20.glUniform2f(GLES20.glGetUniformLocation(program, "uGrid"), job.columns.toFloat(), job.rows.toFloat())
        GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uTime"), time)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uSeed"), (job.packet.id.hashCode() and 0xffff) / 1000f)
    }

    private fun initialize() {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(display != EGL14.EGL_NO_DISPLAY)
        check(EGL14.eglInitialize(display, IntArray(2), 0, IntArray(2), 1))
        val configs = arrayOfNulls<EGLConfig>(1)
        val attributes = intArrayOf(EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8, EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT, EGL14.EGL_SURFACE_TYPE,
            EGL14.EGL_WINDOW_BIT, EGL14.EGL_NONE)
        check(EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, IntArray(1), 0))
        context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
        check(context != EGL14.EGL_NO_CONTEXT)
        surface = EGL14.eglCreateWindowSurface(display, configs[0], source, intArrayOf(EGL14.EGL_NONE), 0)
        check(surface != EGL14.EGL_NO_SURFACE)
        check(EGL14.eglMakeCurrent(display, surface, surface, context))
        program = program(DustVertex, DustFragment)
        intactProgram = program(DustQuadVertex, DustQuadFragment)
        val buffers = IntArray(1)
        GLES20.glGenBuffers(1, buffers, 0)
        quadBuffer = buffers[0]
        val quad = ByteBuffer.allocateDirect(32).order(ByteOrder.nativeOrder()).asFloatBuffer()
        quad.put(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)); quad.position(0)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, quadBuffer)
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, 32, quad, GLES20.GL_STATIC_DRAW)
    }

    private fun program(vertexSource: String, fragmentSource: String): Int {
        val vertex = shader(GLES20.GL_VERTEX_SHADER, vertexSource)
        val fragment = shader(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
        val program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vertex); GLES20.glAttachShader(program, fragment)
        GLES20.glLinkProgram(program)
        val linked = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linked, 0)
        check(linked[0] != 0) { GLES20.glGetProgramInfoLog(program) }
        GLES20.glDeleteShader(vertex); GLES20.glDeleteShader(fragment)
        return program
    }

    private fun shader(type: Int, code: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, code); GLES20.glCompileShader(shader)
        val compiled = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
        check(compiled[0] != 0) { GLES20.glGetShaderInfoLog(shader) }
        return shader
    }

    private fun delete(job: Particles) {
        GLES20.glDeleteTextures(1, intArrayOf(job.texture), 0)
        GLES20.glDeleteBuffers(1, intArrayOf(job.vertices), 0)
    }

    private fun shutdown() {
        if (closed) return
        closed = true
        choreographer?.removeFrameCallback(this)
        jobs.forEach { delete(it); done(it.packet.id) }; jobs.clear()
        if (program != 0) GLES20.glDeleteProgram(program)
        if (intactProgram != 0) GLES20.glDeleteProgram(intactProgram)
        if (quadBuffer != 0) GLES20.glDeleteBuffers(1, intArrayOf(quadBuffer), 0)
        if (display != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface)
            if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
            EGL14.eglTerminate(display)
        }
        source.release()
        thread.quitSafely()
    }
}

// Original analytic particle shaders. Studied Telegram's texture-point approach;
// positions are evaluated from elapsed time rather than transform-feedback state.
internal val DustVertex = """
precision highp float;
attribute vec2 aUv;
uniform vec2 uViewport;
uniform vec4 uRect;
uniform vec2 uGrid;
uniform float uTime;
uniform float uDensity;
uniform float uSeed;
varying vec2 vUv;
varying vec2 vCell;
varying float vAlpha;
float noise(vec2 p) { return fract(sin(dot(p, vec2(127.1, 311.7)) + uSeed) * 43758.5453); }
void main() {
    vec2 cellId = floor(aUv * uGrid);
    float r = noise(cellId + 1.0);
    float q = noise(cellId.yx + 19.0);
    float delay = (1.0 - aUv.x) * 0.24 + r * 0.045;
    float age = max(0.0, uTime - delay);
    float life = 0.30 + q * 0.20;
    float p = clamp(age / life, 0.0, 1.0);
    vec2 velocity = vec2(14.0 + 74.0 * r, -18.0 - 54.0 * q) * uDensity;
    vec2 drift = velocity * age + vec2(19.0, -54.0) * uDensity * age * age;
    drift += vec2(sin(age * 9.0 + r * 6.28), cos(age * 7.0 + q * 6.28)) * uDensity * 2.0 * p;
    vec2 position = uRect.xy + aUv * uRect.zw + drift;
    gl_Position = vec4(position.x / uViewport.x * 2.0 - 1.0, 1.0 - position.y / uViewport.y * 2.0, 0.0, 1.0);
    vec2 cell = uRect.zw / uGrid;
    float pointSize = max(cell.x, cell.y) + 0.75;
    gl_PointSize = max(0.5, pointSize * (1.0 - 0.36 * p));
    vUv = aUv;
    vCell = vec2(pointSize) / uRect.zw;
    vAlpha = age > 0.0 ? 1.0 - smoothstep(0.14, 1.0, p) : 0.0;
}
""".trimIndent()

internal val DustQuadVertex = """
precision highp float;
attribute vec2 aUv;
uniform vec2 uViewport;
uniform vec4 uRect;
varying vec2 vUv;
void main() {
    vec2 position = uRect.xy + aUv * uRect.zw;
    gl_Position = vec4(position.x / uViewport.x * 2.0 - 1.0, 1.0 - position.y / uViewport.y * 2.0, 0.0, 1.0);
    vUv = aUv;
}
""".trimIndent()

internal val DustQuadFragment = """
precision highp float;
uniform sampler2D uTexture;
uniform vec2 uViewport;
uniform float uClipTop;
uniform float uTime;
uniform float uSeed;
uniform vec2 uGrid;
varying vec2 vUv;
float noise(vec2 p) { return fract(sin(dot(p, vec2(127.1, 311.7)) + uSeed) * 43758.5453); }
void main() {
    if (uViewport.y - gl_FragCoord.y < uClipTop) discard;
    vec2 cellId = floor(vUv * uGrid);
    float delay = (1.0 - (cellId.x + 0.5) / uGrid.x) * 0.24 + noise(cellId + 1.0) * 0.045;
    if (uTime > delay) discard;
    gl_FragColor = texture2D(uTexture, vUv);
}
""".trimIndent()

internal val DustFragment = """
precision highp float;
uniform sampler2D uTexture;
uniform vec2 uViewport;
uniform float uClipTop;
varying vec2 vUv;
varying vec2 vCell;
varying float vAlpha;
void main() {
    if (vAlpha < 0.001) discard;
    if (uViewport.y - gl_FragCoord.y < uClipTop) discard;
    vec2 uv = vUv + vec2(gl_PointCoord.x - 0.5, 0.5 - gl_PointCoord.y) * vCell;
    gl_FragColor = texture2D(uTexture, clamp(uv, vec2(0.0), vec2(1.0))) * vAlpha;
}
""".trimIndent()
