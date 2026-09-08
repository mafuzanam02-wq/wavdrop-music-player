package com.launchpoint.wavdrop.ui.components.motion

import androidx.compose.animation.core.tween
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.ui.Modifier
import com.launchpoint.wavdrop.ui.theme.WavdropMotion

/**
 * Restrained placement + fade for keyed LazyColumn items so live changes (search-result updates,
 * sort/filter re-orders, insertion/removal) settle instead of popping (WU-02, Library/Search phase).
 *
 * Uses the shared [WavdropMotion.placementSpec] so every list in the app settles identically, and
 * returns a plain [Modifier] under reduced motion. This generalises the idiom previously kept
 * private in HomeScreen; the queue files stay untouched and keep their own placement handling.
 *
 * Callers must give each item a stable, unique `key` for this to animate correctly.
 */
fun LazyItemScope.wavdropItemPlacement(reducedMotion: Boolean): Modifier =
    if (reducedMotion) {
        Modifier
    } else {
        Modifier.animateItem(
            fadeInSpec = tween(WavdropMotion.Durations.StandardStateChange),
            placementSpec = WavdropMotion.placementSpec(),
            fadeOutSpec = tween(WavdropMotion.Durations.StandardStateChange),
        )
    }
