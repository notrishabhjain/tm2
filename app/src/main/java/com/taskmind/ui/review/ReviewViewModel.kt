package com.taskmind.ui.review

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.taskmind.data.db.entity.ReviewItemEntity
import com.taskmind.di.AppContainer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Spec 16: the review inbox shows the source text with one-tap accept/dismiss
 * and a visible confidence figure. Nothing here has been written to the task
 * list yet - that is the whole point of the middle confidence band.
 */
class ReviewViewModel(private val container: AppContainer) : ViewModel() {

    val items: StateFlow<List<ReviewItemEntity>> = container.taskRepository.observePendingReview()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun accept(item: ReviewItemEntity) {
        viewModelScope.launch {
            container.taskRepository.acceptReviewItem(item.id)
            _message.value = "Added \"${item.title.take(40)}\""
        }
    }

    fun dismiss(item: ReviewItemEntity) {
        viewModelScope.launch {
            container.taskRepository.dismissReviewItem(item.id)
            _message.value = "Dismissed"
        }
    }

    fun clearMessage() {
        _message.value = null
    }
}
