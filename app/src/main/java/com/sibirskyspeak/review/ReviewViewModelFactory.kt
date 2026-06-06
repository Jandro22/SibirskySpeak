package com.sibirskyspeak.review

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.sibirskyspeak.data.LearningRepository
import com.sibirskyspeak.data.SettingsStore

class ReviewViewModelFactory(
    private val repository: LearningRepository,
    private val settings: SettingsStore
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(ReviewViewModel::class.java)) {
            return ReviewViewModel(repository, settings) as T
        }
        error("Unknown ViewModel class: ${modelClass.name}")
    }
}
