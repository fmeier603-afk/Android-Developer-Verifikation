package com.example.runtime

import android.content.Context
import android.net.Uri
import com.example.model.CanvasDrawCommand
import com.example.model.JarDialogState
import com.example.model.JreInstallationStatus
import com.example.model.LogLevel
import com.example.model.UiNodeModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.zip.ZipInputStream

/**
 * JNI-Vertrag für In-Process JVM-Start über libjvm.so (native/jvm_bridge.cpp).
 * Wird aktiviert, sobald das native Modul mit NDK + CMake gebaut wird.
 */
object JniJvmBridge {
    val isNativeLibraryLoaded: Boolean by lazy {
        runCatching {
            System.loadLibrary("jarrunner_jni")
            true
        }.getOrDefault(false)
    }

    external fun nativeLaunchJar(
        libJvmPath: String,
        classPath: String,
        mainClass: String,
        sandboxDir: String
    ): Int
}

object JreManager {
    fun getJreRoot(context: Context): File = File(File(context.filesDir, "runtime"), "jre")

    fun inspectJreStatus(context: Context): JreInstallationStatus {
        val jreDir = getJreRoot(context)
        val javaBin = File(jreDir, "bin/java")
        val libJvmCandidates = listOf(
            File(jreDir, "lib/server/libjvm.so"),
            File(jreDir, "lib/aarch64/server/libjvm.so"),
            File(jreDir, "lib/libjvm.so")
        )
        val foundLibJvm = libJvmCandidates.firstOrNull { it.exists() }
        val totalBytes = if (jreDir.exists()) {
            jreDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        } else 0L

        return JreInstallationStatus(
            isInstalled = javaBin.exists() || foundLibJvm != null,
            jreRootPath = jreDir.absolutePath,
            hasJavaBinary = javaBin.exists(),
            hasLibJvm = foundLibJvm != null,
            libJvmPath = foundLibJvm?.absolutePath,
            totalSizeMb = totalBytes / (1024.0 * 1024.0)
        )
    }

    suspend fun installJreFromZip(context: Context, zipUri: Uri): Result<JreInstallationStatus> =
        withContext(Dispatchers.IO) {
            runCatching {
                val jreDir = getJreRoot(context)
                if (jreDir.exists()) jreDir.deleteRecursively()
                jreDir.mkdirs()

                context.contentResolver.openInputStream(zipUri)?.use { raw ->
                    ZipInputStream(raw).use { zis ->
                        var entry = zis.nextEntry
                        while (entry != null) {
                            val outFile = File(jreDir, entry.name)
                            val canonicalRoot = jreDir.canonicalPath
                            val canonicalOut = outFile.canonicalPath
                            if (!canonicalOut.startsWith("$canonicalRoot${File.separator}") && canonicalOut != canonicalRoot) {
                                error("Unsicherer ZIP-Eintrag: ${entry.name}")
                            }
                            if (entry.isDirectory) {
                                outFile.mkdirs()
                            } else {
                                outFile.parentFile?.mkdirs()
                                FileOutputStream(outFile).use { fos -> zis.copyTo(fos) }
                                if (entry.name.endsWith("/java") || entry.name == "bin/java" || entry.name.endsWith(".so")) {
                                    outFile.setExecutable(true, false)
                                }
                            }
                            entry = zis.nextEntry
                        }
                    }
                } ?: error("Konnte JRE-Archiv nicht öffnen.")

                inspectJreStatus(context)
            }
        }
}

class ProcessJvmSession(
    private val jreRoot: File,
    private val runnerApiJar: File,
    private val appJar: File,
    private val mainClass: String,
    private val sandboxDir: File,
    private val onUiTree: (UiNodeModel) -> Unit,
    private val onToast: (String) -> Unit,
    private val onDialog: (JarDialogState) -> Unit,
    private val onCanvasFrame: (String, List<CanvasDrawCommand>) -> Unit,
    private val onLog: (LogLevel, String) -> Unit
) {
    private var process: Process? = null
    private var stdinWriter: BufferedWriter? = null
    private var readerJob: Job? = null

    fun start(scope: CoroutineScope) {
        val javaBin = File(jreRoot, "bin/java")
        if (!javaBin.exists()) {
            onLog(LogLevel.ERROR, "ARM64 bin/java nicht gefunden unter: ${javaBin.absolutePath}")
            return
        }
        javaBin.setExecutable(true)

        val pb = ProcessBuilder(
            javaBin.absolutePath,
            "-Xmx256m",
            "-Duser.home=${sandboxDir.absolutePath}",
            "-Djava.io.tmpdir=${File(sandboxDir, "tmp").absolutePath}",
            "-cp",
            "${runnerApiJar.absolutePath}:${appJar.absolutePath}",
            "de.jarrunner.api.internal.ChildProcessHost",
            appJar.absolutePath,
            mainClass,
            sandboxDir.absolutePath
        )
        pb.directory(sandboxDir)
        pb.environment()["LD_LIBRARY_PATH"] = "${jreRoot.absolutePath}/lib:${jreRoot.absolutePath}/lib/server"
        pb.redirectErrorStream(false)

        val proc = pb.start()
        process = proc
        stdinWriter = BufferedWriter(OutputStreamWriter(proc.outputStream))
        onLog(LogLevel.INFO, "OpenJDK-Kindprozess gestartet (PID/Sandbox: ${sandboxDir.name})")

        readerJob = scope.launch(Dispatchers.IO) {
            launch {
                BufferedReader(InputStreamReader(proc.errorStream)).useLines { lines ->
                    lines.forEach { err -> onLog(LogLevel.ERROR, err) }
                }
            }
            BufferedReader(InputStreamReader(proc.inputStream)).use { br ->
                while (isActive) {
                    val line = br.readLine() ?: break
                    handleIpcLine(line)
                }
            }
        }
    }

    fun sendClick(nodeId: String) = sendLine("CLICK|$nodeId")
    fun sendTextChange(nodeId: String, text: String) = sendLine("TEXT|$nodeId|${escape(text)}")
    fun sendItemClick(nodeId: String, index: Int, item: String) = sendLine("ITEM|$nodeId|$index|${escape(item)}")
    fun requestCanvasFrame(nodeId: String, width: Int, height: Int, tick: Long) =
        sendLine("FRAME|$nodeId|$width|$height|$tick")

    fun stop() {
        sendLine("EXIT")
        readerJob?.cancel()
        runCatching { stdinWriter?.close() }
        process?.destroy()
        process = null
    }

    private fun sendLine(line: String) {
        runCatching {
            stdinWriter?.apply {
                write(line)
                newLine()
                flush()
            }
        }
    }

    private fun handleIpcLine(line: String) {
        when {
            line.startsWith("UI|") -> {
                val json = JSONObject(line.substring(3))
                onUiTree(parseUiJson(json))
            }
            line.startsWith("TOAST|") -> onToast(unescape(line.substring(6)))
            line.startsWith("DIALOG|") -> {
                val parts = line.split("|", limit = 4)
                if (parts.size == 4) {
                    onDialog(JarDialogState(unescape(parts[1]), unescape(parts[2]), unescape(parts[3])))
                }
            }
            line.startsWith("LOG|") -> onLog(LogLevel.STDOUT, unescape(line.substring(4)))
            line.startsWith("CANVAS|") -> {
                val parts = line.split("|", limit = 3)
                if (parts.size == 3) {
                    onCanvasFrame(parts[1], parseCanvasCommands(parts[2]))
                }
            }
            else -> onLog(LogLevel.STDOUT, line)
        }
    }

    private fun parseUiJson(json: JSONObject): UiNodeModel {
        val id = json.optString("id")
        return when (json.optString("type")) {
            "column" -> UiNodeModel.ColumnNode(
                id = id,
                spacingDp = json.optInt("spacing", 12),
                children = parseChildren(json.optJSONArray("children"))
            )
            "row" -> UiNodeModel.RowNode(
                id = id,
                spacingDp = json.optInt("spacing", 8),
                children = parseChildren(json.optJSONArray("children"))
            )
            "text" -> UiNodeModel.TextNode(
                id = id,
                text = json.optString("text"),
                style = json.optString("style", "body"),
                colorHex = json.optString("color", "")
            )
            "button" -> UiNodeModel.ButtonNode(
                id = id,
                label = json.optString("label"),
                variant = json.optString("variant", "primary")
            )
            "textfield" -> UiNodeModel.TextFieldNode(
                id = id,
                value = json.optString("value"),
                hint = json.optString("hint")
            )
            "list" -> {
                val arr = json.optJSONArray("items") ?: JSONArray()
                val items = (0 until arr.length()).map { arr.getString(it) }
                UiNodeModel.ListNode(id = id, items = items)
            }
            "canvas" -> UiNodeModel.CanvasNode(
                id = id,
                heightDp = json.optInt("heightDp", 240),
                backgroundHex = json.optString("bg", "#090F1D")
            )
            else -> UiNodeModel.TextNode(id = id, text = "Unbekannter UI-Knoten")
        }
    }

    private fun parseChildren(arr: JSONArray?): List<UiNodeModel> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).map { parseUiJson(arr.getJSONObject(it)) }
    }

    private fun parseCanvasCommands(raw: String): List<CanvasDrawCommand> {
        if (raw.isBlank()) return emptyList()
        return raw.split("||").mapNotNull { cmd ->
            val p = cmd.split(";")
            when (p.firstOrNull()) {
                "CIRCLE" -> if (p.size >= 5) CanvasDrawCommand.Circle(p[1].toFloat(), p[2].toFloat(), p[3].toFloat(), p[4]) else null
                "RECT" -> if (p.size >= 6) CanvasDrawCommand.Rect(p[1].toFloat(), p[2].toFloat(), p[3].toFloat(), p[4].toFloat(), p[5]) else null
                "LINE" -> if (p.size >= 7) CanvasDrawCommand.Line(p[1].toFloat(), p[2].toFloat(), p[3].toFloat(), p[4].toFloat(), p[5].toFloat(), p[6]) else null
                "TEXT" -> if (p.size >= 6) CanvasDrawCommand.Text(p[5], p[1].toFloat(), p[2].toFloat(), p[3].toFloat(), p[4]) else null
                else -> null
            }
        }
    }

    private fun escape(s: String): String = s.replace("\\", "\\\\").replace("\n", "\\n").replace("|", "\\p")
    private fun unescape(s: String): String = s.replace("\\p", "|").replace("\\n", "\n").replace("\\\\", "\\")
}
