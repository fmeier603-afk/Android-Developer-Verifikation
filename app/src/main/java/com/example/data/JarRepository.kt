package com.example.data

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.example.model.InstalledJarApp
import com.example.model.JarManifest
import com.example.model.JarSourceType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

class JarRepository(private val context: Context) {

    private val jarAppsDir: File by lazy {
        File(context.filesDir, "jar_apps").apply { mkdirs() }
    }

    private val runtimeDir: File by lazy {
        File(context.filesDir, "runtime").apply { mkdirs() }
    }

    private val metadataFile: File by lazy {
        File(jarAppsDir, ".sources.json")
    }

    private val okHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    suspend fun ensureBundledAssetsAndScan(): List<InstalledJarApp> = withContext(Dispatchers.IO) {
        unpackAssetIfMissing("samples/hello-counter.jar", File(jarAppsDir, "hello-counter.jar"), JarSourceType.BUNDLED, "assets/samples/hello-counter.jar")
        unpackAssetIfMissing("samples/orbit-canvas.jar", File(jarAppsDir, "orbit-canvas.jar"), JarSourceType.BUNDLED, "assets/samples/orbit-canvas.jar")
        unpackAssetIfMissing("runtime/runner-api.jar", File(runtimeDir, "runner-api.jar"), null, null)
        scanInstalledJars()
    }

    private fun unpackAssetIfMissing(
        assetPath: String,
        target: File,
        sourceType: JarSourceType?,
        sourceLocation: String?
    ) {
        if (!target.exists() || target.length() == 0L) {
            context.assets.open(assetPath).use { input ->
                FileOutputStream(target).use { output ->
                    input.copyTo(output)
                }
            }
            if (sourceType != null && sourceLocation != null) {
                saveSourceMetadata(target.name, sourceType, sourceLocation)
            }
        }
    }

    suspend fun scanInstalledJars(): List<InstalledJarApp> = withContext(Dispatchers.IO) {
        val sources = loadSourceMetadata()
        val files = jarAppsDir.listFiles { f -> f.isFile && f.extension.equals("jar", ignoreCase = true) }
            ?.sortedBy { it.name.lowercase() }
            ?: emptyList()

        files.mapNotNull { file ->
            val meta = sources[file.name]
            runCatching {
                parseJarFile(
                    file = file,
                    sourceType = meta?.first ?: JarSourceType.LOCAL_FILE,
                    sourceLocation = meta?.second ?: file.absolutePath
                )
            }.getOrNull()
        }
    }

    suspend fun importJarFromUri(uri: Uri, sourceType: JarSourceType = JarSourceType.LOCAL_FILE): Result<InstalledJarApp> =
        withContext(Dispatchers.IO) {
            runCatching {
                val displayName = resolveDisplayName(uri) ?: "imported_${System.currentTimeMillis()}.jar"
                val safeName = sanitizeJarFileName(displayName)
                val tempFile = File(context.cacheDir, "verify_$safeName")

                context.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(tempFile).use { out -> input.copyTo(out) }
                } ?: error("Konnte URI nicht öffnen: $uri")

                validateAndInstallJar(tempFile, safeName, sourceType, uri.toString())
            }
        }

    suspend fun scanSafFolderAndImport(treeUri: Uri): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            val docId = DocumentsContract.getTreeDocumentId(treeUri)
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId)
            var importedCount = 0
            val projection = arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE
            )
            context.contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                while (cursor.moveToNext()) {
                    val childId = cursor.getString(idCol)
                    val name = cursor.getString(nameCol) ?: ""
                    if (name.endsWith(".jar", ignoreCase = true)) {
                        val fileUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, childId)
                        val res = importJarFromUri(fileUri, JarSourceType.SAF_FOLDER)
                        if (res.isSuccess) importedCount++
                    }
                }
            }
            importedCount
        }
    }

    suspend fun downloadJarFromUrl(
        url: String,
        onProgress: (Float) -> Unit
    ): Result<InstalledJarApp> = withContext(Dispatchers.IO) {
        runCatching {
            val trimmed = url.trim()
            require(trimmed.startsWith("https://", ignoreCase = true)) {
                "Nur sichere HTTPS-URLs werden unterstützt."
            }

            val request = Request.Builder().url(trimmed).build()
            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    error("HTTP-Fehler ${response.code}: ${response.message}")
                }
                val body = response.body ?: error("Leere Antwort vom Server")
                val contentLength = body.contentLength()
                val rawName = trimmed.substringAfterLast('/').substringBefore('?')
                    .ifBlank { "remote_app.jar" }
                val safeName = sanitizeJarFileName(rawName)
                val tempFile = File(context.cacheDir, "dl_$safeName")

                body.byteStream().use { input ->
                    FileOutputStream(tempFile).use { output ->
                        val buffer = ByteArray(8192)
                        var bytesReadTotal = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read == -1) break
                            output.write(buffer, 0, read)
                            bytesReadTotal += read
                            if (contentLength > 0) {
                                onProgress((bytesReadTotal.toFloat() / contentLength).coerceIn(0f, 1f))
                            }
                        }
                    }
                }
                onProgress(1f)
                validateAndInstallJar(tempFile, safeName, JarSourceType.HTTPS_URL, trimmed)
            }
        }
    }

    fun deleteInstalledJar(app: InstalledJarApp): Boolean {
        val file = File(app.jarFilePath)
        val sandboxDir = File(File(context.filesDir, "sandbox"), sanitizeId(app.manifest.id))
        if (sandboxDir.exists()) {
            sandboxDir.deleteRecursively()
        }
        removeSourceMetadata(file.name)
        return file.delete()
    }

    fun getRunnerApiJarFile(): File = File(runtimeDir, "runner-api.jar")

    private fun validateAndInstallJar(
        tempFile: File,
        targetName: String,
        sourceType: JarSourceType,
        sourceLocation: String
    ): InstalledJarApp {
        var hasClassOrManifest = false
        ZipInputStream(tempFile.inputStream()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (entry.name.endsWith(".class") || entry.name == "manifest.json" || entry.name == "META-INF/MANIFEST.MF") {
                    hasClassOrManifest = true
                    break
                }
                entry = zis.nextEntry
            }
        }
        if (!hasClassOrManifest) {
            tempFile.delete()
            error("Ungültiges JAR-Archiv: Keine .class-Dateien oder Manifest gefunden.")
        }

        val finalFile = File(jarAppsDir, targetName)
        tempFile.copyTo(finalFile, overwrite = true)
        tempFile.delete()
        saveSourceMetadata(finalFile.name, sourceType, sourceLocation)
        return parseJarFile(finalFile, sourceType, sourceLocation)
    }

    private fun parseJarFile(
        file: File,
        sourceType: JarSourceType,
        sourceLocation: String
    ): InstalledJarApp {
        ZipFile(file).use { zip ->
            val classNames = mutableListOf<String>()
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val e = entries.nextElement()
                if (!e.isDirectory && e.name.endsWith(".class")) {
                    classNames.add(e.name.removeSuffix(".class").replace('/', '.'))
                }
            }

            val manifestJsonEntry = zip.getEntry("manifest.json")
            val mfEntry = zip.getEntry("META-INF/MANIFEST.MF")
            val mainClassFromMf = mfEntry?.let { entry ->
                zip.getInputStream(entry).bufferedReader().useLines { lines ->
                    lines.firstOrNull { it.startsWith("Main-Class:") }
                        ?.substringAfter("Main-Class:")?.trim()
                }
            }

            val manifest: JarManifest
            val hasManifestJson: Boolean
            if (manifestJsonEntry != null) {
                hasManifestJson = true
                val raw = zip.getInputStream(manifestJsonEntry).bufferedReader().use { it.readText() }
                val json = JSONObject(raw)
                val permArray = json.optJSONArray("permissions")
                val perms = buildList {
                    if (permArray != null) {
                        for (i in 0 until permArray.length()) {
                            add(permArray.getString(i))
                        }
                    }
                    if (isEmpty()) add("UI_BRIDGE")
                }
                manifest = JarManifest(
                    id = json.optString("id", file.nameWithoutExtension),
                    name = json.optString("name", file.nameWithoutExtension),
                    version = json.optString("version", "1.0.0"),
                    author = json.optString("author", "Unbekannt"),
                    description = json.optString("description", "Standard Java-JAR-Anwendung"),
                    mainClass = json.optString("mainClass", mainClassFromMf ?: classNames.firstOrNull().orEmpty()),
                    iconPath = json.optString("icon", "icon.png"),
                    accentColor = json.optString("accentColor", "#F59E0B"),
                    permissions = perms
                )
            } else {
                hasManifestJson = false
                manifest = JarManifest(
                    id = file.nameWithoutExtension.lowercase().replace("[^a-z0-9.]".toRegex(), "_"),
                    name = file.nameWithoutExtension,
                    version = "1.0",
                    author = "Standard-JAR",
                    description = "Ohne manifest.json (erkannt über META-INF/MANIFEST.MF)",
                    mainClass = mainClassFromMf ?: classNames.firstOrNull { !it.contains('$') }.orEmpty(),
                    iconPath = "icon.png",
                    accentColor = "#22D3EE",
                    permissions = listOf("UI_BRIDGE")
                )
            }

            val iconBytes = zip.getEntry(manifest.iconPath)?.let { iconEntry ->
                zip.getInputStream(iconEntry).use { input ->
                    val baos = ByteArrayOutputStream()
                    input.copyTo(baos)
                    baos.toByteArray()
                }
            }

            return InstalledJarApp(
                manifest = manifest,
                jarFilePath = file.absolutePath,
                fileName = file.name,
                sourceType = sourceType,
                sourceLocation = sourceLocation,
                fileSizeBytes = file.length(),
                classCount = classNames.size,
                classNames = classNames.sorted(),
                iconBytes = iconBytes,
                hasManifestJson = hasManifestJson
            )
        }
    }

    private fun resolveDisplayName(uri: Uri): String? {
        context.contentResolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    return cursor.getString(0)
                }
            }
        return uri.lastPathSegment?.substringAfterLast('/')
    }

    private fun sanitizeJarFileName(name: String): String {
        val clean = name.replace("[^a-zA-Z0-9._-]".toRegex(), "_")
        return if (clean.endsWith(".jar", ignoreCase = true)) clean else "$clean.jar"
    }

    private fun sanitizeId(id: String): String = id.replace("[^a-zA-Z0-9._-]".toRegex(), "_")

    private fun loadSourceMetadata(): MutableMap<String, Pair<JarSourceType, String>> {
        val map = mutableMapOf<String, Pair<JarSourceType, String>>()
        if (!metadataFile.exists()) return map
        runCatching {
            val root = JSONObject(metadataFile.readText())
            val keys = root.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val obj = root.getJSONObject(key)
                val type = runCatching { JarSourceType.valueOf(obj.getString("type")) }
                    .getOrDefault(JarSourceType.LOCAL_FILE)
                map[key] = type to obj.optString("location", "")
            }
        }
        return map
    }

    private fun saveSourceMetadata(fileName: String, type: JarSourceType, location: String) {
        val root = if (metadataFile.exists()) {
            runCatching { JSONObject(metadataFile.readText()) }.getOrElse { JSONObject() }
        } else {
            JSONObject()
        }
        root.put(fileName, JSONObject().apply {
            put("type", type.name)
            put("location", location)
        })
        metadataFile.writeText(root.toString(2))
    }

    private fun removeSourceMetadata(fileName: String) {
        if (!metadataFile.exists()) return
        runCatching {
            val root = JSONObject(metadataFile.readText())
            root.remove(fileName)
            metadataFile.writeText(root.toString(2))
        }
    }
}
