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
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import android.view.View
import android.widget.RemoteViews
import androidx.annotation.WorkerThread
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
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
class GridMediaWidgetReceiver : AppWidgetProvider() {

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
                updateWidget(
                    context = context,
                    appWidgetManager = appWidgetManager,
                    appWidgetId = widgetId,
                    uris = widgetRepository.getMediaUris(widgetId = widgetId),
                )
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
        private const val TAG = "GridMediaWidgetReceiver"
        private const val GRID_SPACING = 2

        /**
         * Reads the cached bitmaps from disk and composites the grid, so it must not run on the
         * main thread.
         */
        @WorkerThread
        fun updateWidget(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetId: Int,
            uris: List<Uri>,
        ) {
            val bitmaps = uris.indices.mapNotNull { index ->
                WidgetBitmapLoader.loadCachedBitmap(context, appWidgetId, index)
            }
            val views = RemoteViews(context.packageName, R.layout.widget_single_content)

            if (bitmaps.isNotEmpty()) {
                val gridBitmap = createGridBitmap(bitmaps)
                views.setImageViewBitmap(R.id.widget_image, gridBitmap)
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

        private fun createGridBitmap(bitmaps: List<Bitmap>): Bitmap {
            val cols = when {
                bitmaps.size <= 1 -> 1
                bitmaps.size <= 4 -> 2
                else -> 3
            }
            val rows = (bitmaps.size + cols - 1) / cols
            val cellSize = 1024 / cols
            val totalWidth = cols * cellSize + (cols - 1) * GRID_SPACING
            val totalHeight = rows * cellSize + (rows - 1) * GRID_SPACING

            val result = createBitmap(totalWidth, totalHeight)
            val canvas = Canvas(result)
            canvas.drawColor(Color.DKGRAY)

            bitmaps.forEachIndexed { index, bitmap ->
                val row = index / cols
                val col = index % cols
                val x = col * (cellSize + GRID_SPACING)
                val y = row * (cellSize + GRID_SPACING)

                val scaled = bitmap.scale(cellSize, cellSize, true)
                canvas.drawBitmap(scaled, x.toFloat(), y.toFloat(), null)
                if (scaled !== bitmap) scaled.recycle()
            }

            return result
        }
    }
}
