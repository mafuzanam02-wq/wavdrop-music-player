package com.launchpoint.wavdrop.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** CF-2K1: the pure production-enable decision. The inputs here are test fixtures, never claims of real device evidence. */
class CrossfadeProductionReadinessTest {

    private val allTrue = CrossfadeRolloutReadiness(
        automatedGatePassed = true,
        physicalCorePlaybackValidated = true,
        physicalBackgroundValidated = true,
        physicalBluetoothValidated = true,
        physicalWiredValidated = true,
        equalizerCompatibilityValidated = true,
    )

    @Test fun allConditionsMetCanEnable() {
        assertTrue(canEnableCrossfadeProduction(allTrue))
    }

    @Test fun eachConditionMissingBlocksEnablement() {
        val variants = mapOf(
            "automated" to allTrue.copy(automatedGatePassed = false),
            "core" to allTrue.copy(physicalCorePlaybackValidated = false),
            "background" to allTrue.copy(physicalBackgroundValidated = false),
            "bluetooth" to allTrue.copy(physicalBluetoothValidated = false),
            "wired" to allTrue.copy(physicalWiredValidated = false),
            "eq" to allTrue.copy(equalizerCompatibilityValidated = false),
        )
        variants.forEach { (name, readiness) -> assertFalse("missing $name must block", canEnableCrossfadeProduction(readiness)) }
    }

    @Test fun nothingValidatedCannotEnable() {
        assertFalse(canEnableCrossfadeProduction(CrossfadeRolloutReadiness(false, false, false, false, false, false)))
    }

    @Test fun automatedEvidenceAloneIsNeverEnough() {
        assertFalse(
            canEnableCrossfadeProduction(
                CrossfadeRolloutReadiness(true, false, false, false, false, false),
            ),
        )
    }

    @Test fun physicalEvidenceWithoutAutomatedGateIsNeverEnough() {
        assertFalse(canEnableCrossfadeProduction(allTrue.copy(automatedGatePassed = false)))
    }

    @Test fun shippingGateRemainsFalse() {
        assertFalse(CrossfadeRolloutPolicy.RUNTIME_ENABLED)
    }
}
