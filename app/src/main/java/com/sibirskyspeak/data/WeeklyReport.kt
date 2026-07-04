package com.sibirskyspeak.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName="weekly_reports")
data class WeeklyReport(@PrimaryKey(autoGenerate=true) val id: Long=0, val generatedAt: Long, val periodStart: Long, val bodyJson: String)
