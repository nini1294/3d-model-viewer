package space.nish.stlviewer.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import space.nish.stlviewer.model.BoundingBox
import space.nish.stlviewer.model.MeshBuilder

class RaycasterTest {

    @Test
    fun testBoxIntersection() {
        val box = BoundingBox(minX = -10f, minY = -10f, minZ = -10f, maxX = 10f, maxY = 10f, maxZ = 10f)

        // Ray pointing straight at center
        val hitRay = Ray(ox = 0f, oy = 0f, oz = 30f, dx = 0f, dy = 0f, dz = -1f)
        assertTrue(Raycaster.intersectsBox(box, hitRay))

        // Ray pointing away
        val missRay = Ray(ox = 0f, oy = 0f, oz = 30f, dx = 0f, dy = 0f, dz = 1f)
        assertFalse(Raycaster.intersectsBox(box, missRay))

        // Ray off to the side
        val sideRay = Ray(ox = 30f, oy = 30f, oz = 0f, dx = 0f, dy = 1f, dz = 0f)
        assertFalse(Raycaster.intersectsBox(box, sideRay))
    }

    @Test
    fun testTriangleIntersectionExactCenter() {
        val builder = MeshBuilder(1)
        // Triangle on Z=0 plane: (0, 0), (10, 0), (0, 10)
        builder.addTriangle(floatArrayOf(
            0f, 0f, 0f,
            10f, 0f, 0f,
            0f, 10f, 0f,
        ))
        val mesh = builder.build()

        // Ray from (2, 2, 10) pointing down along -Z
        val ray = Ray(ox = 2f, oy = 2f, oz = 10f, dx = 0f, dy = 0f, dz = -1f)
        val hit = Raycaster.intersect(mesh, ray)

        assertNotNull(hit)
        assertEquals(2f, hit!!.point[0], 1e-4f)
        assertEquals(2f, hit.point[1], 1e-4f)
        assertEquals(0f, hit.point[2], 1e-4f)
        assertEquals(10f, hit.distance, 1e-4f)
        assertFalse(hit.isSnappedToVertex)
    }

    @Test
    fun testTriangleIntersectionSnappingToVertex() {
        val builder = MeshBuilder(1)
        builder.addTriangle(floatArrayOf(
            0f, 0f, 0f,
            10f, 0f, 0f,
            0f, 10f, 0f,
        ))
        val mesh = builder.build()

        // Ray very close to corner (0, 0, 0)
        val ray = Ray(ox = 0.05f, oy = 0.05f, oz = 5f, dx = 0f, dy = 0f, dz = -1f)
        val hit = Raycaster.intersect(mesh, ray)

        assertNotNull(hit)
        // Snapped to (0, 0, 0)
        assertEquals(0f, hit!!.point[0], 1e-4f)
        assertEquals(0f, hit.point[1], 1e-4f)
        assertEquals(0f, hit.point[2], 1e-4f)
        assertTrue(hit.isSnappedToVertex)
    }

    @Test
    fun testRayMiss() {
        val builder = MeshBuilder(1)
        builder.addTriangle(floatArrayOf(
            0f, 0f, 0f,
            10f, 0f, 0f,
            0f, 10f, 0f,
        ))
        val mesh = builder.build()

        // Ray outside triangle
        val ray = Ray(ox = 8f, oy = 8f, oz = 5f, dx = 0f, dy = 0f, dz = -1f)
        val hit = Raycaster.intersect(mesh, ray)
        assertNull(hit)
    }
}
