package com.launchpoint.wavdrop.data.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AudioPermissionHistoryRepositoryTest {
    @get:Rule val temp = TemporaryFolder()

    private fun repo(file: File) = AudioPermissionHistoryRepository(PreferenceDataStoreFactory.create(produceFile = { file }))

    @Test fun `defaults to never granted`() = runBlocking {
        assertFalse(repo(File(temp.root, "a.preferences_pb")).hasEverGranted.first())
    }

    @Test fun `markGranted persists across recreation and is idempotent`() = runBlocking {
        val file = File(temp.root, "b.preferences_pb")
        val job = Job()
        val first = AudioPermissionHistoryRepository(
            PreferenceDataStoreFactory.create(scope = CoroutineScope(job), produceFile = { file }),
        )
        first.markGranted(); first.markGranted(); first.markGranted()
        assertTrue(first.hasEverGranted.first())
        job.cancel() // "terminate the app"
        job.join()
        val recreated = AudioPermissionHistoryRepository(PreferenceDataStoreFactory.create(produceFile = { file }))
        assertTrue(recreated.hasEverGranted.first())
    }

    @Test fun `the history is one way - there is no API to reset it`() {
        val names = AudioPermissionHistoryRepository::class.java.declaredMethods.map { it.name }
        assertTrue(names.any { it.startsWith("markGranted") })
        assertTrue(names.none { it.contains("reset", true) || it.contains("clear", true) || it.contains("setEver", true) })
    }

    @Test fun `the device local flag is never backed up or restored`() {
        val root = File("src/main/kotlin/com/launchpoint/wavdrop/data/backup")
        val offenders = root.walkTopDown().filter { it.isFile && it.extension == "kt" }
            .filter { val t = it.readText(); "AudioPermissionHistory" in t || "audio_permission_ever_granted" in t || "hasEverGranted" in t }
            .map { it.name }.toList()
        assertEquals(emptyList<String>(), offenders)
    }
}
