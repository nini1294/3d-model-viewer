package space.nish.stlviewer.model

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/** Axis-aligned bounding box in model units (STL is unitless; we assume millimetres). */
data class BoundingBox(
    val minX: Float, val minY: Float, val minZ: Float,
    val maxX: Float, val maxY: Float, val maxZ: Float,
) {
    val sizeX get() = maxX - minX
    val sizeY get() = maxY - minY
    val sizeZ get() = maxZ - minZ
    val centerX get() = (minX + maxX) * 0.5f
    val centerY get() = (minY + maxY) * 0.5f
    val centerZ get() = (minZ + maxZ) * 0.5f

    /** Radius of the bounding sphere enclosing the box. Never zero, so it is safe for camera maths. */
    val radius: Float
        get() {
            val r = 0.5f * sqrt(sizeX * sizeX + sizeY * sizeY + sizeZ * sizeZ)
            return if (r > 0f) r else 1f
        }
}

/** Geometric statistics of a mesh. Volume is only meaningful for closed (watertight) meshes. */
data class MeshStats(
    val triangleCount: Int,
    /** Cubic model units (mm³). */
    val volume: Double,
    /** Square model units (mm²). */
    val surfaceArea: Double,
)

/**
 * A triangle soup ready for the GPU.
 *
 * [vertices] is a direct, native-order buffer containing `x, y, z` for every vertex, three vertices per
 * triangle and no indices. Normals are intentionally not stored: the renderer derives flat normals in the
 * fragment shader, which halves memory use for large models.
 */
class Mesh internal constructor(
    val vertices: FloatBuffer,
    val triangleCount: Int,
    val bounds: BoundingBox,
    val stats: MeshStats,
) {
    val vertexCount: Int get() = triangleCount * 3
}

/**
 * Incrementally builds a [Mesh], tracking bounds as triangles are added. Grows its backing buffer when the
 * final triangle count is not known in advance (ASCII STL).
 */
internal class MeshBuilder(initialTriangleCapacity: Int) {
    private var buffer: FloatBuffer = allocate(max(initialTriangleCapacity, 1))
    var triangleCount = 0
        private set

    private var minX = Float.POSITIVE_INFINITY
    private var minY = Float.POSITIVE_INFINITY
    private var minZ = Float.POSITIVE_INFINITY
    private var maxX = Float.NEGATIVE_INFINITY
    private var maxY = Float.NEGATIVE_INFINITY
    private var maxZ = Float.NEGATIVE_INFINITY

    /** Adds one triangle from [v] (9 floats: x0 y0 z0 x1 y1 z1 x2 y2 z2). Non-finite triangles are skipped. */
    fun addTriangle(v: FloatArray) {
        for (i in 0 until 9) if (!v[i].isFinite()) return
        if (buffer.remaining() < 9) grow()
        buffer.put(v, 0, 9)
        var i = 0
        while (i < 9) {
            val x = v[i]; val y = v[i + 1]; val z = v[i + 2]
            if (x < minX) minX = x
            if (x > maxX) maxX = x
            if (y < minY) minY = y
            if (y > maxY) maxY = y
            if (z < minZ) minZ = z
            if (z > maxZ) maxZ = z
            i += 3
        }
        triangleCount++
    }

    fun build(): Mesh {
        if (triangleCount == 0) throw ModelParseException("The file does not contain any triangles")
        val data = buffer.duplicate()
        data.flip()
        val bounds = BoundingBox(minX, minY, minZ, maxX, maxY, maxZ)
        val stats = computeStats(data, triangleCount, bounds)
        return Mesh(data.slice(), triangleCount, bounds, stats)
    }

    private fun grow() {
        val currentTriangles = buffer.capacity() / 9
        val newTriangles = if (currentTriangles < 1024) 1024 else currentTriangles + currentTriangles / 2
        val bigger = allocate(newTriangles)
        buffer.flip()
        bigger.put(buffer)
        buffer = bigger
    }

    private companion object {
        fun allocate(triangles: Int): FloatBuffer {
            val bytes = triangles.toLong() * 9L * 4L
            require(bytes <= Int.MAX_VALUE) { "Model is too large" }
            return ByteBuffer.allocateDirect(bytes.toInt()).order(ByteOrder.nativeOrder()).asFloatBuffer()
        }

        fun computeStats(data: FloatBuffer, triangles: Int, bounds: BoundingBox): MeshStats {
            // Use coordinates relative to the box centre to limit floating point error in the volume sum.
            val cx = bounds.centerX.toDouble()
            val cy = bounds.centerY.toDouble()
            val cz = bounds.centerZ.toDouble()
            var volume6 = 0.0
            var area2 = 0.0
            var o = 0
            for (t in 0 until triangles) {
                val ax = data.get(o) - cx; val ay = data.get(o + 1) - cy; val az = data.get(o + 2) - cz
                val bx = data.get(o + 3) - cx; val by = data.get(o + 4) - cy; val bz = data.get(o + 5) - cz
                val qx = data.get(o + 6) - cx; val qy = data.get(o + 7) - cy; val qz = data.get(o + 8) - cz
                // Signed volume of tetrahedron (origin, a, b, c) * 6 = a · (b × c)
                volume6 += ax * (by * qz - bz * qy) - ay * (bx * qz - bz * qx) + az * (bx * qy - by * qx)
                // Area * 2 = |(b - a) × (c - a)|
                val ux = bx - ax; val uy = by - ay; val uz = bz - az
                val vx = qx - ax; val vy = qy - ay; val vz = qz - az
                val nx = uy * vz - uz * vy
                val ny = uz * vx - ux * vz
                val nz = ux * vy - uy * vx
                area2 += sqrt(nx * nx + ny * ny + nz * nz)
                o += 9
            }
            return MeshStats(triangles, abs(volume6) / 6.0, area2 / 2.0)
        }
    }
}
