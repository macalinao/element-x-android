/*
 * mtrix fork. SPDX-License-Identifier: AGPL-3.0-only
 */

package io.element.android.features.mtrix.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import io.element.android.compound.theme.ElementTheme
import io.element.android.features.mtrix.MtrixAlign
import io.element.android.features.mtrix.MtrixBlock
import io.element.android.features.mtrix.MtrixCell
import io.element.android.features.mtrix.MtrixParser
import io.element.android.libraries.designsystem.theme.components.Text
import java.text.NumberFormat

/**
 * A compact table, scrolling both ways (max 360dp tall). Tapping a header sorts by that column: ascending, descending,
 * then the original order (spec `table`). CSV download is not implemented on Android.
 */
@Composable
internal fun MtrixTableView(block: MtrixBlock.Table) {
    val colors = ElementTheme.colors
    // sort = column index * 2 + (0 asc | 1 desc); -1 = original order
    var sort by rememberSaveable(block.columns.size) { mutableStateOf(-1) }
    val rows = remember(block, sort) {
        if (sort < 0) block.rows else MtrixTableSort.sorted(block.rows, sort / 2, descending = sort % 2 == 1)
    }
    val aligns = remember(block) { block.columns.indices.map { c -> MtrixTableSort.alignment(block, c) } }
    val numberFormat = remember { NumberFormat.getNumberInstance() }

    BlockFrame {
        block.caption?.let {
            Text(it, style = ElementTheme.typography.fontBodySmMedium, color = colors.textPrimary, modifier = Modifier.padding(bottom = 4.dp))
        }
        Column(
            modifier = Modifier
                .heightIn(max = 360.dp)
                .verticalScroll(rememberScrollState())
                .horizontalScroll(rememberScrollState()),
        ) {
            val cells = buildList {
                // header row first, then data rows; laid out as a grid below
                block.columns.forEachIndexed { c, col ->
                    val marker = when (sort) {
                        c * 2 -> " ▲"
                        c * 2 + 1 -> " ▼"
                        else -> ""
                    }
                    add(CellSpec(col.label + marker, aligns[c], header = true, onClick = {
                        sort = when (sort) {
                            c * 2 -> c * 2 + 1
                            c * 2 + 1 -> -1
                            else -> c * 2
                        }
                    }))
                }
                for (row in rows) {
                    row.forEachIndexed { c, v ->
                        val text = if (v is Double) numberFormat.format(v) else MtrixParser.cellText(v)
                        add(CellSpec(text, aligns[c], header = false, onClick = null))
                    }
                }
            }
            Grid(columns = block.columns.size, cells = cells)
        }
        if (block.truncated) {
            Text("(truncated)", style = ElementTheme.typography.fontBodyXsRegular, color = colors.textSecondary)
        }
    }
}

private class CellSpec(val text: String, val align: MtrixAlign, val header: Boolean, val onClick: (() -> Unit)?)

/** A simple grid: every column as wide as its widest cell (cells capped at 220dp), rows as tall as their tallest cell. */
@Composable
private fun Grid(columns: Int, cells: List<CellSpec>) {
    val colors = ElementTheme.colors
    Layout(
        content = {
            cells.forEachIndexed { i, cell ->
                val rowIndex = i / columns
                Text(
                    text = cell.text,
                    style = if (cell.header) ElementTheme.typography.fontBodySmMedium else ElementTheme.typography.fontBodySmRegular,
                    color = if (cell.header) colors.textSecondary else colors.textPrimary,
                    textAlign = when (cell.align) {
                        MtrixAlign.LEFT -> TextAlign.Start
                        MtrixAlign.RIGHT -> TextAlign.End
                        MtrixAlign.CENTER -> TextAlign.Center
                    },
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .let { if (rowIndex % 2 == 0 && rowIndex > 0) it.background(colors.bgSubtleSecondary) else it }
                        .let { m -> cell.onClick?.let { m.clickable(role = Role.Button, onClick = it) } ?: m }
                        .widthIn(max = 220.dp)
                        .padding(horizontal = 6.dp, vertical = 3.dp),
                )
            }
        },
    ) { measurables, _ ->
        val rowsCount = if (columns == 0) 0 else (measurables.size + columns - 1) / columns
        val loose = Constraints()
        // First pass: intrinsic widths to size the columns.
        val widths = IntArray(columns)
        measurables.forEachIndexed { i, m ->
            val w = m.maxIntrinsicWidth(Constraints.Infinity)
            widths[i % columns] = maxOf(widths[i % columns], minOf(w, 220.dp.roundToPx()))
        }
        val placeables = measurables.mapIndexed { i, m ->
            val w = widths[i % columns]
            m.measure(loose.copy(minWidth = w, maxWidth = w))
        }
        val heights = IntArray(rowsCount)
        placeables.forEachIndexed { i, p -> heights[i / columns] = maxOf(heights[i / columns], p.height) }
        val totalW = widths.sum()
        val totalH = heights.sum()
        layout(totalW, totalH) {
            var y = 0
            for (r in 0 until rowsCount) {
                var x = 0
                for (c in 0 until columns) {
                    placeables.getOrNull(r * columns + c)?.placeRelative(x, y)
                    x += widths[c]
                }
                y += heights[r]
            }
        }
    }
}

/** Sorting and alignment rules from the spec, kept pure for tests. */
object MtrixTableSort {
    // An optional currency code ("PHP ") or symbol ("₱"), the number, an optional "%". Not "RK84" or "item 9".
    private val numberLike = Regex("^(?:[A-Z]{2,4}\\s+)?[^\\p{L}\\d\\s+.-]*\\s*([-+]?(?:\\d[\\d,]*(?:\\.\\d+)?|\\.\\d+))\\s*%?$")

    /** A cell read as a number: numbers, and strings like `1,234`, `₱1,200`, `12.5%`, `PHP 99`. */
    fun numeric(v: MtrixCell): Double? = when (v) {
        is Double -> v
        is String -> numberLike.matchEntire(v.trim())?.groupValues?.get(1)?.replace(",", "")?.toDoubleOrNull()
        else -> null
    }

    fun sorted(rows: List<List<MtrixCell>>, column: Int, descending: Boolean): List<List<MtrixCell>> {
        val cmp = Comparator<List<MtrixCell>> { a, b ->
            val x = a.getOrNull(column)
            val y = b.getOrNull(column)
            val ex = x == null || x == ""
            val ey = y == null || y == ""
            when {
                // Empty cells always sort last, whatever the direction.
                ex && ey -> 0
                ex -> if (descending) -1 else 1
                ey -> if (descending) 1 else -1
                else -> {
                    val nx = numeric(x)
                    val ny = numeric(y)
                    if (nx != null && ny != null) nx.compareTo(ny) else naturalCompare(MtrixParser.cellText(x), MtrixParser.cellText(y))
                }
            }
        }
        val asc = rows.sortedWith(cmp)
        return if (descending) asc.reversed() else asc
    }

    /** Natural order: `item 9` before `item 10`, case-insensitive. */
    fun naturalCompare(a: String, b: String): Int {
        val ta = Regex("\\d+|\\D+").findAll(a.lowercase()).map { it.value }.toList()
        val tb = Regex("\\d+|\\D+").findAll(b.lowercase()).map { it.value }.toList()
        for (i in 0 until minOf(ta.size, tb.size)) {
            val x = ta[i]
            val y = tb[i]
            val c = if (x[0].isDigit() && y[0].isDigit()) {
                x.toBigInteger().compareTo(y.toBigInteger())
            } else {
                x.compareTo(y)
            }
            if (c != 0) return c
        }
        return ta.size.compareTo(tb.size)
    }

    /** Explicit `align`, else right when most non-empty cells read as numbers, else left. */
    fun alignment(block: MtrixBlock.Table, column: Int): MtrixAlign {
        block.columns[column].align?.let { return it }
        val cells = block.rows.mapNotNull { it.getOrNull(column) }.filter { it != "" }
        if (cells.isEmpty()) return MtrixAlign.LEFT
        val numeric = cells.count { numeric(it) != null }
        return if (numeric * 2 > cells.size) MtrixAlign.RIGHT else MtrixAlign.LEFT
    }
}
