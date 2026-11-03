@file:OptIn(ExperimentalMaterial3Api::class)

package com.pystudio.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pystudio.app.data.*
import com.pystudio.app.state.*
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

// ===========================================================================
// Markdown preview
// ===========================================================================
private sealed interface MdBlock {
    data class Heading(val level: Int, val text: String) : MdBlock
    data class Para(val text: String) : MdBlock
    data class Bullet(val text: String, val marker: String) : MdBlock
    data class Quote(val text: String) : MdBlock
    data class Code(val text: String) : MdBlock
    data object Rule : MdBlock
}

private fun parseMd(src: String): List<MdBlock> {
    val out = ArrayList<MdBlock>()
    val lines = src.split('\n')
    var i = 0
    val para = StringBuilder()
    fun flush() { if (para.isNotBlank()) out.add(MdBlock.Para(para.toString().trim())); para.clear() }
    while (i < lines.size) {
        val raw = lines[i].trimEnd()
        val t = raw.trim()
        when {
            t.startsWith("```") -> {
                flush()
                val code = StringBuilder()
                i++
                while (i < lines.size && !lines[i].trim().startsWith("```")) { code.append(lines[i]).append('\n'); i++ }
                out.add(MdBlock.Code(code.toString().trimEnd()))
            }
            t.isEmpty() -> flush()
            Regex("^#{1,6} .*").matches(t) -> { flush(); val lv = t.takeWhile { it == '#' }.length; out.add(MdBlock.Heading(lv, t.substring(lv).trim())) }
            Regex("^(-{3,}|\\*{3,}|_{3,})$").matches(t) -> { flush(); out.add(MdBlock.Rule) }
            Regex("^[-*+] .*").matches(t) -> { flush(); out.add(MdBlock.Bullet(t.substring(2).trim(), "•")) }
            Regex("^\\d+[.)] .*").matches(t) -> { flush(); val n = t.takeWhile { it.isDigit() }; out.add(MdBlock.Bullet(t.substringAfter(' ').trim(), "$n.")) }
            t.startsWith(">") -> { flush(); out.add(MdBlock.Quote(t.removePrefix(">").trim())) }
            else -> { if (para.isNotEmpty()) para.append(' '); para.append(t) }
        }
        i++
    }
    flush()
    return out
}

private fun inlineMd(s: String, link: Color, codeBg: Color): AnnotatedString = buildAnnotatedString {
    var i = 0
    while (i < s.length) {
        val c = s[i]
        when {
            s.startsWith("**", i) && s.indexOf("**", i + 2) > 0 -> {
                val e = s.indexOf("**", i + 2)
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(s.substring(i + 2, e)) }
                i = e + 2
            }
            c == '`' && s.indexOf('`', i + 1) > 0 -> {
                val e = s.indexOf('`', i + 1)
                withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeBg)) { append(s.substring(i + 1, e)) }
                i = e + 1
            }
            (c == '*' || c == '_') && i + 1 < s.length && s[i + 1] != ' ' && s.indexOf(c, i + 1) > 0 -> {
                val e = s.indexOf(c, i + 1)
                withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(s.substring(i + 1, e)) }
                i = e + 1
            }
            c == '[' && s.indexOf("](", i) > 0 && s.indexOf(')', s.indexOf("](", i)) > 0 -> {
                val mid = s.indexOf("](", i)
                val e = s.indexOf(')', mid)
                withStyle(SpanStyle(color = link, textDecoration = TextDecoration.Underline)) { append(s.substring(i + 1, mid)) }
                i = e + 1
            }
            else -> { append(c); i++ }
        }
    }
}

@Composable
fun MarkdownScreen(vm: AppViewModel, back: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val text = vm.activeTab?.value?.text ?: ""
    val blocks = remember(text) { parseMd(text) }
    ScreenFrame("Markdown preview · ${vm.activeTab?.name ?: ""}", back) {
        SelectionContainer {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(blocks) { b ->
                    when (b) {
                        is MdBlock.Heading -> Text(
                            inlineMd(b.text, cs.primary, cs.surfaceVariant), fontWeight = FontWeight.Bold,
                            fontSize = when (b.level) { 1 -> 26.sp; 2 -> 22.sp; 3 -> 19.sp; 4 -> 17.sp; else -> 15.sp }
                        )
                        is MdBlock.Para -> Text(inlineMd(b.text, cs.primary, cs.surfaceVariant), fontSize = 15.sp, lineHeight = 22.sp)
                        is MdBlock.Bullet -> Row { Text(b.marker, Modifier.width(24.dp)); Text(inlineMd(b.text, cs.primary, cs.surfaceVariant), fontSize = 15.sp) }
                        is MdBlock.Quote -> Row(Modifier.height(IntrinsicSize.Min)) {
                            Box(Modifier.width(3.dp).fillMaxHeight().background(cs.primary))
                            Text(inlineMd(b.text, cs.primary, cs.surfaceVariant), Modifier.padding(start = 10.dp), color = cs.onSurfaceVariant, fontStyle = FontStyle.Italic)
                        }
                        is MdBlock.Code -> Surface(shape = RoundedCornerShape(10.dp), color = cs.surfaceVariant) {
                            Text(b.text, Modifier.horizontalScroll(rememberScrollState()).padding(12.dp), fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                        }
                        is MdBlock.Rule -> HorizontalDivider()
                    }
                }
            }
        }
    }
}

// ===========================================================================
// JSON viewer
// ===========================================================================
@Composable
fun JsonScreen(vm: AppViewModel, back: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val text = vm.activeTab?.value?.text ?: ""
    val parsed = remember(text) { runCatching { JSONTokener(text).nextValue() } }
    ScreenFrame("JSON viewer · ${vm.activeTab?.name ?: ""}", back) {
        val err = parsed.exceptionOrNull()
        if (err != null) {
            Text("Invalid JSON: ${err.message}", color = cs.error, modifier = Modifier.padding(16.dp))
        } else {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState()).padding(8.dp)) {
                JsonNode(null, parsed.getOrNull(), 0)
            }
        }
    }
}

@Composable
private fun JsonNode(key: String?, v: Any?, depth: Int) {
    val cs = MaterialTheme.colorScheme
    val syn = LocalSyntax.current
    val mono = FontFamily.Monospace
    val keyText = if (key != null) "$key: " else ""
    when (v) {
        is JSONObject -> {
            var open by remember { mutableStateOf(depth < 2) }
            val keys = remember(v) { v.keys().asSequence().toList() }
            Row(Modifier.clickable { open = !open }.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(if (open) Icons.Default.ExpandMore else Icons.Default.ChevronRight, null, Modifier.size(18.dp))
                Text("$keyText{${keys.size}}", fontFamily = mono, fontSize = 13.sp, color = syn.cls)
            }
            if (open) Column(Modifier.padding(start = 18.dp)) {
                keys.take(300).forEach { k -> JsonNode(k, v.opt(k), depth + 1) }
                if (keys.size > 300) Text("… ${keys.size - 300} more", fontSize = 12.sp, color = cs.onSurfaceVariant)
            }
        }
        is JSONArray -> {
            var open by remember { mutableStateOf(depth < 2) }
            Row(Modifier.clickable { open = !open }.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(if (open) Icons.Default.ExpandMore else Icons.Default.ChevronRight, null, Modifier.size(18.dp))
                Text("$keyText[${v.length()}]", fontFamily = mono, fontSize = 13.sp, color = syn.function)
            }
            if (open) Column(Modifier.padding(start = 18.dp)) {
                for (i in 0 until minOf(v.length(), 300)) JsonNode("$i", v.opt(i), depth + 1)
                if (v.length() > 300) Text("… ${v.length() - 300} more", fontSize = 12.sp, color = cs.onSurfaceVariant)
            }
        }
        else -> {
            val (shown, color) = when {
                v == null || v == JSONObject.NULL -> "null" to syn.keyword
                v is String -> "\"$v\"" to syn.string
                v is Boolean -> v.toString() to syn.keyword
                else -> v.toString() to syn.number
            }
            Row(Modifier.padding(start = 18.dp, top = 2.dp, bottom = 2.dp)) {
                Text(keyText, fontFamily = mono, fontSize = 13.sp, color = syn.self)
                Text(shown, fontFamily = mono, fontSize = 13.sp, color = color)
            }
        }
    }
}

// ===========================================================================
// Snippets
// ===========================================================================
private data class Snippet(val title: String, val body: String, val user: Boolean = false)

private val builtinSnippets = listOf(
    Snippet("for loop", "for i in range(10):\n    print(i)\n"),
    Snippet("while loop", "while True:\n    line = input(\"> \")\n    if line == \"q\":\n        break\n"),
    Snippet("function", "def name(arg1, arg2):\n    \"\"\"Describe the function.\"\"\"\n    return arg1 + arg2\n"),
    Snippet("class", "class Name:\n    def __init__(self, value):\n        self.value = value\n\n    def __repr__(self):\n        return f\"Name({self.value!r})\"\n"),
    Snippet("dataclass", "from dataclasses import dataclass\n\n\n@dataclass\nclass Point:\n    x: float = 0.0\n    y: float = 0.0\n"),
    Snippet("main guard", "def main():\n    pass\n\n\nif __name__ == \"__main__\":\n    main()\n"),
    Snippet("try / except", "try:\n    result = 1 / 0\nexcept ZeroDivisionError as e:\n    print(\"Error:\", e)\nelse:\n    print(result)\nfinally:\n    print(\"done\")\n"),
    Snippet("read file", "with open(\"file.txt\", encoding=\"utf-8\") as f:\n    text = f.read()\n"),
    Snippet("write file", "with open(\"out.txt\", \"w\", encoding=\"utf-8\") as f:\n    f.write(\"hello\\n\")\n"),
    Snippet("JSON load / dump", "import json\n\nwith open(\"data.json\") as f:\n    data = json.load(f)\n\nwith open(\"data.json\", \"w\") as f:\n    json.dump(data, f, indent=2)\n"),
    Snippet("list comprehension", "squares = [n * n for n in range(10) if n % 2 == 0]\n"),
    Snippet("dict comprehension", "lookup = {k: v for k, v in zip(keys, values)}\n"),
    Snippet("argparse", "import argparse\n\nparser = argparse.ArgumentParser(description=\"...\")\nparser.add_argument(\"name\")\nargs = parser.parse_args()\n"),
    Snippet("HTTP request (urllib)", "import json\nfrom urllib.request import urlopen\n\nwith urlopen(\"https://api.github.com\") as r:\n    print(json.load(r))\n"),
    Snippet("threading", "import threading\n\n\ndef work(n):\n    print(\"worker\", n)\n\n\nthreads = [threading.Thread(target=work, args=(i,)) for i in range(3)]\nfor t in threads:\n    t.start()\nfor t in threads:\n    t.join()\n"),
    Snippet("asyncio", "import asyncio\n\n\nasync def main():\n    await asyncio.sleep(1)\n    print(\"done\")\n\n\nasyncio.run(main())\n"),
    Snippet("unittest", "import unittest\n\n\nclass TestAdd(unittest.TestCase):\n    def test_add(self):\n        self.assertEqual(1 + 1, 2)\n\n\nif __name__ == \"__main__\":\n    unittest.main()\n"),
    Snippet("sqlite3", "import sqlite3\n\ncon = sqlite3.connect(\"app.db\")\ncon.execute(\"CREATE TABLE IF NOT EXISTS t (id INTEGER PRIMARY KEY, name TEXT)\")\ncon.execute(\"INSERT INTO t (name) VALUES (?)\", (\"PyStudio\",))\ncon.commit()\nprint(con.execute(\"SELECT * FROM t\").fetchall())\n")
)

@Composable
fun SnippetsScreen(vm: AppViewModel, back: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    var user by remember { mutableStateOf(loadUserSnippets(vm.prefs.userSnippets)) }
    var saveDlg by remember { mutableStateOf(false) }
    val t = vm.activeTab
    val selection = t?.let { it.value.text.substring(it.value.selection.min, it.value.selection.max) } ?: ""

    ScreenFrame("Code snippets", back, actions = {
        IconBtn(Icons.Default.Add, "Save selection as snippet", { if (selection.isBlank()) vm.toast("Select some code in the editor first") else saveDlg = true })
    }) {
        LazyColumn(Modifier.fillMaxSize()) {
            if (user.isNotEmpty()) item { SectionTitle("My snippets") }
            items(user, key = { "u" + it.title }) { s -> SnippetRow(s, cs, onInsert = { insert(vm, t, s, back) }, onDelete = {
                user = user.filter { it.title != s.title }
                vm.prefs.userSnippets = saveUserSnippets(user)
            }) }
            item { SectionTitle("Built-in") }
            items(builtinSnippets, key = { "b" + it.title }) { s -> SnippetRow(s, cs, onInsert = { insert(vm, t, s, back) }, onDelete = null) }
        }
    }
    if (saveDlg) InputDialog("Snippet name", "", "Name", "Save", { saveDlg = false }) { name ->
        if (name.isNotBlank()) {
            user = user.filter { it.title != name.trim() } + Snippet(name.trim(), selection, true)
            vm.prefs.userSnippets = saveUserSnippets(user)
        }
    }
}

private fun insert(vm: AppViewModel, t: EditorTab?, s: Snippet, back: () -> Unit) {
    if (t == null) { vm.toast("Open a file first"); return }
    vm.insertText(t, s.body)
    back()
}

@Composable
private fun SnippetRow(s: Snippet, cs: ColorScheme, onInsert: () -> Unit, onDelete: (() -> Unit)?) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).clip(RoundedCornerShape(12.dp)).background(cs.surfaceVariant)
            .clickable { onInsert() }.padding(12.dp),
        verticalAlignment = Alignment.Top
    ) {
        Column(Modifier.weight(1f)) {
            Text(s.title, fontWeight = FontWeight.Medium)
            Text(s.body.trim().lines().take(3).joinToString("\n"), fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = cs.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
        if (onDelete != null) IconBtn(Icons.Default.Delete, "Delete snippet", onDelete, size = 32, tint = cs.error)
    }
}

private fun loadUserSnippets(json: String): List<Snippet> = try {
    val a = JSONArray(json)
    List(a.length()) { i -> a.getJSONObject(i).let { Snippet(it.getString("t"), it.getString("b"), true) } }
} catch (e: Exception) { emptyList() }

private fun saveUserSnippets(list: List<Snippet>): String {
    val a = JSONArray()
    list.forEach { a.put(JSONObject().put("t", it.title).put("b", it.body)) }
    return a.toString()
}

// ===========================================================================
// Documentation viewer (pydoc, offline)
// ===========================================================================
@Composable
fun DocsScreen(vm: AppViewModel, back: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    var name by remember { mutableStateOf("print") }
    var query by remember { mutableStateOf("print") }
    var text by remember { mutableStateOf("Loading…") }
    var modules by remember { mutableStateOf<List<String>>(emptyList()) }
    LaunchedEffect(name, vm.pythonReady) {
        if (!vm.pythonReady) { text = "Python runtime is starting…"; return@LaunchedEffect }
        text = "Loading…"
        text = PyBridge.doc(name)
    }
    LaunchedEffect(vm.pythonReady) { if (vm.pythonReady) modules = PyBridge.stdlibModules() }
    val quick = listOf("print", "str", "list", "dict", "os", "sys", "json", "re", "math", "random", "datetime", "pathlib", "collections", "itertools", "functools", "typing")

    ScreenFrame("Documentation", back) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            CompactField(
                query, { query = it }, Modifier.weight(1f), "module, class or function, e.g. os.path.join", mono = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search, autoCorrect = false, capitalization = KeyboardCapitalization.None),
                keyboardActions = KeyboardActions(onSearch = { name = query.trim() })
            )
            IconBtn(Icons.Default.Search, "Look up", { name = query.trim() })
        }
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 10.dp)) {
            quick.forEach { q -> ToggleChip(q, name == q) { query = q; name = q } }
        }
        if (modules.isNotEmpty()) {
            Text("${modules.size} standard-library modules available offline", fontSize = 11.sp, color = cs.onSurfaceVariant, modifier = Modifier.padding(horizontal = 14.dp, vertical = 2.dp))
        }
        SelectionContainer(Modifier.weight(1f)) {
            Text(
                text, fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 17.sp,
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState()).padding(12.dp)
            )
        }
    }
}
