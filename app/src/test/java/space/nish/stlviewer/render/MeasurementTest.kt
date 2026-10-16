package space.nish.stlviewer.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import space.nish.stlviewer.ui.Measurement
import space.nish.stlviewer.ui.Point3D

class MeasurementTest {

    @Test
    fun testMeasurementCalculations() {
        val p1 = Point3D(0f, 0f, 0f)
        val p2 = Point3D(3f, 4f, 12f)
        val m = Measurement(p1, p2)

        assertEquals(3f, m.deltaX, 1e-4f)
        assertEquals(4f, m.deltaY, 1e-4f)
        assertEquals(12f, m.deltaZ, 1e-4f)
        // 3^2 + 4^2 + 12^2 = 9 + 16 + 144 = 169 -> sqrt is 13
        assertEquals(13f, m.distance, 1e-4f)
    }

    @Test
    fun testBuildMeasurementGeometryEmpty() {
        val empty = ModelRenderer.buildMeasurementGeometry(null, null, 10f)
        assertEquals(0, empty.size)
    }

    @Test
    fun testBuildMeasurementGeometrySinglePoint() {
        val p1 = floatArrayOf(10f, 20f, 30f)
        val geom = ModelRenderer.buildMeasurementGeometry(p1, null, 50f)
        assertTrue(geom.isNotEmpty())
        assertEquals(0, geom.size % 3) // multiple of 3 floats per vertex
        for (f in geom) {
            assertTrue(f.isFinite())
        }
    }

    @Test
    fun testBuildMeasurementGeometryTwoPoints() {
        val p1 = floatArrayOf(0f, 0f, 0f)
        val p2 = floatArrayOf(10f, 10f, 10f)
        val geom = ModelRenderer.buildMeasurementGeometry(p1, p2, 50f)
        assertTrue(geom.isNotEmpty())
        assertEquals(0, geom.size % 3)
        for (f in geom) {
            assertTrue(f.isFinite())
        }
    }
}
