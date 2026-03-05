package com.murmur.reader.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "reading_progress")
data class ReadingProgressEntity(
    @PrimaryKey val documentUri: String,
    val documentTitle: String,
    /** Page index the user was last on (0-based). Stored in column "characterPosition". */
    @ColumnInfo(name = "characterPosition") val currentPageIndex: Int,
    /** Total pages in the document at last save. Stored in column "chunkIndex". */
    @ColumnInfo(name = "chunkIndex") val totalPageCount: Int,
    val lastReadTimestamp: Long = System.currentTimeMillis(),
)
