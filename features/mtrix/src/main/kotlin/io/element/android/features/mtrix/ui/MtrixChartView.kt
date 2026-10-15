/*
 * mtrix fork. SPDX-License-Identifier: AGPL-3.0-only
 */

package io.element.android.features.mtrix.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import io.element.android.compound.theme.ElementTheme
import io.element.android.features.mtrix.MtrixBlock
import io.element.android.features.mtrix.MtrixChartKind
import io.element.android.libraries.designsystem.theme.components.Text
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

// Categorical slots 1..6 of the dataviz reference palette, as in the web renderer (styles.ts).
private val PALETTE_LIGHT = listOf(0xFF2A78D6, 0xFFEB6834, 0xFF1BAF7A, 0xFFEDA100, 0xFFE87BA4, 0xFF008300).map { Color(it) }
private val PALETTE_DARK = listOf(0xFF3987E5, 0xFFD95926, 0xFF199E70, 0xFFC98500, 0xFFD55181, 0xFF008300).map { Color(it) }

/** Line/area/bar charts and sparklines drawn on a Compose Canvas (no chart library). Tap to read values. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MtrixChartView(block: MtrixBlock.Chart) {
    val colors = ElementTheme.colors
    val palette = if (colors.isLight) PALETTE_LIGHT else PALETTE_DARK
    val n = block.labels.size
    var selected by remember(block) { mutableStateOf<Int?>(null) }
    val description = remember(block) { MtrixChartMath.describe(block) }

    if (block.kind == MtrixChartKind.SPARKLINE) {
        val series = block.series.first()
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.semantics { contentDescription = description }) {
            block.title?.let { Text(it, style = ElementTheme.typography.fontBodySmRegular, color = colors.textSecondary) }
            Spacer(Modifier.width(8.dp))
            Canvas(modifier = Modifier.size(width = 96.dp, height = 24.dp)) {
                val (lo, hi) = MtrixChartMath.extent(listOf(series.values), includeZero = false)
                drawLines(series.values, lo, hi, Offset.Zero, size, palette[0], fill = false)
            }
            series.values.lastOrNull { it != null }?.let {
                Spacer(Modifier.width(8.dp))
                Text(MtrixChartMath.format(it) + (block.unit?.let { u -> " $u" } ?: ""), style = ElementTheme.typography.fontBodySmMedium, color = colors.textPrimary)
            }
        }
        return
    }

    val textMeasurer = rememberTextMeasurer()
    val axisStyle = ElementTheme.typography.fontBodyXsRegular.copy(color = colors.textSecondary)
    BlockFrame(modifier = Modifier.widthIn(min = 240.dp)) {
        block.title?.let { Text(it, style = ElementTheme.typography.fontBodySmMedium, color = colors.textPrimary) }
        // Readout: the tapped point's label and each series' value.
        val readout = selected?.takeIf { it < n }?.let { i ->
            val values = block.series.joinToString("  ·  ") { s ->
                val v = s.values.getOrNull(i)
                (s.name?.let { "$it " } ?: "") + (v?.let { MtrixChartMath.format(it) + (block.unit?.let { u -> " $u" } ?: "") } ?: "–")
            }
            "${block.labels[i]}:  $values"
        }
        Text(
            text = readout ?: " ",
            style = ElementTheme.typography.fontBodyXsMedium,
            color = colors.textPrimary,
            maxLines = 2,
        )
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(160.dp)
                .semantics { contentDescription = description }
                .pointerInput(block) {
                    detectTapGestures { pos ->
                        val left = MtrixChartMath.leftGutter(block, textMeasurer, axisStyle).toFloat()
                        val w = size.width - left
                        if (n > 0 && w > 0) {
                            val i = if (block.kind == MtrixChartKind.BAR) {
                                floor((pos.x - left) / (w / n)).toInt()
                            } else {
                                Math.round((pos.x - left) / (w / (n - 1).coerceAtLeast(1))).toInt()
                            }
                            selected = i.coerceIn(0, n - 1).takeIf { it != selected }
                        }
                    }
                },
        ) {
            drawChart(block, palette, textMeasurer, axisStyle, colors.borderInteractiveSecondary, colors.textSecondary, selected)
        }
        if (block.series.size > 1) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 4.dp)) {
                block.series.forEachIndexed { i, s ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Spacer(Modifier.size(8.dp).background(palette[i % palette.size], CircleShape))
                        Spacer(Modifier.width(4.dp))
                        Text(s.name ?: "Series ${i + 1}", style = ElementTheme.typography.fontBodyXsRegular, color = colors.textSecondary)
                    }
                }
            }
        }
    }
}

private fun DrawScope.drawChart(
    block: MtrixBlock.Chart,
    palette: List<Color>,
    textMeasurer: TextMeasurer,
    axisStyle: TextStyle,
    gridColor: Color,
    crosshairColor: Color,
    selected: Int?,
) {
    val n = block.labels.size
    val isBar = block.kind == MtrixChartKind.BAR
    val (lo0, hi0) = MtrixChartMath.extent(block.series.map { it.values }, includeZero = isBar || block.kind == MtrixChartKind.AREA)
    val ticks = MtrixChartMath.niceTicks(lo0, hi0)
    val lo = ticks.first()
    val hi = ticks.last()
    val left = MtrixChartMath.leftGutter(block, textMeasurer, axisStyle).toFloat()
    val bottom = 18.dp.toPx()
    val plot = Size(size.width - left, size.height - bottom - 4.dp.toPx())
    val origin = Offset(left, 4.dp.toPx())
    fun y(v: Double) = origin.y + plot.height - ((v - lo) / (hi - lo)).toFloat() * plot.height

    // Grid and y labels
    for (t in ticks) {
        val ty = y(t)
        drawLine(gridColor.copy(alpha = 0.5f), Offset(left, ty), Offset(size.width, ty), strokeWidth = 1f)
        val label = textMeasurer.measure(MtrixChartMath.compact(t), axisStyle)
        drawText(label, topLeft = Offset(left - label.size.width - 4.dp.toPx(), ty - label.size.height / 2f))
    }
    // x labels (about 6, always the last)
    val xOf: (Int) -> Float = if (isBar) {
        { i -> origin.x + plot.width / n * (i + 0.5f) }
    } else {
        { i -> origin.x + if (n <= 1) plot.width / 2 else plot.width * i / (n - 1) }
    }
    for (i in MtrixChartMath.labelIndices(n, 6)) {
        val label = textMeasurer.measure(block.labels[i], axisStyle, maxLines = 1)
        val x = (xOf(i) - label.size.width / 2f).coerceIn(left, size.width - label.size.width)
        drawText(label, topLeft = Offset(x, size.height - bottom + 2.dp.toPx()))
    }

    if (isBar) {
        val group = plot.width / n
        val k = block.series.size
        val barW = (group * 0.8f) / k
        val zeroY = y(0.0)
        if (selected != null && selected < n) {
            drawRect(crosshairColor.copy(alpha = 0.12f), Offset(origin.x + group * selected, origin.y), Size(group, plot.height))
        }
        block.series.forEachIndexed { si, s ->
            s.values.forEachIndexed { i, v ->
                if (v != null) {
                    val x = origin.x + group * i + group * 0.1f + barW * si
                    val top = minOf(y(v), zeroY)
                    drawRect(palette[si % palette.size], Offset(x, top), Size(barW * 0.9f, abs(y(v) - zeroY).coerceAtLeast(1f)))
                }
            }
        }
    } else {
        if (selected != null && selected < n) {
            val x = xOf(selected)
            drawLine(crosshairColor, Offset(x, origin.y), Offset(x, origin.y + plot.height), strokeWidth = 1.dp.toPx())
        }
        block.series.forEachIndexed { si, s ->
            drawLines(s.values, lo, hi, origin, plot, palette[si % palette.size], fill = block.kind == MtrixChartKind.AREA, n = n)
            if (selected != null) {
                s.values.getOrNull(selected)?.let { v -> drawCircle(palette[si % palette.size], 3.dp.toPx(), Offset(xOf(selected), y(v))) }
            }
        }
    }
}

/** A line through the values (null = gap), optionally with a light fill down to 0. */
private fun DrawScope.drawLines(
    values: List<Double?>,
    lo: Double,
    hi: Double,
    origin: Offset,
    plot: Size,
    color: Color,
    fill: Boolean,
    n: Int = values.size,
) {
    fun x(i: Int) = origin.x + if (n <= 1) plot.width / 2 else plot.width * i / (n - 1)
    fun y(v: Double) = origin.y + plot.height - (if (hi == lo) 0.5f else ((v - lo) / (hi - lo)).toFloat()) * plot.height
    val runs = mutableListOf<List<Int>>()
    var cur = mutableListOf<Int>()
    values.forEachIndexed { i, v ->
        if (v == null) {
            if (cur.isNotEmpty()) runs += cur
            cur = mutableListOf()
        } else {
            cur += i
        }
    }
    if (cur.isNotEmpty()) runs += cur
    for (run in runs) {
        if (fill) {
            val base = y(0.0.coerceIn(lo, hi))
            val area = Path().apply {
                moveTo(x(run.first()), base)
                run.forEach { lineTo(x(it), y(values[it]!!)) }
                lineTo(x(run.last()), base)
                close()
            }
            drawPath(area, color.copy(alpha = 0.18f))
        }
        if (run.size == 1) {
            drawCircle(color, 2.dp.toPx(), Offset(x(run[0]), y(values[run[0]]!!)))
        } else {
            val path = Path().apply {
                moveTo(x(run.first()), y(values[run.first()]!!))
                run.drop(1).forEach { lineTo(x(it), y(values[it]!!)) }
            }
            drawPath(path, color, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}

/** Pure chart geometry and formatting (ported from the web renderer's lib/chart.ts), kept separate for tests. */
object MtrixChartMath {
    /** "Nice" axis ticks covering [min, max]. */
    fun niceTicks(min0: Double, max0: Double, count: Int = 4): List<Double> {
        var min = min0
        var max = max0
        if (min == max) {
            val pad = abs(min).takeIf { it != 0.0 } ?: 1.0
            min -= pad
            max += pad
        }
        val raw = (max - min) / count
        val mag = 10.0.pow(floor(log10(raw)))
        val step = listOf(1.0, 2.0, 2.5, 5.0, 10.0).map { it * mag }.firstOrNull { it >= raw } ?: (10 * mag)
        val lo = floor(min / step) * step
        val hi = ceil(max / step) * step
        val ticks = mutableListOf<Double>()
        var v = lo
        while (v <= hi + step / 2) {
            ticks += String.format(java.util.Locale.ROOT, "%.12g", v).toDouble()
            v += step
        }
        return ticks
    }

    /** Extent of all values; bar/area charts always include 0. */
    fun extent(series: List<List<Double?>>, includeZero: Boolean): Pair<Double, Double> {
        val all = series.flatten().filterNotNull()
        if (all.isEmpty()) return 0.0 to 1.0
        var lo = all.min()
        var hi = all.max()
        if (includeZero) {
            lo = minOf(lo, 0.0)
            hi = maxOf(hi, 0.0)
        }
        return lo to hi
    }

    /** Indices of x labels to show so they don't collide (about [max] of them, always the last). */
    fun labelIndices(n: Int, max: Int): List<Int> {
        if (n <= max) return (0 until n).toList()
        val step = ceil(n.toDouble() / max).toInt()
        val out = (0 until n step step).toMutableList()
        if (out.last() != n - 1) {
            if (n - 1 - out.last() < step / 2.0) out.removeAt(out.lastIndex)
            out += n - 1
        }
        return out
    }

    /** Compact axis label: 950, 1.2k, 3.4M. */
    fun compact(v: Double): String {
        val a = abs(v)
        fun trim(d: Double) = java.text.NumberFormat.getNumberInstance().apply { maximumFractionDigits = 1 }.format(d)
        return when {
            a >= 1e9 -> trim(v / 1e9) + "B"
            a >= 1e6 -> trim(v / 1e6) + "M"
            a >= 1e3 -> trim(v / 1e3) + "k"
            else -> format(v)
        }
    }

    /** A value with locale grouping and at most 2 decimals. */
    fun format(v: Double): String {
        val nf = java.text.NumberFormat.getNumberInstance()
        nf.maximumFractionDigits = 2
        return nf.format(v)
    }

    fun describe(block: MtrixBlock.Chart): String {
        val kind = block.kind.name.lowercase()
        val names = block.series.mapIndexed { i, s -> s.name ?: "series ${i + 1}" }.joinToString(", ")
        return listOfNotNull(block.title, "$kind chart of $names over ${block.labels.size} points").joinToString(": ")
    }

    internal fun leftGutter(block: MtrixBlock.Chart, textMeasurer: TextMeasurer, style: TextStyle): Int {
        val (lo, hi) = extent(block.series.map { it.values }, includeZero = block.kind == MtrixChartKind.BAR || block.kind == MtrixChartKind.AREA)
        val widest = niceTicks(lo, hi).maxOf { textMeasurer.measure(compact(it), style).size.width }
        return widest + 8
    }
}
