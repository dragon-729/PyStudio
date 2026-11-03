package com.pystudio.app.data

import android.content.Context
import android.os.Build
import com.chaquo.python.PyObject
import com.chaquo.python.Python
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Thin, crash-safe wrapper around the embedded CPython runtime (module `pystudio_core`). */
object PyBridge {
    private var core: PyObject? = null
    lateinit var siteDir: File
        private set
    var version: String = ""
        private set

    @Synchronized
    fun init(ctx: Context) {
        if (core != null) return
        siteDir = File(ctx.filesDir, "site-packages")
        val m = Python.getInstance().getModule("pystudio_core")
        val abi = (Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a").replace('-', '_')
        version = m.callAttr("init", siteDir.absolutePath, abi).toString()
        core = m
    }

    private fun call(fn: String, vararg a: Any?): String? = try {
        core?.callAttr(fn, *a)?.toString()
    } catch (e: Exception) {
        null
    }

    private suspend fun <T> py(block: () -> T): T = withContext(Dispatchers.Default) { block() }

    // ---------------- analysis / completion / formatting ----------------
    suspend fun analyze(src: String, path: String): List<Diag> = py {
        val js = call("analyze", src, path) ?: return@py emptyList<Diag>()
        val a = JSONArray(js)
        List(a.length()) { i ->
            val o = a.getJSONObject(i)
            Diag(
                o.getInt("l"), o.getInt("c"), o.optInt("ec", o.getInt("c") + 1),
                when (o.getString("sev")) { "error" -> 2; "warning" -> 1; else -> 0 },
                o.getString("msg"), o.optString("code"), o.optString("fix"), o.optString("op")
            )
        }
    }

    /** line is 1-based, col is the 0-based column of the caret. */
    suspend fun complete(src: String, line: Int, col: Int, path: String, projectDir: String): CompResult = py {
        val js = call("complete", src, line, col, path, projectDir) ?: return@py CompResult(0, emptyList(), "")
        val o = JSONObject(js)
        val a = o.getJSONArray("items")
        CompResult(
            o.optInt("p"),
            List(a.length()) { i -> a.getJSONObject(i).let { CompItem(it.getString("n"), it.optString("t")) } },
            o.optString("sig")
        )
    }

    suspend fun format(src: String): String? = py { call("format_code", src) }

    // ---------------- running / debugging ----------------
    suspend fun runFile(path: String, debug: Boolean, breaks: Map<String, Set<Int>>): Boolean = py {
        val j = JSONObject()
        breaks.forEach { (k, v) -> j.put(k, JSONArray(v.toList())) }
        call("run_file", path, debug, j.toString()) == "True"
    }

    fun stop(): Boolean = call("stop_run") == "True"
    fun isRunning(): Boolean = call("is_running") == "True"
    fun sendInput(text: String) { call("send_input", text) }
    fun debugCmd(cmd: String) { call("debug_cmd", cmd) }

    suspend fun poll(): List<RunEvent> = py {
        val js = call("poll") ?: return@py emptyList<RunEvent>()
        val a = JSONArray(js)
        val out = ArrayList<RunEvent>(a.length())
        for (i in 0 until a.length()) {
            val o = a.getJSONObject(i)
            when (o.getString("t")) {
                "out" -> out.add(OutEv(false, o.getString("s")))
                "err" -> out.add(OutEv(true, o.getString("s")))
                "input" -> out.add(InputEv)
                "dbg" -> out.add(DbgEv(o.getString("s")))
                "done" -> out.add(DoneEv(o.optInt("code"), o.optDouble("secs")))
                "pause" -> out.add(
                    PauseEv(
                        o.getString("file"), o.getInt("line"),
                        frames(o.getJSONArray("stack")), vars(o.getJSONArray("locals")), vars(o.getJSONArray("globals"))
                    )
                )
            }
        }
        out
    }

    private fun frames(a: JSONArray) = List(a.length()) { i ->
        a.getJSONArray(i).let { DFrame(it.getString(0), it.getInt(1), it.getString(2)) }
    }

    private fun vars(a: JSONArray) = List(a.length()) { i ->
        a.getJSONArray(i).let { DVar(it.getString(0), it.getString(1), it.getString(2)) }
    }

    // ---------------- terminal ----------------
    suspend fun termExec(line: String, mode: String): TermResult = py {
        val js = call("term_exec", line, mode) ?: return@py TermResult(emptyList(), false, "")
        val o = JSONObject(js)
        val a = o.getJSONArray("out")
        TermResult(
            List(a.length()) { i -> a.getJSONArray(i).let { it.getString(0) to it.getString(1) } },
            o.optBoolean("more"), o.optString("cwd")
        )
    }

    suspend fun termSetCwd(path: String) { py { call("term_set_cwd", path) } }

    // ---------------- packages ----------------
    suspend fun listPackages(): List<Pkg> = py {
        val js = call("list_packages") ?: return@py emptyList<Pkg>()
        val a = JSONArray(js)
        List(a.length()) { i -> a.getJSONObject(i).let { Pkg(it.getString("name"), it.getString("version"), it.optString("summary")) } }
    }

    suspend fun jobStart(kind: String, arg: String): Boolean = py { call("job_start", kind, arg, true) == "True" }

    suspend fun jobPoll(): JobState = py {
        val js = call("job_poll") ?: return@py JobState("", true, 1)
        val o = JSONObject(js)
        JobState(o.optString("out"), o.optBoolean("done", true), o.optInt("code"))
    }

    // ---------------- docs ----------------
    suspend fun doc(name: String): String = py { call("doc", name) ?: "Documentation unavailable." }

    suspend fun stdlibModules(): List<String> = py {
        val a = JSONArray(call("stdlib_modules") ?: "[]")
        List(a.length()) { a.getString(it) }
    }
}
