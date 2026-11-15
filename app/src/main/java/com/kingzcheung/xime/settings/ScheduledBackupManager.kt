package com.kingzcheung.xime.settings

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.provider.DocumentsContract
import com.kingzcheung.xime.ui.settings.BackupChoices
import com.kingzcheung.xime.util.FileLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 定时自动备份到本地文件夹（SAF 授权目录）。
 *
 * 用户在设置页面通过 OpenDocumentTree 授权一个本地目录并持久化 Uri 权限后，
 * 定时任务或应用生命周期触发时免交互写入符合 v2 格式的备份包。
 * 同时按设定份数（如最近 5 份）自动轮转清理旧备份，避免耗尽磁盘空间。
 */
object ScheduledBackupManager {

    private const val TAG = "ScheduledBackupManager"
    const val JOB_ID = 20261115

    const val KEY_ENABLED = "scheduled_backup_enabled"
    const val KEY_FOLDER_URI = "scheduled_backup_folder_uri"
    const val KEY_FOLDER_NAME = "scheduled_backup_folder_name"
    const val KEY_INTERVAL_DAYS = "scheduled_backup_interval_days"
    const val KEY_MAX_KEEP = "scheduled_backup_max_keep"
    const val KEY_LAST_TIME = "scheduled_backup_last_time_ms"
    const val KEY_LAST_STATUS = "scheduled_backup_last_status"
    const val KEY_LAST_ERROR = "scheduled_backup_last_error"

    const val DEFAULT_INTERVAL_DAYS = 1
    const val DEFAULT_MAX_KEEP = 5

    private fun prefs(context: Context): SharedPreferences =
        SettingsPreferences.getPrefsPublic(context)

    fun isEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
        if (enabled) {
            scheduleJob(context)
        } else {
            cancelJob(context)
        }
    }

    fun getFolderUri(context: Context): Uri? {
        val raw = prefs(context).getString(KEY_FOLDER_URI, null) ?: return null
        return if (raw.isBlank()) null else runCatching { Uri.parse(raw) }.getOrNull()
    }

    fun getFolderDisplayName(context: Context): String =
        prefs(context).getString(KEY_FOLDER_NAME, "").orEmpty()

    fun setFolder(context: Context, uri: Uri?, displayName: String?) {
        prefs(context).edit().apply {
            if (uri != null) {
                putString(KEY_FOLDER_URI, uri.toString())
                putString(KEY_FOLDER_NAME, displayName.orEmpty())
            } else {
                remove(KEY_FOLDER_URI)
                remove(KEY_FOLDER_NAME)
            }
        }.apply()
        if (isEnabled(context)) {
            scheduleJob(context)
        }
    }

    fun getIntervalDays(context: Context): Int =
        prefs(context).getInt(KEY_INTERVAL_DAYS, DEFAULT_INTERVAL_DAYS).coerceIn(1, 30)

    fun setIntervalDays(context: Context, days: Int) {
        prefs(context).edit().putInt(KEY_INTERVAL_DAYS, days.coerceIn(1, 30)).apply()
        if (isEnabled(context)) {
            scheduleJob(context)
        }
    }

    fun getMaxKeep(context: Context): Int =
        prefs(context).getInt(KEY_MAX_KEEP, DEFAULT_MAX_KEEP).coerceIn(1, 100)

    fun setMaxKeep(context: Context, maxKeep: Int) {
        prefs(context).edit().putInt(KEY_MAX_KEEP, maxKeep.coerceIn(1, 100)).apply()
    }

    fun getLastBackupTime(context: Context): Long =
        prefs(context).getLong(KEY_LAST_TIME, 0L)

    fun getLastStatus(context: Context): String =
        prefs(context).getString(KEY_LAST_STATUS, "").orEmpty()

    fun getLastError(context: Context): String? =
        prefs(context).getString(KEY_LAST_ERROR, null)

    private fun recordSuccess(context: Context, timeMs: Long, status: String) {
        prefs(context).edit()
            .putLong(KEY_LAST_TIME, timeMs)
            .putString(KEY_LAST_STATUS, status)
            .remove(KEY_LAST_ERROR)
            .apply()
    }

    private fun recordFailure(context: Context, errorMsg: String) {
        prefs(context).edit()
            .putString(KEY_LAST_ERROR, errorMsg)
            .apply()
    }

    /** 检查是否已到下一次备份时间。 */
    fun isDue(context: Context): Boolean {
        if (!isEnabled(context)) return false
        if (getFolderUri(context) == null) return false
        val last = getLastBackupTime(context)
        val intervalMs = getIntervalDays(context) * 24L * 60 * 60 * 1000L
        return System.currentTimeMillis() - last >= intervalMs
    }

    /** 注册原生 JobScheduler 周期任务。 */
    fun scheduleJob(context: Context) {
        if (!isEnabled(context) || getFolderUri(context) == null) {
            cancelJob(context)
            return
        }
        val jobScheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as? JobScheduler ?: return
        val intervalMs = getIntervalDays(context) * 24L * 60 * 60 * 1000L
        val component = ComponentName(context, ScheduledBackupJobService::class.java)
        val builder = JobInfo.Builder(JOB_ID, component)
            .setPeriodic(intervalMs)
            .setRequiresBatteryNotLow(true)
        jobScheduler.schedule(builder.build())
    }

    /** 取消 JobScheduler 周期任务。 */
    fun cancelJob(context: Context) {
        val jobScheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as? JobScheduler ?: return
        jobScheduler.cancel(JOB_ID)
    }

    /**
     * 执行一次备份落盘并清理过期备份。
     * [manual]: 是否为用户主动点击触发；主动触发不受周期限制，成功后同样更新时间记录与清理。
     */
    suspend fun performBackup(context: Context, manual: Boolean = false): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            val treeUri = getFolderUri(context) ?: error("未设置备份文件夹")
            val includePersonal = BackupChoices.includePersonal(context)
            val includeClipboardHistory = BackupChoices.includeClipboardHistory(context)
            val clipboard = BackupManager.clipboardBackupJson(context, includeClipboardHistory)

            val timeStr = SimpleDateFormat("yyyy-MM-dd_HHmmss", Locale.getDefault()).format(Date())
            val fileName = "CyIME备份-$timeStr.zip"

            val treeDocId = DocumentsContract.getTreeDocumentId(treeUri)
            val parentDocUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, treeDocId)
            val fileUri = DocumentsContract.createDocument(
                context.contentResolver,
                parentDocUri,
                "application/zip",
                fileName
            ) ?: error("无法在目标文件夹中创建备份文件，请检查文件夹权限")

            val output = context.contentResolver.openOutputStream(fileUri, "wt")
                ?: error("无法写入备份文件")

            val fileCount = output.use {
                BackupManager.writeLocalBackup(context, it, includePersonal, clipboard)
            }

            // 清理多余旧备份
            val maxKeep = getMaxKeep(context)
            pruneOldBackups(context, treeUri, maxKeep)

            val now = System.currentTimeMillis()
            val statusText = "备份成功（$fileCount 个文件）"
            recordSuccess(context, now, statusText)
            FileLogger.i(TAG, "Scheduled backup completed: files=$fileCount manual=$manual")

            fileCount
        }.onFailure { error ->
            FileLogger.e(TAG, "Scheduled backup failed", error)
            recordFailure(context, error.message ?: "未知错误")
        }
    }

    /**
     * 自动删除超过保留份数的最旧备份文件。
     */
    fun pruneOldBackups(context: Context, treeUri: Uri, maxKeep: Int): Int {
        if (maxKeep <= 0) return 0
        return runCatching {
            val treeDocId = DocumentsContract.getTreeDocumentId(treeUri)
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, treeDocId)
            val projection = arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED
            )
            val backupFiles = mutableListOf<BackupFileInfo>()
            context.contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
                val idCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val modCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                if (idCol != -1 && nameCol != -1 && modCol != -1) {
                    while (cursor.moveToNext()) {
                        val docId = cursor.getString(idCol) ?: continue
                        val name = cursor.getString(nameCol) ?: continue
                        val modTime = cursor.getLong(modCol)
                        if (isBackupFileName(name)) {
                            backupFiles.add(BackupFileInfo(docId, name, modTime))
                        }
                    }
                }
            }
            // 按修改时间降序排列，新在前、旧在后
            val sorted = backupFiles.sortedByDescending { it.lastModified }
            var deletedCount = 0
            if (sorted.size > maxKeep) {
                val toDelete = sorted.drop(maxKeep)
                for (item in toDelete) {
                    val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, item.docId)
                    if (DocumentsContract.deleteDocument(context.contentResolver, docUri)) {
                        deletedCount++
                    }
                }
            }
            deletedCount
        }.getOrDefault(0)
    }

    /** 应用或输入法启动时被动检查：已到期则后台执行备份。 */
    fun checkAndBackupIfDue(context: Context, scope: CoroutineScope) {
        if (isDue(context)) {
            scope.launch {
                performBackup(context, manual = false)
            }
        }
    }

    internal fun isBackupFileName(name: String): Boolean =
        name.startsWith("CyIME备份-") && name.endsWith(".zip")

    internal data class BackupFileInfo(
        val docId: String,
        val name: String,
        val lastModified: Long
    )
}
