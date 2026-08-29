package com.launchpoint.wavdrop.data.backup

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class BackupExecutionSerializerTest {
    @Test
    fun `overlapping backup operations run one at a time`() = runBlocking {
        val serializer = BackupExecutionSerializer()
        val firstEntered = CompletableDeferred<Unit>()
        val finishFirst = CompletableDeferred<Unit>()
        var activeOperations = 0
        var maxActiveOperations = 0

        val first = async(Dispatchers.Default) {
            serializer.withSerializedBackup {
                activeOperations += 1
                maxActiveOperations = maxOf(maxActiveOperations, activeOperations)
                firstEntered.complete(Unit)
                finishFirst.await()
                activeOperations -= 1
                "first"
            }
        }
        firstEntered.await()

        val second = async(Dispatchers.Default) {
            serializer.withSerializedBackup {
                activeOperations += 1
                maxActiveOperations = maxOf(maxActiveOperations, activeOperations)
                activeOperations -= 1
                "second"
            }
        }

        delay(100L)
        assertFalse(second.isCompleted)

        finishFirst.complete(Unit)

        assertEquals("first", first.await())
        assertEquals("second", second.await())
        assertEquals(1, maxActiveOperations)
    }
}
