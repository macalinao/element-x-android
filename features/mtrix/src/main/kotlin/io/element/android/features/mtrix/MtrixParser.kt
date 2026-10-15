/*
 * mtrix fork. SPDX-License-Identifier: AGPL-3.0-only
 */

package io.element.android.features.mtrix

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.math.floor

/**
 * Total, defensive parser for `com.ianm.mtrix` v1, a port of the Element Web renderer's `schema/` (macalinao/mtrix,
 * `element/src/schema`). Anything malformed, unknown or over a limit is dropped; a payload with nothing renderable
 * yields null so the message renders exactly as upstream Element X would. Nothing parsed here is ever rendered as HTML.
 *
 * Block types this renderer doesn't implement (sources, card, cards, countdown, event, place, ticker, weather, ...)
 * are skipped like unknown types; the message body is always shown, so they degrade to the text fallback.
 */
object MtrixParser {
    object Limits {
        const val BLOCKS = 8
        const val LABEL_CHARS = 64
        const val LINE_CHARS = 200
        const val TEXT_CHARS = 4000
        const val REACTION_KEY_CHARS = 64
        const val SEND_BODY_CHARS = 1000
        const val BUTTONS_PER_BLOCK = 10
        const val CHIPS_PER_BLOCK = 12
        const val TABLE_COLUMNS = 12
        const val TABLE_ROWS = 200
        const val CELL_CHARS = 200
        const val CHART_SERIES = 6
        const val CHART_POINTS = 200
        const val CHART_LABEL_CHARS = 24
        const val CHART_UNIT_CHARS = 12
        const val PROGRESS_STEPS = 50
        const val STEP_TEXT_CHARS = 1000
        const val CODE_CHARS = 20_000
        const val CODE_LINES = 500
    }

    private val json = Json { isLenient = false }

    /**
     * The payload of a message given its raw event JSON, as returned by the SDK's event debug info.
     *
     * @param originalJson the original event (decrypted, for encrypted rooms).
     * @param latestEditJson the latest applied `m.replace` edit, if any. Its `m.new_content` replaces the whole content,
     *   so an edit without the key removes the blocks (the SDK only applies edits from the original sender).
     */
    fun parseEventJson(originalJson: String?, latestEditJson: String?): MtrixPayload? = try {
        val content = if (latestEditJson != null) {
            (json.parseToJsonElement(latestEditJson).obj()?.get("content").obj())?.get("m.new_content").obj()
        } else {
            originalJson?.let { json.parseToJsonElement(it).obj()?.get("content").obj() }
        }
        parseContent(content)
    } catch (_: Exception) {
        null
    }

    /** Cheap check on a raw JSON string before a full parse. */
    fun mayContainPayload(rawJson: String?): Boolean =
        rawJson != null && (rawJson.contains(MtrixConfig.RICH_KEY) || rawJson.contains(MtrixConfig.LEGACY_RICH_KEY))

    /** The payload of a message's (latest) content object, or null when absent, malformed, newer, or empty. */
    fun parseContent(content: JsonObject?): MtrixPayload? = try {
        val raw = content?.get(MtrixConfig.RICH_KEY).obj() ?: content?.get(MtrixConfig.LEGACY_RICH_KEY).obj()
        raw?.let(::parseRich)
    } catch (_: Exception) {
        null
    }

    private fun parseRich(raw: JsonObject): MtrixPayload? {
        val v = num(raw["v"]) ?: return null
        if (v != floor(v) || v < 1 || v > MtrixConfig.RICH_VERSION) return null
        val blocks = raw["blocks"] as? JsonArray ?: return null
        val parsed = blocks.take(Limits.BLOCKS).mapNotNull(::parseBlock)
        return if (parsed.isEmpty()) null else MtrixPayload(v.toInt(), parsed)
    }

    /** One block, or null when malformed or of a type this renderer doesn't implement. */
    fun parseBlock(v: JsonElement?): MtrixBlock? {
        val o = v.obj() ?: return null
        val type = (o["type"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
        return try {
            when (type) {
                "buttons" -> list(o["buttons"], Limits.BUTTONS_PER_BLOCK, ::parseButton).takeIf { it.isNotEmpty() }?.let { MtrixBlock.Buttons(it) }
                "chips" -> list(o["chips"], Limits.CHIPS_PER_BLOCK, ::parseButton).takeIf { it.isNotEmpty() }?.let { MtrixBlock.Chips(it) }
                "table" -> parseTable(o)
                "chart" -> parseChart(o)
                "progress" -> parseProgress(o)
                "details" -> parseDetails(o)
                "code" -> parseCode(o, diff = false)
                "diff" -> parseCode(o, diff = true)
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    // ------------------------------------------------------------ actions / buttons

    fun parseAction(v: JsonElement?): MtrixAction? {
        val o = v.obj() ?: return null
        return when (str(o["type"])) {
            "react" -> {
                // Reaction keys are exact strings (emoji): reject rather than mangle.
                val key = str(o["key"]) ?: return null
                if (key.isBlank() || key.length > Limits.REACTION_KEY_CHARS) null else MtrixAction.React(key)
            }
            "send" -> {
                val body = str(o["body"]) ?: return null
                if (body.isBlank() || body.length > Limits.SEND_BODY_CHARS) null else MtrixAction.Send(body)
            }
            else -> null
        }
    }

    private fun parseButton(v: JsonElement): MtrixButton? {
        val o = v.obj() ?: return null
        val label = line(o["label"]) ?: return null
        val action = parseAction(o["action"]) ?: return null
        val style = when (str(o["style"])) {
            "primary" -> MtrixButtonStyle.PRIMARY
            "danger" -> MtrixButtonStyle.DANGER
            else -> MtrixButtonStyle.DEFAULT
        }
        return MtrixButton(label, style, action)
    }

    // ------------------------------------------------------------ table

    private fun parseTable(v: JsonObject): MtrixBlock.Table? {
        val seen = HashSet<String>()
        val columns = list(v["columns"], Limits.TABLE_COLUMNS) { c ->
            // A column is `{key, label?, align?}` or just a string (key = label).
            val o = if (c is JsonPrimitive && c.isString) JsonObject(mapOf("key" to c)) else c.obj()
            val key = o?.let { line(it["key"], Limits.LABEL_CHARS) }
            if (o == null || key == null || !seen.add(key)) {
                null
            } else {
                MtrixColumn(
                    key = key,
                    label = line(o["label"]) ?: key,
                    align = when (str(o["align"])) {
                        "left" -> MtrixAlign.LEFT
                        "right" -> MtrixAlign.RIGHT
                        "center" -> MtrixAlign.CENTER
                        else -> null
                    },
                )
            }
        }
        val rawRows = v["rows"] as? JsonArray
        if (columns.isEmpty() || rawRows == null) return null
        val rows = list(rawRows, Limits.TABLE_ROWS) { r ->
            // A row is an object keyed by column key, or an array in column order.
            when (r) {
                is JsonArray -> columns.indices.map { i -> scalar(r.getOrNull(i)) }
                is JsonObject -> columns.map { c -> scalar(r[c.key]) }
                else -> null
            }
        }
        if (rows.isEmpty()) return null
        return MtrixBlock.Table(
            caption = line(v["caption"], Limits.LINE_CHARS),
            columns = columns,
            rows = rows,
            truncated = rawRows.size > Limits.TABLE_ROWS,
        )
    }

    // ------------------------------------------------------------ chart

    private fun seriesValues(v: JsonElement?): List<Double?>? {
        val a = v as? JsonArray ?: return null
        val values = a.take(Limits.CHART_POINTS).map { num(it) }
        return if (values.any { it != null }) values else null
    }

    private fun parseChart(v: JsonObject): MtrixBlock.Chart? {
        val kind = when (str(v["kind"])) {
            "line" -> MtrixChartKind.LINE
            "bar" -> MtrixChartKind.BAR
            "area" -> MtrixChartKind.AREA
            "sparkline" -> MtrixChartKind.SPARKLINE
            else -> return null
        }
        // `series` is `[{name?, values}]`; a bare number array is one unnamed series.
        val rawSeries = v["series"]
        var series: List<MtrixSeries> =
            if (rawSeries is JsonArray && rawSeries.all { it is JsonNull || (it is JsonPrimitive && !it.isString && it.doubleOrNull != null) }) {
                seriesValues(rawSeries)?.let { listOf(MtrixSeries(null, it)) }.orEmpty()
            } else {
                list(rawSeries, Limits.CHART_SERIES) { s ->
                    val o = s.obj()
                    val values = o?.let { seriesValues(it["values"]) }
                    if (values == null) null else MtrixSeries(line(o["name"]), values)
                }
            }
        if (series.isEmpty()) return null
        if (kind == MtrixChartKind.SPARKLINE) series = series.take(1)
        val n = series.maxOf { it.values.size }
        val rawLabels = (v["labels"] as? JsonArray)?.take(n).orEmpty()
        val labels = List(n) { i -> scalar(rawLabels.getOrNull(i), Limits.CHART_LABEL_CHARS)?.let(::cellText) ?: "" }
        return MtrixBlock.Chart(
            kind = kind,
            title = line(v["title"], Limits.LINE_CHARS),
            labels = labels,
            series = series,
            unit = line(v["unit"], Limits.CHART_UNIT_CHARS),
        )
    }

    // ------------------------------------------------------------ progress / details / code

    private fun parseProgress(v: JsonObject): MtrixBlock.Progress? {
        val steps = list(v["steps"], Limits.PROGRESS_STEPS) { s ->
            val o = s.obj() ?: return@list null
            val label = line(o["label"], Limits.LINE_CHARS) ?: return@list null
            val status = when (str(o["status"])) {
                "pending" -> MtrixStepStatus.PENDING
                "running" -> MtrixStepStatus.RUNNING
                "done" -> MtrixStepStatus.DONE
                "failed" -> MtrixStepStatus.FAILED
                "skipped" -> MtrixStepStatus.SKIPPED
                else -> return@list null
            }
            MtrixStep(
                id = line(o["id"], Limits.LABEL_CHARS) ?: label,
                label = label,
                status = status,
                detail = text(o["detail"], Limits.STEP_TEXT_CHARS),
                startedAt = timestamp(o["started_at"]),
                endedAt = timestamp(o["ended_at"]),
            )
        }
        if (steps.isEmpty()) return null
        return MtrixBlock.Progress(
            title = line(v["title"], Limits.LINE_CHARS),
            steps = steps,
            summary = text(v["summary"], Limits.STEP_TEXT_CHARS),
        )
    }

    private fun parseDetails(v: JsonObject): MtrixBlock.Details? {
        val summary = line(v["summary"], Limits.LINE_CHARS) ?: return null
        val body = text(v["body"], Limits.TEXT_CHARS) ?: return null
        val open = (v["open"] as? JsonPrimitive)?.let { !it.isString && it.booleanOrNull == true } == true
        return MtrixBlock.Details(summary, body, open)
    }

    private fun parseCode(v: JsonObject, diff: Boolean): MtrixBlock.Code? {
        var s = str(v[if (diff) "diff" else "code"])?.takeIf { it.isNotBlank() } ?: return null
        s = s.replace(Regex("\r\n?"), "\n").replace("\u0000", "")
        var truncated = false
        if (s.length > Limits.CODE_CHARS) {
            s = s.substring(0, Limits.CODE_CHARS)
            truncated = true
        }
        val lines = s.split("\n")
        if (lines.size > Limits.CODE_LINES) {
            s = lines.take(Limits.CODE_LINES).joinToString("\n")
            truncated = true
        }
        s = s.removeSuffix("\n")
        val language = if (diff) null else line(v["language"], 32)
        return MtrixBlock.Code(
            language = language,
            filename = line(v["filename"], Limits.LINE_CHARS),
            code = s,
            truncated = truncated,
            isDiff = diff || language == "diff",
        )
    }

    // ------------------------------------------------------------ helpers (mirror schema/common.ts)

    private fun JsonElement?.obj(): JsonObject? = this as? JsonObject

    private fun str(v: JsonElement?): String? = (v as? JsonPrimitive)?.takeIf { it.isString }?.content

    private val oneLineRegex = Regex("[\\u0000-\\u001f\\u007f-\\u009f\\s]+")
    private val textControlRegex = Regex("[\\u0000-\\u0008\\u000b-\\u001f\\u007f-\\u009f]")

    /** Cut by code points so emoji aren't split into lone surrogates; "…" marks the cut. */
    private fun cap(s: String, max: Int): String {
        val count = s.codePointCount(0, s.length)
        if (count <= max) return s
        return s.substring(0, s.offsetByCodePoints(0, max - 1)) + "…"
    }

    /** One line: whitespace and control characters collapsed, trimmed, capped. */
    fun line(v: JsonElement?, max: Int = Limits.LABEL_CHARS): String? {
        val s = str(v)?.replace(oneLineRegex, " ")?.trim() ?: return null
        return if (s.isEmpty()) null else cap(s, max)
    }

    /** Multi-line plain text: newlines and tabs kept, other control chars dropped, capped. */
    fun text(v: JsonElement?, max: Int = Limits.TEXT_CHARS): String? {
        val s = str(v)?.replace(Regex("\r\n?"), "\n")?.replace(textControlRegex, "") ?: return null
        return if (s.isBlank()) null else cap(s, max)
    }

    /** A finite number (numeric strings are not accepted). */
    fun num(v: JsonElement?): Double? {
        val p = v as? JsonPrimitive ?: return null
        if (p.isString || p is JsonNull) return null
        return p.doubleOrNull?.takeIf { it.isFinite() }
    }

    /** A cell value: numbers kept as [Double], booleans as text, strings as one line; else null. */
    fun scalar(v: JsonElement?, max: Int = Limits.CELL_CHARS): MtrixCell {
        val p = v as? JsonPrimitive ?: return null
        if (p is JsonNull) return null
        if (!p.isString) {
            p.booleanOrNull?.let { return if (it) "true" else "false" }
            return num(p)
        }
        return line(p, max)
    }

    /** Text of a cell value the way JavaScript's String() shows it (2024, not 2024.0). */
    fun cellText(v: MtrixCell): String = when (v) {
        null -> ""
        is Double -> jsNumber(v)
        else -> v.toString()
    }

    fun jsNumber(d: Double): String =
        if (d == floor(d) && kotlin.math.abs(d) < 1e15) d.toLong().toString() else d.toString()

    /**
     * A point in time as ms since the epoch. Numbers are ms; strings must be ISO 8601 with a date. Years outside
     * 2000..2200 are rejected (catches seconds-vs-ms mistakes).
     */
    fun timestamp(v: JsonElement?): Long? {
        val ms: Double? = num(v) ?: str(v)?.takeIf { it.length <= 40 && Regex("^\\d{4}-\\d{2}-\\d{2}").containsMatchIn(it) }?.let(::parseIso)
        if (ms == null) return null
        return if (ms >= 946_684_800_000.0 && ms < 7_258_118_400_000.0) Math.round(ms) else null
    }

    private fun parseIso(s: String): Double? {
        runCatching { return OffsetDateTime.parse(s).toInstant().toEpochMilli().toDouble() }
        runCatching { return Instant.parse(s).toEpochMilli().toDouble() }
        // Like JavaScript's Date.parse: a date-time without offset is local time, a bare date is UTC.
        runCatching { return LocalDateTime.parse(s).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli().toDouble() }
        runCatching { return LocalDate.parse(s).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli().toDouble() }
        return null
    }

    /** Map an array through a parser, capped at [max] input items, nulls dropped. */
    private fun <T : Any> list(v: JsonElement?, max: Int, parse: (JsonElement) -> T?): List<T> {
        val a = v as? JsonArray ?: return emptyList()
        return a.take(max).mapNotNull(parse)
    }
}
