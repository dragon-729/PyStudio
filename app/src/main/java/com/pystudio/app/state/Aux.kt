package com.pystudio.app.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.pystudio.app.data.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Integrated terminal: tiny shell + persistent Python REPL, both executed by the embedded interpreter. */
class TerminalState(private val scope: CoroutineScope, private val onRun: (String) -> Unit) {
    val buf = LineBuffer(3000)
    var mode by mutableStateOf("sh")
    var more by mutableStateOf(false)
    var busy by mutableStateOf(false)
    var cwd by mutableStateOf("")
    private val history = ArrayList<String>()
    private var histIdx = 0

    val prompt: String
        get() = if (mode == "py") (if (more) "... " else ">>> ") else (File(cwd).name.ifEmpty { "/" } + " $ ")

    fun setCwd(dir: File) {
        cwd = dir.absolutePath
        scope.launch { PyBridge.termSetCwd(dir.absolutePath) }
    }

    fun historyUp(): String? {
        if (history.isEmpty()) return null
        histIdx = (histIdx - 1).coerceAtLeast(0)
        return history[histIdx]
    }

    fun historyDown(): String? {
        if (history.isEmpty()) return null
        histIdx = (histIdx + 1).coerceAtMost(history.size)
        return history.getOrNull(histIdx) ?: ""
    }

    fun submit(line: String) {
        if (busy) return
        buf.append(prompt + line + "\n", K_IN)
        if (line.isNotBlank() && history.lastOrNull() != line) history.add(line)
        histIdx = history.size
        val t = line.trim()
        if (mode == "sh" && t == "clear") { buf.clear(); return }
        if (mode == "py" && !more && (t == "exit()" || t == "quit()" || t == "exit")) { mode = "sh"; return }
        scope.launch {
            busy = true
            val r = PyBridge.termExec(line, mode)
            for ((k, s) in r.out) {
                when {
                    s.startsWith("\u0000run:") -> onRun(s.substring(5))
                    s.startsWith("\u0000mode:py") -> {
                        mode = "py"
                        buf.append("Python ${PyBridge.version}\nType exit() to return to the shell.\n", K_SYS)
                    }
                    else -> buf.append(s, if (k == "err") K_ERR else K_OUT)
                }
            }
            more = r.more
            if (r.cwd.isNotEmpty()) cwd = r.cwd
            busy = false
        }
    }

    fun banner() {
        if (buf.lines.isEmpty()) buf.append("PyStudio terminal. Type 'help' for commands, 'python' for the Python shell.\n", K_SYS)
    }
}

data class PkgHit(val name: String, val info: Pypi.Info?)

class PackageState(private val scope: CoroutineScope) {
    var installed by mutableStateOf<List<Pkg>>(emptyList())
    var busy by mutableStateOf(false)
    var searching by mutableStateOf(false)
    var hits by mutableStateOf<List<PkgHit>>(emptyList())
    val log = LineBuffer(2000)

    fun refresh() { scope.launch { installed = PyBridge.listPackages() } }

    fun search(q: String) {
        val query = q.trim()
        if (query.isEmpty()) { hits = emptyList(); return }
        scope.launch {
            searching = true
            val local = Pypi.popular.filter { it.contains(query.lowercase()) }.take(25).map { PkgHit(it, null) }
            val exact = withContext(Dispatchers.IO) { Pypi.lookup(query) }
            val list = ArrayList<PkgHit>()
            if (exact != null) list.add(PkgHit(exact.name, exact))
            list.addAll(local.filter { l -> !l.name.equals(exact?.name, ignoreCase = true) })
            hits = list
            searching = false
        }
    }

    fun install(spec: String) = runJob("install", spec, "pip install $spec")
    fun uninstall(name: String) = runJob("uninstall", name, "pip uninstall $name")
    fun updateAll() { if (installed.isNotEmpty()) install(installed.joinToString(" ") { it.name }) }

    private fun runJob(kind: String, arg: String, title: String) {
        if (busy || arg.isBlank()) return
        scope.launch {
            busy = true
            log.append("\n▶ $title\n", K_SYS)
            if (!PyBridge.jobStart(kind, arg)) {
                log.append("Another package job is still running.\n", K_ERR)
                busy = false
                return@launch
            }
            while (true) {
                delay(150)
                val st = PyBridge.jobPoll()
                if (st.out.isNotEmpty()) log.append(st.out, K_OUT)
                if (st.done) {
                    log.append(if (st.code == 0) "✔ Finished\n" else "✖ Failed (exit code ${st.code})\n", if (st.code == 0) K_SYS else K_ERR)
                    break
                }
            }
            busy = false
            refresh()
        }
    }
}

class GitState(private val scope: CoroutineScope, private val prefs: Prefs) {
    var info by mutableStateOf(GitInfo(false))
    var busy by mutableStateOf(false)
    var status by mutableStateOf("")

    fun refresh(dir: File?) {
        if (dir == null) return
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                try { GitManager.info(dir) to "" } catch (e: Exception) { GitInfo(false) to "Git error: ${e.message}" }
            }
            info = r.first
            if (r.second.isNotEmpty()) status = r.second
        }
    }

    private fun op(dir: File?, title: String, onOk: () -> Unit = {}, block: () -> String?) {
        if (dir == null || busy) return
        scope.launch {
            busy = true
            status = "$title…"
            val r = withContext(Dispatchers.IO) {
                try { Result.success(block() ?: "$title: done") } catch (e: Exception) { Result.failure<String>(e) }
            }
            status = r.getOrElse { "$title failed: ${it.message}" }
            if (r.isSuccess) onOk()
            busy = false
            refresh(dir)
        }
    }

    fun init(dir: File?) = op(dir, "git init") { dir?.let { GitManager.init(it) }; null }

    fun commit(dir: File?, msg: String) = op(dir, "Commit") {
        require(msg.isNotBlank()) { "Enter a commit message" }
        GitManager.commitAll(dir!!, msg, prefs.gitName, prefs.gitEmail)
        null
    }

    fun checkout(dir: File?, branch: String, create: Boolean) = op(dir, "Checkout") {
        GitManager.checkout(dir!!, branch, create)
        null
    }

    fun setRemote(dir: File?, url: String) = op(dir, "Set remote") { GitManager.setRemote(dir!!, url); null }
    fun pull(dir: File?) = op(dir, "Pull") { GitManager.pull(dir!!, prefs.gitUser, prefs.gitToken) }
    fun push(dir: File?) = op(dir, "Push") { GitManager.push(dir!!, prefs.gitUser, prefs.gitToken) }

    fun clone(url: String, root: File, onDone: (File) -> Unit) {
        if (busy || url.isBlank()) return
        scope.launch {
            busy = true
            status = "Cloning…"
            var dest = File(root, url.trimEnd('/').substringAfterLast('/').removeSuffix(".git").ifBlank { "repo" })
            var i = 2
            val base = dest.name
            while (dest.exists()) dest = File(root, "$base-${i++}")
            val r = withContext(Dispatchers.IO) {
                try { GitManager.clone(url.trim(), dest, prefs.gitUser, prefs.gitToken); Result.success(Unit) }
                catch (e: Exception) { dest.deleteRecursively(); Result.failure<Unit>(e) }
            }
            status = if (r.isSuccess) "Cloned into ${dest.name}" else "Clone failed: ${r.exceptionOrNull()?.message}"
            busy = false
            if (r.isSuccess) onDone(dest)
        }
    }
}
