package io.github.yuubiz.folirel

import android.util.Log
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import kotlin.math.abs

/**
 * Position trace of a scroll animation, one sample per frame of the test's own (virtual) frame clock, and the numbers
 * that tell a smooth single movement from a stepped one. Reaching the top at the end is not evaluated here: it is the
 * motion on the way that this is for.
 */
class ScrollTrace(val label: String, val frameMillis: Int, private val frames: List<ListHarness.Frame>, private val density: Float) {
    /** Content movement of each frame in px toward the top (positive = list moves toward its start); estimated when no card is seen twice. */
    val speeds: List<Float>
    val estimated: List<Boolean>
    val teleports: Int

    init {
        val s = ArrayList<Float>()
        val e = ArrayList<Boolean>()
        var teleportCount = 0
        for (i in 1 until frames.size) {
            val prev = frames[i - 1]
            val now = frames[i]
            val common = now.tops.keys.filter { it in prev.tops }
            if (common.isNotEmpty()) {
                // Every card seen in both frames moved by the same amount unless one was re-laid out (a picture arrived): the median ignores those.
                val deltas = common.map { now.tops.getValue(it) - prev.tops.getValue(it) }.sorted()
                s += deltas[deltas.size / 2]
                e += false
            } else {
                val rowPx = now.heights.values.sorted().let { if (it.isEmpty()) 0f else it[it.size / 2] }
                s += (prev.axis - now.axis) * rowPx
                e += true
                if (abs(prev.axis - now.axis) > 0f) teleportCount++
            }
        }
        speeds = s
        estimated = e
        teleports = teleportCount
    }

    private val still = 0.5f

    val firstMoving: Int get() = speeds.indexOfFirst { abs(it) > still }
    val lastMoving: Int get() = speeds.indexOfLast { abs(it) > still }

    /** Frames from the first to the last movement, in ms. */
    val durationMs: Int get() = if (firstMoving < 0) 0 else (lastMoving - firstMoving + 1) * frameMillis

    /**
     * Frames without movement between the first movement and the end of the run-out (the last frame still moving at 5% of the
     * fastest frame): a list that halts on its way. The pixel-by-pixel tail after that is not a stop.
     */
    val stallFrames: Int
        get() {
            val first = firstMoving
            val runOutEnd = speeds.indices.lastOrNull { !estimated[it] && speeds[it] >= 0.05f * peak } ?: return 0
            return if (first < 0 || runOutEnd < first) 0 else (first..runOutEnd).count { abs(speeds[it]) <= still }
        }

    /** Frames that move the list away from its start. */
    val reversals: Int get() = speeds.count { it < -still }

    /** The largest movement of any frame that is not a long skip (a skip is a frame where no card of the last frame is still there). */
    private val peak: Float get() = speeds.indices.filter { !estimated[it] }.maxOfOrNull { speeds[it] } ?: 0f

    /**
     * Times the movement came (nearly) to rest and took off again after it had reached cruising speed: the
     * card-by-card stop-and-go the reader sees when the animation is started again and again.
     */
    val restarts: Int
        get() {
            val top = peak
            if (top <= 0f) return 0
            var cruised = false
            var low = false
            var count = 0
            for (i in speeds.indices) {
                if (estimated[i]) continue
                val v = speeds[i]
                if (v >= 0.5f * top) {
                    if (low) {
                        count++
                        low = false
                    }
                    cruised = true
                } else if (cruised && v < 0.1f * top) {
                    low = true
                }
            }
            return count
        }

    /** Size of the biggest movement of the last frames that is out of line with the motion before it: an instant correction at the end. */
    val finalSnapPx: Float
        get() {
            val last = lastMoving
            if (last < 3) return 0f
            val before = (1..3).map { abs(speeds[last - it]) }.average().toFloat()
            val v = abs(speeds[last])
            return if (v > 3 * before + 4f * density) v else 0f
        }

    fun summary(): String =
        "$label: duration=${durationMs}ms stalls=$stallFrames restarts=$restarts reversals=$reversals teleports=$teleports " +
            "finalSnap=${"%.0f".format(finalSnapPx)}px peak=${"%.0f".format(peak)}px/frame frames=${frames.size}"

    fun log() {
        Log.i(TAG, summary())
        speeds.forEachIndexed { i, v ->
            val f = frames[i + 1]
            Log.i(TAG, "$label,${i + 1},${"%.1f".format(f.axis)},${"%.1f".format(v)},${if (estimated[i]) "est" else "px"},${f.tops.entries.take(6).joinToString(" ") { (k, v) -> k + ":" + "%.0f".format(v) + "/" + "%.0f".format(f.heights.getValue(k)) }}")
        }
    }

    companion object {
        const val TAG = "UXR_TRACE"

        /**
         * Runs the frame clock by hand: [start] triggers the movement, [between] runs before frame `i` (articles arriving, touches),
         * and sampling stops after [settle] frames in which nothing moved (or at [maxFrames]).
         */
        fun record(
            label: String,
            compose: AndroidComposeTestRule<*, *>,
            harness: ListHarness,
            maxFrames: Int = 240,
            settle: Int = 14,
            realSleepMs: Long = 3,
            start: () -> Unit,
            between: (Int) -> Unit = {},
        ): ScrollTrace {
            val frames = ArrayList<ListHarness.Frame>()
            compose.mainClock.autoAdvance = false
            try {
                frames += harness.sample()
                start()
                var quiet = 0
                for (i in 0 until maxFrames) {
                    between(i)
                    // Pictures load on other threads in real time; give them a moment between the virtual frames.
                    Thread.sleep(realSleepMs)
                    compose.mainClock.advanceTimeByFrame()
                    val f = harness.sample()
                    val prev = frames.last()
                    val moved = f.axis != prev.axis || f.tops.entries.any { (k, v) -> prev.tops[k]?.let { abs(it - v) > 0.5f } == true } || f.tops.keys != prev.tops.keys
                    frames += f
                    quiet = if (moved) 0 else quiet + 1
                    if (quiet >= settle) break
                }
            } finally {
                compose.mainClock.autoAdvance = true
            }
            return ScrollTrace(label, 16, frames, compose.density.density).also { it.log() }
        }
    }
}
