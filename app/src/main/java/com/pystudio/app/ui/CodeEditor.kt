package com.pystudio.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pystudio.app.data.Diag
import com.pystudio.app.state.AppViewModel
import com.pystudio.app.state.EditorTab
import kotlinx.coroutines.delay

private const val PLACEHOLDER = " …"

/** Folded line ranges (hidden character ranges) and the list of visible original lines. */
class HiddenRegions(info: CodeInfo, folded: Set<Int>) {
    val regions = ArrayList<IntArray>()
    val visible: IntArray

    init {
        val hidden = BooleanArray(info.lineCount)
        var lastEnd = -1
        for (h in folded.sorted()) {
            val e = info.folds[h] ?: continue
            if (h <= lastEnd || e >= info.lineCount) continue
            regions.add(intArrayOf(info.lineEnd(h), info.lineEnd(e)))
            for (l in (h + 1)..e) hidden[l] = true
            lastEnd = e
        }
        visible = (0 until info.lineCount).filter { !hidden[it] }.toIntArray()
    }
}

class FoldMapping(private val regions: List<IntArray>, private val origLen: Int) : OffsetMapping {
    private val p = PLACEHOLDER.length
    private val transLen = origLen - regions.sumOf { r -> r[1] - r[0] - p }

    override fun originalToTransformed(offset: Int): Int {
        var shift = 0
        for (r in regions) {
            if (offset <= r[0]) break
            if (offset < r[1]) return (r[0] - shift + p).coerceIn(0, transLen)
            shift += r[1] - r[0] - p
        }
        return (offset - shift).coerceIn(0, transLen)
    }

    override fun transformedToOriginal(offset: Int): Int {
        var shift = 0
        for (r in regions) {
            val ts = r[0] - shift
            if (offset <= ts) break
            if (offset < ts + p) return r[0].coerceIn(0, origLen)
            shift += r[1] - r[0] - p
        }
        return (offset + shift).coerceIn(0, origLen)
    }
}

class CodeTransformation(
    private val info: CodeInfo, private val hidden: HiddenRegions, private val folded: Set<Int>,
    private val diags: List<Diag>, private val cursors: List<Int>,
    private val matches: List<IntRange>, private val current: Int, private val c: SyntaxColors
) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val inf = if (text.text == info.text) info else CodeInfo(text.text, c)
        val hid = if (inf === info) hidden else HiddenRegions(inf, folded)
        val len = text.length
        val styled = buildAnnotatedString {
            append(inf.styled)
            for (d in diags) {
                if (d.sev < 1) continue
                val l = d.line - 1
                if (l < 0 || l >= inf.lineCount) continue
                val ls = inf.lineStarts[l]
                val le = inf.lineEnd(l)
                var s = (ls + d.col - 1).coerceAtMost(le)
                if (s >= le) s = maxOf(ls, le - 1)
                var e = (ls + d.endCol - 1).coerceAtMost(le)
                if (e <= s) e = minOf(s + 1, len)
                if (s >= len || e <= s) continue
                val col = if (d.sev == 2) c.error else c.warning
                addStyle(SpanStyle(background = col.copy(alpha = 0.28f), textDecoration = TextDecoration.Underline), s, e)
            }
            for ((k, r) in matches.withIndex()) {
                if (r.first < 0 || r.last + 1 > len) continue
                val a = if (k == current) 0.65f else 0.3f
                addStyle(SpanStyle(background = Color(0xFFFFC107).copy(alpha = a)), r.first, r.last + 1)
            }
            for (cu in cursors) {
                val pos = cu.coerceIn(0, len)
                val range = when {
                    pos < len && text.text[pos] != '\n' -> pos to pos + 1
                    pos > 0 && text.text[pos - 1] != '\n' -> pos - 1 to pos
                    else -> null
                }
                if (range != null) addStyle(SpanStyle(background = c.accent.copy(alpha = 0.7f)), range.first, range.second)
            }
        }
        if (hid.regions.isEmpty()) return TransformedText(styled, OffsetMapping.Identity)
        val out = buildAnnotatedString {
            var pos = 0
            for (r in hid.regions) {
                append(styled.subSequence(pos, r[0]))
                withStyle(SpanStyle(color = c.gutter, background = c.gutter.copy(alpha = 0.2f))) { append(PLACEHOLDER) }
                pos = r[1]
            }
            append(styled.subSequence(pos, len))
        }
        return TransformedText(out, FoldMapping(hid.regions, len))
    }
}

private fun buildGutter(
    info: CodeInfo, hidden: HiddenRegions, folded: Set<Int>, diags: List<Diag>,
    bps: Set<Int>, debugLine: Int?, c: SyntaxColors
): AnnotatedString {
    val w = maxOf(2, info.lineCount.toString().length)
    val sev = HashMap<Int, Int>()
    for (d in diags) if (d.sev >= 1) sev[d.line - 1] = maxOf(sev[d.line - 1] ?: 0, d.sev)
    return buildAnnotatedString {
        for ((row, l) in hidden.visible.withIndex()) {
            if (row > 0) append('\n')
            when {
                debugLine != null && debugLine - 1 == l -> withStyle(SpanStyle(color = Color(0xFFFFD43B))) { append("▶") }
                (l + 1) in bps -> withStyle(SpanStyle(color = Color(0xFFE51400))) { append("●") }
                else -> append(" ")
            }
            val numColor = when (sev[l]) { 2 -> c.error; 1 -> c.warning; else -> c.gutter }
            withStyle(SpanStyle(color = numColor)) { append((l + 1).toString().padStart(w)) }
            val fm = if (info.folds.containsKey(l)) (if (l in folded) "▸" else "▾") else " "
            withStyle(SpanStyle(color = c.gutter)) { append(fm) }
        }
    }
}

@Composable
fun CodeEditor(vm: AppViewModel, tab: EditorTab, modifier: Modifier = Modifier) {
    val prefs = vm.prefs
    val syn = LocalSyntax.current
    val density = LocalDensity.current
    val family = remember(prefs.fontName) { buildFont(prefs.fontName, vm.fontsDir) }
    val fs = prefs.fontSize
    val style = remember(fs, family, syn) {
        TextStyle(color = syn.text, fontFamily = family, fontSize = fs.sp, lineHeight = (fs * 1.5f).sp)
    }
    val value = tab.value
    val path = tab.file.absolutePath
    val info = remember(value.text, syn) { CodeInfo(value.text, syn) }
    val folded = tab.folded
    val hidden = remember(info, folded) { HiddenRegions(info, folded) }
    val bps = vm.breakpoints[path] ?: emptySet()
    val debugLine = vm.pause?.takeIf { it.file == path }?.line
    val matches = if (vm.findOpen) vm.matches else emptyList()
    val diags = tab.diags
    val cursors = tab.cursors
    val transform = remember(info, hidden, folded, diags, cursors, matches, vm.matchIdx, syn) {
        CodeTransformation(info, hidden, folded, diags, cursors, matches, vm.matchIdx, syn)
    }
    val measurer = rememberTextMeasurer()
    val cw = remember(style) { measurer.measure("0", style).size.width.toFloat() }
    val digits = maxOf(2, info.lineCount.toString().length)
    val gutterW = if (prefs.lineNumbers) (with(density) { (cw * (digits + 2)).toDp() } + 8.dp) else 0.dp
    val gutterText = remember(info, hidden, folded, diags, bps, debugLine, syn) {
        buildGutter(info, hidden, folded, diags, bps, debugLine, syn)
    }
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    var gLayout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val vScroll = tab.vScroll
    val hScroll = tab.hScroll

    BoxWithConstraints(modifier.fillMaxSize().background(syn.bg)) {
        val vh = maxHeight
        val vw = maxWidth

        val req = vm.scrollReq
        LaunchedEffect(req?.nonce, tab) {
            val r = req ?: return@LaunchedEffect
            if (r.path != path) return@LaunchedEffect
            var tries = 0
            while (layout == null && tries++ < 20) delay(30)
            val lay = layout ?: return@LaunchedEffect
            val row = hidden.visible.indexOfFirst { it >= r.line }.let { if (it < 0) hidden.visible.size - 1 else it }
            val y = lay.getLineTop(row.coerceIn(0, lay.lineCount - 1))
            vScroll.animateScrollTo((y - with(density) { vh.toPx() } / 3f).toInt().coerceAtLeast(0))
            vm.scrollReq = null
        }

        Row(Modifier.fillMaxWidth().verticalScroll(vScroll).heightIn(min = vh)) {
            if (prefs.lineNumbers) {
                Text(
                    gutterText,
                    style = style.copy(color = syn.gutter),
                    softWrap = false,
                    onTextLayout = { gLayout = it },
                    modifier = Modifier.width(gutterW).background(syn.bg).padding(start = 2.dp, end = 4.dp)
                        .pointerInput(info, hidden, cw, digits) {
                            detectTapGestures { off ->
                                val row = gLayout?.getLineForVerticalPosition(off.y) ?: return@detectTapGestures
                                val l = hidden.visible.getOrNull(row) ?: return@detectTapGestures
                                if (off.x < cw * 1.8f) vm.toggleBreakpoint(path, l + 1)
                                else if (off.x > cw * (digits + 0.6f) && info.folds.containsKey(l)) vm.toggleFold(tab, l)
                            }
                        }
                )
            }
            Box(Modifier.weight(1f).horizontalScroll(hScroll)) {
                BasicTextField(
                    value = value,
                    onValueChange = { vm.onEdit(tab, it) },
                    textStyle = style,
                    cursorBrush = SolidColor(syn.cursor),
                    visualTransformation = transform,
                    onTextLayout = { layout = it },
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.None, autoCorrect = false, keyboardType = KeyboardType.Text
                    ),
                    modifier = Modifier.widthIn(min = vw - gutterW).heightIn(min = vh).padding(horizontal = 4.dp)
                        .onFocusChanged { vm.editorFocused = it.isFocused }
                        .onPreviewKeyEvent { ev ->
                            if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                            when {
                                ev.key == Key.Tab -> { vm.insertText(tab, " ".repeat(prefs.tabSize)); true }
                                ev.isCtrlPressed && ev.key == Key.S -> { vm.save(tab); true }
                                ev.isCtrlPressed && ev.key == Key.Z -> { vm.undo(tab); true }
                                ev.isCtrlPressed && ev.key == Key.Y -> { vm.redo(tab); true }
                                ev.isCtrlPressed && ev.key == Key.F -> { vm.findOpen = true; true }
                                ev.isCtrlPressed && ev.key == Key.Enter -> { vm.run(false); true }
                                else -> false
                            }
                        }
                )
            }
        }
    }
}
