# PyStudio – Python Code Editor

A VS Code–style Python IDE for Android with a **real embedded CPython 3.11 interpreter**.
Kotlin · Jetpack Compose · Material 3 · Chaquopy.

**Free, completely.** No subscriptions, no premium tier, no ads, no tracking, no account.
Projects live in app-private storage. The only permission is `INTERNET` (pip, PyPI lookup, Git).

Contact: Telegram [@YogeshBhai729](https://t.me/YogeshBhai729) · GitHub [hgcheat094-max](https://github.com/hgcheat094-max)

---

## Build the APK

### Option A – GitHub Actions (automatic)
1. Create a GitHub repository and push this folder to `main`.
2. Open **Actions → Build PyStudio APK**. Every push builds `app-debug.apk` and `app-release.apk`
   (download from the run's **Artifacts**).
3. To publish a release: `git tag v1.0.0 && git push origin v1.0.0`. The workflow attaches the APK to a GitHub Release.

### Option B – Android Studio
1. Install **Android Studio Koala or newer**, JDK 17, and **Python 3.11** (must be on `PATH`; used only at build time to fetch wheels).
2. *File → Open* this folder and let Gradle sync (needs internet the first time).
3. Run on a device/emulator (▶), or *Build → Build APK(s)*.

### Option C – command line
```bash
# once, to create the Gradle wrapper (or just open the project in Android Studio)
gradle wrapper --gradle-version 8.7
./gradlew assembleDebug        # → app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease      # → app/build/outputs/apk/release/app-release.apk (debug-signed)
```
> The Gradle wrapper JAR is a binary and is not included; the commands above generate it.
> The APK is ~60–80 MB because it contains the Python runtime (arm64-v8a + x86_64).

## Install
Copy the APK to the phone, open it, and allow “Install unknown apps” for your file manager/browser.
Or: `adb install -r app-debug.apk`. Requires Android 8.0+ (API 26). Designed for 4–8 GB RAM devices.

---

## What is inside

| Area | Implementation |
|---|---|
| UI | Compose + Material 3, VS Code Dark+/Light+ themes, drawer explorer, tabs, status bar, bottom panels |
| Editor | Custom Compose editor: highlighting, line numbers, auto-indent, bracket/quote pairing, **code folding**, find & replace (case/regex), undo/redo, **multi-cursor**, fonts/size/indent settings, hardware-keyboard shortcuts |
| Completion | **Jedi** (the engine behind python-lsp-server) in-process: context-aware members, imports, signatures; shown as a suggestion strip above the keyboard |
| Analysis | `ast` syntax errors + **pyflakes** + **pycodestyle**, debounced, underlined in the editor, Problems panel, next-problem navigation, **quick fixes** (remove unused import, add `:`, add missing `import`, strip whitespace) |
| Format | **autopep8** |
| Run | Real CPython in-process (Chaquopy); run/stop, live stdout/stderr, **`input()`**, tracebacks, multi-file imports from the project |
| Debugger | `bdb`-based: gutter breakpoints, continue, step over/into/out, variables (locals/globals), call stack, debug console (evaluate expressions) |
| Terminal | Mini shell (`ls cd cat mkdir rm cp mv touch echo head wc tree pip python …`), persistent Python REPL (`python`), history |
| Packages | In-app **pip**: install, update, update-all, uninstall, list, progress log. Pure-Python wheels and Chaquopy's Android wheels (numpy, pandas, scipy, pillow …) |
| Files | New file/folder, rename, delete, copy/cut/paste, duplicate, multiple projects, recents, import zip/folder/file, export zip, templates |
| Git | **JGit**: init, status, commit, history, branches, remote, pull, push, clone (HTTPS + token) |
| Extras | Markdown preview, JSON tree viewer, snippets (built-in + your own), offline docs viewer (pydoc), auto-save, session restore, custom keyboard toolbar, custom `.ttf` fonts |

## Architecture
```
app/src/main
├── python/pystudio_core.py      # Python engine: runner, debugger, analysis, completion, pip, shell, docs
└── java/com/pystudio/app
    ├── App.kt, MainActivity.kt
    ├── data/    Prefs, ProjectStore (+templates, file ops), PyBridge (Kotlin⇄Python), Pypi, GitManager, models
    ├── state/   AppViewModel (single source of truth), TerminalState, PackageState, GitState
    └── ui/      Theme, Highlighter(+folding), EditorLogic, CodeEditor, MainScreen, Panels,
                 Explorer(+Projects), PackagesScreen, GitScreen, ToolsScreens, SettingsScreen
```
* Kotlin talks to Python through one module (`PyBridge`), always off the main thread, exchanging JSON.
* Program output is **polled** (25–60 ms, only while a program runs) → no background work when idle.
* stdout/stderr/stdin are routed per thread, so the terminal, pip jobs and your script never mix output.

## Honest limitations
* **Search**: PyPI has switched off its search API, so package search = exact-name lookup + an offline list of popular names.
* **Native wheels**: only wheels built for Android (Chaquopy's repository) work; packages needing compilation (sdists) cannot be built on-device.
* **Stop**: implemented with an async `KeyboardInterrupt`. Code stuck inside a long native call cannot be interrupted; PyStudio then offers *Force restart*.
* **No LSP wire protocol**: Jedi is called directly in-process (same engine, no socket/JSON-RPC overhead).
* **Editor** is not soft-wrapped and is tuned for files up to a few thousand lines (highlighting is disabled above 400 KB).
* **Debugger** traces the main script thread; breakpoints are fixed when a debug session starts.
* No GUI toolkits (tkinter, Qt) or `subprocess` spawning of `python` – Android has no such interpreter binary.
* Multi-cursor: add above/below/at line ends, then type/delete; best with the on-screen toolbar or a non-predictive keyboard.

## Privacy
No analytics, no ads, no crash reporters, no account. Network use is limited to what you trigger: pip, PyPI lookup, Git.
Git token (if you enter one) is stored in the app's private preferences.

License: MIT.
