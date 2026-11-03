@file:OptIn(ExperimentalMaterial3Api::class)

package com.pystudio.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pystudio.app.data.*
import com.pystudio.app.state.*
import java.io.File

@Composable
fun BottomPanel(vm: AppViewModel, host: Host, modifier: Modifier) {
    val cs = MaterialTheme.colorScheme
    val clip = LocalClipboardManager.current
    val problems = vm.tabs.sumOf { t -> t.diags.count { it.sev >= 1 } }
    Column(modifier.fillMaxWidth().background(cs.background)) {
        HorizontalDivider(color = cs.outlineVariant)
        Row(Modifier.fillMaxWidth().background(cs.surface).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()).padding(vertical = 4.dp)) {
                ToggleChip("Console", vm.panel == Panel.Console) { vm.panel = Panel.Console }
                ToggleChip("Problems" + if (problems > 0) " $problems" else "", vm.panel == Panel.Problems) { vm.panel = Panel.Problems }
                ToggleChip("Terminal", vm.panel == Panel.Terminal) { vm.panel = Panel.Terminal; vm.term.banner() }
                ToggleChip("Debug", vm.panel == Panel.Debug) { vm.panel = Panel.Debug }
            }
            when (vm.panel) {
                Panel.Console -> {
                    IconBtn(Icons.Default.ContentCopy, "Copy output", { clip.setText(AnnotatedString(vm.console.text())); vm.toast("Output copied") }, size = 34)
                    IconBtn(Icons.Default.Delete, "Clear", { vm.console.clear() }, size = 34)
                }
                Panel.Terminal -> IconBtn(Icons.Default.Delete, "Clear", { vm.term.buf.clear() }, size = 34)
                else -> {}
            }
            IconBtn(if (vm.panelMax) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowUp, "Resize", { vm.panelMax = !vm.panelMax }, size = 34)
            IconBtn(Icons.Default.Close, "Close panel", { vm.panel = Panel.None; vm.panelMax = false }, size = 34)
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (vm.panel) {
                Panel.Console -> ConsolePanel(vm, host)
                Panel.Problems -> ProblemsPanel(vm)
                Panel.Terminal -> TerminalPanel(vm)
                Panel.Debug -> DebugPanel(vm)
                Panel.None -> {}
            }
        }
    }
}

@Composable
private fun ConsolePanel(vm: AppViewModel, host: Host) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (vm.console.lines.isEmpty()) {
                Text("Press ▶ to run the current file. Output and input() appear here.", color = cs.onSurfaceVariant, fontSize = 13.sp, modifier = Modifier.padding(12.dp))
            }
            LineList(vm.console)
        }
        if (vm.running) {
            val focus = remember { FocusRequester() }
            var text by remember { mutableStateOf("") }
            LaunchedEffect(vm.awaitingInput) { if (vm.awaitingInput) { try { focus.requestFocus() } catch (ignored: Exception) { } } }
            Row(Modifier.fillMaxWidth().background(cs.surface).padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
                CompactField(
                    text, { text = it }, Modifier.weight(1f),
                    if (vm.awaitingInput) "Program is waiting for input…" else "Type input (sent when requested)", mono = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send, autoCorrect = false, capitalization = KeyboardCapitalization.None),
                    keyboardActions = KeyboardActions(onSend = { vm.submitInput(text); text = "" }),
                    focusRequester = focus
                )
                IconBtn(Icons.Default.Send, "Send input", { vm.submitInput(text); text = "" })
                IconBtn(Icons.Default.Stop, "Stop", { vm.stop() }, tint = Color(0xFFF14C4C))
            }
            if (vm.forceStopHint) {
                Row(Modifier.fillMaxWidth().background(cs.errorContainer).padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("The program does not respond to Stop (blocked in native code).", fontSize = 12.sp, color = cs.onErrorContainer, modifier = Modifier.weight(1f))
                    TextButton(onClick = { vm.saveAllSync(); host.restart() }) { Text("Force restart") }
                }
            }
        }
    }
}

@Composable
private fun ProblemsPanel(vm: AppViewModel) {
    val cs = MaterialTheme.colorScheme
    val syn = LocalSyntax.current
    val list = vm.tabs.flatMap { t -> t.diags.map { t to it } }
        .sortedWith(compareBy({ -it.second.sev }, { it.first.name }, { it.second.line }))
    if (list.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No problems detected in open files ✓", color = cs.onSurfaceVariant, fontSize = 13.sp)
        }
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        items(list) { (t, d) ->
            Row(
                Modifier.fillMaxWidth().clickable { vm.openFile(t.file); vm.goToLine(d.line, d.col) }.padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.Top
            ) {
                Icon(
                    when (d.sev) { 2 -> Icons.Default.Error; 1 -> Icons.Default.Warning; else -> Icons.Default.Info }, null,
                    Modifier.size(18.dp).padding(top = 2.dp), tint = when (d.sev) { 2 -> syn.error; 1 -> syn.warning; else -> syn.info }
                )
                Column(Modifier.weight(1f).padding(start = 8.dp)) {
                    Text(d.msg, fontSize = 13.sp)
                    Text("${t.name}:${d.line}:${d.col}  ·  ${d.code}", fontSize = 11.sp, color = cs.onSurfaceVariant)
                    if (d.fix.isNotEmpty()) Text("💡 ${d.fix}", fontSize = 11.sp, color = cs.secondary)
                }
                if (d.op.isNotEmpty()) TextButton(onClick = { vm.applyFix(t, d) }) { Text("Fix") }
            }
            HorizontalDivider(color = cs.outlineVariant.copy(alpha = 0.5f))
        }
    }
}

@Composable
private fun TerminalPanel(vm: AppViewModel) {
    val term = vm.term
    val cs = MaterialTheme.colorScheme
    var text by remember { mutableStateOf("") }
    val submit = { if (!term.busy) { term.submit(text); text = "" } }
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth()) { LineList(term.buf) }
        Row(Modifier.fillMaxWidth().background(cs.surface).padding(horizontal = 6.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(term.prompt, fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = cs.primary, maxLines = 1, modifier = Modifier.widthIn(max = 110.dp), overflow = TextOverflow.Ellipsis)
            CompactField(
                text, { text = it }, Modifier.weight(1f), "command", mono = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send, autoCorrect = false, capitalization = KeyboardCapitalization.None),
                keyboardActions = KeyboardActions(onSend = { submit() })
            )
            IconBtn(Icons.Default.KeyboardArrowUp, "Previous command", { term.historyUp()?.let { text = it } }, size = 34)
            IconBtn(Icons.Default.KeyboardArrowDown, "Next command", { term.historyDown()?.let { text = it } }, size = 34)
            IconBtn(Icons.Default.Send, "Run", { submit() }, size = 34)
        }
    }
}

@Composable
private fun DebugPanel(vm: AppViewModel) {
    val cs = MaterialTheme.colorScheme
    var sub by remember { mutableIntStateOf(0) }
    val p = vm.pause
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconBtn(Icons.Default.PlayArrow, "Continue", { vm.dbg("continue") }, tint = Color(0xFF4CAF50), enabled = p != null)
            IconBtn(Icons.Default.ArrowForward, "Step over", { vm.dbg("over") }, enabled = p != null)
            IconBtn(Icons.Default.ArrowDownward, "Step into", { vm.dbg("into") }, enabled = p != null)
            IconBtn(Icons.Default.ArrowUpward, "Step out", { vm.dbg("out") }, enabled = p != null)
            IconBtn(Icons.Default.Stop, "Stop debugging", { vm.stop() }, tint = Color(0xFFF14C4C), enabled = vm.running)
            Text(
                when {
                    !vm.running -> "Tap the gutter to set breakpoints, then press the bug icon."
                    p == null -> "Running…"
                    else -> "Paused at ${File(p.file).name}:${p.line}"
                },
                fontSize = 12.sp, color = cs.onSurfaceVariant, modifier = Modifier.padding(start = 6.dp), maxLines = 2
            )
        }
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 6.dp)) {
            ToggleChip("Variables", sub == 0) { sub = 0 }
            ToggleChip("Call stack", sub == 1) { sub = 1 }
            ToggleChip("Output", sub == 2) { sub = 2 }
            ToggleChip("Debug console", sub == 3) { sub = 3 }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (sub) {
                0 -> VariablesView(p)
                1 -> StackView(vm, p)
                2 -> LineList(vm.console)
                else -> DebugConsole(vm)
            }
        }
    }
}

@Composable
private fun VariablesView(p: PauseEv?) {
    val cs = MaterialTheme.colorScheme
    if (p == null) {
        Text("Variables are shown while the program is paused at a breakpoint.", color = cs.onSurfaceVariant, fontSize = 13.sp, modifier = Modifier.padding(12.dp))
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        item { SectionTitle("Locals") }
        items(p.locals) { VarRow(it) }
        if (p.globals.isNotEmpty()) {
            item { SectionTitle("Globals") }
            items(p.globals) { VarRow(it) }
        }
    }
}

@Composable
private fun VarRow(v: DVar) {
    val cs = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 3.dp)) {
        Text(v.name, fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = cs.primary, fontWeight = FontWeight.Medium, modifier = Modifier.widthIn(min = 70.dp, max = 120.dp))
        Text(v.type, fontSize = 11.sp, color = cs.onSurfaceVariant, modifier = Modifier.widthIn(min = 50.dp, max = 80.dp).padding(horizontal = 6.dp))
        Text(v.value, fontFamily = FontFamily.Monospace, fontSize = 12.sp, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun StackView(vm: AppViewModel, p: PauseEv?) {
    val cs = MaterialTheme.colorScheme
    if (p == null) {
        Text("The call stack is shown while paused.", color = cs.onSurfaceVariant, fontSize = 13.sp, modifier = Modifier.padding(12.dp))
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        items(p.stack) { f ->
            Column(Modifier.fillMaxWidth().clickable { vm.openFile(File(f.file), f.line) }.padding(horizontal = 12.dp, vertical = 6.dp)) {
                Text(f.func, fontFamily = FontFamily.Monospace, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                Text("${File(f.file).name}:${f.line}", fontSize = 11.sp, color = cs.onSurfaceVariant)
            }
            HorizontalDivider(color = cs.outlineVariant.copy(alpha = 0.5f))
        }
    }
}

@Composable
private fun DebugConsole(vm: AppViewModel) {
    val cs = MaterialTheme.colorScheme
    var text by remember { mutableStateOf("") }
    val go = { if (vm.pause == null) vm.toast("Pause at a breakpoint to evaluate expressions") else { vm.dbgEval(text); text = "" } }
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth()) { LineList(vm.dbgLog) }
        Row(Modifier.fillMaxWidth().background(cs.surface).padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(">>> ", fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = cs.primary)
            CompactField(
                text, { text = it }, Modifier.weight(1f), "evaluate expression", mono = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send, autoCorrect = false, capitalization = KeyboardCapitalization.None),
                keyboardActions = KeyboardActions(onSend = { go() })
            )
            IconBtn(Icons.Default.Send, "Evaluate", { go() }, size = 34)
        }
    }
}
