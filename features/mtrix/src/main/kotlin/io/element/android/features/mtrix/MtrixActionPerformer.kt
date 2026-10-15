/*
 * mtrix fork. SPDX-License-Identifier: AGPL-3.0-only
 */

package io.element.android.features.mtrix

import io.element.android.libraries.matrix.api.core.EventId
import io.element.android.libraries.matrix.api.timeline.Timeline
import io.element.android.libraries.matrix.api.timeline.item.event.EventOrTransactionId
import timber.log.Timber

/**
 * Performs a tapped button's action. The only two things a button can ever do (spec safety rule 5), both in the
 * same room and related to the same event, both visible in the timeline and identical to what the user could do by hand:
 * - [MtrixAction.React]: the user's own `m.reaction` with the exact key.
 * - [MtrixAction.Send]: an `m.text` reply to the event with the exact body.
 *
 * Callers must not invoke this for an already-selected react button: [Timeline.toggleReaction] would redact it.
 */
object MtrixActionPerformer {
    suspend fun perform(timeline: Timeline, eventId: EventId, action: MtrixAction) {
        val result = when (action) {
            is MtrixAction.React -> timeline.toggleReaction(action.key, EventOrTransactionId.Event(eventId)).map { }
            is MtrixAction.Send -> timeline.replyMessage(
                repliedToEventId = eventId,
                body = action.body,
                htmlBody = null,
                intentionalMentions = emptyList(),
            )
        }
        result.onFailure { Timber.e(it, "mtrix: failed to perform button action on $eventId") }
    }
}
