# OpenJDK ARM64 Setup & Architektur-Leitfaden für JarRunner

## 1. Projektstruktur

```text
JarRunner/
├── app/                                  # Android-Host-App (Kotlin + Jetpack Compose)
│   └── src/main/
│       ├── assets/
│       │   ├── runtime/runner-api.jar    # Kompilierte UI-Bridge-API
│       │   └── samples/
│       │       ├── hello-counter.jar     # Beispiel-App: Button & Klick-Zähler
│       │       └── orbit-canvas.jar      # Beispiel-App: 2D-UiCanvas-Bridge
│       └── java/com/example/
│           ├── data/JarRepository.kt     # JAR-Scanner, SAF-Import, HTTPS-Installer
│           ├── model/JarAppModels.kt     # Manifest-, UI-Bridge- & Runtime-Modelle
│           ├── runtime/
│           │   ├── JniJvmBridge.kt       # JNI-Vertrag, JRE-Installer & Child-Process IPC
│           │   ├── JvmClassFileParser.kt # .class Bytecode Parser (0xCAFEBABE)
│           │   ├── MiniJvmEngine.kt      # Eingebauter Bytecode-Interpreter (Sofortstart)
│           │   └── SandboxPolicy.kt      # ClassLoader-Filter & Verzeichnis-Isolation
│           └── ui/
│               ├── JarRunnerViewModel.kt
│               └── screens/              # Launcher, Installer, Runtime, App-Runner
├── runner-api/                           # Schlankes Java-UI-Bridge-SDK (de.jarrunner.api.*)
├── samples/                              # Beispiel-Java-Apps (Quellcode + manifest.json)
├── native/                               # C++ JNI-Bridge (dlopen + JNI_CreateJavaVM)
│   ├── CMakeLists.txt
│   └── jvm_bridge.cpp
└── scripts/
    └── build_arm64_jre.sh                # jlink-Skript zur JRE-Verkleinerung
```

---

## 2. ARM64-OpenJDK vorbereiten & integrieren

### Schritt 1: Android-ARM64-JDK beziehen
Lade ein für Android (`bionic`/`aarch64`) kompiliertes OpenJDK 17 oder 21 herunter (z. B. aus dem PojavLauncher `android_openjdk_build`-Release oder einem Termux/Adoptium-Android-Build). Entpacke es auf dem Entwicklungsrechner (z. B. nach `~/android-jdk21-arm64`).

### Schritt 2: Runtime mit `jlink` verkleinern (~28–38 MB statt >200 MB)
Nutze das mitgelieferte Skript:

```bash
chmod +x scripts/build_arm64_jre.sh
./scripts/build_arm64_jre.sh ~/android-jdk21-arm64/jmods ./jre-arm64-minimal
```

### Schritt 3: Integration in Android
Es gibt zwei Wege:
- **Option A (Empfohlen – On-Demand in `filesDir/runtime/jre`):**
  ```bash
  (cd ./jre-arm64-minimal && zip -r ../jre-arm64-minimal.zip .)
  adb push jre-arm64-minimal.zip /sdcard/Download/
  ```
  In JarRunner unter **Laufzeit -> ARM64-JRE (.zip) importieren** auswählen. JarRunner entpackt das Archiv nach `filesDir/runtime/jre` und setzt automatisch die Ausführungsrechte (`chmod +x`) auf `bin/java` und alle `.so`-Bibliotheken.
- **Option B (APK-Bundle über `jniLibs` / `assets`):**
  Lege `jre-arm64-minimal.zip` in `app/src/main/assets/runtime/` oder kopiere die `.so`-Dateien in `app/src/main/jniLibs/arm64-v8a/`. Für die native `JNI_CreateJavaVM`-Bridge aktiviere `externalNativeBuild { cmake { path = file("../native/CMakeLists.txt") } }` in `app/build.gradle.kts` (erfordert installierten Android NDK + CMake).

---

## 3. In-Process (`JNI_CreateJavaVM`) vs. Isolierter Kindprozess (`bin/java`)

| Kriterium | Kindprozess (`ProcessBuilder` + IPC) | In-Process (`JNI_CreateJavaVM`) |
|---|---|---|
| **Stabilität** | Hoch: Ein Crash (`SIGSEGV` / `OutOfMemoryError`) der JAR beendet nur den Kindprozess, nicht die Host-App. | Geringer: Ein nativer Crash oder `System.exit()` reißt den gesamten Android-Prozess mit. |
| **Speicherverwaltung** | Eigener JVM-Heap (`-Xmx256m`), wird vom OS beim Beenden sofort vollständig freigegeben. | Teilt sich den Prozess-Adressraum mit ART/Dalvik und Jetpack Compose; `DestroyJavaVM()` gibt eine HotSpot-Instanz pro Prozess nicht sauber für einen Neustart frei. |
| **Multitasking / Neustart** | Beliebig oft sauber stopp- und neustartbar. | HotSpot erlaubt offiziell nur **eine** `JavaVM` pro Prozess-Lebenszeit. |
| **Latenz (UI-Bridge)** | Sehr niedrig über `stdin`/`stdout`-Pipe oder `LocalSocket` (~0,2–1 ms). | Direkt im Speicher via JNI-Callbacks. |

**Empfehlung:** Für Produktiv-Launcher ist der **isolierte Kindprozess** (implementiert in `ProcessJvmSession` + `ChildProcessHost`) klar überlegen; `JNI_CreateJavaVM` (`native/jvm_bridge.cpp`) eignet sich vor allem für feste Single-VM-Einbettungen.
