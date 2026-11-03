@file:OptIn(ExperimentalMaterial3Api::class)

package com.pystudio.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pystudio.app.state.*
import java.text.DateFormat
import java.util.Date

@Composable
fun GitScreen(vm: AppViewModel, host: Host, back: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val g = vm.git
    val dir = vm.project
    var msg by remember { mutableStateOf("") }
    var branchDlg by remember { mutableStateOf(false) }
    var remoteDlg by remember { mutableStateOf(false) }
    var switchDlg by remember { mutableStateOf(false) }
    LaunchedEffect(dir) { g.refresh(dir) }

    ScreenFrame("Git · ${dir?.name ?: ""}", back, actions = {
        if (g.busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        IconBtn(Icons.Default.Refresh, "Refresh", { g.refresh(dir) })
    }) {
        if (!g.info.isRepo) {
            Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Icon(Icons.Default.AccountTree, null, Modifier.size(48.dp), tint = cs.primary)
                Text("This project is not a Git repository yet.", Modifier.padding(vertical = 12.dp))
                Button(onClick = { vm.saveAllSync(); g.init(dir) }, enabled = !g.busy) { Text("Initialize repository") }
                if (g.status.isNotEmpty()) Text(g.status, fontSize = 12.sp, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 10.dp))
            }
            return@ScreenFrame
        }
        LazyColumn(Modifier.fillMaxSize()) {
            item {
                Column(Modifier.padding(horizontal = 12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.AccountTree, null, Modifier.size(18.dp), tint = cs.primary)
                        Text(" ${g.info.branch}", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        TextButton(onClick = { switchDlg = true }) { Text("Switch") }
                        TextButton(onClick = { branchDlg = true }) { Text("New branch") }
                    }
                    Text(if (g.info.remote.isEmpty()) "No remote configured" else "origin: ${g.info.remote}", fontSize = 12.sp, color = cs.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                        OutlinedButton(onClick = { remoteDlg = true }) { Text("Remote") }
                        OutlinedButton(onClick = { g.pull(dir) }, enabled = !g.busy && g.info.remote.isNotEmpty()) { Text("Pull") }
                        OutlinedButton(onClick = { g.push(dir) }, enabled = !g.busy && g.info.remote.isNotEmpty()) { Text("Push") }
                    }
                    Spacer(Modifier.height(8.dp))
                    CompactField(msg, { msg = it }, Modifier.fillMaxWidth(), "Commit message")
                    Button(
                        onClick = { vm.saveAllSync(); g.commit(dir, msg); msg = "" },
                        enabled = !g.busy && msg.isNotBlank(), modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
                    ) { Text("Commit all changes") }
                    if (g.status.isNotEmpty()) Text(g.status, fontSize = 12.sp, color = cs.secondary, modifier = Modifier.padding(top = 6.dp))
                    if (vm.prefs.gitToken.isBlank()) Text("Tip: add your Git username and access token in Settings to push/pull.", fontSize = 11.sp, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                }
            }
            item { SectionTitle("Changes (${g.info.changes.size})") }
            if (g.info.changes.isEmpty()) item { Text("Working tree clean", fontSize = 13.sp, color = cs.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp)) }
            items(g.info.changes, key = { it.status + it.path }) { c ->
                Row(Modifier.fillMaxWidth().clickable { dir?.let { vm.openFile(java.io.File(it, c.path)) } }.padding(horizontal = 16.dp, vertical = 5.dp)) {
                    Text(c.status, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
                        color = when (c.status) { "A", "?" -> LocalSyntax.current.number; "D" -> LocalSyntax.current.error; else -> LocalSyntax.current.warning }, modifier = Modifier.width(22.dp))
                    Text(c.path, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            item { SectionTitle("History") }
            items(g.info.commits, key = { it.id }) { c ->
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp)) {
                    Text(c.message, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text("${c.id} · ${c.author} · ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(c.time))}", fontSize = 11.sp, color = cs.onSurfaceVariant)
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
    if (branchDlg) InputDialog("New branch", "", "Branch name", "Create", { branchDlg = false }) { g.checkout(dir, it.trim(), true) }
    if (remoteDlg) InputDialog("Remote URL (origin)", g.info.remote.ifEmpty { "https://github.com/" }, "HTTPS URL", "Save", { remoteDlg = false }) { g.setRemote(dir, it.trim()) }
    if (switchDlg) AlertDialog(
        onDismissRequest = { switchDlg = false }, title = { Text("Switch branch") },
        text = { Column { g.info.branches.forEach { b -> TextButton(onClick = { switchDlg = false; vm.saveAllSync(); g.checkout(dir, b, false) }) { Text(b) } } } },
        confirmButton = { TextButton(onClick = { switchDlg = false }) { Text("Close") } }
    )
}
