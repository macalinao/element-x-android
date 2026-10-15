/*
 * mtrix fork: glue between the timeline and :features:mtrix. Kept in its own file so the upstream files only gain
 * one-line hooks (see README.mtrix.md). SPDX-License-Identifier: AGPL-3.0-only
 */

package io.element.android.features.messages.impl.timeline.mtrix

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import io.element.android.features.messages.impl.timeline.TimelineEvent
import io.element.android.features.messages.impl.timeline.model.TimelineItem
import io.element.android.features.mtrix.ui.MtrixBlocksView
import io.element.android.features.mtrix.MtrixConfig
import io.element.android.features.mtrix.ui.MtrixInteractionState
import io.element.android.features.mtrix.MtrixParser
import io.element.android.features.mtrix.MtrixPayload
import io.element.android.libraries.matrix.api.timeline.item.event.EventTimelineItem
import io.element.android.libraries.matrix.api.timeline.item.event.MessageContent
import io.element.android.libraries.matrix.api.timeline.item.event.NoticeMessageType
import io.element.android.libraries.matrix.api.timeline.item.event.TextMessageType
import io.element.android.libraries.ui.common.layout.ContentAvoidingLayoutData
import timber.log.Timber

/**
 * The `com.ianm.mtrix` payload of a timeline event, or null. Called from the (background) timeline item factory.
 *
 * Element X's mapped [MessageContent] drops unknown content keys, so the payload is read from the raw event JSON the
 * SDK keeps for "View source" (`lazyProvider.debugInfo()`: the decrypted original event, plus the latest applied edit).
 * That FFI call only happens for `m.text`/`m.notice` messages from an allow-listed sender.
 */
fun EventTimelineItem.mtrixPayload(): MtrixPayload? {
    if (eventId == null || !MtrixConfig.isAllowedSender(sender.value)) return null
    val type = (content as? MessageContent)?.type
    if (type !is TextMessageType && type !is NoticeMessageType) return null
    return try {
        val info = timelineItemDebugInfoProvider()
        val edit = info.latestEditedJson
        if (!MtrixParser.mayContainPayload(edit ?: info.originalJson)) return null
        MtrixParser.parseEventJson(info.originalJson, edit)
    } catch (e: Exception) {
        Timber.w(e, "mtrix: could not read the payload of $eventId")
        null
    }
}

/**
 * Draws [content] (Element X's own body rendering, which stays the complete fallback) and, when the event carries a
 * payload, the payload's blocks below it. The timestamp then goes on its own row instead of overlapping the blocks.
 */
@Composable
fun MtrixEventContent(
    event: TimelineItem.Event,
    eventSink: (TimelineEvent.TimelineItemEvent) -> Unit,
    onContentLayoutChange: (ContentAvoidingLayoutData) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable (Modifier, (ContentAvoidingLayoutData) -> Unit) -> Unit,
) {
    val payload = event.mtrix
    val eventId = event.eventId
    if (payload == null || eventId == null) {
        content(modifier, onContentLayoutChange)
        return
    }
    val interactions = remember(event.reactionsState, event.senderId) {
        val reactions = event.reactionsState.reactions
        MtrixInteractionState(
            mine = reactions.filter { it.isHighlighted }.map { it.key }.toSet(),
            // The bot pre-seeds ✅/❌ for one-tap; those aren't choices.
            counts = reactions.associate { r -> r.key to r.senders.count { it.senderId != event.senderId } }.filterValues { it > 0 },
        )
    }
    SideEffect { onContentLayoutChange(ContentAvoidingLayoutData.NotOverlapping) }
    Column(modifier = modifier) {
        content(Modifier, {})
        MtrixBlocksView(
            payload = payload,
            interactions = interactions,
            // Without permission the reaction fails server-side and is logged; the bubble has no room power levels.
            canReact = true,
            onAction = { action -> eventSink(TimelineEvent.MtrixButtonTapped(eventId, action)) },
        )
    }
}
