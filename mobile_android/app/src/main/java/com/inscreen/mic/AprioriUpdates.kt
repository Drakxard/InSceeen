package com.inscreen.mic

import android.content.Context
import android.content.Intent

object AprioriUpdates {
    const val ACTION_CHANGED = "com.inscreen.mic.APRIORI_CHANGED"
    const val EXTRA_STATE = "state"

    fun publish(context: Context, state: String, refreshWidgets: Boolean = true) {
        context.sendBroadcast(
            Intent(ACTION_CHANGED)
                .setPackage(context.packageName)
                .putExtra(EXTRA_STATE, state)
        )
        if (refreshWidgets) AprioriWidgetProvider.updateAll(context)
    }
}
