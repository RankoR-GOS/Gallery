/*
 * SPDX-FileCopyrightText: 2023-2026 IacobIacob01
 * SPDX-License-Identifier: Apache-2.0
 */
package com.dot.gallery.feature_node.presentation.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import androidx.annotation.WorkerThread
import com.dot.gallery.R
import com.dot.gallery.core.util.ext.goAsync
import com.dot.gallery.feature_node.data.repository.WidgetRepository
import com.dot.gallery.feature_node.presentation.main.MainActivity
import com.dot.gallery.feature_node.presentation.widget.data.WidgetBitmapLoader
import com.dot.gallery.injection.qualifier.IoDispatcher
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher

@AndroidEntryPoint
class SingleMediaWidgetReceiver : AppWidgetProvider() {

    @Inject
    internal lateinit var widgetRepository: WidgetRepository

    @Inject
    @IoDispatcher
    lateinit var ioDispatcher: CoroutineDispatcher

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        goAsync(tag = TAG, dispatcher = ioDispatcher) {
            appWidgetIds.forEach { widgetId ->
                updateWidget(context, appWidgetManager, widgetId)
            }
        }
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        super.onDeleted(context, appWidgetIds)
        goAsync(tag = TAG, dispatcher = ioDispatcher) {
            appWidgetIds.forEach { widgetId ->
                widgetRepository.deleteWidgetData(widgetId = widgetId)
                WidgetBitmapLoader.clearCache(context, widgetId)
            }
        }
    }

    companion object {
        private const val TAG = "SingleMediaWidgetReceiver"

        /**
         * Reads the cached bitmap from disk, so it must not run on the main thread.
         */
        @WorkerThread
        fun updateWidget(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int) {
            val bitmap = WidgetBitmapLoader.loadCachedBitmap(context, appWidgetId, 0)
            val views = RemoteViews(context.packageName, R.layout.widget_single_content)

            if (bitmap != null) {
                views.setImageViewBitmap(R.id.widget_image, bitmap)
                views.setViewVisibility(R.id.widget_image, View.VISIBLE)
                views.setViewVisibility(R.id.widget_no_photo_text, View.GONE)
            } else {
                views.setViewVisibility(R.id.widget_image, View.GONE)
                views.setViewVisibility(R.id.widget_no_photo_text, View.VISIBLE)
            }

            // Set click to open app
            val intent = Intent(context, MainActivity::class.java)
            val pendingIntent = PendingIntent.getActivity(
                context, appWidgetId, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_root, pendingIntent)

            appWidgetManager.updateAppWidget(appWidgetId, views)
        }
    }
}
