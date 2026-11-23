package com.example.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.JarRepository
import com.example.model.CanvasDrawCommand
import com.example.model.DownloadProgressState
import com.example.model.InstalledJarApp
import com.example.model.JarDialogState
import com.example.model.JreInstallationStatus
import com.example.model.LogLevel
import com.example.model.RuntimeMode
import com.example.model.SandboxLogEntry
import com.example.model.UiNodeModel
import com.example.runtime.JreManager
import com.example.runtime.MiniJvmEngine
import com.example.runtime.ProcessJvmSession
import com.example.runtime.SandboxPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class JarRunnerViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = JarRepository(application)
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.GERMANY)

    private val _apps = MutableStateFlow<List<InstalledJarApp>>(emptyList())
    val apps: StateFlow<List<InstalledJarApp>> = _apps.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val _runtimeMode = MutableStateFlow(RuntimeMode.EMBEDDED_BYTECODE)
    val runtimeMode: StateFlow<RuntimeMode> = _runtimeMode.asStateFlow()

    private val _jreStatus = MutableStateFlow(JreManager.inspectJreStatus(application))
    val jreStatus: StateFlow<JreInstallationStatus> = _jreStatus.asStateFlow()

    private val _downloadState = MutableStateFlow(DownloadProgressState())
    val downloadState: StateFlow<DownloadProgressState> = _downloadState.asStateFlow()

    private val _statusBanner = MutableStateFlow<String?>(null)
    val statusBanner: StateFlow<String?> = _statusBanner.asStateFlow()

    // Active JAR execution session
    private val _runningApp = MutableStateFlow<InstalledJarApp?>(null)
    val runningApp: StateFlow<InstalledJarApp?> = _runningApp.asStateFlow()

    private val _uiTree = MutableStateFlow<UiNodeModel?>(null)
    val uiTree: StateFlow<UiNodeModel?> = _uiTree.asStateFlow()

    private val _canvasCommands = MutableStateFlow<Map<String, List<CanvasDrawCommand>>>(emptyMap())
    val canvasCommands: StateFlow<Map<String, List<CanvasDrawCommand>>> = _canvasCommands.asStateFlow()

    private val _activeDialog = MutableStateFlow<JarDialogState?>(null)
    val activeDialog: StateFlow<JarDialogState?> = _activeDialog.asStateFlow()

    private val _consoleLogs = MutableStateFlow<List<SandboxLogEntry>>(emptyList())
    val consoleLogs: StateFlow<List<SandboxLogEntry>> = _consoleLogs.asStateFlow()

    private val _instructionCount = MutableStateFlow(0L)
    val instructionCount: StateFlow<Long> = _instructionCount.asStateFlow()

    private val _activeSandboxPath = MutableStateFlow("")
    val activeSandboxPath: StateFlow<String> = _activeSandboxPath.asStateFlow()

    private val _toastEvents = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val toastEvents: SharedFlow<String> = _toastEvents.asSharedFlow()

    private var miniEngine: MiniJvmEngine? = null
    private var processSession: ProcessJvmSession? = null

    init {
        viewModelScope.launch {
            _isScanning.value = true
            _apps.value = repository.ensureBundledAssetsAndScan()
            _jreStatus.value = JreManager.inspectJreStatus(getApplication())
            _isScanning.value = false
        }
    }

    fun rescanJars() {
        viewModelScope.launch {
            _isScanning.value = true
            _apps.value = repository.scanInstalledJars()
            _isScanning.value = false
            _statusBanner.value = "${_apps.value.size} JAR-App(s) im Workspace gefunden."
        }
    }

    fun clearStatusBanner() {
        _statusBanner.value = null
    }

    fun importLocalJar(uri: Uri) {
        viewModelScope.launch {
            val res = repository.importJarFromUri(uri)
            res.onSuccess { app ->
                _apps.value = repository.scanInstalledJars()
                _statusBanner.value = "Installiert: ${app.manifest.name} (${app.classCount} Klassen)"
            }.onFailure { err ->
                _statusBanner.value = "Import fehlgeschlagen: ${err.localizedMessage}"
            }
        }
    }

    fun scanSafFolder(treeUri: Uri) {
        viewModelScope.launch {
            _isScanning.value = true
            val res = repository.scanSafFolderAndImport(treeUri)
            _apps.value = repository.scanInstalledJars()
            _isScanning.value = false
            res.onSuccess { count ->
                _statusBanner.value = "$count JAR-Datei(en) aus dem gewählten Ordner importiert."
            }.onFailure { err ->
                _statusBanner.value = "Ordner-Scan fehlgeschlagen: ${err.localizedMessage}"
            }
        }
    }

    fun downloadJarFromUrl(url: String) {
        viewModelScope.launch {
            _downloadState.value = DownloadProgressState(
                isDownloading = true,
                progress = 0f,
                statusText = "Verbinde per HTTPS..."
            )
            val res = repository.downloadJarFromUrl(url) { progress ->
                _downloadState.value = DownloadProgressState(
                    isDownloading = true,
                    progress = progress,
                    statusText = "Lade JAR herunter... ${(progress * 100).toInt()}%"
                )
            }
            res.onSuccess { app ->
                _apps.value = repository.scanInstalledJars()
                _downloadState.value = DownloadProgressState(
                    isDownloading = false,
                    progress = 1f,
                    statusText = "Installiert: ${app.manifest.name} v${app.manifest.version}"
                )
            }.onFailure { err ->
                _downloadState.value = DownloadProgressState(
                    isDownloading = false,
                    progress = 0f,
                    statusText = "",
                    error = err.localizedMessage ?: "Download fehlgeschlagen"
                )
            }
        }
    }

    fun deleteJar(app: InstalledJarApp) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.deleteInstalledJar(app)
            val updated = repository.scanInstalledJars()
            _apps.value = updated
            _statusBanner.value = "Entfernt: ${app.manifest.name}"
        }
    }

    fun setRuntimeMode(mode: RuntimeMode) {
        if (mode != RuntimeMode.EMBEDDED_BYTECODE && !_jreStatus.value.isInstalled) {
            _statusBanner.value = "Hinweis: Für ${mode.title} zuerst eine ARM64-JRE im Laufzeit-Tab importieren."
        }
        _runtimeMode.value = mode
    }

    fun importJreZip(uri: Uri) {
        viewModelScope.launch {
            _statusBanner.value = "Entpacke ARM64-JRE-Archiv..."
            val res = JreManager.installJreFromZip(getApplication(), uri)
            res.onSuccess { status ->
                _jreStatus.value = status
                _statusBanner.value = if (status.isInstalled) {
                    "ARM64-JRE bereit (${String.format(Locale.GERMANY, "%.1f", status.totalSizeMb)} MB)."
                } else {
                    "Archiv entpackt, aber bin/java oder libjvm.so nicht gefunden."
                }
            }.onFailure { err ->
                _statusBanner.value = "JRE-Import fehlgeschlagen: ${err.localizedMessage}"
            }
        }
    }

    fun launchJarApp(app: InstalledJarApp) {
        stopRunningApp()
        _runningApp.value = app
        _uiTree.value = null
        _canvasCommands.value = emptyMap()
        _consoleLogs.value = emptyList()
        _instructionCount.value = 0L

        val sandboxDir = SandboxPolicy.getOrCreateAppSandboxDir(getApplication(), app.manifest.id)
        _activeSandboxPath.value = sandboxDir.absolutePath

        if (_runtimeMode.value == RuntimeMode.OPENJDK_CHILD_PROCESS && _jreStatus.value.hasJavaBinary) {
            launchViaChildProcess(app, sandboxDir)
        } else {
            launchViaEmbeddedEngine(app, sandboxDir)
        }
    }

    private fun launchViaEmbeddedEngine(app: InstalledJarApp, sandboxDir: File) {
        viewModelScope.launch(Dispatchers.Default) {
            runCatching {
                val engine = MiniJvmEngine(
                    jarFile = File(app.jarFilePath),
                    grantedPermissions = app.manifest.permissions,
                    sandboxDir = sandboxDir,
                    onUiTree = { node -> _uiTree.value = node },
                    onToast = { msg -> _toastEvents.tryEmit(msg) },
                    onDialog = { dlg -> _activeDialog.value = dlg },
                    onLog = { level, msg -> appendLog(level, msg) }
                )
                miniEngine = engine
                engine.start(app.manifest.mainClass)
                _instructionCount.value = engine.getInstructionCount()
            }.onFailure { err ->
                appendLog(LogLevel.ERROR, err.message ?: err.toString())
            }
        }
    }

    private fun launchViaChildProcess(app: InstalledJarApp, sandboxDir: File) {
        val session = ProcessJvmSession(
            jreRoot = JreManager.getJreRoot(getApplication()),
            runnerApiJar = repository.getRunnerApiJarFile(),
            appJar = File(app.jarFilePath),
            mainClass = app.manifest.mainClass,
            sandboxDir = sandboxDir,
            onUiTree = { node -> _uiTree.value = node },
            onToast = { msg -> _toastEvents.tryEmit(msg) },
            onDialog = { dlg -> _activeDialog.value = dlg },
            onCanvasFrame = { id, cmds ->
                _canvasCommands.update { it + (id to cmds) }
            },
            onLog = { level, msg -> appendLog(level, msg) }
        )
        processSession = session
        session.start(viewModelScope)
    }

    fun onBridgeButtonClick(nodeId: String) {
        val engine = miniEngine
        if (engine != null) {
            viewModelScope.launch(Dispatchers.Default) {
                runCatching {
                    engine.dispatchClick(nodeId)
                    _instructionCount.value = engine.getInstructionCount()
                }.onFailure { err ->
                    appendLog(LogLevel.ERROR, err.message ?: "Fehler bei onClick")
                }
            }
        } else {
            processSession?.sendClick(nodeId)
        }
    }

    fun onBridgeTextChange(nodeId: String, newText: String) {
        val engine = miniEngine
        if (engine != null) {
            viewModelScope.launch(Dispatchers.Default) {
                runCatching {
                    engine.dispatchTextChanged(nodeId, newText)
                    _instructionCount.value = engine.getInstructionCount()
                }.onFailure { err ->
                    appendLog(LogLevel.ERROR, err.message ?: "Fehler bei onTextChanged")
                }
            }
        } else {
            processSession?.sendTextChange(nodeId, newText)
        }
    }

    fun onBridgeItemClick(nodeId: String, index: Int, item: String) {
        val engine = miniEngine
        if (engine != null) {
            viewModelScope.launch(Dispatchers.Default) {
                runCatching {
                    engine.dispatchItemClick(nodeId, index, item)
                    _instructionCount.value = engine.getInstructionCount()
                }.onFailure { err ->
                    appendLog(LogLevel.ERROR, err.message ?: "Fehler bei onItemClick")
                }
            }
        } else {
            processSession?.sendItemClick(nodeId, index, item)
        }
    }

    fun requestCanvasTick(canvasId: String, widthPx: Int, heightPx: Int, tick: Long) {
        val engine = miniEngine
        if (engine != null) {
            viewModelScope.launch(Dispatchers.Default) {
                runCatching {
                    val cmds = engine.renderCanvasFrame(canvasId, widthPx, heightPx, tick)
                    _canvasCommands.update { it + (canvasId to cmds) }
                    _instructionCount.value = engine.getInstructionCount()
                }
            }
        } else {
            processSession?.requestCanvasFrame(canvasId, widthPx, heightPx, tick)
        }
    }

    fun dismissDialog() {
        _activeDialog.value = null
    }

    fun stopRunningApp() {
        processSession?.stop()
        processSession = null
        miniEngine = null
        _runningApp.value = null
        _uiTree.value = null
        _activeDialog.value = null
    }

    private fun appendLog(level: LogLevel, message: String) {
        val entry = SandboxLogEntry(
            timestamp = timeFormat.format(Date()),
            level = level,
            message = message
        )
        _consoleLogs.update { (listOf(entry) + it).take(80) }
    }

    override fun onCleared() {
        super.onCleared()
        stopRunningApp()
    }
}
