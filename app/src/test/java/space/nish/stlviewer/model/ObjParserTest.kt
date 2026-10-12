package space.nish.stlviewer.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream

class ObjParserTest {

    private val cubeObjTriangles = """
        # Unit cube with 12 triangle faces
        v 0.0 0.0 0.0
        v 10.0 0.0 0.0
        v 10.0 10.0 0.0
        v 0.0 10.0 0.0
        v 0.0 0.0 10.0
        v 10.0 0.0 10.0
        v 10.0 10.0 10.0
        v 0.0 10.0 10.0

        # bottom
        f 1 3 2
        f 1 4 3
        # top
        f 5 6 7
        f 5 7 8
        # front
        f 1 2 6
        f 1 6 5
        # back
        f 3 4 8
        f 3 8 7
        # right
        f 2 3 7
        f 2 7 6
        # left
        f 4 1 5
        f 4 5 8
    """.trimIndent()

    private val cubeObjQuads = """
        # Unit cube with 6 quad faces
        o Cube
        v 0.0 0.0 0.0
        v 10.0 0.0 0.0
        v 10.0 10.0 0.0
        v 0.0 10.0 0.0
        v 0.0 0.0 10.0
        v 10.0 0.0 10.0
        v 10.0 10.0 10.0
        v 0.0 10.0 10.0

        f 1 3 2 4
        f 5 6 7 8
        f 1 2 6 5
        f 3 4 8 7
        f 2 3 7 6
        f 4 1 5 8
    """.trimIndent()

    private fun parse(text: String, knownLength: Long = text.toByteArray().size.toLong()) =
        ObjParser.parse(ByteArrayInputStream(text.toByteArray()), knownLength)

    private fun assertCube(mesh: Mesh) {
        assertEquals(12, mesh.triangleCount)
        assertEquals(36 * 3, mesh.vertices.limit())
        assertEquals(10f, mesh.bounds.sizeX, 1e-4f)
        assertEquals(10f, mesh.bounds.sizeY, 1e-4f)
        assertEquals(10f, mesh.bounds.sizeZ, 1e-4f)
        assertEquals(1000.0, mesh.stats.volume, 1e-2)
        assertEquals(600.0, mesh.stats.surfaceArea, 1e-2)
    }

    @Test
    fun parsesBasicTriangles() {
        assertCube(parse(cubeObjTriangles))
    }

    @Test
    fun parsesQuadsAndTriangulates() {
        val mesh = parse(cubeObjQuads)
        assertEquals(12, mesh.triangleCount)
        assertEquals(10f, mesh.bounds.sizeX, 1e-4f)
        assertEquals(10f, mesh.bounds.sizeY, 1e-4f)
        assertEquals(10f, mesh.bounds.sizeZ, 1e-4f)
    }

    @Test
    fun parsesTextureCoordinatesAndNormalsInFace() {
        val objWithAttributes = """
            # Vertices, textures, normals
            v 0 0 0
            v 10 0 0
            v 10 10 0
            vt 0.0 0.0
            vt 1.0 0.0
            vt 1.0 1.0
            vn 0 0 1
            vn 0 0 1
            vn 0 0 1
            # v/vt format
            f 1/1 2/2 3/3
            # v/vt/vn format
            f 1/1/1 2/2/2 3/3/3
            # v//vn format
            f 1//1 2//2 3//3
        """.trimIndent()

        val mesh = parse(objWithAttributes)
        assertEquals(3, mesh.triangleCount)
    }

    @Test
    fun parsesNegativeRelativeIndices() {
        val objNegative = """
            v 0 0 0
            v 10 0 0
            v 10 10 0
            # Refers to the last 3 vertices
            f -3 -2 -1
        """.trimIndent()

        val mesh = parse(objNegative)
        assertEquals(1, mesh.triangleCount)
        assertEquals(10f, mesh.bounds.sizeX, 1e-4f)
        assertEquals(10f, mesh.bounds.sizeY, 1e-4f)
    }

    @Test
    fun parsesPolygonFanTriangulation() {
        val polygonObj = """
            v 0 0 0
            v 2 0 0
            v 3 1 0
            v 2 2 0
            v 0 2 0
            # 5-gon -> 3 triangles
            f 1 2 3 4 5
        """.trimIndent()

        val mesh = parse(polygonObj)
        assertEquals(3, mesh.triangleCount)
        assertEquals(3f, mesh.bounds.sizeX, 1e-4f)
        assertEquals(2f, mesh.bounds.sizeY, 1e-4f)
    }

    @Test
    fun parsesHomogeneousWCoordinate() {
        val objWithW = """
            v 0 0 0 1
            v 20 0 0 2
            v 0 30 0 3
            f 1 2 3
        """.trimIndent()

        val mesh = parse(objWithW)
        assertEquals(1, mesh.triangleCount)
        assertEquals(10f, mesh.bounds.sizeX, 1e-4f) // 20 / 2 = 10
        assertEquals(10f, mesh.bounds.sizeY, 1e-4f) // 30 / 3 = 10
    }

    @Test
    fun handlesLineContinuationWithBackslash() {
        val objContinuation = """
            v 0 0 0
            v 10 0 \
              0
            v 10 10 0
            f 1 2 \
              3
        """.trimIndent()

        val mesh = parse(objContinuation)
        assertEquals(1, mesh.triangleCount)
    }

    @Test
    fun handlesUtf8Bom() {
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) +
            cubeObjTriangles.toByteArray(Charsets.UTF_8)
        val mesh = ObjParser.parse(ByteArrayInputStream(bytes))
        assertCube(mesh)
    }

    @Test
    fun emptyFileThrows() {
        assertThrows(ObjParseException::class.java) {
            parse("# Just comments\n\n")
        }
    }

    @Test
    fun verticesWithoutFacesThrows() {
        assertThrows(ObjParseException::class.java) {
            parse("v 1 2 3\nv 4 5 6\n")
        }
    }

    @Test
    fun invalidVertexIndexZeroThrows() {
        assertThrows(ObjParseException::class.java) {
            parse("v 0 0 0\nv 1 0 0\nv 0 1 0\nf 0 1 2\n")
        }
    }

    @Test
    fun outOfBoundsVertexIndexThrows() {
        assertThrows(ObjParseException::class.java) {
            parse("v 0 0 0\nv 1 0 0\nv 0 1 0\nf 1 2 99\n")
        }
    }

    @Test
    fun modelParserDispatchesCorrectly() {
        // By extension
        val meshFromExt = ModelParser.parse(
            ByteArrayInputStream(cubeObjTriangles.toByteArray()),
            fileName = "cube.obj"
        )
        assertCube(meshFromExt)

        // Sniffing without extension
        val meshSniffed = ModelParser.parse(
            ByteArrayInputStream(cubeObjTriangles.toByteArray()),
            fileName = "unknown_model"
        )
        assertCube(meshSniffed)
    }

    @Test
    fun parsesSampleSphereObj() {
        val file = java.io.File("sample-models/sphere.obj")
        if (file.exists()) {
            val mesh = file.inputStream().use { ObjParser.parse(it, file.length()) }
            assertEquals(320, mesh.triangleCount)
            assertEquals(30f, mesh.bounds.sizeX, 0.5f)
            assertEquals(30f, mesh.bounds.sizeY, 0.5f)
            assertEquals(30f, mesh.bounds.sizeZ, 0.5f)
        }
    }
}
