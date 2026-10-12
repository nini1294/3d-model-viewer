package io.github.nishant.stlviewer.render

import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import io.github.nishant.stlviewer.model.BoundingBox
import io.github.nishant.stlviewer.model.Mesh
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToLong

enum class RenderMode(internal val shaderValue: Int) { SHADED(0), SHADED_EDGES(1), WIREFRAME(2) }

/**
 * Draws a [Mesh] plus a build-plate grid. Public setters may be called from any thread; all GL work happens on
 * the GL thread inside the [GLSurfaceView.Renderer] callbacks.
 */
class ModelRenderer(private val camera: OrbitCamera) : GLSurfaceView.Renderer {

    @Volatile var renderMode = RenderMode.SHADED
    @Volatile var showGrid = true
    @Volatile var modelColor = floatArrayOf(0.36f, 0.60f, 0.90f)
    @Volatile var backgroundColor = floatArrayOf(0.12f, 0.12f, 0.14f)
    @Volatile var darkBackground = true

    /** Invoked on the GL thread when the GPU cannot hold the model. */
    @Volatile var onError: ((String) -> Unit)? = null

    private val lock = Any()
    private var mesh: Mesh? = null
    private var meshDirty = false

    private var meshProgram = 0
    private var uMeshView = 0
    private var uMeshMvp = 0
    private var uMeshColor = 0
    private var uMeshEdgeColor = 0
    private var uMeshMode = 0

    private var lineProgram = 0
    private var uLineMvp = 0
    private var uLineColor = 0

    private var meshVao = 0
    private var meshVbo = 0
    private var meshVertexCount = 0

    private var gridVao = 0
    private var gridVbo = 0
    private var gridMinorVertices = 0
    private var gridMajorVertices = 0

    private val view = FloatArray(16)
    private val projection = FloatArray(16)
    private val mvp = FloatArray(16)
    private val eye = FloatArray(3)

    fun setMesh(newMesh: Mesh?) {
        synchronized(lock) {
            mesh = newMesh
            meshDirty = true
        }
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        // A new EGL context invalidates every previous GL object handle.
        meshVao = 0; meshVbo = 0; meshVertexCount = 0
        gridVao = 0; gridVbo = 0; gridMinorVertices = 0; gridMajorVertices = 0

        meshProgram = GlUtil.createProgram(Shaders.MESH_VERTEX, Shaders.MESH_FRAGMENT)
        uMeshView = GLES30.glGetUniformLocation(meshProgram, "uView")
        uMeshMvp = GLES30.glGetUniformLocation(meshProgram, "uMvp")
        uMeshColor = GLES30.glGetUniformLocation(meshProgram, "uColor")
        uMeshEdgeColor = GLES30.glGetUniformLocation(meshProgram, "uEdgeColor")
        uMeshMode = GLES30.glGetUniformLocation(meshProgram, "uMode")

        lineProgram = GlUtil.createProgram(Shaders.LINE_VERTEX, Shaders.LINE_FRAGMENT)
        uLineMvp = GLES30.glGetUniformLocation(lineProgram, "uMvp")
        uLineColor = GLES30.glGetUniformLocation(lineProgram, "uColor")

        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glDepthFunc(GLES30.GL_LEQUAL)
        GLES30.glDisable(GLES30.GL_CULL_FACE) // STL winding is often unreliable: render both sides
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)

        synchronized(lock) { meshDirty = true }
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES30.glViewport(0, 0, width, height)
        camera.setViewport(width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        uploadMeshIfDirty()

        val bg = backgroundColor
        GLES30.glClearColor(bg[0], bg[1], bg[2], 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)

        camera.computeMatrices(view, projection, eye)
        Matrix.multiplyMM(mvp, 0, projection, 0, view, 0)

        if (meshVertexCount > 0) drawMesh()
        if (showGrid && gridMinorVertices + gridMajorVertices > 0) drawGrid()
    }

    private fun drawMesh() {
        val mode = renderMode
        GLES30.glUseProgram(meshProgram)
        GLES30.glUniformMatrix4fv(uMeshView, 1, false, view, 0)
        GLES30.glUniformMatrix4fv(uMeshMvp, 1, false, mvp, 0)
        val c = modelColor
        GLES30.glUniform3f(uMeshColor, c[0], c[1], c[2])
        GLES30.glUniform3f(uMeshEdgeColor, 0.08f, 0.09f, 0.11f)
        GLES30.glUniform1i(uMeshMode, mode.shaderValue)
        if (mode == RenderMode.WIREFRAME) GLES30.glEnable(GLES30.GL_BLEND) else GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glBindVertexArray(meshVao)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, meshVertexCount)
        GLES30.glBindVertexArray(0)
    }

    private fun drawGrid() {
        GLES30.glUseProgram(lineProgram)
        GLES30.glUniformMatrix4fv(uLineMvp, 1, false, mvp, 0)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glDepthMask(false)
        GLES30.glBindVertexArray(gridVao)
        val v = if (darkBackground) 1f else 0f
        GLES30.glUniform4f(uLineColor, v, v, v, 0.10f)
        GLES30.glDrawArrays(GLES30.GL_LINES, 0, gridMinorVertices)
        GLES30.glUniform4f(uLineColor, v, v, v, 0.28f)
        GLES30.glDrawArrays(GLES30.GL_LINES, gridMinorVertices, gridMajorVertices)
        GLES30.glBindVertexArray(0)
        GLES30.glDepthMask(true)
        GLES30.glDisable(GLES30.GL_BLEND)
    }

    private fun uploadMeshIfDirty() {
        val current: Mesh?
        synchronized(lock) {
            if (!meshDirty) return
            meshDirty = false
            current = mesh
        }
        deleteBuffers()
        if (current == null) return

        val ids = IntArray(2)
        GLES30.glGenVertexArrays(2, ids, 0)
        meshVao = ids[0]; gridVao = ids[1]
        GLES30.glGenBuffers(2, ids, 0)
        meshVbo = ids[0]; gridVbo = ids[1]

        var drained = 0
        while (GLES30.glGetError() != GLES30.GL_NO_ERROR && drained++ < 16) { /* clear stale errors */ }
        val data = current.vertices.duplicate().apply { position(0) }
        uploadPositions(meshVao, meshVbo, data, current.vertexCount)
        if (GLES30.glGetError() == GLES30.GL_OUT_OF_MEMORY) {
            deleteBuffers()
            onError?.invoke("Not enough GPU memory to display this model")
            return
        }
        meshVertexCount = current.vertexCount

        val (grid, minorVertices) = buildGrid(current.bounds)
        val gridBuffer = java.nio.ByteBuffer.allocateDirect(grid.size * 4)
            .order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer().put(grid).apply { position(0) }
        uploadPositions(gridVao, gridVbo, gridBuffer, grid.size / 3)
        gridMinorVertices = minorVertices
        gridMajorVertices = grid.size / 3 - minorVertices
    }

    private fun uploadPositions(vao: Int, vbo: Int, data: java.nio.FloatBuffer, vertexCount: Int) {
        GLES30.glBindVertexArray(vao)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, vertexCount * 3 * 4, data, GLES30.GL_STATIC_DRAW)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, 3 * 4, 0)
        GLES30.glBindVertexArray(0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
    }

    private fun deleteBuffers() {
        if (meshVbo != 0 || gridVbo != 0) GLES30.glDeleteBuffers(2, intArrayOf(meshVbo, gridVbo), 0)
        if (meshVao != 0 || gridVao != 0) GLES30.glDeleteVertexArrays(2, intArrayOf(meshVao, gridVao), 0)
        meshVao = 0; meshVbo = 0; meshVertexCount = 0
        gridVao = 0; gridVbo = 0; gridMinorVertices = 0; gridMajorVertices = 0
    }

    companion object {
        /**
         * Builds GL_LINES vertices for a grid lying just under the model. Minor lines come first; returns the
         * vertex array and the number of minor-line vertices.
         */
        internal fun buildGrid(b: BoundingBox): Pair<FloatArray, Int> {
            val half = max(max(b.sizeX, b.sizeY) * 0.5f * 1.5f, b.radius)
            val step = niceStep(half * 2f / 20f)
            val cx = (b.centerX / step).roundToLong() * step
            val cy = (b.centerY / step).roundToLong() * step
            val n = ceil(half / step).toInt()
            val z = b.minZ - b.radius * 0.002f
            val extent = n * step

            val minor = ArrayList<Float>()
            val major = ArrayList<Float>()
            for (i in -n..n) {
                val x = cx + i * step
                val y = cy + i * step
                val isMajor = ((x / step).roundToLong() % 5L == 0L)
                val isMajorY = ((y / step).roundToLong() % 5L == 0L)
                (if (isMajor) major else minor).addAll(listOf(x, cy - extent, z, x, cy + extent, z))
                (if (isMajorY) major else minor).addAll(listOf(cx - extent, y, z, cx + extent, y, z))
            }
            val out = FloatArray(minor.size + major.size)
            minor.forEachIndexed { i, f -> out[i] = f }
            major.forEachIndexed { i, f -> out[minor.size + i] = f }
            return out to minor.size / 3
        }

        /** Rounds [raw] up to 1, 2 or 5 × 10^k. */
        internal fun niceStep(raw: Float): Float {
            if (raw <= 0f || !raw.isFinite()) return 1f
            val magnitude = 10.0.pow(floor(log10(raw.toDouble()))).toFloat()
            val normalized = raw / magnitude
            val nice = when {
                normalized <= 1f -> 1f
                normalized <= 2f -> 2f
                normalized <= 5f -> 5f
                else -> 10f
            }
            return nice * magnitude
        }
    }
}
