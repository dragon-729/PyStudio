package com.pystudio.app.state

import android.app.Application
import android.net.Uri
import androidx.compose.foundation.ScrollState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.documentfile.provider.DocumentFile
import com.pystudio.app.data.*
import com.pystudio.app.ui.EditorLogic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

enum class Screen { Editor, Packages, Git, Settings, About, Markdown, Json, Snippets, Docs, Projects }
enum class Panel { None, Console, Problems, Terminal, Debug }

data class ScrollReq(val path: String, val line: Int, val nonce: Int)

class EditorTab(val file: File, initial: String) {
    var value by mutableStateOf(TextFieldValue(initial))
    var dirty by mutableStateOf(false)
    var diags by mutableStateOf<List<Diag>>(emptyList())
    var folded by mutableStateOf<Set<Int>>(emptySet())
    var cursors by mutableStateOf<List<Int>>(emptyList())
    val vScroll = ScrollState(0)
    val hScroll = ScrollState(0)
    val undo = ArrayList<TextFieldValue>()
    val redo = ArrayList<TextFieldValue>()
    var lastPush = 0L
    val name: String get() = file.name
}

class AppViewModel(private val app: Application) : AndroidViewModel(app) {
    val prefs = Prefs(app)
    val store = ProjectStore(app, prefs)
    val fontsDir = File(app.filesDir, "fonts").also { it.mkdirs() }

    // ---- navigation / chrome ----
    var screen by mutableStateOf(Screen.Editor)
    var panel by mutableStateOf(Panel.None)
    var panelMax by mutableStateOf(false)
    var message by mutableStateOf<String?>(null)
    var pythonReady by mutableStateOf(false)
    var pyVersion by mutableStateOf("")
    var editorFocused by mutableStateOf(false)

    // ---- project / tabs ----
    var project by mutableStateOf<File?>(null)
    val tabs = mutableStateListOf<EditorTab>()
    var active by mutableIntStateOf(0)
    var expanded by mutableStateOf<Set<String>>(emptySet())
    var tree by mutableStateOf<List<FileNode>>(emptyList())
    var clipboard by mutableStateOf<File?>(null)
    var clipboardCut by mutableStateOf(false)
    val activeTab: EditorTab? get() = tabs.getOrNull(active)

    // ---- run / debug ----
    val console = LineBuffer(5000)
    val dbgLog = LineBuffer(1000)
    var running by mutableStateOf(false)
    var debugging by mutableStateOf(false)
    var awaitingInput by mutableStateOf(false)
    var forceStopHint by mutableStateOf(false)
    var pause by mutableStateOf<PauseEv?>(null)
    val breakpoints = mutableStateMapOf<String, Set<Int>>()
    private var pollJob: Job? = null

    // ---- editor helpers ----
    var comps by mutableStateOf<CompResult?>(null)
    var scrollReq by mutableStateOf<ScrollReq?>(null)
    private var nonce = 0
    private val analysisJobs = HashMap<String, Job>()
    private val saveJobs = HashMap<String, Job>()
    private var compJob: Job? = null

    // ---- find & replace ----
    var findOpen by mutableStateOf(false)
    var findQuery by mutableStateOf("")
    var replaceText by mutableStateOf("")
    var caseSens by mutableStateOf(false)
    var useRegex by mutableStateOf(false)
    var matches by mutableStateOf<List<IntRange>>(emptyList())
    var matchIdx by mutableIntStateOf(0)

    // ---- feature states ----
    val term = TerminalState(viewModelScope) { path -> runPath(File(path), false) }
    val pkgs = PackageState(viewModelScope)
    val git = GitState(viewModelScope, prefs)

    init {
        viewModelScope.launch {
            withContext(Dispatchers.Default) { try { PyBridge.init(app) } catch (e: Exception) { message = "Python failed to start: ${e.message}" } }
            pyVersion = PyBridge.version
            pythonReady = pyVersion.isNotEmpty()
            project?.let { term.setCwd(it) }
            pkgs.refresh()
        }
        restoreSession()
    }

    fun toast(msg: String) { message = msg }
    private fun bg(block: () -> Unit) { viewModelScope.launch(Dispatchers.Default) { try { block() } catch (ignored: Exception) { } } }

    private fun attempt(block: () -> Unit) {
        try { block() } catch (e: Exception) { toast(e.message ?: "Operation failed") }
    }

    // ======================================================================
    // Projects & session
    // ======================================================================
    private fun restoreSession() {
        var p = store.projects().firstOrNull { it.name == prefs.lastProject }
        if (p == null) p = store.projects().firstOrNull() ?: store.create("HelloWorld", Templates.all[1])
        openProject(p, restore = true)
    }

    fun openProject(p: File, restore: Boolean = false) {
        saveAllSync()
        tabs.clear()
        active = 0
        breakpoints.clear()
        project = p
        store.touch(p)
        expanded = emptySet()
        refreshTree()
        term.setCwd(p)
        git.refresh(p)
        val wanted = if (restore) prefs.openTabs.split("\n").filter { it.isNotBlank() }.map { File(p, it) }.filter { it.isFile } else emptyList()
        val toOpen = wanted.ifEmpty { listOfNotNull(File(p, "main.py").takeIf { it.isFile }) }
        val savedActive = prefs.activeTab
        viewModelScope.launch {
            for (f in toOpen) openFileInternal(f)
            if (restore && tabs.isNotEmpty()) { active = savedActive.coerceIn(0, tabs.size - 1); persist() }
            screen = Screen.Editor
        }
    }

    fun createProject(name: String, t: Template) = attempt { openProject(store.create(name, t)) }
    fun deleteProject(p: File) {
        val wasCurrent = p.absolutePath == project?.absolutePath
        store.deleteProject(p)
        if (wasCurrent) { tabs.clear(); project = null; restoreSession() }
    }
    fun renameProject(p: File, name: String) = attempt {
        val wasCurrent = p.absolutePath == project?.absolutePath
        val np = store.renameProject(p, name)
        if (wasCurrent) openProject(np)
    }

    fun importZip(uri: Uri) = viewModelScope.launch {
        try {
            val name = DocumentFile.fromSingleUri(app, uri)?.name ?: "imported.zip"
            val dir = withContext(Dispatchers.IO) { store.importZip(uri, name) }
            openProject(dir)
            toast("Imported ${dir.name}")
        } catch (e: Exception) { toast("Import failed: ${e.message}") }
    }

    fun importFolder(uri: Uri) = viewModelScope.launch {
        try {
            val dir = withContext(Dispatchers.IO) { store.importFolder(uri) }
            openProject(dir)
            toast("Imported ${dir.name}")
        } catch (e: Exception) { toast("Import failed: ${e.message}") }
    }

    fun importFile(uri: Uri) = viewModelScope.launch {
        val p = project ?: return@launch
        try {
            val f = withContext(Dispatchers.IO) { store.importFile(uri, p) }
            refreshTree()
            openFile(f)
        } catch (e: Exception) { toast("Import failed: ${e.message}") }
    }

    fun importFont(uri: Uri) = viewModelScope.launch {
        try {
            val name = DocumentFile.fromSingleUri(app, uri)?.name ?: "font.ttf"
            withContext(Dispatchers.IO) {
                app.contentResolver.openInputStream(uri)?.use { i -> File(fontsDir, name).outputStream().use { o -> i.copyTo(o) } }
            }
            prefs.fontName = "custom:$name"
        } catch (e: Exception) { toast("Font import failed: ${e.message}") }
    }

    suspend fun exportProject(): File? {
        val p = project ?: return null
        saveAll()
        return try { withContext(Dispatchers.IO) { store.exportZip(p) } } catch (e: Exception) { toast("Export failed: ${e.message}"); null }
    }

    // ======================================================================
    // Files & tabs
    // ======================================================================
    fun refreshTree() {
        val p = project ?: return
        tree = FileOps.flatten(p, expanded)
    }

    fun toggleDir(f: File) {
        expanded = if (f.absolutePath in expanded) expanded - f.absolutePath else expanded + f.absolutePath
        refreshTree()
    }

    private fun persist() {
        val p = project ?: return
        prefs.openTabs = tabs.mapNotNull { t -> t.file.takeIf { it.absolutePath.startsWith(p.absolutePath + File.separator) }?.relativeTo(p)?.path }
            .joinToString("\n")
        prefs.activeTab = active
    }

    fun selectTab(i: Int) {
        if (i in tabs.indices) { active = i; comps = null; persist(); refreshMatches() }
    }

    fun openFile(f: File, line: Int = 0) {
        val idx = tabs.indexOfFirst { it.file.absolutePath == f.absolutePath }
        if (idx >= 0) {
            selectTab(idx)
            screen = Screen.Editor
            if (line > 0) goToLine(line)
            return
        }
        viewModelScope.launch {
            if (openFileInternal(f)) {
                screen = Screen.Editor
                if (line > 0) goToLine(line)
            }
        }
    }

    private suspend fun openFileInternal(f: File): Boolean {
        if (f.length() > 3_000_000) { toast("File too large to edit (${f.length() / 1024} KB)"); return false }
        val text = withContext(Dispatchers.IO) {
            try {
                val t = f.readText()
                if (t.take(4096).contains('\u0000')) null else t
            } catch (e: Exception) { null }
        }
        if (text == null) { toast("Cannot open binary or unreadable file"); return false }
        val t = EditorTab(f, text)
        tabs.add(t)
        active = tabs.lastIndex
        scheduleAnalysis(t, 150)
        persist()
        refreshMatches()
        return true
    }

    fun closeTab(i: Int) {
        val t = tabs.getOrNull(i) ?: return
        if (t.dirty) save(t)
        tabs.removeAt(i)
        if (active >= tabs.size) active = tabs.size - 1
        if (active < 0) active = 0
        comps = null
        persist()
    }

    private fun closeTabsUnder(path: String) {
        var i = tabs.size - 1
        while (i >= 0) {
            val p = tabs[i].file.absolutePath
            if (p == path || p.startsWith(path + File.separator)) { tabs.removeAt(i) }
            i--
        }
        if (active >= tabs.size) active = (tabs.size - 1).coerceAtLeast(0)
        persist()
    }

    fun newFile(dir: File, name: String) = attempt {
        val f = FileOps.createFile(dir, name)
        expanded = expanded + dir.absolutePath
        refreshTree()
        openFile(f)
    }

    fun newFolder(dir: File, name: String) = attempt {
        FileOps.createFolder(dir, name)
        expanded = expanded + dir.absolutePath
        refreshTree()
    }

    fun renameFile(f: File, name: String) = attempt {
        saveAllSync()
        val wasFile = f.isFile
        val open = tabs.any { it.file.absolutePath == f.absolutePath }
        closeTabsUnder(f.absolutePath)
        val nf = FileOps.rename(f, name)
        refreshTree()
        if (wasFile && open) openFile(nf)
    }

    fun deleteFile(f: File) = attempt {
        closeTabsUnder(f.absolutePath)
        f.deleteRecursively()
        refreshTree()
    }

    fun copyToClipboard(f: File, cut: Boolean) { clipboard = f; clipboardCut = cut; toast(if (cut) "Cut ${f.name}" else "Copied ${f.name}") }

    fun paste(dir: File) = attempt {
        val src = clipboard ?: return@attempt
        saveAllSync()
        if (clipboardCut) {
            closeTabsUnder(src.absolutePath)
            FileOps.moveInto(src, dir)
            clipboard = null
        } else FileOps.copyInto(src, dir)
        expanded = expanded + dir.absolutePath
        refreshTree()
    }

    fun duplicate(f: File) = attempt { FileOps.copyInto(f, f.parentFile!!); refreshTree() }

    // ======================================================================
    // Editing
    // ======================================================================
    private fun countNl(s: String): Int { var n = 0; for (c in s) if (c == '\n') n++; return n }

    fun onEdit(t: EditorTab, nv: TextFieldValue) {
        val old = t.value
        if (nv.text == old.text) {
            t.value = nv
            if (nv.selection != old.selection) { comps = null; t.cursors = emptyList() }
            return
        }
        var res = if (nv.composition == null) EditorLogic.process(old, nv, prefs.tabSize) else nv
        if (t.cursors.isNotEmpty()) {
            val m = EditorLogic.applyMulti(old, res, t.cursors)
            if (m != null) { res = m.first; t.cursors = m.second } else t.cursors = emptyList()
        }
        pushUndo(t, old)
        t.value = res
        t.dirty = true
        val delta = countNl(res.text) - countNl(old.text)
        if (delta != 0) {
            val editLine = countNl(old.text.substring(0, old.selection.min.coerceAtMost(old.text.length)))
            t.folded = t.folded.map { if (it > editLine) it + delta else it }.filter { it >= 0 }.toSet()
            val key = t.file.absolutePath
            breakpoints[key]?.let { bps ->
                breakpoints[key] = bps.map { if (it > editLine + 1) it + delta else it }.filter { it >= 1 }.toSet()
            }
        }
        scheduleAnalysis(t)
        scheduleCompletion(t)
        scheduleSave(t)
        refreshMatches()
    }

    private fun pushUndo(t: EditorTab, old: TextFieldValue) {
        val now = System.currentTimeMillis()
        if (t.undo.isEmpty() || now - t.lastPush > 600) {
            t.undo.add(old)
            if (t.undo.size > 150) t.undo.removeAt(0)
        }
        t.lastPush = now
        t.redo.clear()
    }

    private fun setText(t: EditorTab, text: String, cursor: Int) {
        t.undo.add(t.value)
        if (t.undo.size > 150) t.undo.removeAt(0)
        t.redo.clear()
        t.value = TextFieldValue(text, TextRange(cursor.coerceIn(0, text.length)))
        t.cursors = emptyList()
        t.dirty = true
        scheduleAnalysis(t, 200)
        scheduleSave(t)
        refreshMatches()
    }

    fun undo(t: EditorTab) {
        if (t.undo.isEmpty()) return
        t.redo.add(t.value)
        t.value = t.undo.removeAt(t.undo.size - 1)
        afterReplace(t)
    }

    fun redo(t: EditorTab) {
        if (t.redo.isEmpty()) return
        t.undo.add(t.value)
        t.value = t.redo.removeAt(t.redo.size - 1)
        afterReplace(t)
    }

    private fun afterReplace(t: EditorTab) {
        t.cursors = emptyList()
        t.dirty = true
        comps = null
        scheduleAnalysis(t)
        scheduleSave(t)
        refreshMatches()
    }

    fun insertText(t: EditorTab, s: String, cursorInIns: Int = s.length) {
        val v = t.value
        val a = v.selection.min
        val b = v.selection.max
        onEdit(t, TextFieldValue(v.text.substring(0, a) + s + v.text.substring(b), TextRange(a + cursorInIns)))
    }

    fun toggleFold(t: EditorTab, line: Int) { t.folded = if (line in t.folded) t.folded - line else t.folded + line }

    fun foldAll(t: EditorTab, fold: Boolean, headers: Set<Int>) { t.folded = if (fold) headers else emptySet() }

    fun toggleBreakpoint(path: String, line: Int) {
        val cur = breakpoints[path] ?: emptySet()
        breakpoints[path] = if (line in cur) cur - line else cur + line
    }

    // ---- multi-cursor ----
    fun addCursorVertical(t: EditorTab, down: Boolean) {
        val text = t.value.text
        val all = t.cursors + t.value.selection.start
        val ref = if (down) all.max() else all.min()
        val ls = text.lastIndexOf('\n', ref - 1).let { if (ref == 0) 0 else it + 1 }
        val col = ref - ls
        val target: Int
        if (down) {
            val nl = text.indexOf('\n', ref)
            if (nl < 0) return
            val start = nl + 1
            val end = text.indexOf('\n', start).let { if (it < 0) text.length else it }
            target = minOf(start + col, end)
        } else {
            if (ls == 0) return
            val prevStart = text.lastIndexOf('\n', ls - 2) + 1
            target = minOf(prevStart + col, ls - 1)
        }
        if (target !in all) t.cursors = (t.cursors + target).distinct()
    }

    fun cursorsAtLineEnds(t: EditorTab) {
        val v = t.value
        if (v.selection.collapsed) { toast("Select several lines first"); return }
        val text = v.text
        val ends = ArrayList<Int>()
        var pos = v.selection.min
        while (pos <= v.selection.max) {
            val nl = text.indexOf('\n', pos).let { if (it < 0) text.length else it }
            if (nl > v.selection.max) { if (v.selection.max >= pos) ends.add(v.selection.max); break }
            ends.add(nl)
            pos = nl + 1
        }
        if (ends.isEmpty()) return
        t.value = v.copy(selection = TextRange(ends.last()))
        t.cursors = ends.dropLast(1)
    }

    fun clearCursors(t: EditorTab) { t.cursors = emptyList() }

    // ---- save ----
    fun save(t: EditorTab) {
        val text = t.value.text
        viewModelScope.launch(Dispatchers.IO) {
            try { t.file.writeText(text); t.dirty = t.value.text != text } catch (e: Exception) { toast("Save failed: ${e.message}") }
        }
    }

    suspend fun saveAll() {
        val dirty = tabs.filter { it.dirty }
        if (dirty.isEmpty()) return
        withContext(Dispatchers.IO) {
            for (t in dirty) {
                val text = t.value.text
                try { t.file.writeText(text); t.dirty = t.value.text != text } catch (ignored: Exception) { }
            }
        }
    }

    fun saveAllSync() {
        for (t in tabs) if (t.dirty) {
            try { t.file.writeText(t.value.text); t.dirty = false } catch (ignored: Exception) { }
        }
        persist()
    }

    private fun scheduleSave(t: EditorTab) {
        if (!prefs.autoSave) return
        saveJobs[t.file.path]?.cancel()
        saveJobs[t.file.path] = viewModelScope.launch { delay(1200); save(t) }
    }

    // ---- analysis / completion / formatting ----
    fun scheduleAnalysis(t: EditorTab, wait: Long = 600) {
        if (!prefs.linting || !t.file.name.endsWith(".py")) { t.diags = emptyList(); return }
        analysisJobs[t.file.path]?.cancel()
        analysisJobs[t.file.path] = viewModelScope.launch {
            delay(wait)
            while (!pythonReady) delay(200)
            val text = t.value.text
            val d = PyBridge.analyze(text, t.file.absolutePath)
            if (t.value.text == text) t.diags = d
        }
    }

    private fun scheduleCompletion(t: EditorTab) {
        compJob?.cancel()
        val v = t.value
        if (!prefs.autoComplete || !pythonReady || !t.file.name.endsWith(".py") || !v.selection.collapsed) { comps = null; return }
        val pos = v.selection.start
        val txt = v.text
        val prev = if (pos > 0 && pos <= txt.length) txt[pos - 1] else ' '
        if (!(prev.isLetterOrDigit() || prev == '_' || prev == '.')) { comps = null; return }
        compJob = viewModelScope.launch {
            delay(110)
            var line = 1
            for (i in 0 until pos) if (txt[i] == '\n') line++
            val ls = txt.lastIndexOf('\n', pos - 1) + 1
            val r = PyBridge.complete(txt, line, pos - ls, t.file.absolutePath, project?.absolutePath ?: "")
            if (t.value.text == txt && t.value.selection.start == pos) comps = if (r.items.isEmpty() && r.sig.isEmpty()) null else r
        }
    }

    fun applyCompletion(item: CompItem) {
        val t = activeTab ?: return
        val r = comps ?: return
        val v = t.value
        val pos = v.selection.start
        val start = (pos - r.prefixLen).coerceAtLeast(0)
        val add = if (item.type == "function" && v.text.getOrNull(pos) != '(') "()" else ""
        val text = v.text.substring(0, start) + item.name + add + v.text.substring(pos)
        setText(t, text, start + item.name.length + (if (add.isNotEmpty()) 1 else 0))
        comps = null
    }

    fun formatCode() {
        val t = activeTab ?: return
        if (!t.file.name.endsWith(".py")) { toast("Formatting supports Python files"); return }
        viewModelScope.launch {
            val before = t.value.text
            val out = PyBridge.format(before)
            when {
                out == null -> toast("Formatter is not ready yet")
                t.value.text != before -> toast("File changed while formatting")
                out == before -> toast("Already formatted")
                else -> { setText(t, out, t.value.selection.start.coerceAtMost(out.length)); toast("Formatted") }
            }
        }
    }

    fun applyFix(t: EditorTab, d: Diag) {
        val lines = t.value.text.split('\n').toMutableList()
        val i = d.line - 1
        if (i !in lines.indices) return
        when {
            d.op == "delete_line" -> lines.removeAt(i)
            d.op == "add_colon" -> lines[i] = lines[i].trimEnd() + ":"
            d.op == "strip_ws" -> lines[i] = lines[i].trimEnd()
            d.op.startsWith("import:") -> {
                var at = 0
                while (at < lines.size && (lines[at].startsWith("#!") || lines[at].startsWith("# -*-"))) at++
                lines.add(at, "import " + d.op.removePrefix("import:"))
            }
            else -> { toast(d.fix.ifEmpty { "No automatic fix available" }); return }
        }
        setText(t, lines.joinToString("\n"), t.value.selection.start)
    }

    fun goToLine(line: Int, col: Int = 1) {
        val t = activeTab ?: return
        val text = t.value.text
        var off = 0
        var l = 1
        while (l < line) {
            val n = text.indexOf('\n', off)
            if (n < 0) break
            off = n + 1
            l++
        }
        val end = text.indexOf('\n', off).let { if (it < 0) text.length else it }
        t.value = t.value.copy(selection = TextRange((off + col - 1).coerceIn(off, end)))
        scrollReq = ScrollReq(t.file.absolutePath, l - 1, ++nonce)
    }

    fun gotoProblem(forward: Boolean) {
        val t = activeTab ?: return
        val list = t.diags.filter { it.sev >= 1 }.sortedWith(compareBy({ it.line }, { it.col }))
        if (list.isEmpty()) { toast("No problems found"); return }
        val text = t.value.text
        val cur = countNl(text.substring(0, t.value.selection.start.coerceAtMost(text.length))) + 1
        val d = if (forward) list.firstOrNull { it.line > cur } ?: list.first() else list.lastOrNull { it.line < cur } ?: list.last()
        goToLine(d.line, d.col)
    }

    // ---- find & replace ----
    private fun regexOrNull(): Regex? = try {
        Regex(findQuery, if (caseSens) emptySet() else setOf(RegexOption.IGNORE_CASE))
    } catch (e: Exception) { null }

    fun refreshMatches() {
        val t = activeTab
        if (!findOpen || t == null || findQuery.isEmpty()) { matches = emptyList(); return }
        val text = t.value.text
        val out = ArrayList<IntRange>()
        if (useRegex) {
            val re = regexOrNull()
            if (re != null) for (m in re.findAll(text)) { if (m.value.isEmpty()) continue; out.add(m.range); if (out.size >= 5000) break }
        } else {
            var i = text.indexOf(findQuery, 0, !caseSens)
            while (i >= 0 && out.size < 5000) {
                out.add(i until i + findQuery.length)
                i = text.indexOf(findQuery, i + findQuery.length, !caseSens)
            }
        }
        matches = out
        matchIdx = matchIdx.coerceIn(0, maxOf(0, out.size - 1))
    }

    fun onFindChanged() {
        refreshMatches()
        val t = activeTab ?: return
        val caret = t.value.selection.min
        matchIdx = matches.indexOfFirst { it.first >= caret }.let { if (it < 0) 0 else it }
        if (matches.isNotEmpty()) selectMatch()
    }

    private fun selectMatch() {
        val t = activeTab ?: return
        val m = matches.getOrNull(matchIdx) ?: return
        t.value = t.value.copy(selection = TextRange(m.first, m.last + 1))
        val line = countNl(t.value.text.substring(0, m.first)) // 0-based
        scrollReq = ScrollReq(t.file.absolutePath, line, ++nonce)
    }

    fun findStep(dir: Int) {
        refreshMatches()
        if (matches.isEmpty()) return
        matchIdx = ((matchIdx + dir) % matches.size + matches.size) % matches.size
        selectMatch()
    }

    fun replaceCurrent() {
        val t = activeTab ?: return
        refreshMatches()
        val m = matches.getOrNull(matchIdx) ?: return
        val text = t.value.text
        val rep = if (useRegex) regexOrNull()?.replace(text.substring(m.first, m.last + 1), replaceText) ?: replaceText else replaceText
        setText(t, text.substring(0, m.first) + rep + text.substring(m.last + 1), m.first + rep.length)
        refreshMatches()
        if (matches.isNotEmpty()) { matchIdx = matchIdx.coerceAtMost(matches.size - 1); selectMatch() }
    }

    fun replaceAll() {
        val t = activeTab ?: return
        if (findQuery.isEmpty()) return
        val text = t.value.text
        val out = if (useRegex) (regexOrNull()?.replace(text, replaceText) ?: text) else text.replace(findQuery, replaceText, ignoreCase = !caseSens)
        if (out != text) { setText(t, out, t.value.selection.start); toast("Replaced all") } else toast("No matches")
        refreshMatches()
    }

    // ======================================================================
    // Run / debug
    // ======================================================================
    fun run(debug: Boolean) {
        val t = activeTab
        if (t == null) { toast("Open a Python file first"); return }
        if (!t.file.name.endsWith(".py")) { toast("Only .py files can be run"); return }
        if (!pythonReady) { toast("Python runtime is still starting…"); return }
        if (running) { toast("A program is already running"); return }
        runPath(t.file, debug)
    }

    fun runPath(f: File, debug: Boolean) {
        if (running) { toast("A program is already running"); return }
        viewModelScope.launch {
            saveAll()
            if (prefs.clearOnRun) console.clear()
            console.append("▶ ${if (debug) "Debugging" else "Running"} ${f.name}\n", K_SYS)
            dbgLog.clear()
            pause = null
            val bp = breakpoints.mapValues { it.value }
            if (PyBridge.runFile(f.absolutePath, debug, bp)) {
                running = true
                debugging = debug
                forceStopHint = false
                panel = if (debug) Panel.Debug else Panel.Console
                startPolling()
            } else toast("Could not start the program")
        }
    }

    private fun startPolling() {
        if (pollJob?.isActive == true) return
        pollJob = viewModelScope.launch {
            while (running) {
                val evs = PyBridge.poll()
                handle(evs)
                delay(if (evs.isEmpty()) 60 else 25)
            }
            handle(PyBridge.poll())
        }
    }

    private fun handle(evs: List<RunEvent>) {
        for (e in evs) when (e) {
            is OutEv -> console.append(e.text, if (e.err) K_ERR else K_OUT)
            is InputEv -> { awaitingInput = true; if (panel == Panel.None) panel = Panel.Console }
            is DoneEv -> {
                console.append("\n[Finished with exit code ${e.code} in ${e.secs}s]\n", K_SYS)
                running = false; debugging = false; awaitingInput = false; pause = null; forceStopHint = false
            }
            is DbgEv -> dbgLog.append(e.text, K_OUT)
            is PauseEv -> { pause = e; openFile(File(e.file), e.line) }
        }
    }

    fun stop() {
        bg { PyBridge.stop() }
        viewModelScope.launch { delay(2500); if (running) forceStopHint = true }
    }

    fun submitInput(text: String) {
        awaitingInput = false
        console.append(text + "\n", K_IN)
        bg { PyBridge.sendInput(text) }
    }

    fun dbg(cmd: String) { pause = null; bg { PyBridge.debugCmd(cmd) } }

    fun dbgEval(expr: String) {
        if (expr.isBlank()) return
        dbgLog.append(">>> $expr\n", K_IN)
        bg { PyBridge.debugCmd("eval:$expr") }
    }

    fun onBackground() { saveAllSync() }
}
