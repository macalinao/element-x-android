/*
 * mtrix fork. SPDX-License-Identifier: AGPL-3.0-only
 */

package io.element.android.features.mtrix

/**
 * Settings for rendering `com.ianm.mtrix` rich-message payloads.
 *
 * Spec: docs/rich-messages.md in macalinao/mtrix (the Element Web renderer is the reference implementation).
 */
object MtrixConfig {
    /** Content key carrying the payload on an `m.room.message`. */
    const val RICH_KEY = "com.ianm.mtrix"

    /** Key used before 2026-10-15; read only when [RICH_KEY] is absent. Drop once old approval drafts have expired. */
    const val LEGACY_RICH_KEY = "stream.pilipili.rich"

    /** Highest payload version this renderer understands. */
    const val RICH_VERSION = 1

    /**
     * Senders whose rich payloads are rendered (full MXIDs, no wildcards). Anyone else's payload, Ian's own included,
     * is ignored and the message renders as Element X normally would.
     *
     * Element Web reads the same list from config.json (generated from `services.tala-agent` in the Nix config);
     * Element X has no runtime config for this, so it is the single constant here.
     */
    val ALLOWED_SENDERS: Set<String> = setOf(
        "@claude:matrix.pilipili.stream",
        "@tala:matrix.pilipili.stream",
        "@holo:matrix.pilipili.stream",
        "@revi:matrix.pilipili.stream",
        "@ploy:matrix.pilipili.stream",
    )

    fun isAllowedSender(userId: String?): Boolean = userId != null && userId in ALLOWED_SENDERS
}
