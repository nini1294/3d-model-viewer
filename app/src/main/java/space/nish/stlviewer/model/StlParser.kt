package space.nish.stlviewer.model

import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class StlParseException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * Streaming parser for binary and ASCII STL files.
 *
 * Binary layout: 80-byte header, uint32 triangle count, then 50 bytes per triangle
 * (normal xyz, 3 × vertex xyz as little-endian float32, uint16 attribute).
 */
object StlParser {

    enum class Format { BINARY, ASCII }

    private const val HEADER_SIZE = 80
    private const val BINARY_PREAMBLE = HEADER_SIZE + 4
    private const val TRIANGLE_SIZE = 50
    private const val PEEK_SIZE = 1024
    private const val CHUNK_TRIANGLES = 4096

    /** Upper bound to fail fast on corrupt headers instead of attempting a multi-GB allocation. */
    const val MAX_TRIANGLES = 40_000_000

    /**
     * Parses an STL stream.
     *
     * @param knownLength total size of the stream in bytes if known, or -1. Supplying it makes format detection
     *   bullet-proof for binary files whose header happens to start with "solid".
     */
    fun parse(input: InputStream, knownLength: Long = -1L): Mesh {
        val stream = input as? BufferedInputStream ?: BufferedInputStream(input, 1 shl 16)
        stream.mark(PEEK_SIZE)
        val peek = ByteArray(PEEK_SIZE)
        val peekLength = readFully(stream, peek, 0, PEEK_SIZE)
        stream.reset()

        return when (detectFormat(peek, peekLength, knownLength)) {
            Format.BINARY -> parseBinary(stream, knownLength)
            Format.ASCII -> parseAscii(stream)
        }
    }

    internal fun detectFormat(peek: ByteArray, length: Int, knownLength: Long): Format {
        val startsWithSolid = startsWithSolid(peek, length)
        if (length < BINARY_PREAMBLE) {
            if (startsWithSolid) return Format.ASCII
            throw StlParseException("File is too small to be an STL model")
        }
        if (!startsWithSolid) return Format.BINARY

        // Many binary exporters (e.g. SolidWorks) also write "solid" into the header, so look closer.
        val declared = readUInt32(peek, HEADER_SIZE)
        if (knownLength > 0 && knownLength == BINARY_PREAMBLE + declared * TRIANGLE_SIZE) return Format.BINARY

        val looksLikeText = (0 until length).none { i ->
            val b = peek[i].toInt() and 0xFF
            b < 0x20 && b != '\t'.code && b != '\n'.code && b != '\r'.code && b != 0x0C
        }
        return if (looksLikeText) Format.ASCII else Format.BINARY
    }

    private fun startsWithSolid(peek: ByteArray, length: Int): Boolean {
        var i = 0
        while (i < length && peek[i].toInt().toChar().isWhitespace()) i++
        val keyword = "solid"
        if (length - i < keyword.length) return false
        for (k in keyword.indices) {
            if (peek[i + k].toInt().toChar().lowercaseChar() != keyword[k]) return false
        }
        return true
    }

    private fun parseBinary(stream: InputStream, knownLength: Long): Mesh {
        val preamble = ByteArray(BINARY_PREAMBLE)
        if (readFully(stream, preamble, 0, BINARY_PREAMBLE) < BINARY_PREAMBLE) {
            throw StlParseException("Unexpected end of file in STL header")
        }
        var count = readUInt32(preamble, HEADER_SIZE)
        if (knownLength > 0) {
            val available = (knownLength - BINARY_PREAMBLE) / TRIANGLE_SIZE
            // Tolerate truncated files and exporters that leave the count at zero.
            if (count > available || count == 0L) count = available
        }
        if (count <= 0L) throw StlParseException("The file does not contain any triangles")
        if (count > MAX_TRIANGLES) {
            throw StlParseException("Model has too many triangles ($count). Maximum supported is $MAX_TRIANGLES.")
        }

        val builder = MeshBuilder(count.toInt())
        val chunk = ByteArray(CHUNK_TRIANGLES * TRIANGLE_SIZE)
        val bb = ByteBuffer.wrap(chunk).order(ByteOrder.LITTLE_ENDIAN)
        val tri = FloatArray(9)
        var remaining = count
        while (remaining > 0) {
            val want = minOf(remaining, CHUNK_TRIANGLES.toLong()).toInt() * TRIANGLE_SIZE
            val got = readFully(stream, chunk, 0, want)
            val triangles = got / TRIANGLE_SIZE
            for (t in 0 until triangles) {
                val base = t * TRIANGLE_SIZE + 12 // skip the stored normal; we recompute normals on the GPU
                for (k in 0 until 9) tri[k] = bb.getFloat(base + k * 4)
                builder.addTriangle(tri)
            }
            remaining -= triangles
            if (got < want) break // truncated file: keep what we have
        }
        return builder.build()
    }

    private fun parseAscii(stream: InputStream): Mesh {
        val tokenizer = Tokenizer(stream)
        val builder = MeshBuilder(1024)
        val tri = FloatArray(9)
        var vertexIndex = 0
        while (true) {
            val token = tokenizer.next() ?: break
            if (!token.contentEquals("vertex", ignoreCase = true)) continue
            for (axis in 0 until 3) {
                val number = tokenizer.next()
                    ?: throw StlParseException("Unexpected end of file while reading a vertex")
                tri[vertexIndex * 3 + axis] = number.toString().toFloatOrNull()
                    ?: throw StlParseException("Invalid number '$number' in ASCII STL")
            }
            vertexIndex++
            if (vertexIndex == 3) {
                builder.addTriangle(tri)
                vertexIndex = 0
            }
        }
        return builder.build()
    }

    /** Whitespace tokenizer that reuses one buffer to avoid allocating per token. */
    private class Tokenizer(private val input: InputStream) {
        private val buffer = ByteArray(1 shl 16)
        private var position = 0
        private var limit = 0
        private val token = StringBuilder(32)

        fun next(): CharSequence? {
            token.setLength(0)
            var b = read()
            while (b != -1 && isSpace(b)) b = read()
            if (b == -1) return null
            while (b != -1 && !isSpace(b)) {
                token.append(b.toChar())
                b = read()
            }
            return token
        }

        private fun read(): Int {
            if (position == limit) {
                limit = input.read(buffer, 0, buffer.size)
                position = 0
                if (limit <= 0) {
                    limit = 0
                    return -1
                }
            }
            return buffer[position++].toInt() and 0xFF
        }

        private fun isSpace(b: Int) = b == ' '.code || b == '\n'.code || b == '\r'.code || b == '\t'.code || b == 0x0C
    }

    private fun readUInt32(bytes: ByteArray, offset: Int): Long =
        (bytes[offset].toLong() and 0xFF) or
            ((bytes[offset + 1].toLong() and 0xFF) shl 8) or
            ((bytes[offset + 2].toLong() and 0xFF) shl 16) or
            ((bytes[offset + 3].toLong() and 0xFF) shl 24)

    private fun readFully(input: InputStream, dst: ByteArray, offset: Int, length: Int): Int {
        var total = 0
        while (total < length) {
            val n = input.read(dst, offset + total, length - total)
            if (n < 0) break
            total += n
        }
        return total
    }
}
