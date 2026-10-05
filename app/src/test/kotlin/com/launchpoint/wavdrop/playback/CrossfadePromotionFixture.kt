package com.launchpoint.wavdrop.playback

import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi

/**
 * CF-2M5 test rig: a real [PlayerEngine] whose P1 plays [titles] at index [from] and whose P2 holds the graft-complete,
 * READY, paused NEXT for the exact transition (from -> from + 1), i.e. exactly the state CF-2M4 hands to promotion.
 */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
internal class PromotionRig(
    val titles: List<String> = listOf("A", "B", "C", "D"),
    val from: Int = 1,
    hook: ((PromotionStep) -> Unit)? = null,
) {
    val f = PlayerEngineFixture(p1Titles = titles, p1Index = from, promotionHook = hook)
    val engine get() = f.engine
    val p1 get() = f.p1
    val p2 get() = f.p2
    val key = CrossfadeTransitionKey(7L, from, from + 1)
    val queue: List<MediaItem> = titles.map { ScriptedPlayer.mediaItem(it) }

    /** CURRENT plays; NEXT is prepared and grafted for [key]. Command logs are cleared so a test sees only what follows. */
    fun playAndPrepare(): PromotionRig {
        f.facade.play(); idleMainLooper()
        engine.nextPreparation.request(NextSlotRequest(key, queue))
        p2.becomeReady(); idleMainLooper() // the default looper scheduler runs the graft turns
        check(engine.nextPreparation.state is NextSlotState.Ready) { engine.nextPreparation.state }
        p1.commands.clear()
        p2.commands.clear()
        f.events.events.clear()
        return this
    }

    fun promote(): PromotionStartResult = engine.promoteReadyNext(key).also { idleMainLooper() }
}
