@file:OptIn(ExperimentalMaterial3Api::class)

package com.pystudio.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pystudio.app.data.*
import com.pystudio.app.state.*
import java.io.File

private sealed interface Dlg {
    data class NewFile(val dir: File) : Dlg
    data class NewFolder(val dir: File) : Dlg
    data class Rename(val f: File) : Dlg
    data class Delete(val f: File) : Dlg
}

@Composable
fun ExplorerPane(vm: AppViewModel, host: Host, close: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val p = vm.project
    var dlg by remember { mutableStateOf<Dlg?>(null) }
    val importFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) vm.importFile(uri) }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 10.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("EXPLORER", style = MaterialTheme.typography.labelSmall, color = cs.primary)
                Text(p?.name ?: "No project", style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            IconBtn(Icons.Default.Folder, "Projects", { vm.screen = Screen.Projects; close() })
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp)) {
            IconBtn(Icons.Default.NoteAdd, "New file", { p?.let { dlg = Dlg.NewFile(it) } })
            IconBtn(Icons.Default.CreateNewFolder, "New folder", { p?.let { dlg = Dlg.NewFolder(it) } })
            IconBtn(Icons.Default.Download, "Import file into project", { importFile.launch(arrayOf("*/*")) })
            if (vm.clipboard != null) IconBtn(Icons.Default.ContentCopy, "Paste", { p?.let { vm.paste(it) } }, tint = cs.primary)
            IconBtn(Icons.Default.Refresh, "Refresh", { vm.refreshTree() })
        }
        HorizontalDivider(color = cs.outlineVariant)
        LazyColumn(Modifier.weight(1f)) {
            items(vm.tree, key = { it.file.absolutePath }) { node ->
                NodeRow(vm, node, onOpen = close, onDialog = { dlg = it })
            }
            if (vm.tree.isEmpty()) item { Text("Empty project. Create a file with the buttons above.", color = cs.onSurfaceVariant, fontSize = 13.sp, modifier = Modifier.padding(16.dp)) }
        }
        HorizontalDivider(color = cs.outlineVariant)
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Free & open · no ads", fontSize = 11.sp, color = cs.onSurfaceVariant, modifier = Modifier.weight(1f).padding(start = 8.dp))
            IconBtn(Icons.Default.Send, "Telegram @YogeshBhai729", { host.openUrl(TELEGRAM_URL) }, tint = cs.primary, size = 36)
            IconBtn(Icons.Default.Code, "GitHub", { host.openUrl(GITHUB_URL) }, tint = cs.primary, size = 36)
        }
    }

    when (val d = dlg) {
        is Dlg.NewFile -> InputDialog("New file", "", "File name (e.g. utils.py)", "Create", { dlg = null }) { vm.newFile(d.dir, it.trim()); close() }
        is Dlg.NewFolder -> InputDialog("New folder", "", "Folder name", "Create", { dlg = null }) { vm.newFolder(d.dir, it.trim()) }
        is Dlg.Rename -> InputDialog("Rename", d.f.name, "New name", "Rename", { dlg = null }) { vm.renameFile(d.f, it.trim()) }
        is Dlg.Delete -> ConfirmDialog("Delete ${d.f.name}?", if (d.f.isDirectory) "The folder and everything inside it will be deleted." else "This file will be deleted.", "Delete", { dlg = null }) { vm.deleteFile(d.f) }
        null -> {}
    }
}

const val TELEGRAM_URL = "https://t.me/YogeshBhai729"
const val GITHUB_URL = "https://github.com/hgcheat094-max"

@Composable
private fun NodeRow(vm: AppViewModel, node: FileNode, onOpen: () -> Unit, onDialog: (Dlg) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val syn = LocalSyntax.current
    var menu by remember { mutableStateOf(false) }
    val f = node.file
    val open = vm.expanded.contains(f.absolutePath)
    val selected = vm.activeTab?.file?.absolutePath == f.absolutePath
    Row(
        Modifier.fillMaxWidth().height(40.dp)
            .background(if (selected) cs.primaryContainer.copy(alpha = 0.45f) else Color.Transparent)
            .clickable { if (node.isDir) vm.toggleDir(f) else { vm.openFile(f); onOpen() } }
            .padding(start = (8 + node.depth * 14).dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (node.isDir) {
            Icon(if (open) Icons.Default.ExpandMore else Icons.Default.ChevronRight, null, Modifier.size(18.dp), tint = cs.onSurfaceVariant)
            Icon(if (open) Icons.Default.FolderOpen else Icons.Default.Folder, null, Modifier.padding(horizontal = 6.dp).size(20.dp), tint = syn.function)
        } else {
            Spacer(Modifier.width(18.dp))
            val (icon, tint) = when (f.extension.lowercase()) {
                "py" -> Icons.Default.Code to syn.info
                "md", "txt" -> Icons.Default.Description to cs.onSurfaceVariant
                "json", "toml", "yaml", "yml" -> Icons.Default.Description to syn.warning
                else -> Icons.Default.InsertDriveFile to cs.onSurfaceVariant
            }
            Icon(icon, null, Modifier.padding(horizontal = 6.dp).size(20.dp), tint = tint)
        }
        Text(f.name, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Box {
            IconBtn(Icons.Default.MoreVert, "Actions for ${f.name}", { menu = true }, size = 36)
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                if (node.isDir) {
                    DropdownMenuItem(text = { Text("New file here") }, onClick = { menu = false; onDialog(Dlg.NewFile(f)) })
                    DropdownMenuItem(text = { Text("New folder here") }, onClick = { menu = false; onDialog(Dlg.NewFolder(f)) })
                    if (vm.clipboard != null) DropdownMenuItem(text = { Text("Paste here") }, onClick = { menu = false; vm.paste(f) })
                    HorizontalDivider()
                }
                DropdownMenuItem(text = { Text("Rename") }, onClick = { menu = false; onDialog(Dlg.Rename(f)) })
                DropdownMenuItem(text = { Text("Duplicate") }, onClick = { menu = false; vm.duplicate(f) })
                DropdownMenuItem(text = { Text("Copy") }, onClick = { menu = false; vm.copyToClipboard(f, false) })
                DropdownMenuItem(text = { Text("Cut (move)") }, onClick = { menu = false; vm.copyToClipboard(f, true) })
                DropdownMenuItem(text = { Text("Delete", color = cs.error) }, onClick = { menu = false; onDialog(Dlg.Delete(f)) })
            }
        }
    }
}

// ---------------------------------------------------------------------------
@Composable
fun ProjectsScreen(vm: AppViewModel, host: Host, back: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    var newDlg by remember { mutableStateOf(false) }
    var cloneDlg by remember { mutableStateOf(false) }
    var rename by remember { mutableStateOf<File?>(null) }
    var delete by remember { mutableStateOf<File?>(null) }
    var projects by remember { mutableStateOf(vm.store.projects()) }
    val zipPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) vm.importZip(uri) }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> if (uri != null) vm.importFolder(uri) }
    LaunchedEffect(vm.project, vm.git.status) { projects = vm.store.projects() }

    ScreenFrame("Projects", back) {
        LazyColumn(Modifier.fillMaxSize()) {
            item {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { newDlg = true }, Modifier.weight(1f)) { Icon(Icons.Default.Add, null, Modifier.size(18.dp)); Text(" New") }
                        OutlinedButton(onClick = { cloneDlg = true }, Modifier.weight(1f)) { Icon(Icons.Default.AccountTree, null, Modifier.size(18.dp)); Text(" Clone") }
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { zipPicker.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")) }, Modifier.weight(1f)) { Text("Import .zip") }
                        OutlinedButton(onClick = { folderPicker.launch(null) }, Modifier.weight(1f)) { Text("Import folder") }
                    }
                    if (vm.git.status.isNotEmpty()) Text(vm.git.status, fontSize = 12.sp, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
                }
            }
            val recent = vm.store.recent()
            if (recent.isNotEmpty()) {
                item { SectionTitle("Recent") }
                items(recent, key = { "r" + it.absolutePath }) { p -> ProjectRow(vm, p, host, { rename = it }, { delete = it }, back) }
            }
            item { SectionTitle("All projects (${projects.size})") }
            items(projects, key = { "a" + it.absolutePath }) { p -> ProjectRow(vm, p, host, { rename = it }, { delete = it }, back) }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }

    if (newDlg) NewProjectDialog(onDismiss = { newDlg = false }) { name, tpl -> vm.createProject(name, tpl); back() }
    if (cloneDlg) InputDialog("Clone repository", "https://github.com/", "HTTPS URL", "Clone", { cloneDlg = false }) { url ->
        vm.git.clone(url, vm.store.root) { dir -> vm.openProject(dir); back() }
    }
    rename?.let { f -> InputDialog("Rename project", f.name, "Name", "Rename", { rename = null }) { vm.renameProject(f, it); projects = vm.store.projects() } }
    delete?.let { f -> ConfirmDialog("Delete ${f.name}?", "The whole project will be permanently deleted from this device.", "Delete", { delete = null }) { vm.deleteProject(f); projects = vm.store.projects() } }
}

@Composable
private fun ProjectRow(vm: AppViewModel, p: File, host: Host, onRename: (File) -> Unit, onDelete: (File) -> Unit, back: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    var menu by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val current = vm.project?.absolutePath == p.absolutePath
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 3.dp).clip(RoundedCornerShape(14.dp))
            .background(if (current) cs.primaryContainer.copy(alpha = 0.5f) else cs.surfaceVariant)
            .clickable { vm.openProject(p); back() }.padding(start = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Default.Folder, null, tint = cs.primary)
        Column(Modifier.weight(1f).padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text(p.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${p.walkTopDown().count { it.isFile }} files" + if (current) " · open" else "", fontSize = 12.sp, color = cs.onSurfaceVariant)
        }
        Box {
            IconBtn(Icons.Default.MoreVert, "Project actions", { menu = true })
            DropdownMenu(menu, { menu = false }) {
                DropdownMenuItem(text = { Text("Open") }, onClick = { menu = false; vm.openProject(p); back() })
                DropdownMenuItem(text = { Text("Rename") }, onClick = { menu = false; onRename(p) })
                DropdownMenuItem(text = { Text("Export as .zip") }, onClick = {
                    menu = false
                    scope.launch {
                        val z = withContext(Dispatchers.IO) { runCatching { vm.store.exportZip(p) }.getOrNull() }
                        if (z != null) host.share(z) else vm.toast("Export failed")
                    }
                })
                DropdownMenuItem(text = { Text("Delete", color = cs.error) }, onClick = { menu = false; onDelete(p) })
            }
        }
    }
}

@Composable
private fun NewProjectDialog(onDismiss: () -> Unit, onCreate: (String, Template) -> Unit) {
    var name by remember { mutableStateOf("MyProject") }
    var sel by remember { mutableStateOf(Templates.all[1]) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New project") },
        text = {
            Column {
                OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("Project name") })
                Spacer(Modifier.height(10.dp))
                Text("Template", style = MaterialTheme.typography.labelMedium)
                Column(Modifier.heightIn(max = 260.dp).verticalScroll(androidx.compose.foundation.rememberScrollState())) {
                    Templates.all.forEach { t ->
                        Row(Modifier.fillMaxWidth().clickable { sel = t }.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = sel.id == t.id, onClick = { sel = t })
                            Column { Text(t.title, fontSize = 14.sp); Text(t.desc, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onCreate(name, sel); onDismiss() }) { Text("Create") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
