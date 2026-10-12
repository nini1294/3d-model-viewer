package space.nish.stlviewer.model

import java.io.BufferedInputStream
import java.io.InputStream

/**
 * Unified entry point for parsing supported 3D model formats (STL and OBJ).
 */
object ModelParser {

    enum class Format { STL, OBJ }

    private const val SNIFF_BUFFER_SIZE = 1024

    /**
     * Parses a 3D model stream into a [Mesh].
     *
     * @param input Stream containing model data.
     * @param fileName Optional file name to aid detection by extension.
     * @param knownLength Optional total stream size in bytes (-1 if unknown).
     */
    fun parse(input: InputStream, fileName: String = "", knownLength: Long = -1L): Mesh {
        val stream = input as? BufferedInputStream ?: BufferedInputStream(input, 1 shl 16)
        val lowerName = fileName.lowercase()

        val format = when {
            lowerName.endsWith(".obj") -> Format.OBJ
            lowerName.endsWith(".stl") -> Format.STL
            else -> sniffFormat(stream)
        }

        return when (format) {
            Format.OBJ -> ObjParser.parse(stream, knownLength)
            Format.STL -> StlParser.parse(stream, knownLength)
        }
    }

    internal fun sniffFormat(stream: BufferedInputStream): Format {
        stream.mark(SNIFF_BUFFER_SIZE)
        val buffer = ByteArray(SNIFF_BUFFER_SIZE)
        var total = 0
        while (total < SNIFF_BUFFER_SIZE) {
            val count = stream.read(buffer, total, SNIFF_BUFFER_SIZE - total)
            if (count < 0) break
            total += count
        }
        stream.reset()

        return if (looksLikeObj(buffer, total)) Format.OBJ else Format.STL
    }

    private fun looksLikeObj(bytes: ByteArray, length: Int): Boolean {
        if (length == 0) return false

        // Check if bytes are text
        for (i in 0 until length) {
            val b = bytes[i].toInt() and 0xFF
            if (b < 0x20 && b != '\t'.code && b != '\n'.code && b != '\r'.code && b != 0x0C) {
                return false
            }
        }

        // Check for ASCII STL keyword 'solid'
        var start = 0
        while (start < length && bytes[start].toInt().toChar().isWhitespace()) start++
        val text = String(bytes, start, (length - start).coerceAtMost(32), Charsets.US_ASCII).lowercase()
        if (text.startsWith("solid")) {
            return false
        }

        // Search for OBJ keywords at line starts
        var i = 0
        var hasObjDirective = false
        while (i < length) {
            while (i < length && (bytes[i] == ' '.code.toByte() || bytes[i] == '\t'.code.toByte())) i++
            if (i >= length) break

            val remaining = length - i
            if (remaining >= 2) {
                val c0 = bytes[i].toInt().toChar().lowercaseChar()
                val c1 = bytes[i + 1].toInt().toChar()
                if ((c0 == 'v' || c0 == 'f') && c1.isWhitespace()) {
                    hasObjDirective = true
                    break
                }
                if (c0 == '#' || (c0 == 'o' && c1.isWhitespace()) || (c0 == 'g' && c1.isWhitespace())) {
                    hasObjDirective = true
                }
            }

            // Skip to next line
            while (i < length && bytes[i] != '\n'.code.toByte()) i++
            if (i < length && bytes[i] == '\n'.code.toByte()) i++
        }

        return hasObjDirective
    }
}
