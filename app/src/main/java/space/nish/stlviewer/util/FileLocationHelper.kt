package space.nish.stlviewer.util

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import java.io.File

data class FileLocationInfo(
    /** A clean, user-friendly path or description (e.g. /storage/emulated/0/Download/cube.stl). */
    val displayPath: String,
    /** The raw URI string if different from displayPath, or null. */
    val rawUri: String? = null,
)

object FileLocationHelper {

    fun resolve(context: Context, uri: Uri): FileLocationInfo {
        val rawUriString = Uri.decode(uri.toString())

        // 1. Direct file:// URI
        if (uri.scheme == ContentResolver.SCHEME_FILE) {
            val path = uri.path ?: rawUriString
            return FileLocationInfo(displayPath = path, rawUri = null)
        }

        // 2. content:// URI
        if (uri.scheme == ContentResolver.SCHEME_CONTENT) {
            val authority = uri.authority.orEmpty()

            // Check DocumentsContract
            if (DocumentsContract.isDocumentUri(context, uri)) {
                val docId = runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull().orEmpty()

                when {
                    authority == "com.android.externalstorage.documents" -> {
                        val parts = docId.split(":", limit = 2)
                        if (parts.size == 2) {
                            val type = parts[0]
                            val relativePath = parts[1]
                            val fullPath = if (type.equals("primary", ignoreCase = true)) {
                                "${Environment.getExternalStorageDirectory().path}/$relativePath"
                            } else {
                                "/storage/$type/$relativePath"
                            }
                            return FileLocationInfo(displayPath = fullPath, rawUri = rawUriString)
                        }
                    }

                    authority == "com.android.providers.downloads.documents" -> {
                        if (docId.startsWith("raw:")) {
                            return FileLocationInfo(
                                displayPath = docId.removePrefix("raw:"),
                                rawUri = rawUriString,
                            )
                        }
                    }
                }
            }

            // Try querying MediaStore projection (_data or relative_path + _display_name)
            runCatching {
                val projection = arrayOf(
                    MediaStore.MediaColumns.DATA,
                    MediaStore.MediaColumns.DISPLAY_NAME,
                )
                context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val dataIdx = cursor.getColumnIndex(MediaStore.MediaColumns.DATA)
                        if (dataIdx >= 0 && !cursor.isNull(dataIdx)) {
                            val dataPath = cursor.getString(dataIdx)
                            if (!dataPath.isNullOrBlank() && File(dataPath).exists()) {
                                return FileLocationInfo(displayPath = dataPath, rawUri = rawUriString)
                            }
                        }
                    }
                }
            }

            // Fallback: Show decoded URI as displayPath
            return FileLocationInfo(displayPath = rawUriString, rawUri = null)
        }

        return FileLocationInfo(displayPath = rawUriString, rawUri = null)
    }
}
