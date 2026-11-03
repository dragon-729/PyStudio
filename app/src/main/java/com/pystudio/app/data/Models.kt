package com.pystudio.app.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.setValue

const val K_OUT = 0
const val K_ERR = 1
const val K_SYS = 2
const val K_IN = 3

class ConsoleLine(val text: String, val kind: Int)

/** Append-only line buffer with proper handling of partial lines, \r and ANSI codes. */
class LineBuffer(private val max: Int = 4000) {
    val lines = mutableStateListOf<ConsoleLine>()
    var version by mutableIntStateOf(0)
        private set
    private var open = false
    private val ansi = Regex("\u001B\\[[0-9;?]*[ -/]*[@-~]")

    fun clear() { lines.clear(); open = false; version++ }
    fun text(): String = lines.joinToString("\n") { it.text }

    fun append(raw: String, kind: Int) {
        val s = ansi.replace(raw, "").replace("\r\n", "\n")
        val parts = s.split('\n')
        for ((k, p) in parts.withIndex()) {
            val last = k == parts.lastIndex
            if (last && p.isEmpty()) break
            val ret = p.contains('\r')
            val seg = p.trimEnd('\r').substringAfterLast('\r')
            if (open && lines.isNotEmpty()) {
                val prev = lines[lines.lastIndex]
                lines[lines.lastIndex] = ConsoleLine(if (ret) seg else prev.text + seg, prev.kind)
            } else {
                lines.add(ConsoleLine(seg, kind))
            }
            open = last
        }
        while (lines.size > max) lines.removeAt(0)
        version++
    }
}

/** Diagnostic severity: 0 info (style), 1 warning, 2 error. */
data class Diag(
    val line: Int, val col: Int, val endCol: Int, val sev: Int,
    val msg: String, val code: String, val fix: String, val op: String
)

data class CompItem(val name: String, val type: String)
data class CompResult(val prefixLen: Int, val items: List<CompItem>, val sig: String)

data class Pkg(val name: String, val version: String, val summary: String)
data class JobState(val out: String, val done: Boolean, val code: Int)
data class TermResult(val out: List<Pair<String, String>>, val more: Boolean, val cwd: String)

data class DFrame(val file: String, val line: Int, val func: String)
data class DVar(val name: String, val type: String, val value: String)

sealed interface RunEvent
data class OutEv(val err: Boolean, val text: String) : RunEvent
data object InputEv : RunEvent
data class DoneEv(val code: Int, val secs: Double) : RunEvent
data class DbgEv(val text: String) : RunEvent
data class PauseEv(
    val file: String, val line: Int, val stack: List<DFrame>,
    val locals: List<DVar>, val globals: List<DVar>
) : RunEvent
