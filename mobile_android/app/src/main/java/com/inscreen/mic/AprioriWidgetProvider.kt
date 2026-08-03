package com.inscreen.mic

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews

class AprioriWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { update(context, manager, it) }
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        manager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: android.os.Bundle,
    ) {
        update(context, manager, appWidgetId)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action != ACTION_CONSUME) return
        val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = SystemClock.elapsedRealtime()
        val currentHead = AprioriStore.queueHead(context)
        if (!canConsume(now, preferences.getLong(KEY_LOCK_UNTIL, 0L), currentHead != null)) return
        currentHead ?: return
        preferences.edit().putLong(KEY_LOCK_UNTIL, now + ANIMATION_LOCK_MS).apply()

        val pending = goAsync()
        try {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, AprioriWidgetProvider::class.java))
            val state = AprioriStore.consumeHead(context)
            AprioriUpdates.publish(context, state, refreshWidgets = false)
            val nextHead = AprioriStore.queueHead(state)
            ids.forEach { id ->
                if (supportsFractureAnimation()) {
                    animateFracture(context, manager, id, currentHead, nextHead)
                } else {
                    animateLegacy(context, manager, id, nextHead)
                }
            }
            Handler(Looper.getMainLooper()).postDelayed({
                if (supportsFractureAnimation()) {
                    ids.forEach { id -> settleFracture(context, manager, id) }
                }
                pending.finish()
            }, SETTLE_DELAY_MS)
        } catch (error: Throwable) {
            pending.finish()
            throw error
        }
    }

    companion object {
        private const val ACTION_CONSUME = "com.inscreen.mic.widget.CONSUME"
        private const val PREFS = "apriori_widget"
        private const val KEY_LOCK_UNTIL = "animation_lock_until"
        private const val ANIMATION_LOCK_MS = 700L
        private const val SETTLE_DELAY_MS = 660L

        internal fun canConsume(now: Long, lockedUntil: Long, hasHead: Boolean): Boolean =
            hasHead && now >= lockedUntil

        internal fun supportsFractureAnimation(sdk: Int = Build.VERSION.SDK_INT): Boolean =
            sdk >= Build.VERSION_CODES.P

        fun updateAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, AprioriWidgetProvider::class.java))
            ids.forEach { update(context, manager, it) }
        }

        private fun update(context: Context, manager: AppWidgetManager, id: Int) {
            if (supportsFractureAnimation()) updateFracture(context, manager, id)
            else updateLegacy(context, manager, id)
        }

        private fun updateFracture(context: Context, manager: AppWidgetManager, id: Int) {
            val state = AprioriStore.load(context)
            val head = AprioriStore.queueHead(state)
            val following = AprioriStore.nextQueueHead(state)
            val views = RemoteViews(context.packageName, R.layout.apriori_widget)
            bindNativeCard(views, R.id.widget_back_card, R.id.widget_back_label, following)
            bindNativeCard(views, R.id.widget_front_static_card, R.id.widget_front_static_label, head)
            bindAnimatedLabel(views, head)
            views.setViewVisibility(R.id.widget_front_animation, View.GONE)
            views.setViewVisibility(R.id.widget_front_static_card, View.VISIBLE)
            views.setInt(R.id.widget_front_label_flipper, "setDisplayedChild", 0)
            bindTap(context, views, id, head != null)
            bindDescription(views, head)
            manager.updateAppWidget(id, views)
        }

        private fun updateLegacy(context: Context, manager: AppWidgetManager, id: Int) {
            val head = AprioriStore.queueHead(context)
            val child = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getInt("child_$id", 0)
            val views = RemoteViews(context.packageName, R.layout.apriori_widget)
            bindLegacyHead(views, 0, head)
            bindLegacyHead(views, 1, head)
            views.setInt(R.id.widget_root, "setDisplayedChild", child)
            bindTap(context, views, id, head != null)
            bindDescription(views, head)
            manager.updateAppWidget(id, views)
        }

        private fun bindTap(context: Context, views: RemoteViews, id: Int, canConsume: Boolean) {
            val pendingIntent = if (!canConsume) {
                val open = Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                PendingIntent.getActivity(
                    context,
                    id,
                    open,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            } else {
                val consume = Intent(context, AprioriWidgetProvider::class.java)
                    .setAction(ACTION_CONSUME)
                    .addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                PendingIntent.getBroadcast(
                    context,
                    id,
                    consume,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            }
            views.setOnClickPendingIntent(R.id.widget_root, pendingIntent)
        }

        private fun animateFracture(
            context: Context,
            manager: AppWidgetManager,
            id: Int,
            current: AprioriStore.QueueHead,
            next: AprioriStore.QueueHead?,
        ) {
            val views = RemoteViews(context.packageName, R.layout.apriori_widget)
            val currentStyle = WidgetCardRenderer.present(current)
            bindNativeCard(views, R.id.widget_back_card, R.id.widget_back_label, next)
            bindAnimatedLabel(views, current)
            views.setViewVisibility(R.id.widget_front_animation, View.GONE)
            views.setInt(R.id.widget_front_label_flipper, "setDisplayedChild", 0)
            views.setImageViewResource(
                R.id.widget_front_fill,
                R.drawable.widget_fracture_fill_animation,
            )
            views.setInt(R.id.widget_front_fill, "setColorFilter", currentStyle.color)
            views.setImageViewResource(
                R.id.widget_front_border,
                R.drawable.widget_fracture_border_animation,
            )
            views.setInt(R.id.widget_front_border, "setColorFilter", Color.BLACK)
            views.setImageViewResource(
                R.id.widget_front_texture,
                R.drawable.widget_fracture_texture_animation,
            )
            views.setViewVisibility(R.id.widget_front_animation, View.VISIBLE)
            views.setViewVisibility(R.id.widget_front_static_card, View.GONE)
            views.setInt(R.id.widget_front_label_flipper, "setDisplayedChild", 1)
            bindDescription(views, current)
            manager.partiallyUpdateAppWidget(id, views)
        }

        private fun settleFracture(context: Context, manager: AppWidgetManager, id: Int) {
            val state = AprioriStore.load(context)
            val head = AprioriStore.queueHead(state)
            val following = AprioriStore.nextQueueHead(state)
            val views = RemoteViews(context.packageName, R.layout.apriori_widget)
            views.setViewVisibility(R.id.widget_front_animation, View.GONE)
            views.setInt(R.id.widget_front_label_flipper, "setDisplayedChild", 0)
            bindNativeCard(views, R.id.widget_front_static_card, R.id.widget_front_static_label, head)
            views.setViewVisibility(R.id.widget_front_static_card, View.VISIBLE)
            bindNativeCard(views, R.id.widget_back_card, R.id.widget_back_label, following)
            bindTap(context, views, id, head != null)
            bindDescription(views, head)
            manager.partiallyUpdateAppWidget(id, views)
        }

        private fun animateLegacy(
            context: Context,
            manager: AppWidgetManager,
            id: Int,
            head: AprioriStore.QueueHead?,
        ) {
            val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val oldChild = preferences.getInt("child_$id", 0)
            val nextChild = 1 - oldChild
            val views = RemoteViews(context.packageName, R.layout.apriori_widget)
            bindLegacyHead(views, nextChild, head)
            views.setInt(R.id.widget_root, "setDisplayedChild", nextChild)
            manager.partiallyUpdateAppWidget(id, views)
            preferences.edit().putInt("child_$id", nextChild).apply()
        }

        private fun bindNativeCard(
            views: RemoteViews,
            cardId: Int,
            labelId: Int,
            head: AprioriStore.QueueHead?,
        ) {
            val presentation = WidgetCardRenderer.present(head)
            views.setInt(labelId, "setBackgroundColor", presentation.color)
            views.setTextViewText(labelId, presentation.label)
            views.setTextViewTextSize(
                labelId,
                TypedValue.COMPLEX_UNIT_SP,
                if (head == null) 20f else 40f,
            )
            views.setContentDescription(cardId, description(head))
        }

        private fun bindAnimatedLabel(views: RemoteViews, head: AprioriStore.QueueHead?) {
            val presentation = WidgetCardRenderer.present(head)
            views.setTextViewText(R.id.widget_front_animated_label, presentation.label)
            views.setTextViewTextSize(
                R.id.widget_front_animated_label,
                TypedValue.COMPLEX_UNIT_SP,
                if (head == null) 20f else 40f,
            )
        }

        private fun bindLegacyHead(
            views: RemoteViews,
            child: Int,
            head: AprioriStore.QueueHead?,
        ) {
            val cardId = if (child == 0) R.id.widget_card_0 else R.id.widget_card_1
            val labelId = if (child == 0) R.id.widget_label_0 else R.id.widget_label_1
            bindNativeCard(views, cardId, labelId, head)
        }

        private fun bindDescription(views: RemoteViews, head: AprioriStore.QueueHead?) {
            views.setContentDescription(R.id.widget_root, description(head))
        }

        private fun description(head: AprioriStore.QueueHead?): String = head?.let {
            "Materia actual: ${it.name}, ${it.ticketCount} fichas en la cola. Tocar para destruir y avanzar."
        } ?: "Sin materias. Tocar para abrir InScreen."
    }
}
