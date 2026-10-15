package com.islandify.app.core

import com.islandify.app.R
import com.islandify.app.island.IslandHeadsUpConditionProvider

import android.app.AutomaticZenRule
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.Build
import android.service.notification.Condition
import android.service.notification.ZenPolicy
import androidx.annotation.RequiresApi

/**
 * Uses Android's public Do Not Disturb policy API to suppress only peeking/heads-up visuals.
 * All sound categories remain allowed, so the original notification stays in the shade and keeps
 * its normal sound/vibration while Islandify owns the visual banner.
 */
object HeadsUpController {
    private const val PREF_RULE_ID = "headsUpRuleId"
    private val conditionId = Uri.parse("condition://com.islandify.app/system-heads-up")

    fun sync(context: Context) {
        if (Build.VERSION.SDK_INT < 29 || !Perms.notificationPolicy(context)) return
        syncApi29(context.applicationContext)
    }

    @RequiresApi(29)
    private fun syncApi29(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        val prefs = context.getSharedPreferences("islandify", Context.MODE_PRIVATE)
        val wanted = IslandSettings.enabled.value && IslandSettings.suppressSystemHeadsUp.value
        var ruleId = prefs.getString(PREF_RULE_ID, null)

        if (!wanted) {
            if (ruleId != null) {
                runCatching { manager.removeAutomaticZenRule(ruleId) }
                prefs.edit().remove(PREF_RULE_ID).apply()
            }
            return
        }

        val policy = ZenPolicy.Builder()
            .allowAllSounds()
            .showAllVisualEffects()
            .showPeeking(false)
            .build()
        val rule = AutomaticZenRule(
            context.getString(R.string.heads_up_mode_name),
            ComponentName(context, IslandHeadsUpConditionProvider::class.java),
            null,
            conditionId,
            policy,
            NotificationManager.INTERRUPTION_FILTER_PRIORITY,
            true,
        )

        val existingId = ruleId
        val currentExists = existingId != null &&
            runCatching { manager.getAutomaticZenRule(existingId) }.getOrNull() != null
        ruleId = if (currentExists && existingId != null) {
            runCatching { manager.updateAutomaticZenRule(existingId, rule) }
            existingId
        } else {
            runCatching { manager.addAutomaticZenRule(rule) }.getOrNull()
        }
        val activeRuleId = ruleId ?: return

        prefs.edit().putString(PREF_RULE_ID, activeRuleId).apply()
        val active = Condition(conditionId, "Islandify", Condition.STATE_TRUE)
        runCatching { manager.setAutomaticZenRuleState(activeRuleId, active) }
    }
}
