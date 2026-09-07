package com.launchpoint.wavdrop.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.ui.unit.IntOffset

/**
 * Wavdrop's shared motion vocabulary (WU-02, Phase 2 foundation).
 *
 * This is deliberately small — semantic timing, easing, and spring tokens plus the app's
 * reduced-motion policy — not a design system. Later Home / Library / Now Playing / Settings /
 * Stats polish phases consume these so timings stay consistent instead of being re-invented as
 * literals in each screen (today WrappedScreen and QueueSheet each hard-code 200/220ms tweens and
 * duplicate the reduced-motion check).
 *
 * Names describe PURPOSE, never numbers. The values are grounded in the app's existing
 * conventions: completion feedback and state changes already settle around ~200–220ms, and the
 * queue placement spring already uses [Spring.StiffnessMediumLow] with restrained damping.
 */
object WavdropMotion {

    /** Semantic durations in milliseconds. Kept as plain Ints so they are trivially unit-testable. */
    object Durations {
        /** Press compression / release. Fast enough to feel like direct touch response. */
        const val PressResponse = 90

        /** Smallest state flips (icon swap, tiny toggles). */
        const val FastStateChange = 120

        /** Default state change — the everyday token. Matches WrappedScreen's ~200ms feel. */
        const val StandardStateChange = 180

        /** Emphasis / completion acknowledgement. Matches the queue drop-flash (220ms). */
        const val EmphasizedTransition = 240

        /** Large surfaces: sheets, expanding cards. */
        const val SheetTransition = 240
    }

    /** Semantic easings. Purpose-named so call sites read intentionally. */
    object Easings {
        /** Press/release — Material standard in/out; calm, no overshoot. */
        val Press: Easing = FastOutSlowInEasing

        /** Everyday state changes. */
        val Standard: Easing = FastOutSlowInEasing

        /** Emphasized decelerate (Material 3) for completion / entrance emphasis. */
        val Emphasized: Easing = CubicBezierEasing(0.2f, 0.0f, 0.0f, 1.0f)
    }

    /**
     * Restrained spring constants. Damping is kept at/above critical so nothing rubber-bands or
     * bounces for long — Wavdrop should feel responsive, not springy.
     */
    object Springs {
        /** List reorder / insertion / removal settling. No bounce. */
        const val PlacementDamping = Spring.DampingRatioNoBouncy
        const val PlacementStiffness = Spring.StiffnessMediumLow

        /** Gentle settle for small layout nudges. */
        const val GentleSettleDamping = Spring.DampingRatioNoBouncy
        const val GentleSettleStiffness = Spring.StiffnessMedium
    }

    /**
     * Shared placement spring for list item movement (LazyColumn `Modifier.animateItem`,
     * reorder results, insertion/removal). Consumed by later Home / Library phases; defined here so
     * every list settles identically. Not applied to queue files — those stay closed.
     */
    fun placementSpec(): FiniteAnimationSpec<IntOffset> = spring(
        dampingRatio = Springs.PlacementDamping,
        stiffness = Springs.PlacementStiffness,
        visibilityThreshold = IntOffset.VisibilityThreshold,
    )

    /**
     * The app's reduced-motion policy as a pure function: the OS "Remove animations" setting drives
     * [Settings.Global.ANIMATOR_DURATION_SCALE] toward 0, and Wavdrop treats anything below 0.1 as
     * reduced motion. Centralised here (see `rememberReducedMotion`) so components stop duplicating
     * the raw `Settings.Global` read.
     */
    fun isReducedMotion(animatorDurationScale: Float): Boolean = animatorDurationScale < REDUCED_MOTION_SCALE_THRESHOLD

    const val REDUCED_MOTION_SCALE_THRESHOLD = 0.1f
}
