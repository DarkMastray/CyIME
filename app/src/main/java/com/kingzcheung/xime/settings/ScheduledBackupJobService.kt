package com.kingzcheung.xime.settings

import android.app.job.JobParameters
import android.app.job.JobService
import com.kingzcheung.xime.util.FileLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 响应系统 JobScheduler 定时触发备份的 JobService。
 */
class ScheduledBackupJobService : JobService() {

    private val jobScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onStartJob(params: JobParameters?): Boolean {
        FileLogger.i(TAG, "Scheduled backup job started")
        jobScope.launch {
            try {
                if (ScheduledBackupManager.isDue(applicationContext)) {
                    ScheduledBackupManager.performBackup(applicationContext, manual = false)
                }
            } catch (e: Exception) {
                FileLogger.e(TAG, "Scheduled backup job execution error", e)
            } finally {
                jobFinished(params, false)
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters?): Boolean {
        FileLogger.w(TAG, "Scheduled backup job stopped by system")
        jobScope.cancel()
        return true
    }

    companion object {
        private const val TAG = "ScheduledBackupJobService"
    }
}
