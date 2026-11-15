package com.kingzcheung.xime.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScheduledBackupManagerTest {

    @Test
    fun `isBackupFileName correctly matches CyIME backup archives`() {
        assertTrue(ScheduledBackupManager.isBackupFileName("CyIME备份-2026-11-15_163000.zip"))
        assertTrue(ScheduledBackupManager.isBackupFileName("CyIME备份-2026-11-15.zip"))
        assertFalse(ScheduledBackupManager.isBackupFileName("CyIME-backup.zip"))
        assertFalse(ScheduledBackupManager.isBackupFileName("backup.zip"))
        assertFalse(ScheduledBackupManager.isBackupFileName("CyIME备份-2026-11-15.txt"))
        assertFalse(ScheduledBackupManager.isBackupFileName(""))
    }

    @Test
    fun `backup file sorting orders newest first`() {
        val files = listOf(
            ScheduledBackupManager.BackupFileInfo("1", "CyIME备份-2026-11-10_100000.zip", 1000L),
            ScheduledBackupManager.BackupFileInfo("2", "CyIME备份-2026-11-15_100000.zip", 5000L),
            ScheduledBackupManager.BackupFileInfo("3", "CyIME备份-2026-11-12_100000.zip", 2000L),
        )

        val sorted = files.sortedByDescending { it.lastModified }
        assertEquals(listOf("2", "3", "1"), sorted.map { it.docId })

        val maxKeep = 2
        val toDelete = sorted.drop(maxKeep)
        assertEquals(listOf("1"), toDelete.map { it.docId })
    }

    @Test
    fun `pruning calculation drops nothing when count within maxKeep`() {
        val files = listOf(
            ScheduledBackupManager.BackupFileInfo("1", "CyIME备份-2026-11-15_100000.zip", 5000L),
            ScheduledBackupManager.BackupFileInfo("2", "CyIME备份-2026-11-12_100000.zip", 2000L),
        )

        val sorted = files.sortedByDescending { it.lastModified }
        val toDelete = sorted.drop(5)
        assertTrue(toDelete.isEmpty())
    }

    @Test
    fun `default values are consistent`() {
        assertEquals(1, ScheduledBackupManager.DEFAULT_INTERVAL_DAYS)
        assertEquals(5, ScheduledBackupManager.DEFAULT_MAX_KEEP)
    }
}
