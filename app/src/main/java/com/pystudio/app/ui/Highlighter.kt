package com.pystudio.app.ui

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle

object PyLang {
    val keywords = setOf(
        "False", "None", "True", "and", "as", "assert", "async", "await", "break", "class", "continue", "def",
        "del", "elif", "else", "except", "finally", "for", "from", "global", "if", "import", "in", "is",
        "lambda", "nonlocal", "not", "or", "pass", "raise", "return", "try", "while", "with", "yield"
    )
    val builtins = setOf(
        "print", "len", "range", "int", "str", "float", "list", "dict", "set", "tuple", "bool", "type", "input",
        "open", "isinstance", "issubclass", "enumerate", "zip", "map", "filter", "sorted", "reversed", "sum", "min",
        "max", "abs", "round", "any", "all", "super", "object", "bytes", "bytearray", "callable", "chr", "ord",
        "dir", "divmod", "eval", "exec", "format", "getattr", "setattr", "hasattr", "delattr", "globals", "locals",
        "hash", "hex", "bin", "oct", "id", "iter", "next", "pow", "property", "repr", "slice", "staticmethod",
        "classmethod", "vars", "frozenset", "complex", "memoryview", "help", "compile", "breakpoint",
        "Exception", "BaseException", "ValueError", "TypeError", "KeyError", "IndexError", "AttributeError",
        "NameError", "ImportError", "ModuleNotFoundError", "OSError", "IOError", "FileNotFoundError",
        "RuntimeError", "StopIteration", "ZeroDivisionError", "KeyboardInterrupt", "SystemExit",
        "NotImplementedError", "AssertionError", "OverflowError", "PermissionError", "TimeoutError"
    )
    private val prefixes = setOf("r", "u", "b", "f", "br", "rb", "fr", "rf")
    fun isStringPrefix(s: String) = s.lowercase() in prefixes
}

/** Fast single-pass tokenizer producing a colored AnnotatedString. */
fun highlight(s: String, c: SyntaxColors): AnnotatedString {
    val b = AnnotatedString.Builder(s)
    val n = s.length
    var i = 0
    var prevKw = ""
    fun add(color: androidx.compose.ui.graphics.Color, from: Int, to: Int) {
        if (to > from) b.addStyle(SpanStyle(color = color), from, to)
    }
    fun scanString(start: Int): Int {
        val q = s[start]
        val tq = "$q$q$q"
        if (s.startsWith(tq, start)) {
            var j = start + 3
            while (j < n) {
                if (s[j] == '\\') { j += 2; continue }
                if (s.startsWith(tq, j)) return j + 3
                j++
            }
            return n
        }
        var j = start + 1
        while (j < n && s[j] != '\n') {
            if (s[j] == '\\') { j += 2; continue }
            if (s[j] == q) return j + 1
            j++
        }
        return minOf(j, n)
    }
    while (i < n) {
        val ch = s[i]
        when {
            ch == '#' -> {
                val e = s.indexOf('\n', i).let { if (it < 0) n else it }
                add(c.comment, i, e); i = e
            }
            ch == '"' || ch == '\'' -> {
                val e = minOf(scanString(i), n)
                add(c.string, i, e); i = e; prevKw = ""
            }
            ch.isLetter() || ch == '_' -> {
                var j = i + 1
                while (j < n && (s[j].isLetterOrDigit() || s[j] == '_')) j++
                if (j < n && (s[j] == '"' || s[j] == '\'') && j - i <= 2 && PyLang.isStringPrefix(s.substring(i, j))) {
                    val e = minOf(scanString(j), n)
                    add(c.string, i, e); i = e; prevKw = ""
                } else {
                    val w = s.substring(i, j)
                    when {
                        w in PyLang.keywords -> add(c.keyword, i, j)
                        prevKw == "def" -> add(c.function, i, j)
                        prevKw == "class" -> add(c.cls, i, j)
                        w == "self" || w == "cls" -> add(c.self, i, j)
                        w in PyLang.builtins -> add(c.builtin, i, j)
                        j < n && s[j] == '(' -> add(c.function, i, j)
                    }
                    prevKw = if (w == "def" || w == "class") w else ""
                    i = j
                }
            }
            ch.isDigit() || (ch == '.' && i + 1 < n && s[i + 1].isDigit()) -> {
                var j = i + 1
                while (j < n && (s[j].isLetterOrDigit() || s[j] == '.' || s[j] == '_')) j++
                add(c.number, i, j); i = j
            }
            ch == '@' -> {
                var k = i - 1
                while (k >= 0 && (s[k] == ' ' || s[k] == '\t')) k--
                if (k < 0 || s[k] == '\n') {
                    var j = i + 1
                    while (j < n && (s[j].isLetterOrDigit() || s[j] == '_' || s[j] == '.')) j++
                    add(c.decorator, i, j); i = j
                } else i++
            }
            else -> i++
        }
    }
    return b.toAnnotatedString()
}

/** Per-text analysis shared by the gutter, the visual transformation and folding. */
class CodeInfo(val text: String, val colors: SyntaxColors) {
    val lineStarts: IntArray = run {
        var count = 1
        for (ch in text) if (ch == '\n') count++
        val a = IntArray(count)
        var k = 1
        for ((idx, ch) in text.withIndex()) if (ch == '\n') a[k++] = idx + 1
        a
    }
    val lineCount get() = lineStarts.size
    val styled: AnnotatedString = if (text.length > 400_000) AnnotatedString(text) else highlight(text, colors)
    val folds: Map<Int, Int> by lazy { computeFolds() }

    fun lineEnd(l: Int) = if (l + 1 < lineStarts.size) lineStarts[l + 1] - 1 else text.length

    fun lineOf(offset: Int): Int {
        var lo = 0
        var hi = lineStarts.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (lineStarts[mid] <= offset) lo = mid else hi = mid - 1
        }
        return lo
    }

    /** header line -> last line of its indented block (0-based). */
    private fun computeFolds(): Map<Int, Int> {
        val res = HashMap<Int, Int>()
        val stack = ArrayList<IntArray>() // [line, indent]
        var lastNonBlank = -1
        for (l in 0 until lineCount) {
            val st = lineStarts[l]
            val en = lineEnd(l)
            var k = st
            while (k < en && (text[k] == ' ' || text[k] == '\t')) k++
            if (k >= en || text[k] == '#') continue
            val indent = k - st
            while (stack.isNotEmpty() && indent <= stack.last()[1]) {
                val top = stack.removeAt(stack.size - 1)
                if (lastNonBlank > top[0]) res[top[0]] = lastNonBlank
            }
            var e = en
            while (e > k && (text[e - 1] == ' ' || text[e - 1] == '\t' || text[e - 1] == '\r')) e--
            if (e > k && text[e - 1] == ':') stack.add(intArrayOf(l, indent))
            lastNonBlank = l
        }
        for (top in stack) if (lastNonBlank > top[0]) res[top[0]] = lastNonBlank
        return res
    }
}
