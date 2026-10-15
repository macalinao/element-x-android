/*
 * mtrix fork. SPDX-License-Identifier: AGPL-3.0-only
 */

package io.element.android.features.mtrix

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class MtrixConfigTest {
    @Test
    fun `allow-listed bot senders`() {
        for (bot in listOf("claude", "tala", "holo", "revi", "ploy")) {
            assertThat(MtrixConfig.isAllowedSender("@$bot:matrix.pilipili.stream")).isTrue()
        }
    }

    @Test
    fun `everyone else is rejected, Ian included`() {
        assertThat(MtrixConfig.isAllowedSender("@igm:matrix.pilipili.stream")).isFalse()
        assertThat(MtrixConfig.isAllowedSender("@claude:evil.example")).isFalse()
        assertThat(MtrixConfig.isAllowedSender("@Claude:matrix.pilipili.stream")).isFalse()
        assertThat(MtrixConfig.isAllowedSender("@claude:matrix.pilipili.stream.evil.example")).isFalse()
        assertThat(MtrixConfig.isAllowedSender(" @claude:matrix.pilipili.stream")).isFalse()
        assertThat(MtrixConfig.isAllowedSender("")).isFalse()
        assertThat(MtrixConfig.isAllowedSender(null)).isFalse()
    }

    @Test
    fun `allow-list holds full MXIDs only`() {
        assertThat(MtrixConfig.ALLOWED_SENDERS).hasSize(5)
        for (s in MtrixConfig.ALLOWED_SENDERS) {
            assertThat(s).matches("^@[a-z0-9._=/-]+:matrix\\.pilipili\\.stream$")
        }
    }
}
