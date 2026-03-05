package com.murmur.reader.ui.screens.library

import androidx.lifecycle.ViewModel
import com.murmur.reader.data.local.ReadingProgressDao
import com.murmur.reader.data.local.ReadingProgressEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val progressDao: ReadingProgressDao,
) : ViewModel() {

    val recentDocuments: Flow<List<ReadingProgressEntity>> = progressDao.observeAll()
}
