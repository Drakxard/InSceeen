package com.inscreen.mic

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

class WorkspaceLibraryWorker(context: Context, parameters: WorkerParameters) : Worker(context, parameters) {
    override fun doWork(): Result {
        return try {
            val credentials = WorkspaceCredentialStore(applicationContext).load() ?: return Result.success()
            WorkspaceLibraryStore(applicationContext, credentials).sync(true)
            Result.success()
        } catch (error: WorkspaceHttpException) {
            if (error.status in listOf(401, 403, 410)) Result.failure() else Result.retry()
        } catch (_: Exception) { Result.retry() }
    }
    companion object {
        private const val PERIODIC = "workspace-library-periodic"
        private const val IMMEDIATE = "workspace-library-immediate"
        fun schedule(context: Context) {
            val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).setRequiresStorageNotLow(true).build()
            val manager = WorkManager.getInstance(context)
            manager.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, PeriodicWorkRequestBuilder<WorkspaceLibraryWorker>(15, TimeUnit.MINUTES).setConstraints(constraints).build())
            manager.enqueueUniqueWork(IMMEDIATE, ExistingWorkPolicy.KEEP, OneTimeWorkRequestBuilder<WorkspaceLibraryWorker>().setConstraints(constraints).build())
        }
        fun cancel(context: Context) { WorkManager.getInstance(context).cancelUniqueWork(PERIODIC); WorkManager.getInstance(context).cancelUniqueWork(IMMEDIATE) }
    }
}
