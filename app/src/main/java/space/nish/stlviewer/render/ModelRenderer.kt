package space.nish.stlviewer.render

import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import space.nish.stlviewer.model.BoundingBox
import space.nish.stlviewer.model.Mesh
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

    private var measureVao = 0
    private var measureVbo = 0
    private var measureVertexCount = 0
    private var measurePoint1: FloatArray? = null
    private var measurePoint2: FloatArray? = null
    private var measureDirty = false

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

    fun setMeasurement(p1: FloatArray?, p2: FloatArray?) {
        synchronized(lock) {
            measurePoint1 = p1?.clone()
            measurePoint2 = p2?.clone()
            measureDirty = true
        }
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        // A new EGL context invalidates every previous GL object handle.
        meshVao = 0; meshVbo = 0; meshVertexCount = 0
        gridVao = 0; gridVbo = 0; gridMinorVertices = 0; gridMajorVertices = 0
        measureVao = 0; measureVbo = 0; measureVertexCount = 0

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

        synchronized(lock) {
            meshDirty = true
            measureDirty = true
        }
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES30.glViewport(0, 0, width, height)
        camera.setViewport(width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        uploadMeshIfDirty()
        uploadMeasurementIfDirty()

        val bg = backgroundColor
        GLES30.glClearColor(bg[0], bg[1], bg[2], 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)

        camera.computeMatrices(view, projection, eye)
        Matrix.multiplyMM(mvp, 0, projection, 0, view, 0)

        if (meshVertexCount > 0) drawMesh()
        if (showGrid && gridMinorVertices + gridMajorVertices > 0) drawGrid()
        if (measureVertexCount > 0) drawMeasurement()
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

    private fun uploadMeasurementIfDirty() {
        val p1: FloatArray?
        val p2: FloatArray?
        val radius: Float
        synchronized(lock) {
            if (!measureDirty) return
            measureDirty = false
            p1 = measurePoint1
            p2 = measurePoint2
            radius = mesh?.bounds?.radius ?: 50f
        }

        deleteMeasureBuffers()
        if (p1 == null && p2 == null) return

        val geometry = buildMeasurementGeometry(p1, p2, radius)
        if (geometry.isEmpty()) return

        val ids = IntArray(1)
        GLES30.glGenVertexArrays(1, ids, 0)
        measureVao = ids[0]
        GLES30.glGenBuffers(1, ids, 0)
        measureVbo = ids[0]

        val buffer = java.nio.ByteBuffer.allocateDirect(geometry.size * 4)
            .order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer().put(geometry).apply { position(0) }
        uploadPositions(measureVao, measureVbo, buffer, geometry.size / 3)
        measureVertexCount = geometry.size / 3
    }

    private fun drawMeasurement() {
        GLES30.glUseProgram(lineProgram)
        GLES30.glUniformMatrix4fv(uLineMvp, 1, false, mvp, 0)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBindVertexArray(measureVao)

        // Pass 1: Semi-transparent guide through the model (X-ray pass)
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glUniform4f(uLineColor, 1.0f, 0.72f, 0.12f, 0.35f)
        GLES30.glDrawArrays(GLES30.GL_LINES, 0, measureVertexCount)

        // Pass 2: Solid bright line in front of the model
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glDepthMask(true)
        GLES30.glUniform4f(uLineColor, 1.0f, 0.76f, 0.15f, 1.0f)
        GLES30.glDrawArrays(GLES30.GL_LINES, 0, measureVertexCount)

        GLES30.glBindVertexArray(0)
        GLES30.glDisable(GLES30.GL_BLEND)
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
        deleteMeasureBuffers()
    }

    private fun deleteMeasureBuffers() {
        if (measureVbo != 0) GLES30.glDeleteBuffers(1, intArrayOf(measureVbo), 0)
        if (measureVao != 0) GLES30.glDeleteVertexArrays(1, intArrayOf(measureVao), 0)
        measureVao = 0; measureVbo = 0; measureVertexCount = 0
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

        internal fun buildMeasurementGeometry(p1: FloatArray?, p2: FloatArray?, radius: Float): FloatArray {
            val list = ArrayList<Float>()
            val s = if (radius > 0f && radius.isFinite()) max(radius * 0.025f, 0.5f) else 1f

            fun addPointMarker(p: FloatArray) {
                val x = p[0]; val y = p[1]; val z = p[2]
                // 3-axis crosshair
                list.addAll(listOf(
                    x - s, y, z,   x + s, y, z,
                    x, y - s, z,   x, y + s, z,
                    x, y, z - s,   x, y, z + s
                ))
                // Octahedron diamond edges
                val top = floatArrayOf(x, y, z + s)
                val bot = floatArrayOf(x, y, z - s)
                val r0 = floatArrayOf(x + s, y, z)
                val r1 = floatArrayOf(x, y + s, z)
                val r2 = floatArrayOf(x - s, y, z)
                val r3 = floatArrayOf(x, y - s, z)
                val ring = arrayOf(r0, r1, r2, r3)

                for (i in 0 until 4) {
                    val cur = ring[i]
                    val nxt = ring[(i + 1) % 4]
                    list.addAll(listOf(top[0], top[1], top[2], cur[0], cur[1], cur[2]))
                    list.addAll(listOf(bot[0], bot[1], bot[2], cur[0], cur[1], cur[2]))
                    list.addAll(listOf(cur[0], cur[1], cur[2], nxt[0], nxt[1], nxt[2]))
                }
            }

            p1?.let { addPointMarker(it) }
            p2?.let { addPointMarker(it) }

            if (p1 != null && p2 != null) {
                // Connecting line
                list.addAll(listOf(
                    p1[0], p1[1], p1[2],
                    p2[0], p2[1], p2[2]
                ))

                // End-cap tick marks
                val dx = p2[0] - p1[0]
                val dy = p2[1] - p1[1]
                val dz = p2[2] - p1[2]
                val len = kotlin.math.sqrt(dx * dx + dy * dy + dz * dz)
                if (len > 1e-4f) {
                    val ndx = dx / len
                    val ndy = dy / len
                    val ndz = dz / len
                    val ux: Float; val uy: Float; val uz: Float
                    if (kotlin.math.abs(ndz) < 0.9f) {
                        val cx = -ndy; val cy = ndx; val cz = 0f
                        val clen = kotlin.math.sqrt(cx * cx + cy * cy)
                        ux = cx / clen; uy = cy / clen; uz = 0f
                    } else {
                        val cx = ndz; val cy = 0f; val cz = -ndx
                        val clen = kotlin.math.sqrt(cx * cx + cz * cz)
                        ux = cx / clen; uy = 0f; uz = cz / clen
                    }
                    val capS = s * 0.75f
                    // Ticks at p1
                    list.addAll(listOf(
                        p1[0] - ux * capS, p1[1] - uy * capS, p1[2] - uz * capS,
                        p1[0] + ux * capS, p1[1] + uy * capS, p1[2] + uz * capS
                    ))
                    // Ticks at p2
                    list.addAll(listOf(
                        p2[0] - ux * capS, p2[1] - uy * capS, p2[2] - uz * capS,
                        p2[0] + ux * capS, p2[1] + uy * capS, p2[2] + uz * capS
                    ))
                }
            }

            val out = FloatArray(list.size)
            list.forEachIndexed { i, f -> out[i] = f }
            return out
        }
    }
}
