package com.launchpoint.wavdrop.ui.permission

import com.launchpoint.wavdrop.ui.permission.AudioPermissionRequestOutcome.Blocked
import com.launchpoint.wavdrop.ui.permission.AudioPermissionRequestOutcome.DeniedCanRetry
import com.launchpoint.wavdrop.ui.permission.AudioPermissionStatus.Denied
import com.launchpoint.wavdrop.ui.permission.AudioPermissionStatus.Granted
import com.launchpoint.wavdrop.ui.permission.AudioPermissionStatus.NotRequested
import com.launchpoint.wavdrop.ui.permission.AudioPermissionStatus.PermanentlyDenied
import com.launchpoint.wavdrop.ui.permission.AudioPermissionStatus.Revoked
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AudioPermissionResolverTest {
    private fun resolve(has: Boolean, ever: Boolean, last: AudioPermissionRequestOutcome? = null) =
        AudioPermissionResolver.resolve(has, ever, last)

    @Test fun `never granted and permission absent is first run`() = assertEquals(NotRequested, resolve(false, false))
    @Test fun `never granted and denied with rationale is Denied`() = assertEquals(Denied, resolve(false, false, DeniedCanRetry))
    @Test fun `never granted and permanently blocked is PermanentlyDenied`() = assertEquals(PermanentlyDenied, resolve(false, false, Blocked))
    @Test fun `previously granted and permission absent is Revoked never first run`() {
        assertEquals(Revoked, resolve(false, true))
        assertNotEquals(NotRequested, resolve(false, true))
        assertEquals(Revoked, resolve(false, true, Blocked))
        assertEquals(Revoked, resolve(false, true, DeniedCanRetry))
    }
    @Test fun `previously granted and permission restored is Granted`() = assertEquals(Granted, resolve(true, true))
    @Test fun `current permission wins regardless of history or last request`() {
        for (ever in listOf(false, true)) for (last in listOf(null, DeniedCanRetry, Blocked)) assertEquals(Granted, resolve(true, ever, last))
    }

    @Test fun `request outcomes`() {
        assertNull(AudioPermissionResolver.outcomeOf(granted = true, shouldShowRationale = true))
        assertNull(AudioPermissionResolver.outcomeOf(granted = true, shouldShowRationale = false))
        assertEquals(DeniedCanRetry, AudioPermissionResolver.outcomeOf(false, true))
        assertEquals(Blocked, AudioPermissionResolver.outcomeOf(false, false))
    }

    @Test fun `lifecycle - granted then revoked on resume then restored`() {
        var ever = false
        // first run, user grants
        assertEquals(NotRequested, resolve(false, ever))
        assertEquals(Granted, resolve(true, ever)); ever = true // gate persists the fact when Granted
        // ON_RESUME after revoking in Android Settings
        assertEquals(Revoked, resolve(false, ever))
        // ON_RESUME after re-enabling in Android Settings
        assertEquals(Granted, resolve(true, ever))
    }

    @Test fun `lifecycle - blocked then restored in settings`() {
        assertEquals(PermanentlyDenied, resolve(false, false, Blocked))
        assertEquals(Granted, resolve(true, false, Blocked))
    }
}
