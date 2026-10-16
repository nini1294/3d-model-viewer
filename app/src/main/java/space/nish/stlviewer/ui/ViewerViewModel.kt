package space.nish.stlviewer.ui

import android.app.Application
import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import space.nish.stlviewer.R
import space.nish.stlviewer.model.Mesh
import space.nish.stlviewer.model.ModelParser
import space.nish.stlviewer.render.RenderMode
import space.nish.stlviewer.util.FileLocationHelper
import space.nish.stlviewer.util.FileLocationInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

enum class ModelColor(val rgb: FloatArray) {
    Blue(floatArrayOf(0.36f, 0.60f, 0.90f)),
    Orange(floatArrayOf(0.95f, 0.55f, 0.20f)),
    Grey(floatArrayOf(0.72f, 0.73f, 0.75f)),
    Green(floatArrayOf(0.35f, 0.75f, 0.45f)),
    Red(floatArrayOf(0.88f, 0.30f, 0.30f)),
}

data class LoadedModel(
    val mesh: Mesh,
    val fileName: String,
    /** Size in bytes, or -1 if unknown. */
    val fileSize: Long,
    val loadTimeMs: Long,
    val locationInfo: FileLocationInfo,
) {
    val fileLocation: String get() = locationInfo.displayPath
    val rawUri: String? get() = locationInfo.rawUri
}

data class Point3D(val x: Float, val y: Float, val z: Float) {
    fun toFloatArray(): FloatArray = floatArrayOf(x, y, z)
}

data class Measurement(
    val point1: Point3D,
    val point2: Point3D,
) {
    val deltaX: Float get() = kotlin.math.abs(point2.x - point1.x)
    val deltaY: Float get() = kotlin.math.abs(point2.y - point1.y)
    val deltaZ: Float get() = kotlin.math.abs(point2.z - point1.z)
    val distance: Float get() = kotlin.math.sqrt(
        (point2.x - point1.x) * (point2.x - point1.x) +
        (point2.y - point1.y) * (point2.y - point1.y) +
        (point2.z - point1.z) * (point2.z - point1.z)
    )
}

data class ViewerUiState(
    val model: LoadedModel? = null,
    val loading: Boolean = false,
    val loadingName: String? = null,
    val error: String? = null,
    val infoMessage: String? = null,
    val renderMode: RenderMode = RenderMode.SHADED,
    val showGrid: Boolean = true,
    val modelColor: ModelColor = ModelColor.Blue,
    val isMeasuring: Boolean = false,
    val measurePoint1: Point3D? = null,
    val measurePoint2: Point3D? = null,
) {
    val measurement: Measurement?
        get() = if (measurePoint1 != null && measurePoint2 != null) {
            Measurement(measurePoint1, measurePoint2)
        } else null
}

class ViewerViewModel(application: Application) : AndroidViewModel(application) {

    private val _state = MutableStateFlow(ViewerUiState())
    val state: StateFlow<ViewerUiState> = _state.asStateFlow()

    private var loadJob: Job? = null
    private val cacheModelFile by lazy {
        File(getApplication<Application>().cacheDir, "current_model.bin")
    }

    fun load(uri: Uri) {
        loadJob?.cancel()
        val resolver = getApplication<Application>().contentResolver
        _state.update {
            it.copy(
                loading = true,
                loadingName = null,
                error = null,
                infoMessage = null,
                isMeasuring = false,
                measurePoint1 = null,
                measurePoint2 = null,
            )
        }
        loadJob = viewModelScope.launch {
            try {
                val loaded = withContext(Dispatchers.IO) {
                    val (name, size) = queryNameAndSize(resolver, uri)
                    val location = FileLocationHelper.resolve(getApplication(), uri)
                    _state.update { it.copy(loadingName = name) }

                    // Cache locally so downloading/saving is reliable even if transient URI permissions expire
                    resolver.openInputStream(uri)?.use { input ->
                        cacheModelFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    } ?: throw IOException("Unable to open the file")

                    val effectiveSize = if (size > 0) size else cacheModelFile.length()
                    val start = System.nanoTime()
                    val mesh = cacheModelFile.inputStream().use { ModelParser.parse(it, name, effectiveSize) }
                    val loadTime = (System.nanoTime() - start) / 1_000_000

                    LoadedModel(
                        mesh = mesh,
                        fileName = name,
                        fileSize = effectiveSize,
                        loadTimeMs = loadTime,
                        locationInfo = location,
                    )
                }
                _state.update { it.copy(model = loaded, loading = false, loadingName = null) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: OutOfMemoryError) {
                fail("This model is too large to load on this device")
            } catch (e: SecurityException) {
                fail("Permission denied while opening the file")
            } catch (e: Exception) {
                fail(e.message ?: "Could not open the file")
            }
        }
    }

    fun downloadModel(destinationUri: Uri) {
        val currentModel = _state.value.model ?: return
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val resolver = getApplication<Application>().contentResolver
                    if (!cacheModelFile.exists()) {
                        throw IOException("Model file is no longer in cache")
                    }
                    cacheModelFile.inputStream().use { input ->
                        resolver.openOutputStream(destinationUri)?.use { output ->
                            input.copyTo(output)
                        } ?: throw IOException("Unable to open destination file for writing")
                    }
                }
                _state.update {
                    it.copy(infoMessage = getApplication<Application>().getString(R.string.file_saved, currentModel.fileName))
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(error = getApplication<Application>().getString(R.string.error_saving, e.message ?: "Unknown error"))
                }
            }
        }
    }

    private fun fail(message: String) {
        _state.update { it.copy(loading = false, loadingName = null, error = message) }
    }

    fun reportError(message: String) = fail(message)

    fun clearError() = _state.update { it.copy(error = null) }

    fun clearInfoMessage() = _state.update { it.copy(infoMessage = null) }

    fun setRenderMode(mode: RenderMode) = _state.update { it.copy(renderMode = mode) }

    fun toggleGrid() = _state.update { it.copy(showGrid = !it.showGrid) }

    fun setModelColor(color: ModelColor) = _state.update { it.copy(modelColor = color) }

    fun toggleMeasureMode() {
        _state.update {
            val next = !it.isMeasuring
            it.copy(
                isMeasuring = next,
                measurePoint1 = if (next) it.measurePoint1 else null,
                measurePoint2 = if (next) it.measurePoint2 else null,
            )
        }
    }

    fun onMeasurePointPicked(x: Float, y: Float, z: Float) {
        val newPoint = Point3D(x, y, z)
        _state.update { current ->
            when {
                current.measurePoint1 == null -> current.copy(measurePoint1 = newPoint)
                current.measurePoint2 == null -> current.copy(measurePoint2 = newPoint)
                else -> current.copy(measurePoint1 = newPoint, measurePoint2 = null)
            }
        }
    }

    fun clearMeasurement() {
        _state.update { it.copy(measurePoint1 = null, measurePoint2 = null) }
    }

    fun closeModel() {
        loadJob?.cancel()
        if (cacheModelFile.exists()) cacheModelFile.delete()
        _state.update {
            it.copy(
                model = null,
                isMeasuring = false,
                measurePoint1 = null,
                measurePoint2 = null,
            )
        }
    }

    private fun queryNameAndSize(resolver: ContentResolver, uri: Uri): Pair<String, Long> {
        var name: String? = null
        var size = -1L
        if (uri.scheme == ContentResolver.SCHEME_FILE) {
            uri.path?.let { path ->
                val file = File(path)
                name = file.name
                size = file.length()
            }
        } else {
            runCatching {
                resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
                    ?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                            val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                            if (nameIndex >= 0 && !cursor.isNull(nameIndex)) name = cursor.getString(nameIndex)
                            if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) size = cursor.getLong(sizeIndex)
                        }
                    }
            }
            if (size < 0) {
                size = runCatching { resolver.openAssetFileDescriptor(uri, "r")?.use { it.length } }.getOrNull() ?: -1L
            }
        }
        return (name ?: uri.lastPathSegment?.substringAfterLast('/') ?: "model") to size
    }
}
