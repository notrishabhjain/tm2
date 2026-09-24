package com.taskmind.ui.review

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.taskmind.data.db.entity.ReviewItemEntity
import com.taskmind.di.AppContainer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Spec 16: the review inbox shows the source text with one-tap accept/dismiss
 * and a visible confidence figure. Nothing here has been written to the task
 * list yet - that is the whole point of the middle confidence band.
 */
private const val DAY_MILLIS = 24L * 60 * 60 * 1000

class ReviewViewModel(private val container: AppContainer) : ViewModel() {

    init {
        // The badge means "there is something you have not seen". Opening the
        // screen is seeing it, so the count resets here rather than growing
        // forever across sessions.
        container.notifier.clearReviewNotice()
    }

    val items: StateFlow<List<ReviewItemEntity>> = container.taskRepository.observePendingReview()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * What you said no to, and what the app said no to on your behalf after
     * nobody answered it for a week. The second is why this list exists: an
     * auto-dismissal has to be recoverable, or it is just a silent deletion
     * with a longer fuse.
     */
    val dismissed: StateFlow<List<ReviewItemEntity>> = container.taskRepository.observeDismissedReview()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    /**
     * How many waiting candidates are already past the expiry window, and what
     * that window is.
     *
     * Derived from the list on screen rather than from a second query, so the
     * banner and the rows can never disagree - and so it updates the moment you
     * accept or dismiss one of them.
     *
     * The clock is read on each emission. That makes the count go stale if the
     * screen is left open past midnight, which matters not at all: the button
     * recomputes the cutoff when it is pressed.
     */
    val stale: StateFlow<Stale> = combine(
        items,
        container.settingsRepository.settings,
    ) { pending, settings ->
        val days = settings.reviewExpiryDays
        if (days <= 0) {
            Stale(0, 0)
        } else {
            val cutoff = System.currentTimeMillis() - days * DAY_MILLIS
            Stale(pending.count { it.createdAt < cutoff }, days)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), Stale(0, 0))

    data class Stale(val count: Int, val days: Int)

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

    fun restore(item: ReviewItemEntity) {
        viewModelScope.launch {
            container.taskRepository.restoreReviewItem(item.id)
            _message.value = "Back in the queue"
        }
    }

    /**
     * Clears everything past the window now, rather than waiting for the
     * retention worker's next run.
     *
     * The cutoff is recomputed here from the current clock, so this never acts
     * on a number the screen worked out an hour ago.
     */
    fun clearStale() {
        viewModelScope.launch {
            val days = container.settingsRepository.current().reviewExpiryDays
            if (days <= 0) return@launch
            val cutoff = System.currentTimeMillis() - days * DAY_MILLIS
            val count = container.taskRepository.dismissStaleReviewItems(cutoff)
            _message.value = when (count) {
                0 -> "Nothing was past $days days"
                1 -> "Dismissed 1 item - it is in the Dismissed tab"
                else -> "Dismissed $count items - they are in the Dismissed tab"
            }
        }
    }

    fun clearMessage() {
        _message.value = null
    }
}
