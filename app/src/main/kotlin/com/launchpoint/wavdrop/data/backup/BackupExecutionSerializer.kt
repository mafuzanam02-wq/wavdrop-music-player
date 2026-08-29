package com.launchpoint.wavdrop.data.backup

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@Singleton
class BackupExecutionSerializer @Inject constructor() {
    private val mutex = Mutex()

    suspend fun <T> withSerializedBackup(block: suspend () -> T): T =
        mutex.withLock { block() }
}
