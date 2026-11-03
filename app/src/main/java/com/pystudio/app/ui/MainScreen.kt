@file:OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)

package com.pystudio.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pystudio.app.data.*
import com.pystudio.app.state.*
import kotlinx.coroutines.launch

@Composable
fun AppRoot(vm: AppViewModel, host: Host) {
    val snack = remember { SnackbarHostState() }
    LaunchedEffect(vm.message) {
        val m = vm.message
        if (m != null) { snack.showSnackbar(m); vm.message = null }
    }
    BackHandler(enabled = vm.screen != Screen.Editor) { vm.screen = Screen.Editor }
    var last by remember { mutableStateOf(Screen.Projects) }
    if (vm.screen != Screen.Editor) last = vm.screen

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).systemBarsPadding().imePadding()) {
        EditorScreen(vm, host)
        AnimatedVisibility(
            visible = vm.screen != Screen.Editor,
            enter = fadeIn() + slideInHorizontally { it / 5 },
            exit = fadeOut() + slideOutHorizontally { it / 5 }
        ) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                val back = { vm.screen = Screen.Editor }
                when (last) {
                    Screen.Packages -> PackagesScreen(vm, back)
                    Screen.Git -> GitScreen(vm, host, back)
                    Screen.Settings -> SettingsScreen(vm, back)
                    Screen.About -> AboutScreen(host, back)
                    Screen.Markdown -> MarkdownScreen(vm, back)
                    Screen.Json -> JsonScreen(vm, back)
                    Screen.Snippets -> SnippetsScreen(vm, back)
                    Screen.Docs -> DocsScreen(vm, back)
                    else -> ProjectsScreen(vm, host, back)
                }
            }
        }
        SnackbarHost(snack, Modifier.align(Alignment.BottomCenter).padding(bottom = 36.dp))
    }
}

@Composable
fun EditorScreen(vm: AppViewModel, host: Host) {
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val imeVisible = WindowInsets.isImeVisible
    val t = vm.activeTab

    BackHandler(enabled = drawer.isOpen) { scope.launch { drawer.close() } }
    BackHandler(enabled = vm.findOpen && !drawer.isOpen && vm.screen == Screen.Editor) { vm.findOpen = false; vm.refreshMatches() }
    BackHandler(enabled = vm.panel != Panel.None && !vm.findOpen && !drawer.isOpen && vm.screen == Screen.Editor) { vm.panel = Panel.None }

    ModalNavigationDrawer(
        drawerState = drawer,
        drawerContent = {
            ModalDrawerSheet(Modifier.width(310.dp)) {
                ExplorerPane(vm, host) { scope.launch { drawer.close() } }
            }
        }
    ) {
        Column(Modifier.fillMaxSize()) {
            TopBar(vm, host) { scope.launch { drawer.open() } }
            if (vm.prefs.showTabs && vm.tabs.isNotEmpty()) TabBar(vm)
            if (vm.findOpen) FindBar(vm)
            val panelMax = vm.panel != Panel.None && vm.panelMax
            if (!panelMax) {
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    if (t == null) EmptyState(vm) else key(t.file.absolutePath) { CodeEditor(vm, t) }
                }
            }
            if (t != null && vm.editorFocused && imeVisible) {
                SuggestionBar(vm)
                if (vm.prefs.symbolBar) SymbolBar(vm, t)
            }
            if (vm.panel != Panel.None) BottomPanel(vm, host, Modifier.weight(if (panelMax) 1f else 0.85f))
            StatusBar(vm)
        }
    }
}

// ---------------------------------------------------------------------------
@Composable
private fun TopBar(vm: AppViewModel, host: Host, openDrawer: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val cs = MaterialTheme.colorScheme
    val t = vm.activeTab
    Surface(color = cs.surface) {
        Row(Modifier.fillMaxWidth().height(50.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconBtn(Icons.Default.Menu, "Explorer", openDrawer)
            Column(Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).clickable { vm.screen = Screen.Projects }.padding(horizontal = 6.dp)) {
                Text(
                    (if (t?.dirty == true) "● " else "") + (t?.name ?: "PyStudio"),
                    style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                Text(
                    vm.project?.name ?: "No project", style = MaterialTheme.typography.labelSmall,
                    color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
            if (vm.running) IconBtn(Icons.Default.Stop, "Stop", { vm.stop() }, tint = Color(0xFFF14C4C))
            else IconBtn(Icons.Default.PlayArrow, "Run", { vm.run(false) }, tint = Color(0xFF4CAF50))
            IconBtn(Icons.Default.BugReport, "Debug", { if (vm.running) vm.toast("Stop the running program first") else vm.run(true) })
            IconBtn(Icons.Default.Search, "Find and replace", { vm.findOpen = !vm.findOpen; vm.refreshMatches() })
            Box {
                IconBtn(Icons.Default.MoreVert, "More", { menu = true })
                MainMenu(vm, host, menu) { menu = false }
            }
        }
    }
}

@Composable
private fun MItem(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    DropdownMenuItem(text = { Text(text) }, leadingIcon = { Icon(icon, null, Modifier.size(20.dp)) }, onClick = onClick)
}

@Composable
private fun MainMenu(vm: AppViewModel, host: Host, open: Boolean, close: () -> Unit) {
    val syn = LocalSyntax.current
    val t = vm.activeTab
    val scope = rememberCoroutineScope()
    DropdownMenu(expanded = open, onDismissRequest = close) {
        MItem("Save all", Icons.Default.Save) { close(); scope.launch { vm.saveAll(); vm.toast("Saved") } }
        MItem("Format code", Icons.Default.FormatAlignLeft) { close(); vm.formatCode() }
        MItem("Undo", Icons.Default.Undo) { close(); t?.let { vm.undo(it) } }
        MItem("Redo", Icons.Default.Redo) { close(); t?.let { vm.redo(it) } }
        MItem("Next problem", Icons.Default.Error) { close(); vm.gotoProblem(true) }
        if (t != null) {
            MItem("Fold all", Icons.Default.UnfoldLess) { close(); vm.foldAll(t, true, CodeInfo(t.value.text, syn).folds.keys) }
            MItem("Unfold all", Icons.Default.UnfoldMore) { close(); vm.foldAll(t, false, emptySet()) }
            HorizontalDivider()
            MItem("Add cursor below", Icons.Default.KeyboardArrowDown) { close(); vm.addCursorVertical(t, true) }
            MItem("Add cursor above", Icons.Default.KeyboardArrowUp) { close(); vm.addCursorVertical(t, false) }
            MItem("Cursors at line ends of selection", Icons.Default.Edit) { close(); vm.cursorsAtLineEnds(t) }
            if (t.cursors.isNotEmpty()) MItem("Clear extra cursors", Icons.Default.Clear) { close(); vm.clearCursors(t) }
        }
        HorizontalDivider()
        MItem("Console", Icons.Default.Terminal) { close(); vm.panel = Panel.Console }
        MItem("Problems", Icons.Default.Warning) { close(); vm.panel = Panel.Problems }
        MItem("Terminal", Icons.Default.Code) { close(); vm.panel = Panel.Terminal; vm.term.banner() }
        MItem("Debug panel", Icons.Default.BugReport) { close(); vm.panel = Panel.Debug }
        HorizontalDivider()
        MItem("Package manager", Icons.Default.Inventory2) { close(); vm.screen = Screen.Packages; vm.pkgs.refresh() }
        MItem("Git", Icons.Default.AccountTree) { close(); vm.git.refresh(vm.project); vm.screen = Screen.Git }
        if (t?.name?.endsWith(".md") == true) MItem("Markdown preview", Icons.Default.Description) { close(); vm.screen = Screen.Markdown }
        if (t?.name?.endsWith(".json") == true) MItem("JSON viewer", Icons.Default.Code) { close(); vm.screen = Screen.Json }
        MItem("Code snippets", Icons.Default.ContentCopy) { close(); vm.screen = Screen.Snippets }
        MItem("Documentation", Icons.Default.MenuBook) { close(); vm.screen = Screen.Docs }
        HorizontalDivider()
        MItem("Projects", Icons.Default.Folder) { close(); vm.screen = Screen.Projects }
        MItem("Export project (zip)", Icons.Default.Share) { close(); scope.launch { vm.exportProject()?.let { host.share(it) } } }
        MItem("Settings", Icons.Default.Settings) { close(); vm.screen = Screen.Settings }
        MItem("About & contact", Icons.Default.Info) { close(); vm.screen = Screen.About }
    }
}

@Composable
private fun TabBar(vm: AppViewModel) {
    val cs = MaterialTheme.colorScheme
    val state = rememberLazyListState()
    LaunchedEffect(vm.active, vm.tabs.size) {
        if (vm.tabs.isNotEmpty()) state.animateScrollToItem(vm.active.coerceIn(0, vm.tabs.lastIndex))
    }
    LazyRow(state = state, modifier = Modifier.fillMaxWidth().background(cs.surfaceVariant)) {
        itemsIndexed(vm.tabs, key = { _, t -> t.file.absolutePath }) { i, t ->
            val sel = i == vm.active
            Row(
                Modifier.height(36.dp)
                    .background(if (sel) cs.background else Color.Transparent)
                    .drawBehind { if (sel) drawRect(cs.primary, size = Size(size.width, 2.dp.toPx())) }
                    .clickable { vm.selectTab(i) }
                    .padding(start = 12.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    t.name, fontSize = 13.sp, maxLines = 1,
                    color = if (sel) cs.onSurface else cs.onSurfaceVariant,
                    fontWeight = if (sel) FontWeight.Medium else FontWeight.Normal
                )
                if (t.dirty) Box(Modifier.padding(start = 6.dp).size(7.dp).clip(RoundedCornerShape(50)).background(cs.primary))
                IconButton(onClick = { vm.closeTab(i) }, modifier = Modifier.size(30.dp)) {
                    Icon(Icons.Default.Close, "Close ${t.name}", Modifier.size(15.dp), tint = cs.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun FindBar(vm: AppViewModel) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().background(cs.surface).padding(horizontal = 8.dp, vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CompactField(vm.findQuery, { vm.findQuery = it; vm.onFindChanged() }, Modifier.weight(1f), "Find")
            Text(
                if (vm.findQuery.isEmpty()) "" else if (vm.matches.isEmpty()) "0/0" else "${vm.matchIdx + 1}/${vm.matches.size}",
                fontSize = 12.sp, color = cs.onSurfaceVariant, modifier = Modifier.padding(horizontal = 6.dp)
            )
            IconBtn(Icons.Default.KeyboardArrowUp, "Previous match", { vm.findStep(-1) }, size = 34)
            IconBtn(Icons.Default.KeyboardArrowDown, "Next match", { vm.findStep(1) }, size = 34)
            IconBtn(Icons.Default.Close, "Close find", { vm.findOpen = false; vm.refreshMatches() }, size = 34)
        }
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            CompactField(vm.replaceText, { vm.replaceText = it }, Modifier.weight(1f), "Replace with")
            TextButton(onClick = { vm.replaceCurrent() }) { Text("Replace") }
            TextButton(onClick = { vm.replaceAll() }) { Text("All") }
        }
        Row {
            ToggleChip("Aa  Match case", vm.caseSens) { vm.caseSens = !vm.caseSens; vm.onFindChanged() }
            ToggleChip(".*  Regex", vm.useRegex) { vm.useRegex = !vm.useRegex; vm.onFindChanged() }
        }
    }
}

@Composable
private fun SuggestionBar(vm: AppViewModel) {
    val r = vm.comps ?: return
    val cs = MaterialTheme.colorScheme
    val syn = LocalSyntax.current
    Column(Modifier.fillMaxWidth().background(cs.surfaceContainerHigh)) {
        if (r.sig.isNotEmpty()) {
            Text(
                r.sig, fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = cs.secondary, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp)
            )
        }
        if (r.items.isNotEmpty()) {
            LazyRow(contentPadding = PaddingValues(horizontal = 6.dp), modifier = Modifier.fillMaxWidth()) {
                items(r.items) { item ->
                    val (badge, color) = when (item.type) {
                        "function" -> "ƒ" to syn.function
                        "class" -> "C" to syn.cls
                        "module" -> "M" to syn.builtin
                        "keyword" -> "k" to syn.keyword
                        "param" -> "p" to syn.self
                        "property" -> "P" to syn.self
                        else -> "v" to syn.self
                    }
                    Row(
                        Modifier.padding(end = 4.dp).clip(RoundedCornerShape(8.dp)).clickable { vm.applyCompletion(item) }
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(badge, color = color, fontWeight = FontWeight.Bold, fontSize = 13.sp, modifier = Modifier.padding(end = 5.dp))
                        Text(item.name, fontFamily = FontFamily.Monospace, fontSize = 14.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun SymbolBar(vm: AppViewModel, t: EditorTab) {
    val cs = MaterialTheme.colorScheme
    val keys = listOf(
        "(", ")", "[", "]", "{", "}", "\"", "'", ":", "=", "#", "_", ".", ",", "+", "-", "*", "/", "<", ">", "!", "&", "|", "\\", "%", "~", "@", "$", "^", ";"
    )
    Row(Modifier.fillMaxWidth().background(cs.surfaceContainerHigh).horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp, vertical = 2.dp)) {
        SymKey("⇥") { vm.insertText(t, " ".repeat(vm.prefs.tabSize)) }
        SymKey("↶") { vm.undo(t) }
        SymKey("↷") { vm.redo(t) }
        for (k in keys) SymKey(k) { vm.insertText(t, k) }
    }
}

@Composable
private fun SymKey(label: String, onClick: () -> Unit) {
    Box(
        Modifier.padding(horizontal = 2.dp).size(width = 38.dp, height = 36.dp).clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant).clickable { onClick() },
        contentAlignment = Alignment.Center
    ) { Text(label, fontFamily = FontFamily.Monospace, fontSize = 16.sp) }
}

@Composable
private fun StatusBar(vm: AppViewModel) {
    val cs = MaterialTheme.colorScheme
    val t = vm.activeTab
    val errors = t?.diags?.count { it.sev == 2 } ?: 0
    val warns = t?.diags?.count { it.sev == 1 } ?: 0
    var line = 1
    var col = 1
    if (t != null) {
        val text = t.value.text
        val pos = t.value.selection.start.coerceIn(0, text.length)
        for (i in 0 until pos) { if (text[i] == '\n') { line++; col = 1 } else col++ }
    }
    Surface(color = cs.primaryContainer, contentColor = cs.onPrimaryContainer) {
        Row(
            Modifier.fillMaxWidth().height(26.dp).padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(Modifier.clickable { vm.panel = if (vm.panel == Panel.Problems) Panel.None else Panel.Problems }, verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Error, null, Modifier.size(13.dp)); Text(" $errors", fontSize = 11.sp)
                Spacer(Modifier.width(8.dp))
                Icon(Icons.Default.Warning, null, Modifier.size(13.dp)); Text(" $warns", fontSize = 11.sp)
            }
            Spacer(Modifier.weight(1f))
            if (vm.running) Text(if (vm.debugging) "● debugging" else "● running", fontSize = 11.sp)
            if (t != null) Text("Ln $line, Col $col", fontSize = 11.sp)
            Text("Spaces: ${vm.prefs.tabSize}", fontSize = 11.sp)
            Text(if (vm.pythonReady) "Python ${vm.pyVersion}" else "Python starting…", fontSize = 11.sp)
        }
    }
}

@Composable
private fun EmptyState(vm: AppViewModel) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(Icons.Default.Code, null, Modifier.size(56.dp), tint = cs.primary)
        Spacer(Modifier.height(12.dp))
        Text("PyStudio", style = MaterialTheme.typography.headlineSmall)
        Text("Open a file from the explorer or create a new project.", color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp, bottom = 16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = { vm.screen = Screen.Projects }) { Text("Projects") }
            OutlinedButton(onClick = { vm.project?.let { vm.newFile(it, "main.py") } }) { Text("New main.py") }
        }
    }
}
