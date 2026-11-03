@file:OptIn(ExperimentalMaterial3Api::class)

package com.pystudio.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pystudio.app.state.*

@Composable
fun PackagesScreen(vm: AppViewModel, back: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val pk = vm.pkgs
    var tab by remember { mutableIntStateOf(0) }
    var query by remember { mutableStateOf("") }
    var uninstall by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { pk.refresh() }

    ScreenFrame("Package manager", back, actions = {
        if (pk.busy) CircularProgressIndicator(Modifier.size(20.dp).padding(end = 2.dp), strokeWidth = 2.dp)
        IconBtn(Icons.Default.Refresh, "Refresh", { pk.refresh() })
    }) {
        if (pk.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        Row(Modifier.padding(horizontal = 10.dp, vertical = 4.dp)) {
            ToggleChip("Installed (${pk.installed.size})", tab == 0) { tab = 0 }
            ToggleChip("Search & install", tab == 1) { tab = 1 }
            ToggleChip("Log", tab == 2) { tab = 2 }
        }
        when (tab) {
            0 -> {
                Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { pk.updateAll(); tab = 2 }, enabled = !pk.busy && pk.installed.isNotEmpty()) { Text("Update all") }
                }
                if (pk.installed.isEmpty()) {
                    Text("No packages installed yet. Use “Search & install”. Packages are stored privately on this device.", color = cs.onSurfaceVariant, fontSize = 13.sp, modifier = Modifier.padding(16.dp))
                }
                LazyColumn(Modifier.fillMaxSize()) {
                    items(pk.installed, key = { it.name }) { p ->
                        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("${p.name}  ${p.version}", fontFamily = FontFamily.Monospace, fontSize = 14.sp)
                                if (p.summary.isNotEmpty()) Text(p.summary, fontSize = 12.sp, color = cs.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                            IconBtn(Icons.Default.Refresh, "Update ${p.name}", { pk.install(p.name); tab = 2 }, enabled = !pk.busy)
                            IconBtn(Icons.Default.Delete, "Uninstall ${p.name}", { uninstall = p.name }, tint = cs.error, enabled = !pk.busy)
                        }
                        HorizontalDivider(color = cs.outlineVariant.copy(alpha = 0.5f))
                    }
                }
            }
            1 -> Column(Modifier.fillMaxSize()) {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    CompactField(
                        query, { query = it; pk.search(it) }, Modifier.weight(1f), "Package name, or several: numpy pandas==2.2", mono = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search, autoCorrect = false, capitalization = KeyboardCapitalization.None),
                        keyboardActions = KeyboardActions(onSearch = { pk.search(query) })
                    )
                    IconBtn(Icons.Default.Download, "Install typed text", { pk.install(query.trim()); tab = 2 }, enabled = query.isNotBlank() && !pk.busy)
                }
                Text(
                    "Installs pure-Python packages and Android builds from Chaquopy’s repository (numpy, pandas, scipy, pillow, …). " +
                        "Use exact names for PyPI details; the popular list works offline.",
                    fontSize = 11.sp, color = cs.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
                )
                if (pk.searching) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 4.dp))
                LazyColumn(Modifier.fillMaxSize()) {
                    items(pk.hits, key = { it.name }) { h ->
                        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(h.name + (h.info?.let { "  ${it.version}" } ?: ""), fontFamily = FontFamily.Monospace, fontSize = 14.sp)
                                h.info?.let {
                                    if (it.summary.isNotEmpty()) Text(it.summary, fontSize = 12.sp, color = cs.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    if (it.requires.isNotEmpty()) Text("Python ${it.requires}", fontSize = 11.sp, color = cs.onSurfaceVariant)
                                }
                            }
                            val installed = pk.installed.any { it.name.equals(h.name, true) }
                            FilledTonalButton(onClick = { pk.install(h.name); tab = 2 }, enabled = !pk.busy) { Text(if (installed) "Update" else "Install") }
                            Spacer(Modifier.width(8.dp))
                        }
                        HorizontalDivider(color = cs.outlineVariant.copy(alpha = 0.5f))
                    }
                }
            }
            else -> Box(Modifier.fillMaxSize()) { LineList(pk.log) }
        }
    }
    uninstall?.let { n -> ConfirmDialog("Uninstall $n?", "The package files will be removed.", "Uninstall", { uninstall = null }) { pk.uninstall(n); tab = 2 } }
}
