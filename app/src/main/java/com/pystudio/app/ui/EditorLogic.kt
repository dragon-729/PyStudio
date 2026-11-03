package com.pystudio.app.ui

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue

object EditorLogic {
    private val pairs = mapOf('(' to ')', '[' to ']', '{' to '}', '"' to '"', '\'' to '\'')
    private val closers = setOf(')', ']', '}', '"', '\'')
    private val dedentWords = Regex("^\\s*(return|pass|break|continue|raise)\\b")

    private fun tv(text: String, cursor: Int) = TextFieldValue(text, TextRange(cursor))

    /** Smart typing: auto-indent, bracket/quote pairing, type-over, smart backspace, tab -> spaces. */
    fun process(old: TextFieldValue, nv: TextFieldValue, tab: Int): TextFieldValue {
        val o = old.text
        val n = nv.text
        val selS = old.selection.min
        val selE = old.selection.max
        val removed = selE - selS
        val added = n.length - (o.length - removed)
        if (selE > o.length) return nv

        if (added > 0 && n.startsWith(o.substring(0, selS)) && n.endsWith(o.substring(selE)) && selS + added <= n.length) {
            val ins = n.substring(selS, selS + added)
            if (added == 1) {
                val ch = ins[0]
                if (removed == 0 && ch in closers && selS < o.length && o[selS] == ch) return tv(o, selS + 1)
                if (ch == '\n') return autoIndent(o, selS, selE, tab)
                if (ch == '\t') {
                    val sp = " ".repeat(tab)
                    return tv(o.substring(0, selS) + sp + o.substring(selE), selS + tab)
                }
                val close = pairs[ch]
                if (close != null) {
                    if (removed > 0) {
                        val t = o.substring(0, selS) + ch + o.substring(selS, selE) + close + o.substring(selE)
                        return TextFieldValue(t, TextRange(selS + 1, selE + 1))
                    }
                    val next = if (selS < o.length) o[selS] else '\n'
                    val prev = if (selS > 0) o[selS - 1] else '\n'
                    val quote = ch == '"' || ch == '\''
                    val ok = if (quote) !(prev.isLetterOrDigit() || prev == '_' || prev == ch) && !next.isLetterOrDigit()
                    else next.isWhitespace() || next in closers || next == ',' || next == ':'
                    if (ok) return tv(o.substring(0, selS) + ch + close + o.substring(selS), selS + 1)
                }
            }
            return nv
        }
        // backspace on a collapsed caret
        if (removed == 0 && n.length == o.length - 1 && selS > 0 &&
            n.startsWith(o.substring(0, selS - 1)) && n.endsWith(o.substring(selS))
        ) {
            val deleted = o[selS - 1]
            val nextCh = if (selS < o.length) o[selS] else null
            if (pairs[deleted] != null && pairs[deleted] == nextCh) {
                return tv(o.removeRange(selS - 1, selS + 1), selS - 1)
            }
            if (deleted == ' ') {
                val ls = o.lastIndexOf('\n', selS - 1) + 1
                val prefix = o.substring(ls, selS)
                if (prefix.all { it == ' ' }) {
                    val k = ((prefix.length - 1) % tab) + 1
                    return tv(o.removeRange(selS - k, selS), selS - k)
                }
            }
        }
        return nv
    }

    private fun autoIndent(o: String, selS: Int, selE: Int, tab: Int): TextFieldValue {
        val before = o.substring(0, selS)
        val after = o.substring(selE)
        val ls = before.lastIndexOf('\n') + 1
        val line = before.substring(ls)
        val base = line.takeWhile { it == ' ' || it == '\t' }
        val code = line.substringBefore('#').trimEnd()
        val unit = " ".repeat(tab)
        var indent = base
        val opener = code.endsWith("(") || code.endsWith("[") || code.endsWith("{")
        if (code.endsWith(":") || opener) indent = base + unit
        else if (dedentWords.containsMatchIn(line) && base.length >= tab && base.all { it == ' ' }) indent = base.dropLast(tab)
        val between = opener && after.firstOrNull()?.let { it == ')' || it == ']' || it == '}' } == true
        val text = before + "\n" + indent + (if (between) "\n" + base else "") + after
        return tv(text, before.length + 1 + indent.length)
    }

    /**
     * Replays the primary edit (old -> res) at every extra cursor.
     * Returns the new value plus the new offsets of the extra cursors, or null if the edit cannot be mirrored.
     */
    fun applyMulti(old: TextFieldValue, res: TextFieldValue, extras: List<Int>): Pair<TextFieldValue, List<Int>>? {
        if (!old.selection.collapsed || !res.selection.collapsed) return null
        val o = old.text
        val r = res.text
        val c0 = old.selection.start
        val q = res.selection.start
        val dl = r.length - o.length
        var ins = ""
        var back = 0
        var fwd = 0
        var cur = 0
        if (dl > 0) {
            if (c0 + dl > r.length || !r.startsWith(o.substring(0, c0)) || !r.endsWith(o.substring(c0))) return null
            ins = r.substring(c0, c0 + dl)
            cur = (q - c0).coerceIn(0, dl)
        } else if (dl < 0) {
            val k = -dl
            val combos = listOf(k to 0, 0 to k, 1 to 1)
            val hit = combos.firstOrNull { (b, f) ->
                (b + f == k) && c0 - b >= 0 && c0 + f <= o.length && q == c0 - b && r == o.removeRange(c0 - b, c0 + f)
            } ?: return null
            back = hit.first
            fwd = hit.second
        } else return null

        val all = (extras + c0).filter { it in 0..o.length }.distinct().sorted()
        val sb = StringBuilder()
        var last = 0
        val newPos = HashMap<Int, Int>()
        for (c in all) {
            val s = c - back
            val e = c + fwd
            if (s < last || s < 0 || e > o.length) return null
            sb.append(o, last, s)
            val start = sb.length
            sb.append(ins)
            newPos[c] = start + cur
            last = e
        }
        sb.append(o, last, o.length)
        val primary = newPos[c0] ?: return null
        return Pair(tv(sb.toString(), primary), all.filter { it != c0 }.map { newPos[it]!! })
    }
}
