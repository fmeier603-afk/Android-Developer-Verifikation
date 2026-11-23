package com.example

import com.example.model.CanvasDrawCommand
import com.example.model.JarDialogState
import com.example.model.UiNodeModel
import com.example.runtime.MiniJvmEngine
import com.example.runtime.SandboxPolicy
import com.example.runtime.SandboxSecurityException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class MiniJvmEngineTest {

    @Test
    fun helloCounterJarExecutesButtonCounterAndDialog() {
        val jar = File("src/main/assets/samples/hello-counter.jar").let {
            if (it.exists()) it else File("app/src/main/assets/samples/hello-counter.jar")
        }
        assertTrue("hello-counter.jar muss existieren", jar.exists())

        val sandbox = Files.createTempDirectory("test_sandbox_hello").toFile()
        var latestTree: UiNodeModel? = null
        var lastDialog: JarDialogState? = null
        val toasts = mutableListOf<String>()

        val engine = MiniJvmEngine(
            jarFile = jar,
            grantedPermissions = listOf("UI_BRIDGE", "SANDBOX_FS"),
            sandboxDir = sandbox,
            onUiTree = { latestTree = it },
            onToast = { toasts.add(it) },
            onDialog = { lastDialog = it },
            onLog = { _, _ -> }
        )

        engine.start("de.jarrunner.samples.HelloCounterApp")
        val initialRoot = latestTree as UiNodeModel.ColumnNode
        val initialRow = initialRoot.children.filterIsInstance<UiNodeModel.RowNode>().first()
        val clickBtn = initialRow.children.filterIsInstance<UiNodeModel.ButtonNode>().first()
        assertEquals("Klick +1 (0)", clickBtn.label)

        // Click 5 times -> triggers milestone Toast and increments counter to 5
        repeat(5) {
            val root = latestTree as UiNodeModel.ColumnNode
            val row = root.children.filterIsInstance<UiNodeModel.RowNode>().first()
            val btn = row.children.filterIsInstance<UiNodeModel.ButtonNode>().first()
            engine.dispatchClick(btn.id)
        }

        val after5Root = latestTree as UiNodeModel.ColumnNode
        val after5Row = after5Root.children.filterIsInstance<UiNodeModel.RowNode>().first()
        val after5Btn = after5Row.children.filterIsInstance<UiNodeModel.ButtonNode>().first()
        assertEquals("Klick +1 (5)", after5Btn.label)
        assertTrue(toasts.any { it.contains("5 Klicks") })

        // Click Reset button -> triggers Dialog and resets counter to 0
        val resetBtn = after5Row.children.filterIsInstance<UiNodeModel.ButtonNode>()[1]
        engine.dispatchClick(resetBtn.id)
        assertNotNull(lastDialog)
        assertEquals("Zähler zurückgesetzt", lastDialog?.title)

        sandbox.deleteRecursively()
    }

    @Test
    fun orbitCanvasJarRendersCanvasFrameCommands() {
        val jar = File("src/main/assets/samples/orbit-canvas.jar").let {
            if (it.exists()) it else File("app/src/main/assets/samples/orbit-canvas.jar")
        }
        assertTrue("orbit-canvas.jar muss existieren", jar.exists())

        val sandbox = Files.createTempDirectory("test_sandbox_orbit").toFile()
        var latestTree: UiNodeModel? = null

        val engine = MiniJvmEngine(
            jarFile = jar,
            grantedPermissions = listOf("UI_BRIDGE", "CANVAS_2D"),
            sandboxDir = sandbox,
            onUiTree = { latestTree = it },
            onToast = {},
            onDialog = {},
            onLog = { _, _ -> }
        )

        engine.start("de.jarrunner.samples.OrbitCanvasApp")
        val root = latestTree as UiNodeModel.ColumnNode
        val canvasNode = root.children.filterIsInstance<UiNodeModel.CanvasNode>().first()

        val cmds = engine.renderCanvasFrame(canvasNode.id, 600, 400, 10L)
        assertTrue("Canvas sollte Zeichenbefehle liefern", cmds.isNotEmpty())
        assertTrue(cmds.any { it is CanvasDrawCommand.Circle })
        assertTrue(cmds.any { it is CanvasDrawCommand.Line })
        assertTrue(cmds.any { it is CanvasDrawCommand.Text && it.text.contains("FRAME 10") })

        sandbox.deleteRecursively()
    }

    @Test(expected = SandboxSecurityException::class)
    fun sandboxBlocksRuntimeClassAccess() {
        SandboxPolicy.validateClassAccess("java/lang/Runtime", listOf("UI_BRIDGE"))
    }

    @Test(expected = SandboxSecurityException::class)
    fun sandboxBlocksPathTraversal() {
        val sandbox = Files.createTempDirectory("test_sandbox_path").toFile()
        try {
            SandboxPolicy.resolveSafePath(sandbox, "../../etc/passwd")
        } finally {
            sandbox.deleteRecursively()
        }
    }
}
