package com.sibirskyspeak.review

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.sibirskyspeak.data.LearningRepository

class ReviewViewModelFactory(
    private val repository: LearningRepository
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(ReviewViewModel::class.java)) {
            return ReviewViewModel(repository) as T
        }
        error("Unknown ViewModel class: ${modelClass.name}")
    }
}
