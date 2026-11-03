package com.pystudio.app.ui

import android.graphics.Typeface
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.sp
import com.pystudio.app.data.*
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.io.File

fun buildFont(name: String, dir: File): FontFamily = try {
    when {
        name == "serif-mono" -> FontFamily(Typeface.create("serif-monospace", Typeface.NORMAL))
        name.startsWith("custom:") -> FontFamily(Typeface.createFromFile(File(dir, name.removePrefix("custom:"))))
        else -> FontFamily.Monospace
    }
} catch (e: Exception) {
    FontFamily.Monospace
}

@Composable
fun IconBtn(icon: ImageVector, desc: String, onClick: () -> Unit, tint: Color = MaterialTheme.colorScheme.onSurface,
            size: Int = 40, enabled: Boolean = true) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(size.dp)) {
        Icon(icon, desc, tint = if (enabled) tint else tint.copy(alpha = 0.35f), modifier = Modifier.size(22.dp))
    }
}

@Composable
fun InputDialog(
    title: String, initial: String = "", label: String = "", confirm: String = "OK",
    onDismiss: () -> Unit, onDone: (String) -> Unit
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(text, { text = it }, singleLine = true, label = { if (label.isNotEmpty()) Text(label) }) },
        confirmButton = { TextButton(onClick = { onDone(text); onDismiss() }) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
fun ConfirmDialog(title: String, message: String, confirm: String = "Delete", onDismiss: () -> Unit, onYes: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = { TextButton(onClick = { onYes(); onDismiss() }) { Text(confirm, color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
fun SectionTitle(text: String) {
    Text(
        text.uppercase(), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 16.dp, top = 14.dp, bottom = 4.dp)
    )
}

@Composable
fun SettingRow(title: String, subtitle: String = "", onClick: (() -> Unit)? = null, trailing: @Composable () -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(Modifier.weight(1f).padding(end = 8.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle.isNotEmpty()) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        trailing()
    }
}

/** Full-screen scaffold used by every secondary screen: back arrow + title + content area. */
@Composable
fun ScreenFrame(title: String, onBack: () -> Unit, actions: @Composable () -> Unit = {}, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconBtn(Icons.Default.ArrowBack, "Back", onBack)
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f).padding(start = 4.dp))
            actions()
        }
        content()
    }
}

@Composable
fun ScrollColumn(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(modifier.verticalScroll(rememberScrollState())) { content() }
}

interface Host {
    fun share(f: File)
    fun openUrl(url: String)
    fun restart()
}

@Composable
fun CompactField(
    value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier, placeholder: String = "",
    mono: Boolean = false, keyboardOptions: androidx.compose.foundation.text.KeyboardOptions = androidx.compose.foundation.text.KeyboardOptions.Default,
    keyboardActions: androidx.compose.foundation.text.KeyboardActions = androidx.compose.foundation.text.KeyboardActions.Default,
    focusRequester: androidx.compose.ui.focus.FocusRequester? = null, password: Boolean = false
) {
    val cs = MaterialTheme.colorScheme
    val fm = if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier
    androidx.compose.foundation.text.BasicTextField(
        value = value, onValueChange = onChange, singleLine = true,
        textStyle = androidx.compose.ui.text.TextStyle(
            color = cs.onSurface, fontSize = 14.sp, fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default
        ),
        cursorBrush = androidx.compose.ui.graphics.SolidColor(cs.primary),
        keyboardOptions = keyboardOptions, keyboardActions = keyboardActions,
        visualTransformation = if (password) androidx.compose.ui.text.input.PasswordVisualTransformation()
        else androidx.compose.ui.text.input.VisualTransformation.None,
        modifier = modifier.then(fm),
        decorationBox = { inner ->
            Box(
                Modifier.fillMaxWidth().heightIn(min = 38.dp).clip(RoundedCornerShape(10.dp)).background(cs.surfaceVariant)
                    .padding(horizontal = 10.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                if (value.isEmpty()) Text(placeholder, color = cs.onSurfaceVariant, fontSize = 14.sp)
                inner()
            }
        }
    )
}

@Composable
fun ToggleChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Surface(
        onClick = onClick, shape = RoundedCornerShape(18.dp),
        color = if (selected) cs.primaryContainer else cs.surfaceVariant,
        modifier = Modifier.padding(end = 6.dp)
    ) {
        Text(
            label, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            color = if (selected) cs.onPrimaryContainer else cs.onSurfaceVariant
        )
    }
}

/** Scrolling, selectable list of console lines that sticks to the bottom as output arrives. */
@Composable
fun LineList(buf: LineBuffer, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val syn = LocalSyntax.current
    val state = androidx.compose.foundation.lazy.rememberLazyListState()
    val n = buf.lines.size
    LaunchedEffect(buf.version) { if (n > 0) state.scrollToItem(n - 1) }
    androidx.compose.foundation.text.selection.SelectionContainer(modifier) {
        androidx.compose.foundation.lazy.LazyColumn(state = state, modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp)) {
            items(n, key = null) { i ->
                val l = buf.lines.getOrNull(i) ?: return@items
                Text(
                    l.text.ifEmpty { " " }, fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 17.sp,
                    color = when (l.kind) { K_ERR -> syn.error; K_SYS -> cs.primary; K_IN -> syn.string; else -> cs.onSurface }
                )
            }
        }
    }
}
