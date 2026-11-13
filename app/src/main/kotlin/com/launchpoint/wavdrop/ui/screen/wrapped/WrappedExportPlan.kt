package com.launchpoint.wavdrop.ui.screen.wrapped

import com.launchpoint.wavdrop.data.artwork.ArtworkResolver
import com.launchpoint.wavdrop.data.model.WrappedScope
import com.launchpoint.wavdrop.data.model.WrappedSummary
import com.launchpoint.wavdrop.data.settings.WrappedVisualStyle
import java.util.UUID

/**
 * P5-WR-VID-02A2: immutable description of one static slide export, captured from the selected report. It holds no
 * reference to the live screen, pager position or settings flows, so later changes to those cannot alter an export in
 * progress. Pure Kotlin: building and inspecting a plan needs no Android graphics.
 */
internal const val WRAPPED_EXPORT_WIDTH_PX = 720
internal const val WRAPPED_EXPORT_HEIGHT_PX = 1280

/** Provisional per-slide video duration. Independent of the live story durations (wrappedSlideDurationMs). */
internal const val WRAPPED_EXPORT_SLIDE_DURATION_MS = 3_000

internal data class WrappedExportPage(
    val index: Int,
    val id: WrappedPageId,
    /** Sequential, sortable file name, e.g. `slide_01_intro.png`. */
    val fileName: String,
    val durationMs: Int,
)

internal data class WrappedExportPlan(
    val exportId: String,
    val reportKey: String,
    val scope: WrappedScope,
    val periodLabel: String,
    val summary: WrappedSummary,
    val periodCopy: WrappedPeriodCopy,
    val visualSettings: WrappedVisualSettings,
    val visualStyle: WrappedVisualStyle,
    val milestones: List<WrappedMilestone>,
    val includesMilestonePage: Boolean,
    /** The artwork the Intro page would load live, or null (no album, or artwork backgrounds off). */
    val introArtworkUri: String?,
    val pages: List<WrappedExportPage>,
) {
    val pageCount: Int get() = pages.size
    val totalDurationMs: Int get() = pages.sumOf { it.durationMs }
}

private val SAFE_EXPORT_ID = Regex("[A-Za-z0-9-]{1,64}")

internal fun wrappedExportFileName(index: Int, id: WrappedPageId): String =
    "slide_${(index + 1).toString().padStart(2, '0')}_${id.name.lowercase()}.png"

/**
 * Builds the plan from the same inputs the live screen uses, so page order, optional Milestones and the Intro artwork
 * source cannot drift from live behaviour. [exportId] must be unique per export (a UUID in production); it names the
 * output directory and is restricted to a filesystem-safe alphabet.
 */
internal fun buildWrappedExportPlan(
    summary: WrappedSummary,
    visualSettings: WrappedVisualSettings,
    visualStyle: WrappedVisualStyle,
    milestonePreferenceEnabled: Boolean,
    exportId: String = UUID.randomUUID().toString(),
    slideDurationMs: Int = WRAPPED_EXPORT_SLIDE_DURATION_MS,
    artworkUriFor: (Long?) -> String? = ArtworkResolver::albumArtworkUri,
): WrappedExportPlan {
    require(SAFE_EXPORT_ID.matches(exportId)) { "unsafe export id" }
    require(slideDurationMs > 0) { "slide duration must be positive" }
    val period = summary.period
    val scope = period.scope
    val milestones = if (scope == WrappedScope.YEARLY) computeMilestones(summary) else emptyList()
    val showMilestones = shouldShowWrappedMilestonePage(
        scope = scope,
        preferenceEnabled = milestonePreferenceEnabled,
        hasMilestones = milestones.isNotEmpty(),
    )
    val pages = wrappedPageIds(scope, showMilestones).mapIndexed { index, id ->
        WrappedExportPage(index, id, wrappedExportFileName(index, id), slideDurationMs)
    }
    return WrappedExportPlan(
        exportId = exportId,
        reportKey = period.toWrappedReportKey(),
        scope = scope,
        periodLabel = period.displayLabel,
        summary = summary,
        periodCopy = period.toWrappedPeriodCopy(),
        visualSettings = visualSettings,
        visualStyle = visualStyle,
        milestones = milestones.toList(),
        includesMilestonePage = showMilestones,
        introArtworkUri = artworkUriFor(summary.mostPlayedSong?.song?.albumId)
            .takeIf { visualSettings.useArtworkBackgrounds },
        pages = pages,
    )
}
