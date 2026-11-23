package com.example.runtime

import android.content.Context
import java.io.File

class SandboxSecurityException(message: String) : SecurityException(message)

object SandboxPolicy {
    private val blockedClassPrefixes = listOf(
        "java/lang/Runtime",
        "java/lang/ProcessBuilder",
        "java/lang/ProcessImpl",
        "java/lang/reflect/",
        "java/lang/ClassLoader",
        "java/nio/file/",
        "sun/misc/Unsafe",
        "jdk/internal/"
    )

    private val networkClassPrefixes = listOf(
        "java/net/",
        "javax/net/",
        "java/net/http/"
    )

    fun validateClassAccess(internalClassName: String, grantedPermissions: List<String>) {
        if (blockedClassPrefixes.any { internalClassName.startsWith(it) }) {
            throw SandboxSecurityException("Sandbox-Filter blockiert Systemklasse: $internalClassName")
        }
        if (networkClassPrefixes.any { internalClassName.startsWith(it) } &&
            !grantedPermissions.contains("NET")
        ) {
            throw SandboxSecurityException("Netzwerkzugriff ohne 'NET'-Berechtigung blockiert: $internalClassName")
        }
    }

    fun validateMethodCall(owner: String, methodName: String) {
        if (owner == "java/lang/System" && (methodName == "exit" || methodName == "load" || methodName == "loadLibrary")) {
            throw SandboxSecurityException("Sandbox-Filter blockiert System.$methodName()")
        }
    }

    fun getOrCreateAppSandboxDir(context: Context, appId: String): File {
        val safeId = appId.replace("[^a-zA-Z0-9._-]".toRegex(), "_")
        val root = File(File(context.filesDir, "sandbox"), safeId)
        val tmp = File(root, "tmp")
        root.mkdirs()
        tmp.mkdirs()
        return root
    }

    fun resolveSafePath(sandboxRoot: File, requestedPath: String): File {
        val candidate = File(sandboxRoot, requestedPath)
        val rootCanonical = sandboxRoot.canonicalPath
        val targetCanonical = candidate.canonicalPath
        if (targetCanonical != rootCanonical && !targetCanonical.startsWith("$rootCanonical${File.separator}")) {
            throw SandboxSecurityException("Path-Traversal blockiert: $requestedPath")
        }
        return candidate
    }
}
