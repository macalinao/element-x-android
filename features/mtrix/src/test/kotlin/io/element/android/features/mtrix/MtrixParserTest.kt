/*
 * mtrix fork. SPDX-License-Identifier: AGPL-3.0-only
 */

package io.element.android.features.mtrix

import com.google.common.truth.Truth.assertThat
import io.element.android.features.mtrix.ui.MtrixChartMath
import io.element.android.features.mtrix.ui.MtrixTableSort
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Test

class MtrixParserTest {
    private fun content(json: String): JsonObject = Json.parseToJsonElement(json).jsonObject
    private fun rich(vararg blocks: String, v: String = "1") = content("""{"msgtype":"m.notice","body":"x","com.ianm.mtrix":{"v":$v,"blocks":[${blocks.joinToString(",")}]}}""")
    private fun block(json: String) = MtrixParser.parseBlock(Json.parseToJsonElement(json))

    private val btn = """{"type":"buttons","buttons":[{"label":"Ok","action":{"type":"react","key":"👍"}}]}"""

    @Test
    fun `envelope - version, keys, legacy fallback, unknown blocks`() {
        assertThat(MtrixParser.parseContent(rich(btn))?.blocks).hasSize(1)
        assertThat(MtrixParser.parseContent(rich(btn, v = "2"))).isNull()
        assertThat(MtrixParser.parseContent(rich(btn, v = "1.5"))).isNull()
        assertThat(MtrixParser.parseContent(rich(btn, v = "\"1\""))).isNull()
        assertThat(MtrixParser.parseContent(rich(btn, v = "0"))).isNull()
        assertThat(MtrixParser.parseContent(content("""{"com.ianm.mtrix":{"v":1}}"""))).isNull()
        assertThat(MtrixParser.parseContent(content("""{"com.ianm.mtrix":"nope"}"""))).isNull()
        assertThat(MtrixParser.parseContent(content("""{"body":"plain"}"""))).isNull()
        assertThat(MtrixParser.parseContent(null)).isNull()
        // nothing renderable -> null (tile untouched)
        assertThat(MtrixParser.parseContent(rich("""{"type":"hologram"}"""))).isNull()
        // unknown (and unimplemented) types are skipped, the rest kept in order
        val p = MtrixParser.parseContent(rich("""{"type":"hologram"}""", """{"type":"weather","location":"x","temp":1}""", btn))
        assertThat(p?.blocks?.map { it::class }).containsExactly(MtrixBlock.Buttons::class)
        // legacy key read only when the new one is absent
        assertThat(MtrixParser.parseContent(content("""{"stream.pilipili.rich":{"v":1,"blocks":[$btn]}}"""))?.blocks).hasSize(1)
        assertThat(
            MtrixParser.parseContent(content("""{"com.ianm.mtrix":{"v":1,"blocks":[]},"stream.pilipili.rich":{"v":1,"blocks":[$btn]}}"""))
        ).isNull()
        // block cap
        assertThat(MtrixParser.parseContent(rich(*Array(20) { btn }))?.blocks).hasSize(MtrixParser.Limits.BLOCKS)
        // non-object blocks
        assertThat(MtrixParser.parseContent(rich("1", "null", "\"buttons\"", btn))?.blocks).hasSize(1)
    }

    @Test
    fun `event json - original, decrypted shape, edits replace the payload`() {
        val original = """{"type":"m.room.message","sender":"@holo:matrix.pilipili.stream","content":{"msgtype":"m.text","body":"x","com.ianm.mtrix":{"v":1,"blocks":[$btn]}}}"""
        assertThat(MtrixParser.parseEventJson(original, null)?.blocks).hasSize(1)
        val editWithout = """{"type":"m.room.message","content":{"body":"* Sent","m.new_content":{"msgtype":"m.text","body":"Sent ✅"},"m.relates_to":{"rel_type":"m.replace","event_id":"${'$'}x"}}}"""
        assertThat(MtrixParser.parseEventJson(original, editWithout)).isNull()
        val editWith = """{"type":"m.room.message","content":{"body":"* x","m.new_content":{"msgtype":"m.text","body":"x","com.ianm.mtrix":{"v":1,"blocks":[$btn,$btn]}}}}"""
        assertThat(MtrixParser.parseEventJson(original, editWith)?.blocks).hasSize(2)
        // the edit's outer content is not the new content
        val editOuterOnly = """{"content":{"body":"* x","com.ianm.mtrix":{"v":1,"blocks":[$btn]},"m.new_content":{"body":"x"}}}"""
        assertThat(MtrixParser.parseEventJson(original, editOuterOnly)).isNull()
        assertThat(MtrixParser.parseEventJson("not json", null)).isNull()
        assertThat(MtrixParser.parseEventJson(null, null)).isNull()
        assertThat(MtrixParser.mayContainPayload(original)).isTrue()
        assertThat(MtrixParser.mayContainPayload("""{"content":{"body":"hi"}}""")).isFalse()
    }

    @Test
    fun `buttons and chips - only react and send actions, caps, styles`() {
        val b = block(
            """{"type":"buttons","buttons":[
              {"label":"  Send\n now ","style":"primary","action":{"type":"react","key":"✅"}},
              {"label":"Weird","style":"loud","action":{"type":"send","body":"!approve 1"}},
              {"label":"","action":{"type":"react","key":"x"}},
              {"label":"Bad","action":{"type":"open_url","url":"https://x"}},
              {"label":"Cmd","action":{"type":"command","command":"rm -rf"}},
              {"label":"Long body","action":{"type":"send","body":"${"x".repeat(1001)}"}},
              {"label":"Empty key","action":{"type":"react","key":" "}},
              {"label":"Long key","action":{"type":"react","key":"${"k".repeat(65)}"}},
              {"label":"No action"}
            ]}"""
        ) as MtrixBlock.Buttons
        assertThat(b.buttons).hasSize(2)
        assertThat(b.buttons[0]).isEqualTo(MtrixButton("Send now", MtrixButtonStyle.PRIMARY, MtrixAction.React("✅")))
        assertThat(b.buttons[1]).isEqualTo(MtrixButton("Weird", MtrixButtonStyle.DEFAULT, MtrixAction.Send("!approve 1")))
        // send body kept verbatim (exactly what the bot understands)
        val exact = block("""{"type":"buttons","buttons":[{"label":"x","action":{"type":"send","body":"  !approve  abc "}}]}""") as MtrixBlock.Buttons
        assertThat(exact.buttons[0].action).isEqualTo(MtrixAction.Send("  !approve  abc "))
        // label capped at 64 code points with an ellipsis, emoji not split
        val long = block("""{"type":"buttons","buttons":[{"label":"${"😀".repeat(100)}","action":{"type":"react","key":"a"}}]}""") as MtrixBlock.Buttons
        val label = long.buttons[0].label
        assertThat(label.codePointCount(0, label.length)).isEqualTo(64)
        assertThat(label).endsWith("…")
        // caps
        val many = (0 until 20).joinToString(",") { """{"label":"c$it","action":{"type":"send","body":"c$it"}}""" }
        assertThat((block("""{"type":"chips","chips":[$many]}""") as MtrixBlock.Chips).chips).hasSize(12)
        assertThat((block("""{"type":"buttons","buttons":[$many]}""") as MtrixBlock.Buttons).buttons).hasSize(10)
        assertThat(block("""{"type":"chips","chips":[]}""")).isNull()
        assertThat(block("""{"type":"buttons"}""")).isNull()
    }

    @Test
    fun `table - columns, rows, cells, truncation`() {
        val t = block(
            """{"type":"table","caption":"Prices",
              "columns":[{"key":"item","label":"Item"},{"key":"price","label":"Price","align":"right"},"store",{"key":"item"},42],
              "rows":[{"item":"RK84","price":2499,"store":"Shopee"},["Aula",2899.5,true,"ignored"],"bad",{"item":null}]}"""
        ) as MtrixBlock.Table
        assertThat(t.columns.map { it.key }).containsExactly("item", "price", "store").inOrder()
        assertThat(t.columns[2].label).isEqualTo("store")
        assertThat(t.columns[1].align).isEqualTo(MtrixAlign.RIGHT)
        assertThat(t.rows).hasSize(3)
        assertThat(t.rows[0]).containsExactly("RK84", 2499.0, "Shopee").inOrder()
        assertThat(t.rows[1]).containsExactly("Aula", 2899.5, "true").inOrder()
        assertThat(t.rows[2]).containsExactly(null, null, null).inOrder()
        assertThat(t.truncated).isFalse()
        val big = (0 until 250).joinToString(",") { "[$it]" }
        val tb = block("""{"type":"table","columns":["n"],"rows":[$big]}""") as MtrixBlock.Table
        assertThat(tb.rows).hasSize(200)
        assertThat(tb.truncated).isTrue()
        assertThat(block("""{"type":"table","columns":[],"rows":[[1]]}""")).isNull()
        assertThat(block("""{"type":"table","columns":["a"],"rows":[]}""")).isNull()
    }

    @Test
    fun `table - sorting and alignment`() {
        assertThat(MtrixTableSort.numeric("₱1,200")).isEqualTo(1200.0)
        assertThat(MtrixTableSort.numeric("12.5%")).isEqualTo(12.5)
        assertThat(MtrixTableSort.numeric("PHP 99")).isEqualTo(99.0)
        assertThat(MtrixTableSort.numeric("abc")).isNull()
        val rows = listOf(listOf<Any?>("item 10"), listOf<Any?>(null), listOf<Any?>("item 9"), listOf<Any?>("Item 1"))
        assertThat(MtrixTableSort.sorted(rows, 0, descending = false).map { it[0] }).containsExactly("Item 1", "item 9", "item 10", null).inOrder()
        assertThat(MtrixTableSort.sorted(rows, 0, descending = true).map { it[0] }).containsExactly("item 10", "item 9", "Item 1", null).inOrder()
        val t = block("""{"type":"table","columns":["name","price"],"rows":[["a","₱1,200"],["b",900],["c",""]]}""") as MtrixBlock.Table
        assertThat(MtrixTableSort.alignment(t, 0)).isEqualTo(MtrixAlign.LEFT)
        assertThat(MtrixTableSort.alignment(t, 1)).isEqualTo(MtrixAlign.RIGHT)
    }

    @Test
    fun `chart - kinds, series shapes, gaps, labels`() {
        val c = block(
            """{"type":"chart","kind":"line","title":"Spend","unit":"₱","labels":["W1","W2",3],
              "series":[{"name":"Food","values":[1200,900,1500,null]},{"name":"Empty","values":[null,"x"]},{"values":[1,2]}]}"""
        ) as MtrixBlock.Chart
        assertThat(c.kind).isEqualTo(MtrixChartKind.LINE)
        assertThat(c.series).hasSize(2)
        assertThat(c.series[0].values).containsExactly(1200.0, 900.0, 1500.0, null).inOrder()
        assertThat(c.labels).containsExactly("W1", "W2", "3", "").inOrder()
        assertThat(c.unit).isEqualTo("₱")
        val bare = block("""{"type":"chart","kind":"bar","series":[1,2,null,4]}""") as MtrixBlock.Chart
        assertThat(bare.series).containsExactly(MtrixSeries(null, listOf(1.0, 2.0, null, 4.0)))
        val spark = block("""{"type":"chart","kind":"sparkline","series":[{"values":[1]},{"values":[2]}]}""") as MtrixBlock.Chart
        assertThat(spark.series).hasSize(1)
        assertThat(block("""{"type":"chart","kind":"pie","series":[1,2]}""")).isNull()
        assertThat(block("""{"type":"chart","kind":"line","series":[]}""")).isNull()
        assertThat(block("""{"type":"chart","kind":"line","series":[null,null]}""")).isNull()
        val manySeries = (0 until 10).joinToString(",") { """{"values":[$it]}""" }
        assertThat((block("""{"type":"chart","kind":"line","series":[$manySeries]}""") as MtrixBlock.Chart).series).hasSize(6)
    }

    @Test
    fun `chart math`() {
        assertThat(MtrixChartMath.niceTicks(0.0, 1500.0)).containsExactly(0.0, 500.0, 1000.0, 1500.0).inOrder()
        assertThat(MtrixChartMath.niceTicks(5.0, 5.0).first()).isAtMost(0.0)
        assertThat(MtrixChartMath.extent(listOf(listOf(3.0, null, 7.0)), includeZero = true)).isEqualTo(0.0 to 7.0)
        assertThat(MtrixChartMath.extent(listOf(listOf(null)), includeZero = false)).isEqualTo(0.0 to 1.0)
        assertThat(MtrixChartMath.labelIndices(5, 6)).containsExactly(0, 1, 2, 3, 4).inOrder()
        assertThat(MtrixChartMath.labelIndices(20, 6).last()).isEqualTo(19)
        assertThat(MtrixChartMath.labelIndices(20, 6).size).isAtMost(7)
    }

    @Test
    fun `progress, details, code, diff`() {
        val p = block(
            """{"type":"progress","title":"Release","steps":[
              {"id":"lint","label":"Lint","status":"done","started_at":1760522400000,"ended_at":1760522412000},
              {"label":"Tests","status":"running","detail":"412/980","started_at":"2026-10-15T18:00:00+08:00"},
              {"label":"Bad","status":"exploded"},
              {"label":"Old","status":"done","started_at":1760522400}
            ]}"""
        ) as MtrixBlock.Progress
        assertThat(p.steps.map { it.label }).containsExactly("Lint", "Tests", "Old").inOrder()
        assertThat(p.steps[1].id).isEqualTo("Tests")
        assertThat(p.steps[1].startedAt).isEqualTo(1792058400000L) // 2026-10-15T10:00:00Z
        assertThat(p.steps[2].startedAt).isNull() // seconds, not ms
        val d = block("""{"type":"details","summary":"Log","body":"a\nb","open":true}""") as MtrixBlock.Details
        assertThat(d.open).isTrue()
        assertThat(d.body).isEqualTo("a\nb")
        assertThat(block("""{"type":"details","summary":"Log"}""")).isNull()
        val code = block("""{"type":"code","language":"rust","code":"fn main() {}\r\n"}""") as MtrixBlock.Code
        assertThat(code.code).isEqualTo("fn main() {}")
        assertThat(code.isDiff).isFalse()
        val diff = block("""{"type":"diff","diff":"-a\n+b"}""") as MtrixBlock.Code
        assertThat(diff.isDiff).isTrue()
        val lines = (0 until 600).joinToString("\\n") { "l$it" }
        val big = block("""{"type":"code","code":"$lines"}""") as MtrixBlock.Code
        assertThat(big.truncated).isTrue()
        assertThat(big.code.split("\n")).hasSize(500)
    }

    @Test
    fun `parser never throws on hostile input`() {
        val inputs = listOf("[]", "{}", """{"com.ianm.mtrix":{"v":1e400,"blocks":[]}}""", """{"com.ianm.mtrix":{"v":1,"blocks":{"0":{}}}}""")
        for (i in inputs) {
            assertThat(MtrixParser.parseEventJson("""{"content":$i}""", null)).isNull()
        }
    }
}
