package com.siliconprime.tabletmirror.viewer

/**
 * Decides what the decoder is allowed to be fed.
 *
 * H.264 is a chain: most frames describe only what changed since the frames before
 * them, and only a keyframe stands on its own. Throwing away an arbitrary frame
 * and carrying on therefore does not cost one frame — it costs every frame after
 * it, because they all refer to something the decoder was never given. The picture
 * freezes or smears and stays that way until the next keyframe, however long that
 * takes, while the connection remains perfectly healthy and the app has no idea
 * anything is wrong. Reconnecting is what fixed it, because a fresh session starts
 * with a keyframe.
 *
 * So a drop is never partial. Once the chain is broken the only correct thing to do
 * is abandon everything up to the next keyframe and resume from there. That is what
 * the host already does with its own send backlog; this is the same rule at the
 * other end.
 *
 * Pure logic, no Android types, so the awkward orderings are unit tested rather
 * than discovered on a wall in a kitchen.
 */
class FrameGate(private val capacity: Int = DEFAULT_CAPACITY) {

    enum class Verdict {
        /** Hand it to the decoder. */
        DECODE,

        /** Undecodable on its own. Drop it; keep waiting for a keyframe. */
        DROP,

        /**
         * Falling behind, and this frame cannot restart the chain. Drop it *and*
         * everything already queued, then wait for a keyframe.
         */
        DROP_ALL,

        /**
         * Falling behind, but this frame is a keyframe, so it can restart the chain
         * on its own. Discard the backlog and decode this one — the fastest possible
         * recovery, with no wait at all.
         */
        FLUSH_AND_DECODE,
    }

    /** Nothing decodable has been seen yet, so nothing before a keyframe is usable. */
    private var awaitingKeyFrame = true

    /** True while the picture is knowingly stale. Observable so the rule can be tested. */
    val stalled: Boolean get() = awaitingKeyFrame

    /**
     * @param isKeyFrame whether this access unit stands on its own.
     * @param queuedCount how many frames are already waiting for the decoder.
     */
    fun offer(isKeyFrame: Boolean, queuedCount: Int): Verdict {
        if (awaitingKeyFrame) {
            if (!isKeyFrame) return Verdict.DROP
            awaitingKeyFrame = false
            return Verdict.DECODE
        }

        if (queuedCount >= capacity) {
            // A keyframe needs nothing that came before it, so the backlog can go and
            // this frame can be decoded immediately.
            if (isKeyFrame) return Verdict.FLUSH_AND_DECODE
            awaitingKeyFrame = true
            return Verdict.DROP_ALL
        }

        return Verdict.DECODE
    }

    companion object {
        /**
         * Roughly a third of a second at 30fps. Deep enough to ride out a brief
         * scheduling hiccup, shallow enough that a real slowdown is resolved by
         * dropping to a fresh keyframe rather than by drifting further behind — on a
         * till you want the current screen, not every screen that led to it.
         */
        const val DEFAULT_CAPACITY = 10
    }
}
