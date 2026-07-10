package com.sibirskyspeak.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "curriculum_state")
data class CurriculumState(
    @PrimaryKey val id: Int = 0,
    val version: String,
    val checksum: String,
    val manifestJson: String,
    val installedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "curriculum_migration_reports")
data class CurriculumMigrationReport(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val fromVersion: String?,
    val toVersion: String,
    val appeared: Int,
    val moved: Int,
    val retired: Int,
    val detailsJson: String,
    val createdAt: Long = System.currentTimeMillis(),
    val shown: Boolean = false
)

@Entity(tableName = "exit_ticket_results")
data class ExitTicketResult(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val unit: Int,
    val band: String = "A1",
    val recognition: Boolean,
    val production: Boolean,
    val listening: Boolean,
    val reading: Boolean,
    val completedAt: Long = System.currentTimeMillis()
)
