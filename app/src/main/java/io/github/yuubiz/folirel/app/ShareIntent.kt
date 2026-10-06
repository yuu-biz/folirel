package io.github.yuubiz.folirel.app

import android.content.Intent

/**
 * Turns an Intent into "something was shared" and nothing more (Share to Subscribe): no address is read from the text here and nothing is
 * fetched. A launcher start, a resume from the recents list and every other intent are not shares.
 */
object ShareIntent {
    /** A share of plain text. [text] is null when the sender put no readable text in it. */
    class Shared(val text: String?)

    /**
     * The share in [intent], or null when it is not one. The text is taken out of the intent (so a recreated activity or a copy of the
     * intent cannot be handled twice), and an extra that cannot be read as text, which a malformed sender can send, is a share with no text.
     */
    fun consume(intent: Intent?): Shared? {
        if (intent == null || intent.action != Intent.ACTION_SEND) return null
        if (intent.type?.substringBefore(';')?.trim()?.lowercase() != "text/plain") return null
        // The task was started by an old share and is being reopened from the recents list: that share was handled already.
        if (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return null
        val text = try {
            intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
        } catch (_: RuntimeException) {
            null
        }
        try {
            intent.removeExtra(Intent.EXTRA_TEXT)
        } catch (_: RuntimeException) {
            // Nothing to clean up in an intent whose extras cannot be read.
        }
        return Shared(text)
    }
}
