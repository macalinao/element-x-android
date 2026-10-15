/*
 * mtrix fork. SPDX-License-Identifier: AGPL-3.0-only
 */

@file:OptIn(ExperimentalTestApi::class)

package io.element.android.features.mtrix

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.AndroidComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runAndroidComposeUiTest
import com.google.common.truth.Truth.assertThat
import io.element.android.compound.theme.ElementTheme
import io.element.android.features.mtrix.ui.MtrixBlocksView
import io.element.android.features.mtrix.ui.MtrixInteractionState
import io.element.android.tests.testutils.robolectric.RobolectricTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Test

class MtrixBlocksViewTest : RobolectricTest() {
    private val payload = MtrixParser.parseContent(
        Json.parseToJsonElement(
            """{"body":"x","com.ianm.mtrix":{"v":1,"blocks":[
              {"type":"buttons","buttons":[
                {"label":"Send","style":"primary","action":{"type":"react","key":"✅"}},
                {"label":"Drop","style":"danger","action":{"type":"react","key":"❌"}},
                {"label":"Approve via cmd","action":{"type":"send","body":"!approve abc123"}}]},
              {"type":"chips","chips":[{"label":"Cheaper","action":{"type":"send","body":"show cheaper ones"}}]},
              {"type":"table","caption":"Keyboards","columns":["Name",{"key":"Price","align":"right"}],"rows":[["RK84","₱2,499"],["Aula F75","₱2,899"]]},
              {"type":"chart","kind":"line","title":"Spend","unit":"₱","labels":["W1","W2","W3"],"series":[{"name":"Food","values":[1200,null,1500]},{"name":"Transport","values":[400,450,380]}]},
              {"type":"chart","kind":"bar","title":"Bars","labels":["a","b"],"series":[3,-1]},
              {"type":"chart","kind":"sparkline","title":"Trend","series":[1,3,2]},
              {"type":"progress","title":"Release","steps":[{"label":"Lint","status":"done","started_at":1760522400000,"ended_at":1760522412000},{"label":"Tests","status":"running"}]},
              {"type":"details","summary":"Build log","body":"line 1\nline 2"},
              {"type":"diff","filename":"a.rs","diff":"--- a\n+++ b\n-old\n+new"}
            ]}}"""
        ).jsonObject
    )!!

    private fun AndroidComposeUiTest<ComponentActivity>.render(
        interactions: MtrixInteractionState,
        actions: MutableList<MtrixAction>,
    ) {
        setContent {
            ElementTheme {
                MtrixBlocksView(payload = payload, interactions = interactions, canReact = true, onAction = { actions += it })
            }
        }
    }

    @Test
    fun `all supported blocks render`() = runAndroidComposeUiTest {
        render(MtrixInteractionState(), mutableListOf())
        assertThat(payload.blocks).hasSize(8) // the 9th (diff) is past the 8-block cap
        onNodeWithText("Send").assertExists()
        onNodeWithText("Cheaper").assertExists()
        onNodeWithText("Keyboards").assertExists()
        onNodeWithText("RK84").assertExists()
        onNodeWithText("Spend").assertExists()
        onNodeWithText("Food").assertExists()
        onNodeWithText("Release").assertExists()
        onNodeWithText("Lint (12s)").assertExists()
        onNodeWithText("▸ Build log").assertExists()
    }

    @Test
    fun `a tap sends the action once, a second tap is a no-op`() = runAndroidComposeUiTest {
        val actions = mutableListOf<MtrixAction>()
        render(MtrixInteractionState(), actions)
        onNodeWithText("Send").performClick()
        onNodeWithText("Send").performClick()
        onNodeWithText("Approve via cmd").performClick()
        onNodeWithText("Approve via cmd").performClick()
        onNodeWithText("Drop").performClick()
        assertThat(actions).containsExactly(
            MtrixAction.React("✅"),
            MtrixAction.Send("!approve abc123"),
            MtrixAction.React("❌"),
        ).inOrder()
    }

    @Test
    fun `an already-chosen reaction is never toggled off`() = runAndroidComposeUiTest {
        val actions = mutableListOf<MtrixAction>()
        render(MtrixInteractionState(mine = setOf("✅"), counts = mapOf("✅" to 1)), actions)
        onNodeWithText("Send").performClick()
        assertThat(actions).isEmpty()
    }

    @Test
    fun `table headers sort`() = runAndroidComposeUiTest {
        render(MtrixInteractionState(), mutableListOf())
        onNodeWithText("Price").performClick()
        onNodeWithText("Price ▲").assertExists()
        onNodeWithText("Price ▲").performClick()
        onNodeWithText("Price ▼").assertExists()
    }
}
