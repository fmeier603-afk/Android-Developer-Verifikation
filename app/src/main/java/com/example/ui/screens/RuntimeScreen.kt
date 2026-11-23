package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.model.JreInstallationStatus
import com.example.model.RuntimeMode
import com.example.ui.theme.EmeraldSafe
import java.util.Locale

@Composable
fun RuntimeScreen(
    selectedMode: RuntimeMode,
    jreStatus: JreInstallationStatus,
    onSelectMode: (RuntimeMode) -> Unit,
    onImportJreZip: () -> Unit,
    modifier: Modifier = Modifier
) {
    var selectedDocSection by remember { mutableStateOf(0) }

    Box(
        contentAlignment = Alignment.TopCenter,
        modifier = modifier.fillMaxSize()
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier
                .widthIn(max = 680.dp)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            Text(
                text = "Laufzeit & Architektur",
                style = MaterialTheme.typography.headlineMedium
            )

            // Active Mode Selector
            Card(
                shape = RoundedCornerShape(18.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Filled.Memory,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Ausführungsmodus", style = MaterialTheme.typography.titleLarge)
                    }
                    Spacer(modifier = Modifier.height(10.dp))

                    RuntimeMode.entries.forEach { mode ->
                        val isSelected = selectedMode == mode
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelectMode(mode) }
                                .padding(vertical = 8.dp)
                                .testTag("runtime_mode_${mode.name.lowercase()}")
                        ) {
                            RadioButton(
                                selected = isSelected,
                                onClick = { onSelectMode(mode) }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(mode.title, style = MaterialTheme.typography.titleMedium)
                                Text(
                                    text = mode.subtitle,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }

            // ARM64 OpenJDK Runtime Status & Importer
            Card(
                shape = RoundedCornerShape(18.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("ARM64 OpenJDK 21 JRE", style = MaterialTheme.typography.titleLarge)
                        Surface(
                            color = (if (jreStatus.isInstalled) EmeraldSafe else MaterialTheme.colorScheme.primary)
                                .copy(alpha = 0.16f),
                            shape = RoundedCornerShape(50)
                        ) {
                            Text(
                                text = if (jreStatus.isInstalled) "Installiert" else "Optional",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (jreStatus.isInstalled) EmeraldSafe else MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = if (jreStatus.isInstalled) {
                            "JRE-Größe: ${String.format(Locale.GERMANY, "%.1f", jreStatus.totalSizeMb)} MB • bin/java: ${jreStatus.hasJavaBinary} • libjvm.so: ${jreStatus.hasLibJvm}"
                        } else {
                            "Aktuell läuft die eingebaute Kotlin-JVM-Sandbox. Optional kannst du eine per jlink verkleinerte ARM64-JRE (.zip) in filesDir/runtime/jre importieren."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    FilledTonalButton(
                        onClick = onImportJreZip,
                        modifier = Modifier
                            .fillMaxWidth()
                            .minimumInteractiveComponentSize()
                            .testTag("import_jre_zip_button")
                    ) {
                        Icon(Icons.Filled.Archive, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("ARM64-JRE (.zip) importieren")
                    }
                }
            }

            // Sandbox Security Matrix
            Card(
                shape = RoundedCornerShape(18.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Filled.Shield,
                            contentDescription = null,
                            tint = EmeraldSafe
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Sandbox & Sicherheit", style = MaterialTheme.typography.titleLarge)
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    val rules = listOf(
                        "ClassLoader-Filter blockiert Runtime, ProcessBuilder, Unsafe & Reflection",
                        "Dediziertes Arbeitsverzeichnis pro App (filesDir/sandbox/<appId>)",
                        "Kanonische Path-Traversal-Prüfung bei Dateizugriffen",
                        "Netzwerkzugriff (java.net.*) nur mit 'NET' im manifest.json",
                        "Instruktions-Budget schützt vor Endlosschleifen"
                    )
                    rules.forEach { rule ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(vertical = 4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.CheckCircle,
                                contentDescription = null,
                                tint = EmeraldSafe,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = rule,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            // Architecture & SDK Reference Snippets
            Card(
                shape = RoundedCornerShape(18.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Filled.Code,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.secondary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("JNI / jlink / SDK Referenz", style = MaterialTheme.typography.titleLarge)
                    }
                    Spacer(modifier = Modifier.height(10.dp))

                    val tabs = listOf("jlink ARM64", "JNI C++", "Sample JAR")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        tabs.forEachIndexed { idx, label ->
                            Surface(
                                color = if (selectedDocSection == idx) {
                                    MaterialTheme.colorScheme.primaryContainer
                                } else {
                                    MaterialTheme.colorScheme.surfaceVariant
                                },
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.clickable { selectedDocSection = idx }
                            ) {
                                Text(
                                    text = label,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = if (selectedDocSection == idx) {
                                        MaterialTheme.colorScheme.onPrimaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))
                    val codeText = when (selectedDocSection) {
                        0 -> """
                            jlink \
                              --module-path /arm64-jdk-21/jmods \
                              --add-modules java.base,java.logging,java.net.http \
                              --strip-debug --no-man-pages \
                              --compress=zip-6 \
                              --output ./jre-arm64-minimal
                        """.trimIndent()
                        1 -> """
                            void *h = dlopen(libJvmPath, RTLD_NOW | RTLD_GLOBAL);
                            auto createVm = (CreateJavaVM_t) dlsym(h, "JNI_CreateJavaVM");
                            JavaVMInitArgs args { JNI_VERSION_21, nOpts, opts, JNI_TRUE };
                            createVm(&jvm, (void**)&jvmEnv, &args);
                        """.trimIndent()
                        else -> """
                            public class HelloApp implements RunnerApp {
                              public void onStart(UiContext ctx) {
                                UiColumn root = new UiColumn();
                                root.add(new UiButton("Klick +1")
                                  .setOnClick(() -> ctx.showToast("Hallo!")));
                                ctx.setContent(root);
                              }
                            }
                        """.trimIndent()
                    }
                    Surface(
                        color = Color(0xFF070B14),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = codeText,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                }
            }
        }
    }
}
