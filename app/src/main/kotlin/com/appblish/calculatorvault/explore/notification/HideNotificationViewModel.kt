package com.appblish.calculatorvault.explore.notification

import androidx.lifecycle.ViewModel
import com.appblish.calculatorvault.explore.ExploreStore
import com.appblish.calculatorvault.explore.NotificationRule
import kotlinx.coroutines.flow.StateFlow

/** Backs the Hide Notification manager over the shared [ExploreStore]. */
class HideNotificationViewModel : ViewModel() {
    val hideAll: StateFlow<Boolean> = ExploreStore.hideAllNotifications
    val rules: StateFlow<List<NotificationRule>> = ExploreStore.notificationRules

    fun setHideAll(hidden: Boolean) = ExploreStore.setHideAllNotifications(hidden)

    fun setHidden(
        packageName: String,
        hidden: Boolean,
    ) = ExploreStore.setNotificationHidden(packageName, hidden)
}
