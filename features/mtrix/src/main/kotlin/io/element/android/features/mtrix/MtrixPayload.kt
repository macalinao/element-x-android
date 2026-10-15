/*
 * mtrix fork. SPDX-License-Identifier: AGPL-3.0-only
 */

package io.element.android.features.mtrix

import androidx.compose.runtime.Immutable

/** A parsed, normalised `com.ianm.mtrix` payload: only blocks this renderer supports, every value capped. */
@Immutable
data class MtrixPayload(
    val version: Int,
    val blocks: List<MtrixBlock>,
)

/** What a button or chip does when tapped. Nothing else is possible (spec: safety rule 5). */
@Immutable
sealed interface MtrixAction {
    /** Send the user's own `m.reaction` (`m.annotation` on this event) with [key]. */
    data class React(val key: String) : MtrixAction

    /** Send [body] as an `m.text` reply to this event. */
    data class Send(val body: String) : MtrixAction
}

enum class MtrixButtonStyle { DEFAULT, PRIMARY, DANGER }

@Immutable
data class MtrixButton(
    val label: String,
    val style: MtrixButtonStyle,
    val action: MtrixAction,
)

enum class MtrixAlign { LEFT, RIGHT, CENTER }

@Immutable
data class MtrixColumn(
    val key: String,
    val label: String,
    val align: MtrixAlign?,
)

/** A table cell: a [String], a [Double] (numbers stay numeric for alignment/sorting) or null. */
typealias MtrixCell = Any?

enum class MtrixChartKind { LINE, BAR, AREA, SPARKLINE }

@Immutable
data class MtrixSeries(
    val name: String?,
    /** null = gap */
    val values: List<Double?>,
)

enum class MtrixStepStatus { PENDING, RUNNING, DONE, FAILED, SKIPPED }

@Immutable
data class MtrixStep(
    val id: String,
    val label: String,
    val status: MtrixStepStatus,
    val detail: String?,
    val startedAt: Long?,
    val endedAt: Long?,
)

@Immutable
sealed interface MtrixBlock {
    data class Buttons(val buttons: List<MtrixButton>) : MtrixBlock

    data class Chips(val chips: List<MtrixButton>) : MtrixBlock

    data class Table(
        val caption: String?,
        val columns: List<MtrixColumn>,
        /** rows[r][c] for columns[c] */
        val rows: List<List<MtrixCell>>,
        val truncated: Boolean,
    ) : MtrixBlock

    data class Chart(
        val kind: MtrixChartKind,
        val title: String?,
        /** x labels, padded/trimmed to the longest series */
        val labels: List<String>,
        val series: List<MtrixSeries>,
        val unit: String?,
    ) : MtrixBlock

    data class Progress(
        val title: String?,
        val steps: List<MtrixStep>,
        val summary: String?,
    ) : MtrixBlock

    data class Details(
        val summary: String,
        val body: String,
        val open: Boolean,
    ) : MtrixBlock

    data class Code(
        val language: String?,
        val filename: String?,
        val code: String,
        val truncated: Boolean,
        /** `diff` blocks (and `code` with language "diff") get +/- line colouring. */
        val isDiff: Boolean,
    ) : MtrixBlock
}
