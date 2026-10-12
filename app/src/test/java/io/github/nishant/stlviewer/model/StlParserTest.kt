package io.github.nishant.stlviewer.model

import io.github.nishant.stlviewer.render.ModelRenderer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class StlParserTest {

    /** 12 triangles of an axis-aligned cube from (0,0,0) to (size,size,size), outward CCW winding. */
    private fun cubeTriangles(size: Float = 10f): List<FloatArray> {
        val s = size
        val p = arrayOf(
            floatArrayOf(0f, 0f, 0f), floatArrayOf(s, 0f, 0f), floatArrayOf(s, s, 0f), floatArrayOf(0f, s, 0f),
            floatArrayOf(0f, 0f, s), floatArrayOf(s, 0f, s), floatArrayOf(s, s, s), floatArrayOf(0f, s, s),
        )
        val faces = arrayOf(
            intArrayOf(0, 2, 1), intArrayOf(0, 3, 2), // bottom
            intArrayOf(4, 5, 6), intArrayOf(4, 6, 7), // top
            intArrayOf(0, 1, 5), intArrayOf(0, 5, 4), // front
            intArrayOf(2, 3, 7), intArrayOf(2, 7, 6), // back
            intArrayOf(1, 2, 6), intArrayOf(1, 6, 5), // right
            intArrayOf(3, 0, 4), intArrayOf(3, 4, 7), // left
        )
        return faces.map { f -> p[f[0]] + p[f[1]] + p[f[2]] }
    }

    private fun binaryStl(triangles: List<FloatArray>, header: String = "binary", declaredCount: Int = triangles.size): ByteArray {
        val buffer = ByteBuffer.allocate(84 + 50 * triangles.size).order(ByteOrder.LITTLE_ENDIAN)
        val headerBytes = header.toByteArray(Charsets.US_ASCII).copyOf(80)
        buffer.put(headerBytes)
        buffer.putInt(declaredCount)
        for (t in triangles) {
            repeat(3) { buffer.putFloat(0f) } // normal
            t.forEach { buffer.putFloat(it) }
            buffer.putShort(0)
        }
        return buffer.array()
    }

    private fun asciiStl(triangles: List<FloatArray>): ByteArray = buildString {
        appendLine("solid cube")
        for (t in triangles) {
            appendLine("  facet normal 0 0 0")
            appendLine("    outer loop")
            for (v in 0 until 3) appendLine("      vertex ${t[v * 3]} ${t[v * 3 + 1]} ${t[v * 3 + 2]}")
            appendLine("    endloop")
            appendLine("  endfacet")
        }
        appendLine("endsolid cube")
    }.toByteArray()

    private fun parse(bytes: ByteArray, knownLength: Long = bytes.size.toLong()) =
        StlParser.parse(ByteArrayInputStream(bytes), knownLength)

    private fun assertCube(mesh: Mesh) {
        assertEquals(12, mesh.triangleCount)
        assertEquals(36 * 3, mesh.vertices.limit())
        assertEquals(10f, mesh.bounds.sizeX, 1e-5f)
        assertEquals(10f, mesh.bounds.sizeY, 1e-5f)
        assertEquals(10f, mesh.bounds.sizeZ, 1e-5f)
        assertEquals(1000.0, mesh.stats.volume, 1e-3)
        assertEquals(600.0, mesh.stats.surfaceArea, 1e-3)
    }

    @Test
    fun parsesBinary() = assertCube(parse(binaryStl(cubeTriangles())))

    @Test
    fun parsesAscii() = assertCube(parse(asciiStl(cubeTriangles())))

    @Test
    fun parsesAsciiWithoutKnownLength() = assertCube(parse(asciiStl(cubeTriangles()), knownLength = -1))

    @Test
    fun binaryWithSolidHeaderIsDetectedAsBinary() {
        assertCube(parse(binaryStl(cubeTriangles(), header = "solid exported by some CAD tool")))
        // Even without the length hint, the binary payload contains control bytes.
        assertCube(parse(binaryStl(cubeTriangles(), header = "solid exported by some CAD tool"), knownLength = -1))
    }

    @Test
    fun truncatedBinaryKeepsCompleteTriangles() {
        val full = binaryStl(cubeTriangles())
        val truncated = full.copyOf(84 + 50 * 5 + 20)
        val mesh = parse(truncated)
        assertEquals(5, mesh.triangleCount)
        val meshUnknownLength = parse(truncated, knownLength = -1)
        assertEquals(5, meshUnknownLength.triangleCount)
    }

    @Test
    fun zeroDeclaredCountUsesFileLength() {
        val mesh = parse(binaryStl(cubeTriangles(), declaredCount = 0))
        assertEquals(12, mesh.triangleCount)
    }

    @Test
    fun skipsNonFiniteTriangles() {
        val tris = cubeTriangles().toMutableList()
        tris += floatArrayOf(Float.NaN, 0f, 0f, 1f, 0f, 0f, 0f, 1f, 0f)
        val mesh = parse(binaryStl(tris))
        assertEquals(12, mesh.triangleCount)
    }

    @Test
    fun emptyAsciiFails() {
        assertThrows(StlParseException::class.java) { parse("solid x\nendsolid x\n".toByteArray()) }
    }

    @Test
    fun tinyGarbageFails() {
        assertThrows(StlParseException::class.java) { parse(byteArrayOf(1, 2, 3)) }
    }

    @Test
    fun largeAsciiGrowsBuffer() {
        val many = (0 until 3000).map { i ->
            val o = i.toFloat()
            floatArrayOf(o, 0f, 0f, o + 1f, 0f, 0f, o, 1f, 0f)
        }
        val mesh = parse(asciiStl(many))
        assertEquals(3000, mesh.triangleCount)
        assertEquals(3000f, mesh.bounds.sizeX, 1e-3f)
    }

    @Test
    fun niceGridStep() {
        assertEquals(1f, ModelRenderer.niceStep(0.8f), 1e-6f)
        assertEquals(2f, ModelRenderer.niceStep(1.5f), 1e-6f)
        assertEquals(5f, ModelRenderer.niceStep(4.2f), 1e-6f)
        assertEquals(10f, ModelRenderer.niceStep(7f), 1e-5f)
        assertEquals(50f, ModelRenderer.niceStep(31f), 1e-4f)
    }

    @Test
    fun gridCoversModel() {
        val b = parse(binaryStl(cubeTriangles())).bounds
        val (grid, minor) = ModelRenderer.buildGrid(b)
        assertTrue(grid.isNotEmpty())
        assertTrue(minor in 0..grid.size / 3)
        val xs = grid.filterIndexed { i, _ -> i % 3 == 0 }
        assertTrue(xs.min() <= b.minX && xs.max() >= b.maxX)
    }
}
