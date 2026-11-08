package com.healthanalyzer.app.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.window.PopupProperties
import kotlinx.coroutines.launch
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** A labelled dropdown button that opens a menu of [options]. */
@Composable
fun <T> Picker(
    label: String,
    selected: T?,
    options: List<T>,
    display: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "Choose…",
) {
    var open by remember { mutableStateOf(false) }
    Column(modifier) {
        if (label.isNotBlank()) {
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }
        Box {
            Surface(
                onClick = { open = true },
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        selected?.let(display) ?: placeholder,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (selected != null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        "▾",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 14.sp,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
            DropdownMenu(
                expanded = open,
                onDismissRequest = { open = false },
                modifier = Modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh),
            ) {
                options.forEach { option ->
                    val isSelected = option == selected
                    DropdownMenuItem(
                        text = {
                            Text(
                                display(option),
                                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                            )
                        },
                        onClick = { open = false; onSelect(option) },
                    )
                }
            }
        }
    }
}

/** Modern pill badge for status, activity types, and source indicators. */
@Composable
fun StatusBadge(
    text: String,
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.surfaceVariant,
    contentColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    dotColor: Color? = null,
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = containerColor,
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            dotColor?.let {
                Box(
                    Modifier.size(6.dp).clip(CircleShape).background(it),
                )
            }
            Text(
                text = text,
                style = MaterialTheme.typography.labelSmall,
                color = contentColor,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Segmented Control for switching views (e.g. List vs Dashboard). */
@Composable
fun SegmentedSwitcher(
    selectedIndex: Int,
    options: List<String>,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(3.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            options.forEachIndexed { index, title ->
                val selected = index == selectedIndex
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(9.dp))
                        .background(if (selected) MaterialTheme.colorScheme.primary else Color.Transparent)
                        .clickable { onSelect(index) }
                        .padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.labelLarge,
                        color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                    )
                }
            }
        }
    }
}

/**
 * Small Markdown renderer for model output: headings, bullet and numbered lists, block quotes,
 * fenced code, GitHub-style tables, and inline **bold**, *italic*, ~~strike~~, `code` and links.
 * Text stays selectable.
 */
@Composable
fun MarkdownText(text: String, modifier: Modifier = Modifier) {
    val blocks = remember(text) { parseMarkdownBlocks(text) }
    SelectionContainer(modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            blocks.forEach { block ->
                when (block) {
                    is MdBlock.Code -> CodeLine(block.line)
                    is MdBlock.Table -> MarkdownTable(block)
                    is MdBlock.Line -> MarkdownLine(block.line)
                }
            }
        }
    }
}

@Composable
private fun CodeLine(line: String) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.7f),
        shape = RoundedCornerShape(4.dp),
        modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp),
    ) {
        Text(
            line,
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun MarkdownLine(line: String) {
    when {
        line.isBlank() -> Spacer(Modifier.height(4.dp))
        HEADING.matches(line) -> {
            val m = HEADING.find(line)!!
            val level = m.groupValues[1].length
            Text(
                inline(m.groupValues[2].trim().trimEnd('#').trim()),
                style = when {
                    level <= 2 -> MaterialTheme.typography.titleLarge
                    level == 3 -> MaterialTheme.typography.titleMedium
                    else -> MaterialTheme.typography.titleSmall
                },
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
            )
        }
        RULE.matches(line) -> {
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
                modifier = Modifier.padding(vertical = 8.dp),
            )
        }
        QUOTE.matches(line) -> {
            Row(Modifier.padding(vertical = 2.dp).height(IntrinsicSize.Min)) {
                Box(
                    Modifier.width(3.dp).fillMaxHeight()
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)),
                )
                Text(
                    inline(QUOTE.find(line)!!.groupValues[1]),
                    style = MaterialTheme.typography.bodyMedium,
                    fontStyle = FontStyle.Italic,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 10.dp),
                )
            }
        }
        BULLET.matches(line) -> {
            val m = BULLET.find(line)!!
            val indent = (m.groupValues[1].length / 2).coerceAtMost(4)
            Row(Modifier.padding(start = (8 + indent * 12).dp, top = 2.dp)) {
                Text("•  ", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                Text(inline(m.groupValues[2]), style = MaterialTheme.typography.bodyMedium)
            }
        }
        NUMBERED.matches(line) -> {
            val m = NUMBERED.find(line)!!
            val indent = (m.groupValues[1].length / 2).coerceAtMost(4)
            Row(Modifier.padding(start = (8 + indent * 12).dp, top = 2.dp)) {
                Text("${m.groupValues[2]}.  ", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                Text(inline(m.groupValues[3]), style = MaterialTheme.typography.bodyMedium)
            }
        }
        else -> Text(
            inline(line),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/**
 * Grid table: each column is as wide as its widest cell (capped, long cells wrap), each row as tall
 * as its tallest cell. Tables wider than the screen scroll horizontally.
 */
@Composable
private fun MarkdownTable(table: MdBlock.Table) {
    val cols = table.columnCount
    val border = MaterialTheme.colorScheme.outlineVariant
    val headerBg = MaterialTheme.colorScheme.surfaceContainerHighest
    val stripeBg = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.4f)
    val rows = listOfNotNull(table.header) + table.rows
    Box(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .horizontalScroll(rememberScrollState()),
    ) {
        Layout(
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .border(1.dp, border, RoundedCornerShape(6.dp)),
            content = {
                rows.forEachIndexed { r, row ->
                    val isHeader = table.header != null && r == 0
                    val bodyIndex = if (table.header != null) r - 1 else r
                    repeat(cols) { c ->
                        Box(
                            Modifier
                                .background(
                                    when {
                                        isHeader -> headerBg
                                        bodyIndex % 2 == 1 -> stripeBg
                                        else -> Color.Transparent
                                    },
                                )
                                .border(0.5.dp, border.copy(alpha = 0.6f))
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                        ) {
                            Text(
                                inline(row.getOrElse(c) { "" }),
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = if (isHeader) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (isHeader) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                textAlign = when (table.aligns.getOrNull(c)) {
                                    MdAlign.Center -> TextAlign.Center
                                    MdAlign.Right -> TextAlign.End
                                    else -> TextAlign.Start
                                },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            },
        ) { measurables, _ ->
            val maxCol = 220.dp.roundToPx()
            val minCol = 40.dp.roundToPx()
            val widths = IntArray(cols) { minCol }
            measurables.forEachIndexed { i, m ->
                val c = i % cols
                widths[c] = maxOf(widths[c], m.maxIntrinsicWidth(Constraints.Infinity).coerceAtMost(maxCol))
            }
            val heights = IntArray(rows.size)
            measurables.forEachIndexed { i, m ->
                val r = i / cols
                heights[r] = maxOf(heights[r], m.minIntrinsicHeight(widths[i % cols]))
            }
            val placeables = measurables.mapIndexed { i, m ->
                m.measure(Constraints.fixed(widths[i % cols], heights[i / cols]))
            }
            layout(widths.sum(), heights.sum()) {
                var y = 0
                for (r in rows.indices) {
                    var x = 0
                    for (c in 0 until cols) {
                        placeables[r * cols + c].place(x, y)
                        x += widths[c]
                    }
                    y += heights[r]
                }
            }
        }
    }
}

internal enum class MdAlign { Left, Center, Right }

internal sealed interface MdBlock {
    data class Line(val line: String) : MdBlock
    data class Code(val line: String) : MdBlock
    data class Table(val header: List<String>?, val aligns: List<MdAlign>, val rows: List<List<String>>) : MdBlock {
        val columnCount: Int get() = maxOf(header?.size ?: 0, rows.maxOfOrNull { it.size } ?: 0, 1)
    }
}

/** Splits Markdown into renderable blocks, grouping consecutive pipe rows into tables. */
internal fun parseMarkdownBlocks(text: String): List<MdBlock> {
    val lines = text.lines().map { it.trimEnd() }
    val out = mutableListOf<MdBlock>()
    var inCode = false
    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        when {
            line.trimStart().startsWith("```") -> {
                inCode = !inCode
                if (!inCode) out += MdBlock.Line("")
                i++
            }
            inCode -> { out += MdBlock.Code(line); i++ }
            isTableStart(lines, i) -> {
                val tableLines = mutableListOf<String>()
                while (i < lines.size && lines[i].isNotBlank() && '|' in lines[i]) tableLines += lines[i++]
                out += buildTable(tableLines)
            }
            else -> { out += MdBlock.Line(line); i++ }
        }
    }
    return out
}

private fun isTableStart(lines: List<String>, i: Int): Boolean {
    val line = lines[i].trim()
    if (line.startsWith("|")) return true
    // Tables written without outer pipes: "a | b" followed by "---|---".
    return '|' in line && lines.getOrNull(i + 1)?.let { TABLE_SEPARATOR.matches(it) } == true
}

private fun buildTable(lines: List<String>): MdBlock.Table {
    val hasHeader = lines.size >= 2 && TABLE_SEPARATOR.matches(lines[1])
    if (!hasHeader) {
        return MdBlock.Table(null, emptyList(), lines.filterNot { TABLE_SEPARATOR.matches(it) }.map(::splitRow))
    }
    val aligns = splitRow(lines[1]).map { spec ->
        val left = spec.startsWith(":")
        val right = spec.endsWith(":")
        when {
            left && right -> MdAlign.Center
            right -> MdAlign.Right
            else -> MdAlign.Left
        }
    }
    val body = lines.drop(2).filterNot { TABLE_SEPARATOR.matches(it) }.map(::splitRow)
    return MdBlock.Table(splitRow(lines[0]), aligns, body)
}

internal fun splitRow(line: String): List<String> {
    var s = line.trim()
    if (s.startsWith("|")) s = s.substring(1)
    if (s.endsWith("|") && !s.endsWith("\\|")) s = s.dropLast(1)
    val cells = mutableListOf<String>()
    val cur = StringBuilder()
    var j = 0
    while (j < s.length) {
        val ch = s[j]
        when {
            ch == '\\' && j + 1 < s.length && s[j + 1] == '|' -> { cur.append('|'); j += 2; continue }
            ch == '|' -> { cells += cur.toString().trim(); cur.clear() }
            else -> cur.append(ch)
        }
        j++
    }
    cells += cur.toString().trim()
    return cells
}

private val HEADING = Regex("""^(#{1,6})\s+(.*)$""")
private val RULE = Regex("""^\s*([-*_])(\s*\1){2,}\s*$""")
private val QUOTE = Regex("""^\s*>\s?(.*)$""")
private val BULLET = Regex("""^(\s*)[-*+]\s+(.*)$""")
private val NUMBERED = Regex("""^(\s*)(\d+)[.)]\s+(.*)$""")
private val TABLE_SEPARATOR = Regex("""^\s*\|?\s*:?-+:?\s*(\|\s*:?-+:?\s*)*\|?\s*$""")
private val INLINE = Regex(
    """\*\*(.+?)\*\*|__(.+?)__|`([^`]+)`|~~(.+?)~~""" +
        """|(?<![\w*])\*(?![\s*])(.+?)(?<![\s*])\*(?![\w*])""" +
        """|(?<![\w_])_(?![\s_])(.+?)(?<![\s_])_(?![\w_])""" +
        """|\[([^\]]+)]\(([^)\s]+)\)""",
)

// Models often put HTML line breaks inside table cells, since Markdown cells can't hold newlines.
private val HTML_BREAK = Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE)

private fun inline(raw: String): AnnotatedString = buildAnnotatedString {
    val s = raw.replace(HTML_BREAK, "\n")
    var last = 0
    INLINE.findAll(s).forEach { m ->
        append(s.substring(last, m.range.first))
        val g = m.groupValues
        when {
            g[1].isNotEmpty() -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(inline(g[1])) }
            g[2].isNotEmpty() -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(inline(g[2])) }
            g[3].isNotEmpty() -> withStyle(
                SpanStyle(
                    fontFamily = FontFamily.Monospace,
                    background = Color(0x22888888),
                    color = Color(0xFF00E5BE),
                ),
            ) { append(g[3]) }
            g[4].isNotEmpty() -> withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) { append(inline(g[4])) }
            g[5].isNotEmpty() -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(inline(g[5])) }
            g[6].isNotEmpty() -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(inline(g[6])) }
            else -> withLink(LinkAnnotation.Url(g[8], TextLinkStyles(SpanStyle(color = Color(0xFF00E5BE), textDecoration = TextDecoration.Underline)))) {
                append(g[7])
            }
        }
        last = m.range.last + 1
    }
    append(s.substring(last))
}

data class PlaceholderItem(
    val tag: String,
    val label: String,
    val description: String,
)

val PROMPT_PLACEHOLDERS = listOf(
    PlaceholderItem("athlete", "{{athlete}}", "Max/resting HR, notes and goals from Settings"),
    PlaceholderItem("run", "{{run}}", "Target workout run: metrics, km splits, HR zones, drift"),
    PlaceholderItem("recent_runs", "{{recent_runs}}", "Recent runs, last 28 days (or {{recent_runs:N}})"),
    PlaceholderItem("weekly_summary", "{{weekly_summary}}", "Weekly totals, last 12 weeks (or {{weekly_summary:N}})"),
    PlaceholderItem("dynamics", "{{dynamics}}", "Running form averages: cadence, contact time, oscillation"),
    PlaceholderItem("running_stats", "{{running_stats}}", "Long-term monthly stats, bests, easy-run pace/HR"),
    PlaceholderItem("baseline", "{{baseline}}", "Saved athlete baseline profile"),
    PlaceholderItem("today", "{{today}}", "Today's date (yyyy-mm-dd)"),
    PlaceholderItem("language", "{{language}}", "Reply language configured in Settings"),
)

internal data class PlaceholderMatch(
    val isActive: Boolean,
    val query: String,
    val openIndex: Int,
)

internal fun detectPlaceholderQuery(text: String, cursor: Int): PlaceholderMatch {
    if (cursor !in 0..text.length) return PlaceholderMatch(false, "", -1)
    val lastOpen = text.substring(0, cursor).lastIndexOf("{{")
    if (lastOpen == -1) return PlaceholderMatch(false, "", -1)
    val candidate = text.substring(lastOpen + 2, cursor)
    if (candidate.contains("}}") || candidate.contains("}") ||
        candidate.contains("\n") || candidate.contains("\r") ||
        candidate.contains("{")
    ) {
        return PlaceholderMatch(false, "", -1)
    }
    return PlaceholderMatch(true, candidate.trim(), lastOpen)
}

internal fun insertPlaceholder(text: String, cursor: Int, openIndex: Int, tag: String): Pair<String, Int> {
    val prefix = text.substring(0, openIndex)
    val remainder = text.substring(cursor)
    val suffix = when {
        remainder.startsWith("}}") -> remainder.substring(2)
        remainder.startsWith("}") -> remainder.substring(1)
        else -> remainder
    }
    val insertion = "{{$tag}}"
    val newText = prefix + insertion + suffix
    val newCursor = prefix.length + insertion.length
    return Pair(newText, newCursor)
}

internal fun filterPlaceholders(query: String, placeholders: List<PlaceholderItem>): List<PlaceholderItem> {
    if (query.isEmpty()) return placeholders
    return placeholders.filter {
        it.tag.contains(query, ignoreCase = true) ||
        it.label.contains(query, ignoreCase = true) ||
        it.description.contains(query, ignoreCase = true)
    }.sortedBy {
        if (it.tag.startsWith(query, ignoreCase = true)) 0 else 1
    }
}

/**
 * Text field with intelligent placeholder insertion.
 * When the user types `{{`, a dropdown list opens.
 * As the user types after `{{`, the dropdown list filters in real-time based on input.
 * Selecting a placeholder inserts it into the text and places the cursor right after `}}`.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PlaceholderAwareTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    minLines: Int = 1,
    maxLines: Int = Int.MAX_VALUE,
    placeholders: List<PlaceholderItem> = PROMPT_PLACEHOLDERS,
) {
    var tfv by remember { mutableStateOf(TextFieldValue(value, TextRange(value.length))) }

    LaunchedEffect(value) {
        if (value != tfv.text) {
            val safeSelection = TextRange(value.length.coerceAtMost(tfv.selection.end))
            tfv = tfv.copy(text = value, selection = safeSelection)
        }
    }

    val text = tfv.text
    val cursor = tfv.selection.end
    val coroutineScope = rememberCoroutineScope()
    val bringIntoViewRequester = remember { BringIntoViewRequester() }

    val match = remember(text, cursor) { detectPlaceholderQuery(text, cursor) }
    val filteredPlaceholders = remember(match.query, placeholders, match.isActive) {
        if (!match.isActive) emptyList() else filterPlaceholders(match.query, placeholders)
    }

    var dismissedForIndex by remember { mutableIntStateOf(-1) }

    Box(modifier = modifier) {
        OutlinedTextField(
            value = tfv,
            onValueChange = { newTfv ->
                tfv = newTfv
                onValueChange(newTfv.text)
                coroutineScope.launch {
                    bringIntoViewRequester.bringIntoView()
                }
            },
            label = { Text(label) },
            shape = RoundedCornerShape(12.dp),
            minLines = minLines,
            maxLines = maxLines,
            modifier = Modifier
                .fillMaxWidth()
                .bringIntoViewRequester(bringIntoViewRequester)
                .onFocusChanged {
                    if (it.isFocused) {
                        coroutineScope.launch {
                            bringIntoViewRequester.bringIntoView()
                        }
                    }
                },
        )

        DropdownMenu(
            expanded = match.isActive && filteredPlaceholders.isNotEmpty() && dismissedForIndex != match.openIndex,
            onDismissRequest = { dismissedForIndex = match.openIndex },
            properties = PopupProperties(focusable = false),
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .heightIn(max = 260.dp)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        ) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 6.dp)) {
                Text(
                    if (match.query.isEmpty()) "Insert Placeholder (type to filter)" else "Placeholders matching \"${match.query}\":",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            filteredPlaceholders.forEach { item ->
                DropdownMenuItem(
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                item.label,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                item.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    },
                    onClick = {
                        val (newText, newCursor) = insertPlaceholder(text, cursor, match.openIndex, item.tag)
                        tfv = TextFieldValue(newText, TextRange(newCursor))
                        onValueChange(newText)
                        coroutineScope.launch {
                            bringIntoViewRequester.bringIntoView()
                        }
                    },
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        }
    }
}
