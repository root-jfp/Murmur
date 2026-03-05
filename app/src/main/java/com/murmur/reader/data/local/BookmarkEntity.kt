package com.murmur.reader.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "bookmarks")
data class BookmarkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val documentUri: String,
    val documentTitle: String,
    val characterPosition: Int,
    val note: String = "",
    val createdTimestamp: Long = System.currentTimeMillis(),
)
