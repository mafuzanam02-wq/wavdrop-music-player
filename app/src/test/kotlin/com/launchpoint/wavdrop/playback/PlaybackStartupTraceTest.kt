package com.launchpoint.wavdrop.playback

import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import com.launchpoint.wavdrop.data.model.Song
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * Pure-JVM tests of the DEBUG-only startup tracer: correlation, cold/warm, pending resume, completion, expiry, privacy,
 * no-op when disabled, and the attribution rules: only an audio event from the request's own physical player, whose playlist
 * content matches what the request installed, and whose media item is the request's start item, can complete a trace.
 */
class PlaybackStartupTraceTest {

    private var nowNanos = 0L
    private val lines = mutableListOf<String>()

    @Before fun setUp() {
        PlaybackStartupTrace.resetForTest()
        PlaybackStartupTrace.enabled = true
        PlaybackStartupTrace.clockNanos = { nowNanos }
        PlaybackStartupTrace.sink = { lines += it }
    }

    @After fun tearDown() = PlaybackStartupTrace.resetForTest()

    private fun advanceMs(ms: Long) { nowNanos += ms * 1_000_000L }

    private fun stages() = lines.map { Regex("stage=(\\S+)").find(it)!!.groupValues[1] }

    private fun song(id: Long) = Song(
        id = id, title = "T", artist = "A", album = "B", albumId = 0L, duration = 1_000L,
        uri = "content://media/$id", dateAdded = 0L, trackNumber = 0, year = 2020,
    )

    private fun songs(ids: List<Long>) = ids.map(::song)

    /** The fingerprint a player's timeline would yield for exactly these ids in this order. */
    private fun fp(ids: List<Long>) = PlaybackStartupTrace.fingerprint(ids.size) { ids[it] }

    private val list = (100L..109L).toList()

    /** A request that opened its install window for [ids] (nothing installed/prepared yet). */
    private fun installing(ids: List<Long> = list, startId: Long = ids.first(), entry: String = "play_from_queue") {
        PlaybackStartupTrace.begin(entry, ids.size, controllerConnected = true)
        PlaybackStartupTrace.expectStart(startId)
        PlaybackStartupTrace.onInstallStarting("play_from_queue", songs(ids))
    }

    /** A request that completed install + prepare and whose owner (player [owner]) was bound by a matching callback. */
    private fun preparedAndOwned(ids: List<Long> = list, startId: Long = ids.first(), owner: Int = 1) {
        installing(ids, startId)
        PlaybackStartupTrace.bindOwner(owner, ids.size, fp(ids))
        PlaybackStartupTrace.onPlaylistInstalled("play_from_queue", ids.size, 5)
        PlaybackStartupTrace.onPrepareIssued()
    }

    // ---- fingerprint -----------------------------------------------------------------------------------------------------

    @Test fun fingerprintIsOrderSensitiveAndDistinguishesSameLengthPlaylists() {
        val a = listOf(1L, 2L, 3L, 4L)
        assertEquals(fp(a), fp(a.toList()))
        assertNotEquals(fp(a), fp(listOf(1L, 2L, 3L, 5L)))   // one different id, same length
        assertNotEquals(fp(a), fp(listOf(4L, 3L, 2L, 1L)))   // same ids, different order
        assertNotEquals(fp(a), fp(listOf(1L, 2L, 3L)))       // different length
    }

    @Test fun longPlaylistsAreSampledButStillDistinguishTypicalDifferences() {
        val n = PlaybackStartupTrace.FULL_HASH_LIMIT * 20
        val base = (0L until n).toList()
        val reordered = base.reversed()
        val shifted = base.map { it + 1 }
        assertEquals(fp(base), fp(base.toList()))
        assertNotEquals(fp(base), fp(reordered))
        assertNotEquals(fp(base), fp(shifted))
        // The documented limit: a change at an unsampled position (index 1 is not sampled when stride is 20) is NOT detected.
        val unsampledChange = base.toMutableList().also { it[1] = -5L }
        assertEquals(fp(base), fp(unsampledChange))
    }

    // ---- correlation / timeline ----------------------------------------------------------------------------------------

    @Test fun attributableRequestRecordsOrderedStagesAndCompletesOnOwnersAudioAdvance() {
        PlaybackStartupTrace.hintEntry("songs_list")
        advanceMs(7)
        PlaybackStartupTrace.begin("play_from_queue", queueSize = 10, controllerConnected = true)
        PlaybackStartupTrace.expectStart(100L)
        advanceMs(5); PlaybackStartupTrace.mark("logical_queue_built")
        advanceMs(1); PlaybackStartupTrace.onInstallStarting("play_from_queue", songs(list))
        advanceMs(40); PlaybackStartupTrace.onMaterialized("play_from_queue", 10, 0, 10)
        advanceMs(1); PlaybackStartupTrace.bindOwner(1, 10, fp(list)) // the player's callback fires inside setMediaItems
        advanceMs(1); PlaybackStartupTrace.onPlaylistInstalled("play_from_queue", 10, 26)
        advanceMs(1); PlaybackStartupTrace.onPrepareIssued()
        advanceMs(100); PlaybackStartupTrace.onPlayerState("ctl", Player.STATE_BUFFERING)
        advanceMs(20); PlaybackStartupTrace.onPlayerState("ctl", Player.STATE_READY)
        advanceMs(3); PlaybackStartupTrace.onIsPlaying("ctl")
        advanceMs(12); PlaybackStartupTrace.onAudioPositionAdvancing(serial = 1, mediaId = 100L)

        assertEquals(
            listOf(
                "request_received", "logical_queue_built", "playlist_install_started", "materialize_done",
                "playlist_visible_on_player", "playlist_install_done", "prepare_issued",
                "ctl_state_BUFFERING", "ctl_state_READY", "ctl_is_playing_true", "audio_position_advancing", "complete",
            ),
            stages(),
        )
        assertTrue(lines.all { it.startsWith("id=1 entry=songs_list cold=false queue=10 ") })
        assertTrue(lines.first().contains("tapToRequestMs=7.00"))
        assertTrue(lines.last().endsWith("basis=audio_position_advancing"))
        val elapsed = lines.map { Regex("\\+([0-9.]+)ms").find(it)!!.groupValues[1].toDouble() }
        assertEquals(elapsed.sorted(), elapsed)
        assertEquals(184.0, elapsed.last(), 0.001)
    }

    @Test fun pendingRequestKeepsIdAndStartTimeAcrossControllerConnection() {
        PlaybackStartupTrace.begin("play_from_queue", 1_000, controllerConnected = false)
        advanceMs(2); PlaybackStartupTrace.mark("ui_state_published")
        PlaybackStartupTrace.onControllerParked()
        advanceMs(900) // service bind + ExoPlayer construction + MediaController connect

        PlaybackStartupTrace.withPendingDrain {
            // The drained request re-enters playFromQueueInternal, which calls begin() again: it must resume, not restart.
            PlaybackStartupTrace.begin("play_from_queue", 1_000, controllerConnected = true)
            advanceMs(10); PlaybackStartupTrace.mark("play_issued")
        }

        assertEquals(
            listOf("request_received", "ui_state_published", "controller_unavailable_parked", "controller_connected_pending_drain", "play_issued"),
            stages(),
        )
        assertTrue(lines.all { it.startsWith("id=1 ") && it.contains("cold=true") })
        assertTrue(lines.last().contains("+912.00ms"))
    }

    @Test fun drainStateIsClearedWhenTheDrainedCommandThrows() {
        PlaybackStartupTrace.begin("play_from_queue", 10, controllerConnected = false)
        PlaybackStartupTrace.onControllerParked()
        try {
            PlaybackStartupTrace.withPendingDrain { throw IllegalStateException("boom") }
            fail("the exception must propagate unchanged")
        } catch (e: IllegalStateException) {
            assertEquals("boom", e.message)
        }
        // If the drain flag leaked, this fresh request would be mistaken for the parked one and silently resumed.
        advanceMs(3)
        PlaybackStartupTrace.begin("search_preserve", 4, controllerConnected = true)
        assertEquals(
            listOf("request_received", "controller_unavailable_parked", "controller_connected_pending_drain", "superseded", "request_received"),
            stages(),
        )
        assertTrue(lines.last().startsWith("id=2 entry=search_preserve cold=false "))
    }

    @Test fun newFreshRequestSupersedesAnOpenTraceWithANewId() {
        PlaybackStartupTrace.begin("play_from_queue", 100, controllerConnected = true)
        advanceMs(5)
        PlaybackStartupTrace.begin("search_preserve", 3, controllerConnected = true)

        assertEquals(listOf("request_received", "superseded", "request_received"), stages())
        assertTrue(lines[1].startsWith("id=1 "))
        assertTrue(lines[2].startsWith("id=2 entry=search_preserve "))
    }

    @Test fun staleHintIsIgnored() {
        PlaybackStartupTrace.hintEntry("songs_list")
        advanceMs(6_000)
        PlaybackStartupTrace.begin("play_from_queue", 10, controllerConnected = true)
        assertTrue(lines.single().startsWith("id=1 entry=play_from_queue "))
        assertFalse(lines.single().contains("tapToRequestMs"))
    }

    @Test fun secondHintRefinesNameButKeepsOriginalTapTime() {
        PlaybackStartupTrace.hintEntry("search_tap")
        advanceMs(30) // e.g. the asynchronous search-tap-behavior settings read
        PlaybackStartupTrace.hintEntry("search_preserve_queue")
        advanceMs(2)
        PlaybackStartupTrace.begin("search_preserve", 5, controllerConnected = true)
        assertTrue(lines.single().startsWith("id=1 entry=search_preserve_queue "))
        assertTrue(lines.single().contains("tapToRequestMs=32.00"))
    }

    // ---- ownership binding: ordering, content, staleness ------------------------------------------------------------------

    @Test fun serviceCallbackBeforeInstallCompletionStillBinds_theCallbackFiresInsideSetMediaItems() {
        installing()
        // The player's listener runs synchronously inside the controller's setMediaItems call: BEFORE onPlaylistInstalled.
        PlaybackStartupTrace.bindOwner(1, list.size, fp(list))
        PlaybackStartupTrace.onPlaylistInstalled("play_from_queue", list.size, 5)
        PlaybackStartupTrace.onPrepareIssued()
        assertEquals(
            listOf("request_received", "playlist_install_started", "playlist_visible_on_player", "playlist_install_done", "prepare_issued"),
            stages(),
        )
        PlaybackStartupTrace.onAudioPositionAdvancing(1, list.first())
        assertTrue(stages().contains("complete"))
    }

    @Test fun callbackAfterInstallReturnsOrAfterPrepareAlsoBinds_asyncDispatch() {
        installing()
        PlaybackStartupTrace.onPlaylistInstalled("play_from_queue", list.size, 5)
        PlaybackStartupTrace.onPrepareIssued()
        PlaybackStartupTrace.bindOwner(1, list.size, fp(list)) // the command was dispatched asynchronously
        PlaybackStartupTrace.onAudioPositionAdvancing(1, list.first())
        assertTrue(stages().contains("playlist_visible_on_player"))
        assertTrue(stages().contains("complete"))
    }

    @Test fun staleCallbackBeforeTheInstallWindowNeverBindsANewerRequest() {
        PlaybackStartupTrace.begin("songs_list", list.size, controllerConnected = true)
        PlaybackStartupTrace.expectStart(100L)
        // A playlist change from an EARLIER request's install, even with identical content and length, arrives before this
        // request opened its install window: it is stale.
        PlaybackStartupTrace.bindOwner(1, list.size, fp(list))
        PlaybackStartupTrace.onInstallStarting("play_from_queue", songs(list))
        PlaybackStartupTrace.onPlaylistInstalled("play_from_queue", list.size, 5)
        PlaybackStartupTrace.onPrepareIssued()
        assertTrue(lines.any { it.contains("stage=playlist_change_ignored") && it.contains("reason=before_install_window") })
        assertFalse(stages().contains("playlist_visible_on_player"))
        PlaybackStartupTrace.onAudioPositionAdvancing(1, list.first())
        assertTrue(lines.last().endsWith("reason=no_owner player=1"))
        assertFalse(stages().contains("complete"))
    }

    @Test fun differentPlaylistsOfIdenticalLengthDoNotBind() {
        installing(ids = list)
        val sameLengthOtherContent = (200L..209L).toList()
        assertEquals(list.size, sameLengthOtherContent.size)
        PlaybackStartupTrace.bindOwner(1, sameLengthOtherContent.size, fp(sameLengthOtherContent))
        PlaybackStartupTrace.onPlaylistInstalled("play_from_queue", list.size, 5)
        PlaybackStartupTrace.onPrepareIssued()
        assertTrue(stages().contains("playlist_change_unmatched"))
        assertFalse(stages().contains("playlist_visible_on_player"))
        PlaybackStartupTrace.onAudioPositionAdvancing(1, list.first())
        assertTrue(lines.last().endsWith("reason=no_owner player=1"))
        assertFalse(stages().contains("complete"))
    }

    @Test fun reorderedPlaylistOfTheSameItemsDoesNotBind() {
        installing(ids = list)
        PlaybackStartupTrace.bindOwner(1, list.size, fp(list.reversed()))
        assertTrue(stages().contains("playlist_change_unmatched"))
        assertFalse(stages().contains("playlist_visible_on_player"))
    }

    @Test fun unregisteredPlayerNeverBinds() {
        installing()
        PlaybackStartupTrace.bindOwner(-1, list.size, fp(list))
        assertTrue(stages().contains("playlist_change_unmatched"))
        assertFalse(stages().contains("playlist_visible_on_player"))
    }

    @Test fun ownerIsFixedByTheFirstMatchAndSamePlayerRepeatsAreHarmless() {
        installing()
        PlaybackStartupTrace.bindOwner(1, list.size, fp(list))
        PlaybackStartupTrace.bindOwner(1, list.size, fp(list)) // e.g. a SOURCE_UPDATE-free duplicate PLAYLIST_CHANGED
        assertEquals(1, stages().count { it == "playlist_visible_on_player" })
        assertFalse(stages().contains("owner_ambiguous"))
    }

    @Test fun onlyTheFirstInstallDefinesTheRequestsPlaylist() {
        installing()
        PlaybackStartupTrace.onPlaylistInstalled("play_from_queue", list.size, 5)
        // A later whole-queue re-sync (a different list) is logged but does not redefine what this request installed.
        PlaybackStartupTrace.onInstallStarting("sync_player_queue:Foo", songs((300L..324L).toList()))
        assertTrue(lines.last().endsWith("reinstall=true"))
        PlaybackStartupTrace.onPlaylistInstalled("sync_player_queue:Foo", 25, 9)
        assertTrue(lines.last().contains("reinstall=true"))
        PlaybackStartupTrace.onPrepareIssued()
        PlaybackStartupTrace.bindOwner(1, 25, fp((300L..324L).toList())) // the re-sync's playlist: not this request's
        assertFalse(stages().contains("playlist_visible_on_player"))
        PlaybackStartupTrace.bindOwner(1, list.size, fp(list))
        assertTrue(stages().contains("playlist_visible_on_player"))
    }

    // ---- superseded requests and delayed callbacks -----------------------------------------------------------------------

    @Test fun delayedCallbacksOfASupersededRequestCannotBindOrCompleteTheNewOne() {
        val first = list
        val second = (200L..219L).toList()
        preparedAndOwned(ids = first, startId = first.first(), owner = 1)
        // A second tap supersedes the first before its audio started.
        PlaybackStartupTrace.begin("songs_list", second.size, controllerConnected = true)
        PlaybackStartupTrace.expectStart(second.first())
        lines.clear()

        // Request 1's delayed playlist callback and audio event arrive while request 2 is still BEGUN.
        PlaybackStartupTrace.bindOwner(1, first.size, fp(first))
        PlaybackStartupTrace.onAudioPositionAdvancing(1, first.first())
        assertTrue(lines.all { it.startsWith("id=2 ") })
        assertEquals(listOf("playlist_change_ignored", "audio_position_advancing_unattributed"), stages())
        assertTrue(lines[1].endsWith("reason=before_prepare player=1"))

        // Request 2 opens its window; request 1's playlist (a delayed callback) is not request 2's and does not bind.
        PlaybackStartupTrace.onInstallStarting("play_from_queue", songs(second))
        PlaybackStartupTrace.bindOwner(1, first.size, fp(first))
        assertTrue(lines.last().contains("stage=playlist_change_unmatched"))
        PlaybackStartupTrace.onPlaylistInstalled("play_from_queue", second.size, 5)
        PlaybackStartupTrace.onPrepareIssued()
        PlaybackStartupTrace.onAudioPositionAdvancing(1, first.first()) // still no owner for request 2
        assertTrue(lines.last().endsWith("reason=no_owner player=1"))

        // Request 2's own callback binds and its own start item completes it; request 1's item is rejected.
        PlaybackStartupTrace.bindOwner(1, second.size, fp(second))
        PlaybackStartupTrace.onAudioPositionAdvancing(1, first.first())
        assertTrue(lines.last().endsWith("reason=media_mismatch player=1"))
        assertFalse(stages().contains("complete"))
        PlaybackStartupTrace.onAudioPositionAdvancing(1, second.first())
        assertTrue(lines.last().startsWith("id=2 ") && lines.last().endsWith("basis=audio_position_advancing"))
    }

    @Test fun callbackAfterCompletionOrAbortIsIgnored() {
        preparedAndOwned()
        PlaybackStartupTrace.onAudioPositionAdvancing(serial = 1, mediaId = list.first())
        val count = lines.size
        PlaybackStartupTrace.onAudioPositionAdvancing(serial = 1, mediaId = list.first()) // a later transition's audio start
        PlaybackStartupTrace.bindOwner(2, list.size, fp(list))
        PlaybackStartupTrace.onAudioDiagnostic("audio_underrun", 1, "bufferSize=1")
        PlaybackStartupTrace.onPlayerState("ctl", Player.STATE_BUFFERING)
        assertEquals(count, lines.size)

        PlaybackStartupTrace.begin("play_from_queue", 3, controllerConnected = true)
        PlaybackStartupTrace.abort("invalid_start_index")
        val afterAbort = lines.size
        PlaybackStartupTrace.onAudioPositionAdvancing(serial = 1, mediaId = list.first())
        assertEquals(afterAbort, lines.size)
    }

    // ---- crossfade: secondary player ---------------------------------------------------------------------------------------

    @Test fun audioEventFromTheSecondaryPlayerIsUnattributedAndDoesNotComplete() {
        preparedAndOwned(owner = 1)
        lines.clear()

        PlaybackStartupTrace.onAudioPositionAdvancing(serial = 2, mediaId = list.first()) // crossfade NEXT slot
        assertEquals(listOf("audio_position_advancing_unattributed"), stages())
        assertTrue(lines.single().endsWith("reason=other_player player=2"))

        // The request is still open and the owner's own event still completes it.
        PlaybackStartupTrace.onAudioPositionAdvancing(serial = 1, mediaId = list.first())
        assertEquals(listOf("audio_position_advancing_unattributed", "audio_position_advancing", "complete"), stages())
    }

    @Test fun secondaryPlayerReceivingADifferentPlaylistDoesNotAffectOwnership() {
        preparedAndOwned(owner = 1)
        lines.clear()
        PlaybackStartupTrace.bindOwner(2, 40, fp((500L..539L).toList())) // NEXT slot graft of another queue
        assertEquals(listOf("playlist_change_unmatched"), stages())
        PlaybackStartupTrace.onAudioPositionAdvancing(serial = 1, mediaId = list.first())
        assertTrue(stages().contains("complete"))
    }

    @Test fun secondaryPlayerReceivingAMatchingGraftMakesOwnershipAmbiguousAndRefusesCompletion() {
        preparedAndOwned(owner = 1)
        lines.clear()
        PlaybackStartupTrace.bindOwner(2, list.size, fp(list)) // NEXT slot graft of the SAME queue
        assertEquals(listOf("owner_ambiguous"), stages())
        assertTrue(lines.single().endsWith("owner=1 also=2"))

        PlaybackStartupTrace.onAudioPositionAdvancing(serial = 1, mediaId = list.first())
        PlaybackStartupTrace.onAudioPositionAdvancing(serial = 2, mediaId = list.first())
        assertTrue(lines[1].endsWith("reason=owner_ambiguous player=1"))
        assertTrue(lines[2].endsWith("reason=owner_ambiguous player=2"))
        assertFalse(stages().contains("complete"))
        // Underruns are not attributed to anyone while ambiguous.
        PlaybackStartupTrace.onAudioDiagnostic("audio_underrun", 1, "bufferSize=1")
        assertTrue(lines.last().contains("attr=unattributed"))
    }

    @Test fun audioEventForADifferentMediaItemIsUnattributed() {
        preparedAndOwned(startId = 100L, owner = 1)
        lines.clear()
        PlaybackStartupTrace.onAudioPositionAdvancing(serial = 1, mediaId = 101L)
        assertTrue(lines.single().endsWith("reason=media_mismatch player=1"))
        PlaybackStartupTrace.onAudioPositionAdvancing(serial = 1, mediaId = Long.MIN_VALUE) // unknown media
        assertTrue(lines.last().endsWith("reason=media_unknown player=1"))
        assertFalse(stages().contains("complete"))
    }

    @Test fun audioEventBeforeInstallOrBeforePrepareOrBeforeOwnerIsUnattributed() {
        PlaybackStartupTrace.begin("play_from_queue", 10, controllerConnected = true)
        PlaybackStartupTrace.expectStart(100L)
        PlaybackStartupTrace.onAudioPositionAdvancing(serial = 1, mediaId = 100L) // before install
        PlaybackStartupTrace.onInstallStarting("play_from_queue", songs(list))
        PlaybackStartupTrace.onPlaylistInstalled("play_from_queue", list.size, 5)
        PlaybackStartupTrace.onAudioPositionAdvancing(serial = 1, mediaId = 100L) // installed, not prepared
        PlaybackStartupTrace.onPrepareIssued()
        PlaybackStartupTrace.onAudioPositionAdvancing(serial = 1, mediaId = 100L) // prepared, no owner bound
        val reasons = lines.filter { it.contains("unattributed") }.map { Regex("reason=(\\S+)").find(it)!!.groupValues[1] }
        assertEquals(listOf("before_prepare", "before_prepare", "no_owner"), reasons)
        assertFalse(stages().contains("complete"))
    }

    @Test fun audioDiagnosticsAreLabelledByOwnershipAndNeverComplete() {
        preparedAndOwned(owner = 1)
        lines.clear()
        PlaybackStartupTrace.onAudioDiagnostic("audio_underrun", 1, "bufferSize=64")
        PlaybackStartupTrace.onAudioDiagnostic("audio_sink_error", 2, "type=Foo")
        assertTrue(lines[0].endsWith("attr=owner player=1 bufferSize=64"))
        assertTrue(lines[1].endsWith("attr=unattributed player=2 type=Foo"))
        assertFalse(stages().contains("complete"))
    }

    // ---- request correlation: state callbacks, restore, rejects, expiry -------------------------------------------------

    @Test fun stateCallbacksBeforeTheRequestsOwnPrepareAreFlaggedAndNeverComplete() {
        PlaybackStartupTrace.begin("play_from_queue", 10, controllerConnected = true)
        PlaybackStartupTrace.onPlayerState("ctl", Player.STATE_READY) // the PREVIOUS track's state
        PlaybackStartupTrace.onIsPlaying("svc")
        PlaybackStartupTrace.recordPlayerError("ctl", "ERROR_CODE_IO_UNSPECIFIED")
        assertTrue(lines[1].endsWith("attr=pre_prepare"))
        assertTrue(lines[2].endsWith("attr=pre_prepare"))
        assertTrue(lines[3].endsWith("code=ERROR_CODE_IO_UNSPECIFIED attr=pre_prepare"))
        assertFalse(stages().contains("complete"))
    }

    @Test fun sessionRestoreWithoutPlayIntentCompletesOnlyAtControllerReadyAfterItsOwnPrepare() {
        PlaybackStartupTrace.begin("startup_restore", 12_000, controllerConnected = true, playIntended = false)
        PlaybackStartupTrace.expectStart(100L)
        PlaybackStartupTrace.onPlayerState("ctl", Player.STATE_READY) // before prepare: must not complete
        assertFalse(stages().contains("complete"))

        PlaybackStartupTrace.onInstallStarting("startup_restore", songs(list))
        PlaybackStartupTrace.onPlaylistInstalled("startup_restore", list.size, 5)
        PlaybackStartupTrace.onPrepareIssued()
        advanceMs(50); PlaybackStartupTrace.onPlayerState("svc", Player.STATE_READY) // service side never completes
        assertFalse(stages().contains("complete"))
        advanceMs(5); PlaybackStartupTrace.onPlayerState("ctl", Player.STATE_READY)
        assertTrue(lines.last().endsWith("basis=ctl_ready_after_prepare"))
        // Nothing after completion is attributed to the finished trace.
        val count = lines.size
        PlaybackStartupTrace.onPlayerState("ctl", Player.STATE_BUFFERING)
        assertEquals(count, lines.size)
    }

    @Test fun prepareWithoutAnInstallForTheRequestIsFlagged() {
        PlaybackStartupTrace.begin("play_from_queue", 10, controllerConnected = true)
        PlaybackStartupTrace.onPrepareIssued() // e.g. a recovery prepare, not this request's
        assertTrue(lines.last().endsWith("attr=no_install_for_request"))
        PlaybackStartupTrace.onAudioPositionAdvancing(serial = 1, mediaId = 100L)
        assertFalse(stages().contains("complete"))
    }

    @Test fun rejectedRequestIsTracedAndClosed() {
        PlaybackStartupTrace.rejected("play_from_queue", 500, "absent_or_ambiguous_start")
        assertEquals(listOf("request_received", "aborted"), stages())
        assertTrue(lines.last().endsWith("reason=absent_or_ambiguous_start"))
        PlaybackStartupTrace.mark("late")
        assertEquals(2, lines.size)
    }

    @Test fun errorsLogOnlyTheCodeNameFromARealException() {
        preparedAndOwned()
        lines.clear()
        val error = PlaybackException("secret path /storage/emulated/0/Music/Private.mp3", null, PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND)
        PlaybackStartupTrace.onPlayerError("ctl", error)
        PlaybackStartupTrace.mark("bad_media_recovery_advance", 1)
        PlaybackStartupTrace.abort("bad_media_exhausted")
        assertEquals(listOf("ctl_player_error", "bad_media_recovery_advance", "aborted"), stages())
        assertTrue(lines[0].endsWith("code=ERROR_CODE_IO_FILE_NOT_FOUND"))
        assertTrue(lines.none { it.contains("secret") || it.contains("/") })
    }

    @Test fun marksAfterExpiryAreDroppedAndTheTraceIsClosed() {
        PlaybackStartupTrace.begin("play_from_queue", 10, controllerConnected = true)
        advanceMs(PlaybackStartupTrace.TRACE_EXPIRY_MS + 1)
        PlaybackStartupTrace.onPlayerState("ctl", Player.STATE_BUFFERING) // e.g. a much later natural transition
        assertEquals(listOf("request_received", "expired"), stages())
        PlaybackStartupTrace.onPlayerState("ctl", Player.STATE_READY)
        assertEquals(2, lines.size)
    }

    @Test fun everyLineUsesOnlyTheFixedVocabularyAndNumbers() {
        PlaybackStartupTrace.hintEntry("songs_list")
        preparedAndOwned(ids = listOf(123_456L, 123_457L), startId = 123_456L)
        PlaybackStartupTrace.onControllerParked()
        PlaybackStartupTrace.recordPlayerError("svc", "ERROR_CODE_DECODER_INIT_FAILED")
        PlaybackStartupTrace.bindOwner(2, 2, fp(listOf(123_456L, 123_457L)))
        PlaybackStartupTrace.onAudioDiagnostic("audio_underrun", 2, "bufferSize=64")
        PlaybackStartupTrace.onAudioPositionAdvancing(serial = 2, mediaId = 123_456L)
        // Identifiers, '=', digits, '.', '+', ':' (operation names) and spaces only: no quotes, slashes or URI characters.
        val allowed = Regex("[A-Za-z0-9_=+.: ]+")
        lines.forEach { assertTrue("unexpected characters in: $it", allowed.matches(it)) }
        // The start-item id and the content fingerprint are compared in memory only and never logged.
        assertTrue(lines.none { it.contains("123456") || it.contains(fp(listOf(123_456L, 123_457L)).toString()) })
    }

    @Test fun disabledTracerIsInertAndLogsNothing() {
        PlaybackStartupTrace.enabled = false
        PlaybackStartupTrace.hintEntry("songs_list")
        PlaybackStartupTrace.begin("play_from_queue", 10_000, controllerConnected = false)
        PlaybackStartupTrace.expectStart(7L)
        PlaybackStartupTrace.mark("logical_queue_built")
        PlaybackStartupTrace.mark("x", 1)
        PlaybackStartupTrace.onInstallStarting("op", songs(list))
        PlaybackStartupTrace.onMaterialized("op", 1, 0, 1)
        PlaybackStartupTrace.onPlaylistInstalled("op", 1, 1)
        PlaybackStartupTrace.onPrepareIssued()
        PlaybackStartupTrace.onControllerParked()
        PlaybackStartupTrace.withPendingDrain { PlaybackStartupTrace.mark("inside") }
        PlaybackStartupTrace.bindOwner(1, 1, 0L)
        PlaybackStartupTrace.onPlayerState("ctl", Player.STATE_READY)
        PlaybackStartupTrace.onIsPlaying("ctl")
        PlaybackStartupTrace.onPlayerError("ctl", PlaybackException("m", null, PlaybackException.ERROR_CODE_UNSPECIFIED))
        PlaybackStartupTrace.onAudioPositionAdvancing(1, 7L)
        PlaybackStartupTrace.onAudioDiagnostic("audio_underrun", 1, "d")
        PlaybackStartupTrace.abort("r")
        PlaybackStartupTrace.rejected("e", 1, "r")
        assertTrue(lines.isEmpty())

        // Enabling afterwards starts from a clean slate: nothing leaked from the disabled calls (ids restart at 1).
        PlaybackStartupTrace.enabled = true
        PlaybackStartupTrace.begin("play_from_queue", 1, controllerConnected = true)
        assertTrue(lines.single().startsWith("id=1 entry=play_from_queue "))
    }

    @Test fun releaseDefaultIsDisabledWhenBuildIsNotDebug() {
        // Documents the contract: the production default is BuildConfig.DEBUG (a compile-time false in release builds).
        PlaybackStartupTrace.resetForTest()
        assertEquals(com.launchpoint.wavdrop.BuildConfig.DEBUG, PlaybackStartupTrace.enabled)
    }
}
