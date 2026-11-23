package com.example.model

enum class JarSourceType(val label: String) {
    BUNDLED("Bundled SDK"),
    LOCAL_FILE("Lokal"),
    SAF_FOLDER("Ordner-Scan"),
    HTTPS_URL("HTTPS-Repo")
}

enum class RuntimeMode(val title: String, val subtitle: String) {
    EMBEDDED_BYTECODE(
        title = "Embedded Bytecode Sandbox",
        subtitle = "Pure-Kotlin JVM-Interpreter + ClassFilter (sofort aktiv)"
    ),
    OPENJDK_CHILD_PROCESS(
        title = "OpenJDK ARM64 Kindprozess",
        subtitle = "Isolierter bin/java Prozess + JSON-IPC-Bridge"
    ),
    JNI_IN_PROCESS(
        title = "JNI In-Process (libjvm.so)",
        subtitle = "dlopen + JNI_CreateJavaVM im selben Prozess"
    )
}

data class JarManifest(
    val id: String,
    val name: String,
    val version: String,
    val author: String,
    val description: String,
    val mainClass: String,
    val iconPath: String = "icon.png",
    val accentColor: String = "#F59E0B",
    val permissions: List<String> = listOf("UI_BRIDGE")
)

data class InstalledJarApp(
    val manifest: JarManifest,
    val jarFilePath: String,
    val fileName: String,
    val sourceType: JarSourceType,
    val sourceLocation: String,
    val fileSizeBytes: Long,
    val classCount: Int,
    val classNames: List<String>,
    val iconBytes: ByteArray?,
    val hasManifestJson: Boolean
)

data class JreInstallationStatus(
    val isInstalled: Boolean,
    val jreRootPath: String,
    val hasJavaBinary: Boolean,
    val hasLibJvm: Boolean,
    val libJvmPath: String?,
    val totalSizeMb: Double
)

enum class LogLevel {
    INFO, STDOUT, SECURITY, ERROR
}

data class SandboxLogEntry(
    val timestamp: String,
    val level: LogLevel,
    val message: String
)

data class JarDialogState(
    val title: String,
    val message: String,
    val confirmLabel: String
)

sealed class UiNodeModel(open val id: String, open val paddingDp: Int) {
    data class ColumnNode(
        override val id: String,
        override val paddingDp: Int = 0,
        val spacingDp: Int = 12,
        val children: List<UiNodeModel> = emptyList()
    ) : UiNodeModel(id, paddingDp)

    data class RowNode(
        override val id: String,
        override val paddingDp: Int = 0,
        val spacingDp: Int = 8,
        val children: List<UiNodeModel> = emptyList()
    ) : UiNodeModel(id, paddingDp)

    data class TextNode(
        override val id: String,
        override val paddingDp: Int = 0,
        val text: String,
        val style: String = "body",
        val colorHex: String = ""
    ) : UiNodeModel(id, paddingDp)

    data class ButtonNode(
        override val id: String,
        override val paddingDp: Int = 0,
        val label: String,
        val variant: String = "primary"
    ) : UiNodeModel(id, paddingDp)

    data class TextFieldNode(
        override val id: String,
        override val paddingDp: Int = 0,
        val value: String,
        val hint: String
    ) : UiNodeModel(id, paddingDp)

    data class ListNode(
        override val id: String,
        override val paddingDp: Int = 0,
        val items: List<String>
    ) : UiNodeModel(id, paddingDp)

    data class CanvasNode(
        override val id: String,
        override val paddingDp: Int = 0,
        val heightDp: Int = 240,
        val backgroundHex: String = "#090F1D"
    ) : UiNodeModel(id, paddingDp)
}

sealed interface CanvasDrawCommand {
    data class Circle(val x: Float, val y: Float, val radius: Float, val colorHex: String) : CanvasDrawCommand
    data class Rect(val x: Float, val y: Float, val width: Float, val height: Float, val colorHex: String) : CanvasDrawCommand
    data class Line(val x1: Float, val y1: Float, val x2: Float, val y2: Float, val strokeWidth: Float, val colorHex: String) : CanvasDrawCommand
    data class Text(val text: String, val x: Float, val y: Float, val size: Float, val colorHex: String) : CanvasDrawCommand
}

data class DownloadProgressState(
    val isDownloading: Boolean = false,
    val progress: Float = 0f,
    val statusText: String = "",
    val error: String? = null
)
