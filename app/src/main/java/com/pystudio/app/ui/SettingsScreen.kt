@file:OptIn(ExperimentalMaterial3Api::class)

package com.pystudio.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pystudio.app.state.*

@Composable
private fun SwitchRow(title: String, sub: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    SettingRow(title, sub, onClick = { onChange(!checked) }) { Switch(checked = checked, onCheckedChange = onChange) }
}

@Composable
fun SettingsScreen(vm: AppViewModel, back: () -> Unit) {
    val p = vm.prefs
    val cs = MaterialTheme.colorScheme
    val fontPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) vm.importFont(uri) }

    ScreenFrame("Settings", back) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            SectionTitle("Appearance")
            Row(Modifier.padding(horizontal = 12.dp)) {
                ToggleChip("Dark", p.theme == "dark") { p.theme = "dark" }
                ToggleChip("Light", p.theme == "light") { p.theme = "light" }
                ToggleChip("System", p.theme == "system") { p.theme = "system" }
            }
            SettingRow("Font size", "${p.fontSize.toInt()} sp") {}
            Slider(
                value = p.fontSize, onValueChange = { p.fontSize = it.toInt().toFloat() }, valueRange = 10f..28f, steps = 17,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
            Text("Editor font", Modifier.padding(horizontal = 16.dp, vertical = 4.dp), fontSize = 13.sp, color = cs.onSurfaceVariant)
            Row(Modifier.padding(horizontal = 12.dp)) {
                ToggleChip("Monospace", p.fontName == "mono") { p.fontName = "mono" }
                ToggleChip("Serif mono", p.fontName == "serif-mono") { p.fontName = "serif-mono" }
                ToggleChip(if (p.fontName.startsWith("custom:")) p.fontName.removePrefix("custom:") else "Import .ttf", p.fontName.startsWith("custom:")) {
                    fontPicker.launch(arrayOf("*/*"))
                }
            }
            Text("Preview: def hello(name): return f\"Hi {name}\"", Modifier.padding(16.dp), fontFamily = buildFont(p.fontName, vm.fontsDir), fontSize = p.fontSize.sp)

            SectionTitle("Editor")
            Text("Indent size", Modifier.padding(horizontal = 16.dp, vertical = 4.dp), fontSize = 13.sp, color = cs.onSurfaceVariant)
            Row(Modifier.padding(horizontal = 12.dp)) {
                listOf(2, 4, 8).forEach { n -> ToggleChip("$n spaces", p.tabSize == n) { p.tabSize = n } }
            }
            SwitchRow("Line numbers", "Show the gutter with breakpoints and fold arrows", p.lineNumbers) { p.lineNumbers = it }
            SwitchRow("Tab bar", "Show open files as tabs", p.showTabs) { p.showTabs = it }
            SwitchRow("Keyboard toolbar", "Symbol keys above the keyboard", p.symbolBar) { p.symbolBar = it }
            SwitchRow("Auto-save", "Save files automatically while typing", p.autoSave) { p.autoSave = it }
            SwitchRow("Code completion", "Suggestions while typing (Jedi)", p.autoComplete) { p.autoComplete = it }
            SwitchRow("Live code analysis", "Syntax errors, pyflakes and style warnings", p.linting) {
                p.linting = it
                vm.tabs.forEach { t -> if (it) vm.scheduleAnalysis(t, 100) else t.diags = emptyList() }
            }
            SwitchRow("Clear console on run", "Start every run with an empty console", p.clearOnRun) { p.clearOnRun = it }

            SectionTitle("Git credentials")
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                CompactField(p.gitName, { p.gitName = it }, Modifier.fillMaxWidth(), "Author name")
                CompactField(p.gitEmail, { p.gitEmail = it }, Modifier.fillMaxWidth(), "Author email")
                CompactField(p.gitUser, { p.gitUser = it }, Modifier.fillMaxWidth(), "Username (for push/pull)")
                CompactField(p.gitToken, { p.gitToken = it }, Modifier.fillMaxWidth(), "Access token", password = true)
                Text("Stored only in this app’s private storage. Never sent anywhere except to the Git server you push to.", fontSize = 11.sp, color = cs.onSurfaceVariant)
            }

            SectionTitle("Python runtime")
            SettingRow("Interpreter", if (vm.pythonReady) "CPython ${vm.pyVersion} (embedded via Chaquopy)" else "Starting…") {}
            SettingRow("Packages folder", "Private app storage, removed on uninstall") {}
            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
fun AboutScreen(host: Host, back: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    ScreenFrame("About", back) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Default.Code, null, Modifier.size(64.dp), tint = cs.primary)
            Text("PyStudio – Python Code Editor", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 8.dp))
            Text("Version 1.0.0", color = cs.onSurfaceVariant, fontSize = 13.sp)
            Text(
                "A VS Code–style Python IDE for Android with a real embedded CPython interpreter.",
                modifier = Modifier.padding(vertical = 14.dp), color = cs.onSurface
            )
            Surface(shape = MaterialTheme.shapes.large, color = cs.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Free forever", fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                    Text("• Every feature is unlocked: no subscriptions, no premium tier", fontSize = 13.sp)
                    Text("• No advertisements and no tracking or analytics", fontSize = 13.sp)
                    Text("• No account required, projects stay on your device", fontSize = 13.sp)
                    Text("• Only permission: Internet (pip, PyPI lookup, Git)", fontSize = 13.sp)
                }
            }
            Spacer(Modifier.height(20.dp))
            Text("Contact & source", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            Button(onClick = { host.openUrl(TELEGRAM_URL) }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Send, null, Modifier.size(18.dp)); Text("  Telegram  @YogeshBhai729")
            }
            OutlinedButton(onClick = { host.openUrl(GITHUB_URL) }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Icon(Icons.Default.Code, null, Modifier.size(18.dp)); Text("  GitHub  hgcheat094-max")
            }
            Spacer(Modifier.height(20.dp))
            Text(
                "Built with Kotlin, Jetpack Compose, Material 3, Chaquopy (CPython), Jedi, pyflakes, pycodestyle, autopep8, pip and JGit.",
                fontSize = 11.sp, color = cs.onSurfaceVariant
            )
        }
    }
}
