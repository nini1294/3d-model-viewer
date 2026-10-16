package space.nish.stlviewer.render

import space.nish.stlviewer.model.BoundingBox
import space.nish.stlviewer.model.Mesh
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

data class Ray(
    val ox: Float, val oy: Float, val oz: Float,
    val dx: Float, val dy: Float, val dz: Float,
)

data class PickResult(
    /** The 3D world coordinates of the picked point (snapped to vertex if close). */
    val point: FloatArray, // x, y, z
    /** Distance along ray from origin to intersection point. */
    val distance: Float,
    /** Whether the point was snapped to a nearby vertex. */
    val isSnappedToVertex: Boolean,
)

object Raycaster {

    /**
     * Intersects [ray] with the axis-aligned [box].
     */
    fun intersectsBox(box: BoundingBox, ray: Ray): Boolean {
        var tmin = Float.NEGATIVE_INFINITY
        var tmax = Float.POSITIVE_INFINITY

        if (abs(ray.dx) > 1e-7f) {
            val t1 = (box.minX - ray.ox) / ray.dx
            val t2 = (box.maxX - ray.ox) / ray.dx
            tmin = max(tmin, min(t1, t2))
            tmax = min(tmax, max(t1, t2))
        } else if (ray.ox < box.minX || ray.ox > box.maxX) {
            return false
        }

        if (abs(ray.dy) > 1e-7f) {
            val t1 = (box.minY - ray.oy) / ray.dy
            val t2 = (box.maxY - ray.oy) / ray.dy
            tmin = max(tmin, min(t1, t2))
            tmax = min(tmax, max(t1, t2))
        } else if (ray.oy < box.minY || ray.oy > box.maxY) {
            return false
        }

        if (abs(ray.dz) > 1e-7f) {
            val t1 = (box.minZ - ray.oz) / ray.dz
            val t2 = (box.maxZ - ray.oz) / ray.dz
            tmin = max(tmin, min(t1, t2))
            tmax = min(tmax, max(t1, t2))
        } else if (ray.oz < box.minZ || ray.oz > box.maxZ) {
            return false
        }

        return tmax >= max(tmin, 0f)
    }

    /**
     * Casts [ray] through [mesh] using Möller–Trumbore ray-triangle intersection.
     * Snaps to the nearest triangle vertex if the hit is within 25% of a corner.
     * Returns the closest hit [PickResult], or null if the ray misses the mesh.
     */
    fun intersect(mesh: Mesh, ray: Ray): PickResult? {
        if (!intersectsBox(mesh.bounds, ray)) return null

        val buf = mesh.vertices.duplicate().apply { position(0) }
        val triangleCount = mesh.triangleCount
        if (triangleCount == 0) return null

        var closestT = Float.POSITIVE_INFINITY
        var bestHitX = 0f
        var bestHitY = 0f
        var bestHitZ = 0f
        var bestSnapped = false

        // Buffer in chunks of up to 1000 triangles (9000 floats) to eliminate per-float JNI overhead.
        val chunkSize = min(triangleCount * 9, 9000)
        val chunk = FloatArray(chunkSize)

        var trianglesLeft = triangleCount
        while (trianglesLeft > 0) {
            val toRead = min(trianglesLeft * 9, chunkSize)
            buf.get(chunk, 0, toRead)
            val numTrianglesInChunk = toRead / 9
            trianglesLeft -= numTrianglesInChunk

            var idx = 0
            for (t in 0 until numTrianglesInChunk) {
                val v0x = chunk[idx]; val v0y = chunk[idx + 1]; val v0z = chunk[idx + 2]
                val v1x = chunk[idx + 3]; val v1y = chunk[idx + 4]; val v1z = chunk[idx + 5]
                val v2x = chunk[idx + 6]; val v2y = chunk[idx + 7]; val v2z = chunk[idx + 8]
                idx += 9

                val edge1X = v1x - v0x; val edge1Y = v1y - v0y; val edge1Z = v1z - v0z
                val edge2X = v2x - v0x; val edge2Y = v2y - v0y; val edge2Z = v2z - v0z

                // pvec = dir x edge2
                val pvecX = ray.dy * edge2Z - ray.dz * edge2Y
                val pvecY = ray.dz * edge2X - ray.dx * edge2Z
                val pvecZ = ray.dx * edge2Y - ray.dy * edge2X

                val det = edge1X * pvecX + edge1Y * pvecY + edge1Z * pvecZ
                if (abs(det) < 1e-7f) continue

                val invDet = 1.0f / det
                val tvecX = ray.ox - v0x; val tvecY = ray.oy - v0y; val tvecZ = ray.oz - v0z
                val u = (tvecX * pvecX + tvecY * pvecY + tvecZ * pvecZ) * invDet
                if (u < 0f || u > 1f) continue

                // qvec = tvec x edge1
                val qvecX = tvecY * edge1Z - tvecZ * edge1Y
                val qvecY = tvecZ * edge1X - tvecX * edge1Z
                val qvecZ = tvecX * edge1Y - tvecY * edge1X

                val v = (ray.dx * qvecX + ray.dy * qvecY + ray.dz * qvecZ) * invDet
                if (v < 0f || u + v > 1f) continue

                val hitDist = (edge2X * qvecX + edge2Y * qvecY + edge2Z * qvecZ) * invDet
                if (hitDist > 1e-5f && hitDist < closestT) {
                    closestT = hitDist

                    val w0 = 1f - u - v
                    val w1 = u
                    val w2 = v

                    // Snap to corner vertex if close (barycentric weight > 0.65)
                    when {
                        w0 > 0.65f -> {
                            bestHitX = v0x; bestHitY = v0y; bestHitZ = v0z
                            bestSnapped = true
                        }
                        w1 > 0.65f -> {
                            bestHitX = v1x; bestHitY = v1y; bestHitZ = v1z
                            bestSnapped = true
                        }
                        w2 > 0.65f -> {
                            bestHitX = v2x; bestHitY = v2y; bestHitZ = v2z
                            bestSnapped = true
                        }
                        else -> {
                            bestHitX = ray.ox + hitDist * ray.dx
                            bestHitY = ray.oy + hitDist * ray.dy
                            bestHitZ = ray.oz + hitDist * ray.dz
                            bestSnapped = false
                        }
                    }
                }
            }
        }

        if (closestT.isInfinite()) return null
        return PickResult(floatArrayOf(bestHitX, bestHitY, bestHitZ), closestT, bestSnapped)
    }
}
