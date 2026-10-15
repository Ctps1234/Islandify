package com.islandify.app.island

import com.islandify.app.core.HeadsUpController

import android.net.Uri
import android.service.notification.ConditionProviderService

/** Owner required by Android for Islandify's visual-only automatic notification rule. */
class IslandHeadsUpConditionProvider : ConditionProviderService() {
    override fun onConnected() = HeadsUpController.sync(this)

    override fun onSubscribe(conditionId: Uri) = HeadsUpController.sync(this)

    override fun onUnsubscribe(conditionId: Uri) = Unit
}
