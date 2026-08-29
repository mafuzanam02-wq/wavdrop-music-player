package com.launchpoint.wavdrop.data.backup

import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequest
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.launchpoint.wavdrop.data.settings.AppSettingsRepository
import com.launchpoint.wavdrop.data.settings.AutoBackupInterval
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AutoBackupWorkSchedulerTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `off interval cancels unique work`() {
        val gateway = FakeAutoBackupWorkManagerGateway()
        scheduler(gateway).reconcile(AutoBackupInterval.OFF)

        assertEquals(listOf(AutoBackupWorkScheduler.UNIQUE_WORK_NAME), gateway.cancelledWork)
        assertEquals(emptyList<FakeAutoBackupWorkManagerGateway.EnqueueCall>(), gateway.enqueueCalls)
    }

    @Test
    fun `enabled interval enqueues one unique periodic due check with update policy`() {
        val gateway = FakeAutoBackupWorkManagerGateway()
        scheduler(gateway).reconcile(AutoBackupInterval.DAILY)

        assertEquals(emptyList<String>(), gateway.cancelledWork)
        assertEquals(1, gateway.enqueueCalls.size)
        val call = gateway.enqueueCalls.single()
        assertEquals(AutoBackupWorkScheduler.UNIQUE_WORK_NAME, call.uniqueWorkName)
        assertEquals(ExistingPeriodicWorkPolicy.UPDATE, call.existingWorkPolicy)
    }

    @Test
    fun `settings changes update the same unique periodic work instead of creating a new name`() {
        val gateway = FakeAutoBackupWorkManagerGateway()
        val scheduler = scheduler(gateway)

        scheduler.reconcile(AutoBackupInterval.DAILY)
        scheduler.reconcile(AutoBackupInterval.WEEKLY)

        assertEquals(
            listOf(
                AutoBackupWorkScheduler.UNIQUE_WORK_NAME,
                AutoBackupWorkScheduler.UNIQUE_WORK_NAME,
            ),
            gateway.enqueueCalls.map { it.uniqueWorkName },
        )
        assertEquals(
            listOf(ExistingPeriodicWorkPolicy.UPDATE, ExistingPeriodicWorkPolicy.UPDATE),
            gateway.enqueueCalls.map { it.existingWorkPolicy },
        )
    }

    private fun scheduler(
        gateway: AutoBackupWorkManagerGateway,
    ): AutoBackupWorkScheduler =
        AutoBackupWorkScheduler(
            appSettingsRepository = AppSettingsRepository(
                PreferenceDataStoreFactory.create(
                    produceFile = {
                        temporaryFolder.newFile("settings-${System.nanoTime()}.preferences_pb")
                    },
                ),
            ),
            workManagerGateway = gateway,
        )

    private class FakeAutoBackupWorkManagerGateway : AutoBackupWorkManagerGateway {
        data class EnqueueCall(
            val uniqueWorkName: String,
            val existingWorkPolicy: ExistingPeriodicWorkPolicy,
        )

        val enqueueCalls = mutableListOf<EnqueueCall>()
        val cancelledWork = mutableListOf<String>()

        override fun enqueueUniquePeriodicWork(
            uniqueWorkName: String,
            existingWorkPolicy: ExistingPeriodicWorkPolicy,
            request: PeriodicWorkRequest,
        ) {
            enqueueCalls += EnqueueCall(uniqueWorkName, existingWorkPolicy)
        }

        override fun cancelUniqueWork(uniqueWorkName: String) {
            cancelledWork += uniqueWorkName
        }
    }
}
