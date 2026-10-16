package space.nish.stlviewer.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.CenterFocusStrong
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.GridOn
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Straighten
import androidx.compose.material.icons.outlined.ViewInAr
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import space.nish.stlviewer.R
import space.nish.stlviewer.model.Mesh
import space.nish.stlviewer.render.ModelSurfaceView
import space.nish.stlviewer.render.RenderMode
import java.text.NumberFormat
import java.util.Locale

private val DarkViewportBackground = floatArrayOf(0.110f, 0.114f, 0.129f)
private val LightViewportBackground = floatArrayOf(0.925f, 0.937f, 0.953f)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ViewerScreen(viewModel: ViewerViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val openDocument = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(viewModel::load)
    }
    val saveDocument = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        uri?.let(viewModel::downloadModel)
    }
    // STL has no universally registered MIME type, so accept everything and let the parser decide.
    val launchPicker = { openDocument.launch(arrayOf("*/*")) }
    val launchSave: () -> Unit = {
        state.model?.fileName?.let { name -> saveDocument.launch(name) }
    }
    val snackbarHostState = remember { SnackbarHostState() }
    val controller = remember { ViewportController() }
    var showInfo by rememberSaveable { mutableStateOf(false) }
    var showLocation by rememberSaveable { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val dark = isSystemInDarkTheme()
    val background = if (dark) DarkViewportBackground else LightViewportBackground

    LaunchedEffect(state.error) {
        state.error?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearError()
        }
    }

    LaunchedEffect(state.infoMessage) {
        state.infoMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearInfoMessage()
        }
    }

    val model = state.model

    Scaffold(
        containerColor = Color(background[0], background[1], background[2]),
        contentWindowInsets = WindowInsets(0),
        snackbarHost = { SnackbarHost(snackbarHostState, Modifier.navigationBarsPadding()) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = model?.fileName ?: stringResource(R.string.app_name),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                actions = {
                    IconButton(onClick = launchPicker) {
                        Icon(Icons.Outlined.FolderOpen, contentDescription = stringResource(R.string.open_file))
                    }
                    if (model != null) {
                        IconButton(onClick = launchSave) {
                            Icon(Icons.Outlined.FileDownload, contentDescription = stringResource(R.string.download_as_new_file))
                        }
                        IconButton(onClick = { showInfo = true }) {
                            Icon(Icons.Outlined.Info, contentDescription = stringResource(R.string.model_info))
                        }
                    }
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.more_options))
                        }
                        OverflowMenu(
                            expanded = menuOpen,
                            onDismiss = { menuOpen = false },
                            state = state,
                            onShowLocation = { showLocation = true },
                            onDownload = launchSave,
                            onToggleGrid = viewModel::toggleGrid,
                            onColor = viewModel::setModelColor,
                            onClose = viewModel::closeModel,
                        )
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize()) {
            if (model != null) {
                ModelViewport(
                    mesh = model.mesh,
                    renderMode = state.renderMode,
                    showGrid = state.showGrid,
                    modelColor = state.modelColor.rgb,
                    background = background,
                    darkBackground = dark,
                    isMeasuring = state.isMeasuring,
                    measurePoint1 = state.measurePoint1,
                    measurePoint2 = state.measurePoint2,
                    onPointPicked = { pt -> viewModel.onMeasurePointPicked(pt[0], pt[1], pt[2]) },
                    controller = controller,
                    onRenderError = viewModel::reportError,
                    modifier = Modifier.fillMaxSize(),
                )
                MeasurementOverlay(
                    isMeasuring = state.isMeasuring,
                    measurePoint1 = state.measurePoint1,
                    measurePoint2 = state.measurePoint2,
                    measurement = state.measurement,
                    onClear = viewModel::clearMeasurement,
                    onExit = viewModel::toggleMeasureMode,
                    onCopy = { summary ->
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("Measurement", summary))
                        scope.launch {
                            snackbarHostState.showSnackbar(context.getString(R.string.measure_copied))
                        }
                    },
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(padding)
                        .padding(top = 8.dp),
                )
                BottomControls(
                    renderMode = state.renderMode,
                    isMeasuring = state.isMeasuring,
                    onRenderMode = viewModel::setRenderMode,
                    onToggleMeasure = viewModel::toggleMeasureMode,
                    onResetView = controller::resetCamera,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        .padding(16.dp),
                )
            } else if (!state.loading) {
                EmptyState(
                    onOpen = launchPicker,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(padding)
                        .padding(32.dp),
                )
            }
            if (state.loading) LoadingOverlay(state.loadingName)
        }
    }

    if (showInfo && model != null) {
        ModelInfoSheet(
            model = model,
            onDismiss = { showInfo = false },
            onDownload = launchSave,
            onCopied = { msg -> scope.launch { snackbarHostState.showSnackbar(msg) } },
        )
    }

    if (showLocation && model != null) {
        FileLocationDialog(
            model = model,
            onDismiss = { showLocation = false },
            onDownload = launchSave,
            onCopied = { msg -> scope.launch { snackbarHostState.showSnackbar(msg) } },
        )
    }
}

/** Lets Compose UI (e.g. the reset button) talk to the underlying GL view. */
class ViewportController {
    internal var view: ModelSurfaceView? = null
    fun resetCamera() {
        view?.resetCamera()
    }
}

@Composable
private fun ModelViewport(
    mesh: Mesh,
    renderMode: RenderMode,
    showGrid: Boolean,
    modelColor: FloatArray,
    background: FloatArray,
    darkBackground: Boolean,
    isMeasuring: Boolean,
    measurePoint1: Point3D?,
    measurePoint2: Point3D?,
    onPointPicked: (FloatArray) -> Unit,
    controller: ViewportController,
    onRenderError: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnError by rememberUpdatedState(onRenderError)
    val currentOnPointPicked by rememberUpdatedState(onPointPicked)
    var surfaceView by remember { mutableStateOf<ModelSurfaceView?>(null) }

    AndroidView(
        modifier = modifier,
        factory = { context ->
            ModelSurfaceView(context).also { view ->
                view.onRenderError = { message -> currentOnError(message) }
                controller.view = view
                surfaceView = view
            }
        },
        update = { view ->
            view.setColors(modelColor, background, darkBackground)
            view.setDisplayMode(renderMode)
            view.setShowGrid(showGrid)
            view.setMesh(mesh)
            view.isMeasureMode = isMeasuring
            view.onPointPicked = { currentOnPointPicked(it) }
            view.setMeasurementPoints(
                measurePoint1?.toFloatArray(),
                measurePoint2?.toFloatArray()
            )
        },
        onRelease = { view ->
            if (controller.view === view) controller.view = null
        },
    )

    // GLSurfaceView must be told when the host is paused/resumed so its render thread can release the GPU.
    DisposableEffect(lifecycleOwner, surfaceView) {
        val view = surfaceView
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> view?.onResume()
                Lifecycle.Event.ON_PAUSE -> view?.onPause()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
}

@Composable
private fun MeasurementOverlay(
    isMeasuring: Boolean,
    measurePoint1: Point3D?,
    measurePoint2: Point3D?,
    measurement: Measurement?,
    onClear: () -> Unit,
    onExit: () -> Unit,
    onCopy: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!isMeasuring) return

    Box(modifier = modifier) {
        if (measurement != null) {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.95f),
                tonalElevation = 6.dp,
                shadowElevation = 8.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Outlined.Straighten,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = stringResource(R.string.measure_title),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                        Row {
                            val formattedDistance = String.format(Locale.getDefault(), "%.2f", measurement.distance)
                            val formattedDx = String.format(Locale.getDefault(), "%.2f", measurement.deltaX)
                            val formattedDy = String.format(Locale.getDefault(), "%.2f", measurement.deltaY)
                            val formattedDz = String.format(Locale.getDefault(), "%.2f", measurement.deltaZ)
                            val copySummary = "Distance: $formattedDistance mm (ΔX: $formattedDx, ΔY: $formattedDy, ΔZ: $formattedDz mm)"

                            IconButton(onClick = { onCopy(copySummary) }, modifier = Modifier.size(36.dp)) {
                                Icon(Icons.Outlined.ContentCopy, contentDescription = stringResource(R.string.copy_path), modifier = Modifier.size(18.dp))
                            }
                            IconButton(onClick = onClear, modifier = Modifier.size(36.dp)) {
                                Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.measure_clear), modifier = Modifier.size(18.dp))
                            }
                        }
                    }

                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = String.format(Locale.getDefault(), "%.2f mm", measurement.distance),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )

                    Spacer(Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        DeltaBadge(label = "ΔX", value = String.format(Locale.getDefault(), "%.2f mm", measurement.deltaX), modifier = Modifier.weight(1f))
                        DeltaBadge(label = "ΔY", value = String.format(Locale.getDefault(), "%.2f mm", measurement.deltaY), modifier = Modifier.weight(1f))
                        DeltaBadge(label = "ΔZ", value = String.format(Locale.getDefault(), "%.2f mm", measurement.deltaZ), modifier = Modifier.weight(1f))
                    }

                    Spacer(Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        OutlinedButton(onClick = onClear, modifier = Modifier.height(36.dp)) {
                            Text(stringResource(R.string.measure_clear))
                        }
                        Spacer(Modifier.width(8.dp))
                        Button(onClick = onExit, modifier = Modifier.height(36.dp)) {
                            Text(stringResource(R.string.measure_done))
                        }
                    }
                }
            }
        } else {
            val instruction = if (measurePoint1 == null) {
                stringResource(R.string.measure_instruction_p1)
            } else {
                stringResource(R.string.measure_instruction_p2)
            }
            Surface(
                shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.95f),
                tonalElevation = 4.dp,
                shadowElevation = 6.dp,
                modifier = Modifier.padding(horizontal = 24.dp),
            ) {
                Row(
                    modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Outlined.Straighten,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = instruction,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                    )
                    Spacer(Modifier.width(12.dp))
                    if (measurePoint1 != null) {
                        TextButton(onClick = onClear, modifier = Modifier.height(32.dp)) {
                            Text(stringResource(R.string.measure_clear), style = MaterialTheme.typography.labelMedium)
                        }
                    }
                    IconButton(onClick = onExit, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.measure_done), modifier = Modifier.size(16.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun DeltaBadge(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BottomControls(
    renderMode: RenderMode,
    isMeasuring: Boolean,
    onRenderMode: (RenderMode) -> Unit,
    onToggleMeasure: () -> Unit,
    onResetView: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val labels = mapOf(
        RenderMode.SHADED to stringResource(R.string.mode_solid),
        RenderMode.SHADED_EDGES to stringResource(R.string.mode_edges),
        RenderMode.WIREFRAME to stringResource(R.string.mode_wire),
    )
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Surface(
            shape = RoundedCornerShape(50),
            color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.92f),
            tonalElevation = 3.dp,
        ) {
            SingleChoiceSegmentedButtonRow(Modifier.padding(4.dp)) {
                val modes = RenderMode.entries
                modes.forEachIndexed { index, mode ->
                    SegmentedButton(
                        selected = mode == renderMode,
                        onClick = { onRenderMode(mode) },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = modes.size),
                        icon = {},
                    ) {
                        Text(labels.getValue(mode))
                    }
                }
            }
        }
        Spacer(Modifier.width(10.dp))
        FilledTonalIconButton(
            onClick = onToggleMeasure,
            colors = if (isMeasuring) {
                IconButtonDefaults.filledTonalIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                )
            } else {
                IconButtonDefaults.filledTonalIconButtonColors()
            },
            modifier = Modifier.size(48.dp),
        ) {
            Icon(Icons.Outlined.Straighten, contentDescription = stringResource(R.string.measure))
        }
        Spacer(Modifier.width(10.dp))
        FilledTonalIconButton(onClick = onResetView, modifier = Modifier.size(48.dp)) {
            Icon(Icons.Outlined.CenterFocusStrong, contentDescription = stringResource(R.string.reset_view))
        }
    }
}

@Composable
private fun OverflowMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    state: ViewerUiState,
    onShowLocation: () -> Unit,
    onDownload: () -> Unit,
    onToggleGrid: () -> Unit,
    onColor: (ModelColor) -> Unit,
    onClose: () -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        if (state.model != null) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.file_location)) },
                leadingIcon = { Icon(Icons.Outlined.Folder, contentDescription = null) },
                onClick = {
                    onShowLocation()
                    onDismiss()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.download_as_new_file)) },
                leadingIcon = { Icon(Icons.Outlined.FileDownload, contentDescription = null) },
                onClick = {
                    onDownload()
                    onDismiss()
                },
            )
            HorizontalDivider()
        }
        DropdownMenuItem(
            text = { Text(stringResource(R.string.show_grid)) },
            leadingIcon = { Icon(Icons.Outlined.GridOn, contentDescription = null) },
            trailingIcon = { if (state.showGrid) Icon(Icons.Filled.Check, contentDescription = null) },
            onClick = onToggleGrid,
        )
        HorizontalDivider()
        Text(
            text = stringResource(R.string.model_color),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        ModelColor.entries.forEach { color ->
            DropdownMenuItem(
                text = { Text(color.name) },
                leadingIcon = {
                    Icon(
                        Icons.Filled.Circle,
                        contentDescription = null,
                        tint = Color(color.rgb[0], color.rgb[1], color.rgb[2]),
                    )
                },
                trailingIcon = { if (state.modelColor == color) Icon(Icons.Filled.Check, contentDescription = null) },
                onClick = { onColor(color) },
            )
        }
        if (state.model != null) {
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text(stringResource(R.string.close_model)) },
                leadingIcon = { Icon(Icons.Outlined.Close, contentDescription = null) },
                onClick = {
                    onClose()
                    onDismiss()
                },
            )
        }
    }
}

@Composable
private fun EmptyState(onOpen: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box(
            modifier = Modifier
                .size(120.dp)
                .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Outlined.ViewInAr,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
        Text(stringResource(R.string.empty_title), style = MaterialTheme.typography.headlineSmall)
        Text(
            stringResource(R.string.empty_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Button(onClick = onOpen) {
            Icon(Icons.Outlined.FolderOpen, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.open_file))
        }
    }
}

@Composable
private fun LoadingOverlay(name: String?) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.35f)),
        contentAlignment = Alignment.Center,
    ) {
        Surface(shape = RoundedCornerShape(24.dp), tonalElevation = 6.dp) {
            Column(
                modifier = Modifier.padding(horizontal = 32.dp, vertical = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CircularProgressIndicator()
                Spacer(Modifier.height(16.dp))
                Text(
                    text = if (name != null) stringResource(R.string.loading_named, name) else stringResource(R.string.loading),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelInfoSheet(
    model: LoadedModel,
    onDismiss: () -> Unit,
    onDownload: () -> Unit,
    onCopied: (String) -> Unit,
) {
    val context = LocalContext.current
    val mesh = model.mesh
    val b = mesh.bounds
    val stats = mesh.stats
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
        ) {
            Text(model.fileName, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(16.dp))
            InfoRow(
                label = stringResource(R.string.info_location),
                value = model.fileLocation,
                onCopy = {
                    copyToClipboard(context, model.fileLocation)
                    onCopied(context.getString(R.string.location_copied))
                },
            )
            InfoRow(stringResource(R.string.info_triangles), NumberFormat.getIntegerInstance().format(stats.triangleCount))
            InfoRow(
                stringResource(R.string.info_dimensions),
                "${fmt(b.sizeX)} × ${fmt(b.sizeY)} × ${fmt(b.sizeZ)} mm",
            )
            InfoRow(stringResource(R.string.info_volume), "${fmt(stats.volume / 1000.0)} cm³")
            InfoRow(stringResource(R.string.info_area), "${fmt(stats.surfaceArea / 100.0)} cm²")
            if (model.fileSize >= 0) {
                InfoRow(stringResource(R.string.info_file_size), Formatter.formatShortFileSize(context, model.fileSize))
            }
            InfoRow(stringResource(R.string.info_load_time), "${model.loadTimeMs} ms")
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = {
                    onDismiss()
                    onDownload()
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Outlined.FileDownload, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.download_as_new_file))
            }
            Spacer(Modifier.height(16.dp))
            Text(
                stringResource(R.string.info_units_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.gestures_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun InfoRow(
    label: String,
    value: String,
    onCopy: (() -> Unit)? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(16.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f, fill = false),
        ) {
            Text(
                text = value,
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.End,
                maxLines = if (onCopy != null) 3 else 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (onCopy != null) {
                IconButton(
                    onClick = onCopy,
                    modifier = Modifier.size(32.dp).padding(start = 4.dp),
                ) {
                    Icon(
                        Icons.Outlined.ContentCopy,
                        contentDescription = stringResource(R.string.copy_path),
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

@Composable
private fun FileLocationDialog(
    model: LoadedModel,
    onDismiss: () -> Unit,
    onDownload: () -> Unit,
    onCopied: (String) -> Unit,
) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.Folder, contentDescription = null) },
        title = {
            Text(
                text = stringResource(R.string.file_location),
                style = MaterialTheme.typography.headlineSmall,
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = model.fileName,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text(
                            text = stringResource(R.string.info_location),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.height(4.dp))
                        SelectionContainer {
                            Text(
                                text = model.fileLocation,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
                if (model.rawUri != null && model.rawUri != model.fileLocation) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHighest,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Text(
                                text = stringResource(R.string.source_uri),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.secondary,
                            )
                            Spacer(Modifier.height(4.dp))
                            SelectionContainer {
                                Text(
                                    text = model.rawUri.orEmpty(),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onDismiss()
                    onDownload()
                },
            ) {
                Icon(Icons.Outlined.FileDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.download_as_new_file))
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(android.R.string.cancel))
                }
                OutlinedButton(
                    onClick = {
                        copyToClipboard(context, model.fileLocation)
                        onCopied(context.getString(R.string.location_copied))
                    },
                ) {
                    Icon(Icons.Outlined.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.copy_path))
                }
            }
        },
    )
}

private fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    val clip = ClipData.newPlainText("Location", text)
    clipboard?.setPrimaryClip(clip)
}

private fun fmt(value: Number): String = String.format(Locale.getDefault(), "%.2f", value.toDouble())
