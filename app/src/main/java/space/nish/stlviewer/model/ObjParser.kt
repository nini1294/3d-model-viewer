package space.nish.stlviewer.model

import java.io.BufferedInputStream
import java.io.InputStream

class ObjParseException(message: String, cause: Throwable? = null) : ModelParseException(message, cause)

/**
 * High-performance streaming parser for Wavefront OBJ files (`.obj`).
 *
 * Supported features:
 * - Geometric vertices (`v x y z [w]`) with optional homogeneous coordinate
 * - Triangular faces (`f v1 v2 v3`), quads (`f v1 v2 v3 v4`), and arbitrary convex n-gons (fan triangulation)
 * - All face vertex formats: `v`, `v/vt`, `v/vt/vn`, `v//vn`
 * - 1-based positive indices and negative relative indices (e.g. `-1` for the most recent vertex)
 * - Comments (`#`), object names (`o`), groups (`g`), smoothing groups (`s`), materials (`usemtl`, `mtllib`)
 * - Line continuation with backslash (`\`)
 * - UTF-8 byte order mark (BOM) handling
 * - Zero line-string allocations during streaming for minimal GC overhead on Android
 */
object ObjParser {

    fun parse(input: InputStream, knownLength: Long = -1L): Mesh {
        val stream = input as? BufferedInputStream ?: BufferedInputStream(input, 1 shl 16)
        val reader = LineReader(stream)

        val vertices = ChunkedVertexList()
        val initialCapacity = if (knownLength > 0) {
            (knownLength / 40L).toInt().coerceIn(1024, 10_000_000)
        } else {
            1024
        }
        val builder = MeshBuilder(initialCapacity)
        val faceIndices = IntList(16)
        val tri = FloatArray(9)

        while (reader.readLine()) {
            val line = reader.line
            val len = reader.lineLength
            val lineNum = reader.lineNumber
            var pos = 0

            // Skip UTF-8 BOM if present on the very first line
            if (lineNum == 1 && len >= 3 &&
                line[0] == 0xEF.toByte() && line[1] == 0xBB.toByte() && line[2] == 0xBF.toByte()
            ) {
                pos = 3
            }

            while (pos < len && isSpace(line[pos])) pos++
            if (pos >= len || line[pos] == '#'.code.toByte()) continue

            val c0 = line[pos]
            val c1 = if (pos + 1 < len) line[pos + 1] else 0.toByte()

            // Geometric vertex: 'v' followed by whitespace
            if ((c0 == 'v'.code.toByte() || c0 == 'V'.code.toByte()) && isSpace(c1)) {
                pos += 2
                var coordCount = 0
                var x = 0f; var y = 0f; var z = 0f; var w = 1f

                while (pos < len) {
                    while (pos < len && isSpace(line[pos])) pos++
                    if (pos >= len || line[pos] == '#'.code.toByte()) break

                    val tokenStart = pos
                    while (pos < len && !isSpace(line[pos]) && line[pos] != '#'.code.toByte()) pos++
                    val tokenLen = pos - tokenStart

                    val value = parseFloatToken(line, tokenStart, tokenLen, lineNum)
                    when (coordCount) {
                        0 -> x = value
                        1 -> y = value
                        2 -> z = value
                        3 -> w = value
                    }
                    coordCount++
                }

                if (coordCount < 3) {
                    throw ObjParseException("Incomplete vertex definition on line $lineNum")
                }
                if (coordCount >= 4 && w != 0f && w != 1f) {
                    x /= w
                    y /= w
                    z /= w
                }
                vertices.add(x, y, z)
                continue
            }

            // Face definition: 'f' followed by whitespace
            if ((c0 == 'f'.code.toByte() || c0 == 'F'.code.toByte()) && isSpace(c1)) {
                pos += 2
                faceIndices.clear()

                while (pos < len) {
                    while (pos < len && isSpace(line[pos])) pos++
                    if (pos >= len || line[pos] == '#'.code.toByte()) break

                    // Parse vertex index (portion before any '/' or whitespace)
                    var negative = false
                    if (line[pos] == '-'.code.toByte()) {
                        negative = true
                        pos++
                    } else if (line[pos] == '+'.code.toByte()) {
                        pos++
                    }

                    var rawIndex = 0
                    var hasDigits = false
                    while (pos < len && line[pos] in '0'.code.toByte()..'9'.code.toByte()) {
                        rawIndex = rawIndex * 10 + (line[pos] - '0'.code.toByte())
                        hasDigits = true
                        pos++
                    }

                    if (!hasDigits) {
                        throw ObjParseException("Expected vertex index in face on line $lineNum")
                    }
                    if (negative) rawIndex = -rawIndex

                    // Skip texture and normal indices (e.g. '/vt/vn' or '//vn') up to next whitespace or '#'
                    while (pos < len && !isSpace(line[pos]) && line[pos] != '#'.code.toByte()) {
                        pos++
                    }

                    val totalVertices = vertices.vertexCount
                    val index = if (rawIndex > 0) {
                        rawIndex - 1
                    } else if (rawIndex < 0) {
                        totalVertices + rawIndex
                    } else {
                        throw ObjParseException("Vertex index 0 is invalid in OBJ format on line $lineNum")
                    }

                    if (index < 0 || index >= totalVertices) {
                        throw ObjParseException("Vertex index $rawIndex out of bounds (1..$totalVertices) on line $lineNum")
                    }

                    faceIndices.add(index)
                }

                val count = faceIndices.size
                if (count < 3) continue // Ignore degenerate face elements (lines/points)

                if (count == 3) {
                    val v0 = faceIndices.get(0)
                    val v1 = faceIndices.get(1)
                    val v2 = faceIndices.get(2)
                    tri[0] = vertices.getX(v0); tri[1] = vertices.getY(v0); tri[2] = vertices.getZ(v0)
                    tri[3] = vertices.getX(v1); tri[4] = vertices.getY(v1); tri[5] = vertices.getZ(v1)
                    tri[6] = vertices.getX(v2); tri[7] = vertices.getY(v2); tri[8] = vertices.getZ(v2)
                    builder.addTriangle(tri)
                } else {
                    // Convex polygon: triangulate as fan from v0
                    val v0 = faceIndices.get(0)
                    val x0 = vertices.getX(v0)
                    val y0 = vertices.getY(v0)
                    val z0 = vertices.getZ(v0)
                    for (i in 1 until count - 1) {
                        val v1 = faceIndices.get(i)
                        val v2 = faceIndices.get(i + 1)
                        tri[0] = x0; tri[1] = y0; tri[2] = z0
                        tri[3] = vertices.getX(v1); tri[4] = vertices.getY(v1); tri[5] = vertices.getZ(v1)
                        tri[6] = vertices.getX(v2); tri[7] = vertices.getY(v2); tri[8] = vertices.getZ(v2)
                        builder.addTriangle(tri)
                    }
                }
                continue
            }
            // Other elements (vn, vt, vp, o, g, s, mtllib, usemtl) are safely ignored.
        }

        if (builder.triangleCount == 0) {
            throw ObjParseException("The OBJ file does not contain any faces or triangles")
        }

        return builder.build()
    }

    private fun parseFloatToken(line: ByteArray, start: Int, length: Int, lineNumber: Int): Float {
        return try {
            java.lang.Float.parseFloat(String(line, start, length, Charsets.US_ASCII))
        } catch (e: NumberFormatException) {
            throw ObjParseException("Invalid number format on line $lineNumber", e)
        }
    }

    private fun isSpace(b: Byte): Boolean {
        val c = b.toInt()
        return c == ' '.code || c == '\t'.code || c == '\r'.code || c == '\n'.code || c == 0x0C
    }

    /**
     * Chunked vertex storage holding (x, y, z) floats without large contiguous heap arrays
     * or copying on growth.
     */
    private class ChunkedVertexList(private val chunkVertices: Int = 16384) {
        private val chunkSize = chunkVertices * 3
        private val chunks = ArrayList<FloatArray>()
        var vertexCount = 0
            private set

        fun add(x: Float, y: Float, z: Float) {
            val chunkIdx = vertexCount / chunkVertices
            val offset = (vertexCount % chunkVertices) * 3
            if (chunkIdx >= chunks.size) {
                chunks.add(FloatArray(chunkSize))
            }
            val chunk = chunks[chunkIdx]
            chunk[offset] = x
            chunk[offset + 1] = y
            chunk[offset + 2] = z
            vertexCount++
        }

        fun getX(index: Int): Float = chunks[index / chunkVertices][(index % chunkVertices) * 3]
        fun getY(index: Int): Float = chunks[index / chunkVertices][(index % chunkVertices) * 3 + 1]
        fun getZ(index: Int): Float = chunks[index / chunkVertices][(index % chunkVertices) * 3 + 2]
    }

    /** Lightweight primitive int array list to avoid boxing during face triangulation. */
    private class IntList(initialCapacity: Int = 16) {
        private var data = IntArray(initialCapacity)
        var size = 0
            private set

        fun add(element: Int) {
            if (size >= data.size) {
                data = data.copyOf(data.size * 2)
            }
            data[size++] = element
        }

        fun get(index: Int): Int = data[index]

        fun clear() {
            size = 0
        }
    }

    /**
     * Buffered line reader that streams bytes into a reusable byte array.
     * Handles Unix and Windows newlines as well as backslash line continuations.
     */
    private class LineReader(private val input: InputStream) {
        private val buffer = ByteArray(65536)
        private var bufferPos = 0
        private var bufferLimit = 0

        var line = ByteArray(1024)
            private set
        var lineLength = 0
            private set
        var lineNumber = 0
            private set

        fun readLine(): Boolean {
            lineLength = 0
            var hasData = false
            var prevWasBackslash = false

            while (true) {
                if (bufferPos >= bufferLimit) {
                    bufferLimit = input.read(buffer, 0, buffer.size)
                    bufferPos = 0
                    if (bufferLimit <= 0) {
                        if (hasData) {
                            lineNumber++
                            stripTrailingCR()
                            return true
                        }
                        return false
                    }
                }

                val b = buffer[bufferPos++]
                hasData = true

                if (b == '\n'.code.toByte()) {
                    lineNumber++
                    if (prevWasBackslash) {
                        removeTrailingBackslash()
                        prevWasBackslash = false
                        continue
                    }
                    stripTrailingCR()
                    return true
                } else if (b == '\\'.code.toByte()) {
                    prevWasBackslash = true
                    append(b)
                } else {
                    if (b != '\r'.code.toByte() && b != ' '.code.toByte() && b != '\t'.code.toByte()) {
                        prevWasBackslash = false
                    }
                    append(b)
                }
            }
        }

        private fun stripTrailingCR() {
            if (lineLength > 0 && line[lineLength - 1] == '\r'.code.toByte()) {
                lineLength--
            }
        }

        private fun removeTrailingBackslash() {
            if (lineLength > 0 && line[lineLength - 1] == '\r'.code.toByte()) lineLength--
            while (lineLength > 0 && (line[lineLength - 1] == ' '.code.toByte() || line[lineLength - 1] == '\t'.code.toByte())) {
                lineLength--
            }
            if (lineLength > 0 && line[lineLength - 1] == '\\'.code.toByte()) {
                lineLength--
            }
            append(' '.code.toByte())
        }

        private fun append(b: Byte) {
            if (lineLength >= line.size) {
                line = line.copyOf(line.size * 2)
            }
            line[lineLength++] = b
        }
    }
}
