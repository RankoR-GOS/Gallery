package com.dot.gallery.feature_node.data.repository

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.UriPermission
import android.net.Uri
import com.dot.gallery.feature_node.data.model.WidgetType
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
internal class WidgetRepositoryTest {

    private lateinit var applicationContext: Context
    private lateinit var contentResolver: ContentResolver
    private lateinit var repository: WidgetRepository

    @Before
    fun setUp() {
        applicationContext = RuntimeEnvironment.getApplication()
        applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        contentResolver = mockk(relaxed = true)
        val context = mockk<Context>()
        every { context.contentResolver } returns contentResolver
        every { context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE) } returns
                applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
        repository = WidgetRepositoryImpl(context = context)
    }

    @After
    fun tearDown() {
        applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @Test
    fun replaceWidgetData_deduplicatesUrisWithoutReordering() {
        val firstUri = Uri.parse("content://provider/first")
        val secondUri = Uri.parse("content://provider/second")
        every { contentResolver.persistedUriPermissions } returns emptyList()
        every {
            contentResolver.takePersistableUriPermission(any(), Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } just runs

        val saved = repository.replaceWidgetData(
            widgetId = 1,
            type = WidgetType.GRID,
            uris = listOf(firstUri, secondUri, firstUri),
        )

        assertEquals(true, saved)
        assertEquals(listOf(firstUri, secondUri), repository.getMediaUris(widgetId = 1))
    }

    @Test
    fun sharedUriGrant_isReleasedOnlyAfterLastWidgetIsDeleted() {
        val sharedUri = Uri.parse("content://provider/shared")
        val replacementUri = Uri.parse("content://provider/replacement")
        val permission = mockk<UriPermission> {
            every { uri } returns sharedUri
            every { isReadPermission } returns true
        }
        every { contentResolver.persistedUriPermissions } returns listOf(permission)
        every {
            contentResolver.takePersistableUriPermission(any(), Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } just runs
        every {
            contentResolver.releasePersistableUriPermission(any(), Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } just runs
        repository.replaceWidgetData(
            widgetId = 1,
            type = WidgetType.SINGLE,
            uris = listOf(sharedUri),
        )
        repository.replaceWidgetData(
            widgetId = 2,
            type = WidgetType.SINGLE,
            uris = listOf(sharedUri),
        )

        repository.replaceWidgetData(
            widgetId = 1,
            type = WidgetType.SINGLE,
            uris = listOf(replacementUri),
        )

        verify(exactly = 0) {
            contentResolver.releasePersistableUriPermission(
                sharedUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }

        val deleted = repository.deleteWidgetData(widgetId = 2)

        assertEquals(true, deleted)
        assertNull(repository.getWidgetData(widgetId = 2))
        assertFalse(repository.getMediaUris(widgetId = 1).contains(sharedUri))
        verify(exactly = 1) {
            contentResolver.releasePersistableUriPermission(
                sharedUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
    }

    private companion object {
        private const val PREFERENCES_NAME = "gallery_widget_prefs"
    }
}
