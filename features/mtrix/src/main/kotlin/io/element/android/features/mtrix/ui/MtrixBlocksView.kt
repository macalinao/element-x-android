/*
 * mtrix fork. SPDX-License-Identifier: AGPL-3.0-only
 */

package io.element.android.features.mtrix.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.element.android.compound.theme.ElementTheme
import io.element.android.features.mtrix.MtrixAction
import io.element.android.features.mtrix.MtrixBlock
import io.element.android.features.mtrix.MtrixButton
import io.element.android.features.mtrix.MtrixButtonStyle
import io.element.android.features.mtrix.MtrixPayload
import io.element.android.features.mtrix.MtrixStepStatus
import io.element.android.libraries.designsystem.theme.components.Text

/**
 * What the current user has already done on the event, for button state.
 *
 * @param mine reaction keys the current user has reacted with (not redacted; local echoes included).
 * @param counts per key, how many users other than the event's sender reacted (the bot's pre-seeded ✅/❌ don't count).
 */
data class MtrixInteractionState(
    val mine: Set<String> = emptySet(),
    val counts: Map<String, Int> = emptyMap(),
)

/**
 * The payload's blocks, drawn below Element X's own rendering of the message body (which is the complete fallback).
 *
 * @param onAction invoked when a not-yet-selected button or chip is tapped. Tapping a selected one is a no-op, not a
 *   toggle (spec: a redaction would look like undoing something the bot may already have acted on).
 */
@Composable
fun MtrixBlocksView(
    payload: MtrixPayload,
    interactions: MtrixInteractionState,
    canReact: Boolean,
    onAction: (MtrixAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Sends tapped in this session: a send button has no timeline-derived state here, and this also
    // absorbs double taps before the reaction's local echo shows up.
    var tapped by rememberSaveable { mutableStateOf(emptyList<String>()) }
    // Once the timeline reflects the user's reactions, it is the source of truth again for react buttons
    // (so removing the reaction through the reaction pill un-selects the button).
    LaunchedEffect(interactions.mine) { tapped = tapped.filterNot { it.startsWith("r:") } }
    fun isSelected(action: MtrixAction): Boolean = when (action) {
        is MtrixAction.React -> action.key in interactions.mine || "r:${action.key}" in tapped
        is MtrixAction.Send -> "s:${action.body}" in tapped
    }
    fun onTap(action: MtrixAction) {
        if (isSelected(action)) return
        if (action is MtrixAction.React && !canReact) return
        tapped = tapped + when (action) {
            is MtrixAction.React -> "r:${action.key}"
            is MtrixAction.Send -> "s:${action.body}"
        }
        onAction(action)
    }

    Column(
        modifier = modifier.padding(top = 4.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for (block in payload.blocks) {
            when (block) {
                is MtrixBlock.Buttons -> ButtonRow(block.buttons, chips = false, interactions, ::isSelected, ::onTap)
                is MtrixBlock.Chips -> ButtonRow(block.chips, chips = true, interactions, ::isSelected, ::onTap)
                is MtrixBlock.Table -> MtrixTableView(block)
                is MtrixBlock.Chart -> MtrixChartView(block)
                is MtrixBlock.Progress -> ProgressView(block)
                is MtrixBlock.Details -> DetailsView(block)
                is MtrixBlock.Code -> CodeView(block)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ButtonRow(
    buttons: List<MtrixButton>,
    chips: Boolean,
    interactions: MtrixInteractionState,
    isSelected: (MtrixAction) -> Boolean,
    onTap: (MtrixAction) -> Unit,
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        for (button in buttons) {
            val selected = isSelected(button.action)
            val count = (button.action as? MtrixAction.React)?.let { interactions.counts[it.key] } ?: 0
            Pill(button, chips, selected, count, onClick = { onTap(button.action) })
        }
    }
}

@Composable
private fun Pill(
    button: MtrixButton,
    chip: Boolean,
    selected: Boolean,
    count: Int,
    onClick: () -> Unit,
) {
    val colors = ElementTheme.colors
    val shape = RoundedCornerShape(50)
    val (bg, fg, border) = when {
        chip -> Triple(colors.bgSubtlePrimary, colors.textPrimary, null)
        button.style == MtrixButtonStyle.PRIMARY -> Triple(colors.bgActionPrimaryRest, colors.textOnSolidPrimary, null)
        button.style == MtrixButtonStyle.DANGER -> Triple(Color.Transparent, colors.textCriticalPrimary, colors.borderCriticalPrimary)
        else -> Triple(Color.Transparent, colors.textPrimary, colors.borderInteractiveSecondary)
    }
    // The exact reaction key / message body, like the web renderer's tooltip.
    val what = when (val a = button.action) {
        is MtrixAction.React -> "reacts ${a.key}"
        is MtrixAction.Send -> "sends \"${a.body}\""
    }
    Row(
        modifier = Modifier
            .clip(shape)
            .background(bg, shape)
            .let { if (border != null) it.border(1.dp, border, shape) else it }
            .clickable(role = Role.Button, onClick = onClick)
            .semantics {
                this.selected = selected
                contentDescription = "${button.label}, $what"
            }
            .padding(horizontal = if (chip) 10.dp else 14.dp, vertical = if (chip) 5.dp else 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selected) {
            Text(text = "✓ ", color = fg, style = ElementTheme.typography.fontBodySmMedium)
        }
        Text(
            text = button.label,
            color = fg,
            style = if (chip) ElementTheme.typography.fontBodySmRegular else ElementTheme.typography.fontBodySmMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (count > 0) {
            Text(
                text = " $count",
                color = fg.copy(alpha = 0.75f),
                style = ElementTheme.typography.fontBodyXsMedium,
            )
        }
    }
}

@Composable
private fun ProgressView(block: MtrixBlock.Progress) {
    val colors = ElementTheme.colors
    val done = block.steps.count { it.status == MtrixStepStatus.DONE }
    val failed = block.steps.count { it.status == MtrixStepStatus.FAILED }
    BlockFrame {
        Row(verticalAlignment = Alignment.CenterVertically) {
            block.title?.let {
                Text(it, style = ElementTheme.typography.fontBodyMdMedium, color = colors.textPrimary, modifier = Modifier.weight(1f, fill = false))
                Text("  ", style = ElementTheme.typography.fontBodySmRegular)
            }
            Text(
                text = "$done/${block.steps.size} done" + if (failed > 0) ", $failed failed" else "",
                style = ElementTheme.typography.fontBodySmRegular,
                color = colors.textSecondary,
            )
        }
        for (step in block.steps) {
            val (icon, tint) = when (step.status) {
                MtrixStepStatus.PENDING -> "○" to colors.iconTertiary
                MtrixStepStatus.RUNNING -> "◐" to colors.iconAccentPrimary
                MtrixStepStatus.DONE -> "✓" to colors.iconSuccessPrimary
                MtrixStepStatus.FAILED -> "✕" to colors.iconCriticalPrimary
                MtrixStepStatus.SKIPPED -> "–" to colors.iconTertiary
            }
            Row(modifier = Modifier.padding(top = 4.dp)) {
                Text(icon, color = tint, style = ElementTheme.typography.fontBodyMdMedium, modifier = Modifier.width(20.dp))
                Column(modifier = Modifier.weight(1f, fill = false)) {
                    val elapsed = step.startedAt?.let { s -> step.endedAt?.let { e -> formatElapsed(e - s) } }
                    Text(
                        text = step.label + (elapsed?.let { " ($it)" } ?: ""),
                        style = ElementTheme.typography.fontBodyMdRegular,
                        color = if (step.status == MtrixStepStatus.SKIPPED) colors.textSecondary else colors.textPrimary,
                        textDecoration = if (step.status == MtrixStepStatus.SKIPPED) TextDecoration.LineThrough else null,
                    )
                    step.detail?.let {
                        Text(it, style = ElementTheme.typography.fontBodySmRegular, color = colors.textSecondary)
                    }
                }
            }
        }
        block.summary?.let {
            Text(it, style = ElementTheme.typography.fontBodySmRegular, color = colors.textSecondary, modifier = Modifier.padding(top = 6.dp))
        }
    }
}

@Composable
private fun DetailsView(block: MtrixBlock.Details) {
    var open by rememberSaveable(block.summary) { mutableStateOf(block.open) }
    val colors = ElementTheme.colors
    BlockFrame {
        Text(
            text = (if (open) "▾ " else "▸ ") + block.summary,
            style = ElementTheme.typography.fontBodyMdMedium,
            color = colors.textPrimary,
            modifier = Modifier
                .fillMaxWidth()
                .clickable(role = Role.Button) { open = !open },
        )
        if (open) {
            Text(
                text = block.body,
                style = ElementTheme.typography.fontBodySmRegular.copy(fontFamily = FontFamily.Monospace),
                color = colors.textPrimary,
                modifier = Modifier
                    .padding(top = 6.dp)
                    .heightIn(max = 360.dp)
                    .verticalScroll(rememberScrollState()),
            )
        }
    }
}

@Composable
private fun CodeView(block: MtrixBlock.Code) {
    val colors = ElementTheme.colors
    @Suppress("DEPRECATION")
    val clipboard = LocalClipboardManager.current
    val lines = remember(block.code) { block.code.split("\n") }
    val added = if (block.isDiff) lines.count { it.startsWith("+") && !it.startsWith("+++") } else 0
    val removed = if (block.isDiff) lines.count { it.startsWith("-") && !it.startsWith("---") } else 0
    BlockFrame {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val header = listOfNotNull(block.filename, block.language).joinToString(" · ")
            Text(
                text = header + if (block.isDiff) "  +$added −$removed" else "",
                style = ElementTheme.typography.fontBodyXsMedium,
                color = colors.textSecondary,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "Copy",
                style = ElementTheme.typography.fontBodyXsMedium,
                color = colors.textActionAccent,
                modifier = Modifier
                    .clickable(role = Role.Button) { clipboard.setText(AnnotatedString(block.code)) }
                    .padding(4.dp),
            )
        }
        val code = remember(block.code, block.isDiff, colors) {
            if (!block.isDiff) {
                AnnotatedString(block.code)
            } else {
                diffAnnotated(lines, colors.textSuccessPrimary, colors.textCriticalPrimary, colors.textSecondary)
            }
        }
        Text(
            text = code,
            style = ElementTheme.typography.fontBodyXsRegular.copy(fontFamily = FontFamily.Monospace),
            color = colors.textPrimary,
            softWrap = false,
            modifier = Modifier
                .padding(top = 4.dp)
                .heightIn(max = 360.dp)
                .verticalScroll(rememberScrollState())
                .horizontalScroll(rememberScrollState()),
        )
        if (block.truncated) {
            Text("(truncated)", style = ElementTheme.typography.fontBodyXsRegular, color = colors.textSecondary)
        }
    }
}

private fun diffAnnotated(lines: List<String>, add: Color, del: Color, muted: Color): AnnotatedString =
    androidx.compose.ui.text.buildAnnotatedString {
        lines.forEachIndexed { i, l ->
            val style = when {
                l.startsWith("+++") || l.startsWith("---") -> androidx.compose.ui.text.SpanStyle(fontWeight = FontWeight.Bold)
                l.startsWith("+") -> androidx.compose.ui.text.SpanStyle(color = add)
                l.startsWith("-") -> androidx.compose.ui.text.SpanStyle(color = del)
                l.startsWith("@@") -> androidx.compose.ui.text.SpanStyle(color = muted)
                else -> null
            }
            if (style != null) pushStyle(style)
            append(l)
            if (style != null) pop()
            if (i < lines.lastIndex) append("\n")
        }
    }

/** A subtle rounded box that every non-button block sits in. */
@Composable
internal fun BlockFrame(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val colors = ElementTheme.colors
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(colors.bgCanvasDefault.copy(alpha = 0.6f))
            .border(1.dp, colors.borderInteractiveSecondary.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
            .padding(8.dp),
    ) { content() }
}

/** "45s", "1:05", "12:03:09". */
internal fun formatElapsed(ms: Long): String {
    val t = (ms / 1000).coerceAtLeast(0)
    val h = t / 3600
    val m = (t % 3600) / 60
    val s = t % 60
    return when {
        h > 0 -> "%d:%02d:%02d".format(h, m, s)
        m > 0 -> "%d:%02d".format(m, s)
        else -> "${s}s"
    }
}
