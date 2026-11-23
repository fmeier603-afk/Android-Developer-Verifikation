package com.example

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.ui.JarRunnerViewModel
import com.example.ui.screens.InstallerScreen
import com.example.ui.screens.JarAppRunnerScreen
import com.example.ui.screens.LauncherScreen
import com.example.ui.screens.RuntimeScreen
import com.example.ui.theme.JarRunnerTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            JarRunnerTheme {
                JarRunnerAppRoot()
            }
        }
    }
}

@Composable
fun JarRunnerAppRoot(vm: JarRunnerViewModel = viewModel()) {
    val context = LocalContext.current
    val apps by vm.apps.collectAsStateWithLifecycle()
    val isScanning by vm.isScanning.collectAsStateWithLifecycle()
    val runtimeMode by vm.runtimeMode.collectAsStateWithLifecycle()
    val jreStatus by vm.jreStatus.collectAsStateWithLifecycle()
    val downloadState by vm.downloadState.collectAsStateWithLifecycle()
    val statusBanner by vm.statusBanner.collectAsStateWithLifecycle()

    val runningApp by vm.runningApp.collectAsStateWithLifecycle()
    val uiTree by vm.uiTree.collectAsStateWithLifecycle()
    val canvasCommands by vm.canvasCommands.collectAsStateWithLifecycle()
    val activeDialog by vm.activeDialog.collectAsStateWithLifecycle()
    val consoleLogs by vm.consoleLogs.collectAsStateWithLifecycle()
    val instructionCount by vm.instructionCount.collectAsStateWithLifecycle()
    val activeSandboxPath by vm.activeSandboxPath.collectAsStateWithLifecycle()

    val jarPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { vm.importLocalJar(it) }
    }
    val jreZipPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { vm.importJreZip(it) }
    }
    val safFolderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { treeUri ->
        treeUri?.let {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    it,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            vm.scanSafFolder(it)
        }
    }

    val activeApp = runningApp
    if (activeApp != null) {
        JarAppRunnerScreen(
            app = activeApp,
            runtimeMode = runtimeMode,
            uiTree = uiTree,
            canvasCommands = canvasCommands,
            activeDialog = activeDialog,
            consoleLogs = consoleLogs,
            instructionCount = instructionCount,
            sandboxPath = activeSandboxPath,
            toastEvents = vm.toastEvents,
            onButtonClick = vm::onBridgeButtonClick,
            onTextChange = vm::onBridgeTextChange,
            onItemClick = vm::onBridgeItemClick,
            onRequestCanvasFrame = vm::requestCanvasTick,
            onDismissDialog = vm::dismissDialog,
            onStopAndExit = vm::stopRunningApp
        )
        return
    }

    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(statusBanner) {
        statusBanner?.let { msg ->
            snackbarHostState.showSnackbar(msg)
            vm.clearStatusBanner()
        }
    }

    val tabLabels = listOf(
        stringResource(R.string.tab_launcher),
        stringResource(R.string.tab_installer),
        stringResource(R.string.tab_runtime)
    )
    val selectedIcons = listOf(Icons.Filled.Apps, Icons.Filled.Download, Icons.Filled.Memory)
    val unselectedIcons = listOf(Icons.Outlined.Apps, Icons.Outlined.Download, Icons.Outlined.Memory)

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val useRail = maxWidth >= 640.dp

        Scaffold(
            snackbarHost = { SnackbarHost(snackbarHostState) },
            bottomBar = {
                if (!useRail) {
                    NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                        tabLabels.forEachIndexed { idx, label ->
                            NavigationBarItem(
                                selected = selectedTab == idx,
                                onClick = { selectedTab = idx },
                                icon = {
                                    Icon(
                                        imageVector = if (selectedTab == idx) selectedIcons[idx] else unselectedIcons[idx],
                                        contentDescription = label
                                    )
                                },
                                label = { Text(label) },
                                modifier = Modifier.testTag("nav_tab_$idx")
                            )
                        }
                    }
                }
            }
        ) { innerPadding ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                if (useRail) {
                    NavigationRail(
                        containerColor = MaterialTheme.colorScheme.surface,
                        modifier = Modifier.fillMaxHeight()
                    ) {
                        tabLabels.forEachIndexed { idx, label ->
                            NavigationRailItem(
                                selected = selectedTab == idx,
                                onClick = { selectedTab = idx },
                                icon = {
                                    Icon(
                                        imageVector = if (selectedTab == idx) selectedIcons[idx] else unselectedIcons[idx],
                                        contentDescription = label
                                    )
                                },
                                label = { Text(label) },
                                modifier = Modifier.testTag("nav_tab_$idx")
                            )
                        }
                    }
                }

                Box(modifier = Modifier.weight(1f).fillMaxSize()) {
                    when (selectedTab) {
                        0 -> LauncherScreen(
                            apps = apps,
                            isScanning = isScanning,
                            runtimeMode = runtimeMode,
                            onRescan = vm::rescanJars,
                            onPickSafFolder = { safFolderPicker.launch(null) },
                            onLaunchApp = vm::launchJarApp,
                            onDeleteApp = vm::deleteJar
                        )
                        1 -> InstallerScreen(
                            downloadState = downloadState,
                            onDownloadUrl = vm::downloadJarFromUrl,
                            onPickLocalJar = {
                                jarPicker.launch(arrayOf("application/java-archive", "application/octet-stream", "*/*"))
                            },
                            onPickSafFolder = { safFolderPicker.launch(null) }
                        )
                        2 -> RuntimeScreen(
                            selectedMode = runtimeMode,
                            jreStatus = jreStatus,
                            onSelectMode = vm::setRuntimeMode,
                            onImportJreZip = {
                                jreZipPicker.launch(arrayOf("application/zip", "application/octet-stream", "*/*"))
                            }
                        )
                    }
                }
            }
        }
    }
}
