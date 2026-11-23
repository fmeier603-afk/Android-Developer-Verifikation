package com.example.ui.screens

import android.graphics.Paint
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.model.CanvasDrawCommand
import com.example.model.InstalledJarApp
import com.example.model.JarDialogState
import com.example.model.LogLevel
import com.example.model.RuntimeMode
import com.example.model.SandboxLogEntry
import com.example.model.UiNodeModel
import com.example.ui.theme.AlertRed
import com.example.ui.theme.EmeraldSafe
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharedFlow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JarAppRunnerScreen(
    app: InstalledJarApp,
    runtimeMode: RuntimeMode,
    uiTree: UiNodeModel?,
    canvasCommands: Map<String, List<CanvasDrawCommand>>,
    activeDialog: JarDialogState?,
    consoleLogs: List<SandboxLogEntry>,
    instructionCount: Long,
    sandboxPath: String,
    toastEvents: SharedFlow<String>,
    onButtonClick: (String) -> Unit,
    onTextChange: (String, String) -> Unit,
    onItemClick: (String, Int, String) -> Unit,
    onRequestCanvasFrame: (String, Int, Int, Long) -> Unit,
    onDismissDialog: () -> Unit,
    onStopAndExit: () -> Unit
) {
    BackHandler { onStopAndExit() }

    val snackbarHostState = remember { SnackbarHostState() }
    var showConsole by remember { mutableStateOf(false) }

    LaunchedEffect(toastEvents) {
        toastEvents.collect { msg ->
            snackbarHostState.showSnackbar(msg)
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbarHostState, modifier = Modifier.navigationBarsPadding()) },
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                navigationIcon = {
                    IconButton(
                        onClick = onStopAndExit,
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("stop_jar_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.cd_stop_app)
                        )
                    }
                },
                title = {
                    Column {
                        Text(
                            text = app.manifest.name,
                            style = MaterialTheme.typography.titleLarge
                        )
                        Text(
                            text = "${runtimeMode.title} • $instructionCount Ops",
                            style = MaterialTheme.typography.labelSmall,
                            color = EmeraldSafe
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = { showConsole = !showConsole },
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("toggle_console_button")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Terminal,
                            contentDescription = stringResource(R.string.cd_console),
                            tint = if (showConsole) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .navigationBarsPadding()
        ) {
            // Sandbox Telemetry Strip
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Lock,
                        contentDescription = null,
                        tint = EmeraldSafe,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Sandbox: .../${sandboxPath.substringAfterLast('/')}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = app.manifest.permissions.joinToString(", "),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }
            }

            // Main Native UI-Bridge Canvas/Tree Area
            Box(
                contentAlignment = Alignment.TopCenter,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .widthIn(max = 680.dp)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp)
                ) {
                    if (uiTree != null) {
                        RenderUiNode(
                            node = uiTree,
                            canvasCommands = canvasCommands,
                            onButtonClick = onButtonClick,
                            onTextChange = onTextChange,
                            onItemClick = onItemClick,
                            onRequestCanvasFrame = onRequestCanvasFrame
                        )
                    } else {
                        Text(
                            text = "Initialisiere JVM-Sandbox...",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Collapsible Live Sandbox Console
            AnimatedVisibility(visible = showConsole) {
                Surface(
                    color = Color(0xFF070B14),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp)
                ) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Text(
                                text = "SANDBOX STDOUT & SECURITY LOG",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                            IconButton(
                                onClick = { showConsole = false },
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Close,
                                    contentDescription = "Konsole schließen",
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                        LazyColumn(
                            contentPadding = PaddingValues(10.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                            modifier = Modifier.fillMaxSize()
                        ) {
                            items(consoleLogs) { entry ->
                                val tint = when (entry.level) {
                                    LogLevel.INFO -> MaterialTheme.colorScheme.secondary
                                    LogLevel.STDOUT -> MaterialTheme.colorScheme.onBackground
                                    LogLevel.SECURITY -> MaterialTheme.colorScheme.primary
                                    LogLevel.ERROR -> AlertRed
                                }
                                Text(
                                    text = "[${entry.timestamp}] ${entry.message}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = tint
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    activeDialog?.let { dlg ->
        AlertDialog(
            onDismissRequest = onDismissDialog,
            title = { Text(dlg.title) },
            text = { Text(dlg.message) },
            confirmButton = {
                TextButton(onClick = onDismissDialog) {
                    Text(dlg.confirmLabel)
                }
            }
        )
    }
}

@Composable
private fun RenderUiNode(
    node: UiNodeModel,
    canvasCommands: Map<String, List<CanvasDrawCommand>>,
    onButtonClick: (String) -> Unit,
    onTextChange: (String, String) -> Unit,
    onItemClick: (String, Int, String) -> Unit,
    onRequestCanvasFrame: (String, Int, Int, Long) -> Unit,
    modifier: Modifier = Modifier
) {
    when (node) {
        is UiNodeModel.ColumnNode -> {
            Column(
                verticalArrangement = Arrangement.spacedBy(node.spacingDp.dp),
                modifier = modifier.fillMaxWidth()
            ) {
                node.children.forEach { child ->
                    RenderUiNode(
                        node = child,
                        canvasCommands = canvasCommands,
                        onButtonClick = onButtonClick,
                        onTextChange = onTextChange,
                        onItemClick = onItemClick,
                        onRequestCanvasFrame = onRequestCanvasFrame
                    )
                }
            }
        }

        is UiNodeModel.RowNode -> {
            Row(
                horizontalArrangement = Arrangement.spacedBy(node.spacingDp.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = modifier.fillMaxWidth()
            ) {
                node.children.forEach { child ->
                    RenderUiNode(
                        node = child,
                        canvasCommands = canvasCommands,
                        onButtonClick = onButtonClick,
                        onTextChange = onTextChange,
                        onItemClick = onItemClick,
                        onRequestCanvasFrame = onRequestCanvasFrame,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        is UiNodeModel.TextNode -> {
            val textStyle = when (node.style) {
                "headline" -> MaterialTheme.typography.headlineSmall
                "title" -> MaterialTheme.typography.titleLarge
                "mono" -> MaterialTheme.typography.labelMedium
                "caption" -> MaterialTheme.typography.bodyMedium
                else -> MaterialTheme.typography.bodyLarge
            }
            val color = remember(node.colorHex) {
                if (node.colorHex.isNotBlank()) {
                    runCatching { Color(android.graphics.Color.parseColor(node.colorHex)) }.getOrNull()
                } else null
            } ?: MaterialTheme.colorScheme.onBackground

            Text(
                text = node.text,
                style = textStyle,
                color = color,
                modifier = modifier
            )
        }

        is UiNodeModel.ButtonNode -> {
            when (node.variant) {
                "outlined" -> OutlinedButton(
                    onClick = { onButtonClick(node.id) },
                    modifier = modifier
                        .minimumInteractiveComponentSize()
                        .testTag("bridge_btn_${node.id}")
                ) {
                    Text(node.label)
                }
                "tonal" -> FilledTonalButton(
                    onClick = { onButtonClick(node.id) },
                    modifier = modifier
                        .minimumInteractiveComponentSize()
                        .testTag("bridge_btn_${node.id}")
                ) {
                    Text(node.label)
                }
                else -> Button(
                    onClick = { onButtonClick(node.id) },
                    modifier = modifier
                        .minimumInteractiveComponentSize()
                        .testTag("bridge_btn_${node.id}")
                ) {
                    Text(node.label)
                }
            }
        }

        is UiNodeModel.TextFieldNode -> {
            var localValue by remember(node.hint) { mutableStateOf(node.value) }
            OutlinedTextField(
                value = localValue,
                onValueChange = { updated ->
                    localValue = updated
                    onTextChange(node.id, updated)
                },
                label = { Text(node.hint) },
                singleLine = true,
                modifier = modifier
                    .fillMaxWidth()
                    .testTag("bridge_input_${node.id}")
            )
        }

        is UiNodeModel.ListNode -> {
            Card(
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                modifier = modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    node.items.forEachIndexed { idx, item ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onItemClick(node.id, idx, item) }
                                .padding(horizontal = 14.dp, vertical = 12.dp)
                        ) {
                            Text(
                                text = item,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f)
                            )
                        }
                        if (idx < node.items.lastIndex) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                        }
                    }
                }
            }
        }

        is UiNodeModel.CanvasNode -> {
            BridgeCanvasSurface(
                node = node,
                commands = canvasCommands[node.id].orEmpty(),
                onRequestFrame = onRequestCanvasFrame,
                modifier = modifier
            )
        }
    }
}

@Composable
private fun BridgeCanvasSurface(
    node: UiNodeModel.CanvasNode,
    commands: List<CanvasDrawCommand>,
    onRequestFrame: (String, Int, Int, Long) -> Unit,
    modifier: Modifier = Modifier
) {
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    var tick by remember { mutableLongStateOf(0L) }
    val bgColor = remember(node.backgroundHex) {
        runCatching { Color(android.graphics.Color.parseColor(node.backgroundHex)) }
            .getOrDefault(Color(0xFF090F1D))
    }

    LaunchedEffect(node.id, canvasSize) {
        if (canvasSize.width > 0 && canvasSize.height > 0) {
            while (true) {
                onRequestFrame(node.id, canvasSize.width, canvasSize.height, tick++)
                delay(33L)
            }
        }
    }

    val textPaint = remember {
        Paint().apply {
            isAntiAlias = true
        }
    }

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(node.heightDp.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(bgColor)
            .onSizeChanged { canvasSize = it }
    ) {
        commands.forEach { cmd ->
            when (cmd) {
                is CanvasDrawCommand.Circle -> {
                    val col = parseHexColor(cmd.colorHex)
                    drawCircle(
                        color = col,
                        radius = cmd.radius,
                        center = Offset(cmd.x, cmd.y)
                    )
                }
                is CanvasDrawCommand.Rect -> {
                    val col = parseHexColor(cmd.colorHex)
                    drawRect(
                        color = col,
                        topLeft = Offset(cmd.x, cmd.y),
                        size = Size(cmd.width, cmd.height)
                    )
                }
                is CanvasDrawCommand.Line -> {
                    val col = parseHexColor(cmd.colorHex)
                    drawLine(
                        color = col,
                        start = Offset(cmd.x1, cmd.y1),
                        end = Offset(cmd.x2, cmd.y2),
                        strokeWidth = cmd.strokeWidth
                    )
                }
                is CanvasDrawCommand.Text -> {
                    textPaint.color = runCatching { android.graphics.Color.parseColor(cmd.colorHex) }
                        .getOrDefault(android.graphics.Color.WHITE)
                    textPaint.textSize = cmd.size * density
                    drawContext.canvas.nativeCanvas.drawText(cmd.text, cmd.x, cmd.y, textPaint)
                }
            }
        }
    }
}

private fun parseHexColor(hex: String): Color =
    runCatching { Color(android.graphics.Color.parseColor(hex)) }.getOrDefault(Color.White)
