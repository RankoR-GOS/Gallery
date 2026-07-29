package com.dot.gallery.feature_node.data.repository

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.room.Room
import com.dot.gallery.core.dataStore
import com.dot.gallery.feature_node.data.data_source.InternalDatabase
import com.dot.gallery.feature_node.data.model.Category
import com.dot.gallery.feature_node.data.model.ImageEmbedding
import com.dot.gallery.feature_node.data.model.MediaCategory
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
internal class AiMediaAnalysisRepositoryTest {
    private lateinit var database: InternalDatabase
    private lateinit var repository: AiMediaAnalysisRepository

    @Before
    fun setUp() {
        val context: Context = RuntimeEnvironment.getApplication()
        runBlocking {
            context.dataStore.edit { preferences -> preferences.clear() }
        }
        database = Room.inMemoryDatabaseBuilder(context, InternalDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = AiMediaAnalysisRepositoryImpl(context = context, database = database)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun clearAllGeneratedData_preservesCategoryDefinitionAndManualMembership() {
        runTest {
            val category = Category(
                id = CATEGORY_ID,
                name = "User category",
                searchTerms = "sunset",
                embedding = floatArrayOf(0.1f, 0.2f),
                referenceImageIds = listOf(MEDIA_ID),
                isUserCreated = true,
            )
            database.getCategoryDao().insertCategory(category = category)
            database.getImageEmbeddingDao().addImageEmbedding(
                ImageEmbedding(
                    id = MEDIA_ID,
                    date = 10L,
                    embedding = floatArrayOf(0.3f, 0.4f),
                ),
            )
            database.getCategoryDao().insertMediaCategory(
                mediaCategory = MediaCategory(
                    mediaId = MEDIA_ID,
                    categoryId = CATEGORY_ID,
                    similarityScore = 0.9f,
                    isManuallyAdded = true,
                ),
            )
            database.getCategoryDao().insertMediaCategory(
                mediaCategory = MediaCategory(
                    mediaId = OTHER_MEDIA_ID,
                    categoryId = CATEGORY_ID,
                    similarityScore = 0.8f,
                ),
            )

            repository.clearAllGeneratedData()

            assertNull(database.getImageEmbeddingDao().getRecord(id = MEDIA_ID))
            assertEquals(
                listOf(MEDIA_ID),
                database.getCategoryDao().getAllClassifiedMediaIds(),
            )
            val preservedCategory = database.getCategoryDao()
                .getCategoryById(categoryId = CATEGORY_ID)
            assertEquals(category.name, preservedCategory?.name)
            assertEquals(category.searchTerms, preservedCategory?.searchTerms)
            assertEquals(category.referenceImageIds, preservedCategory?.referenceImageIds)
            assertNull(preservedCategory?.embedding)
            assertEquals(1, database.getCategoryDao().getAllCategories().first().size)
        }
    }

    @Test
    fun invalidateGeneratedData_preservesManualMappingForChangedMedia() {
        runTest {
            database.getCategoryDao().insertCategory(
                category = Category(
                    id = CATEGORY_ID,
                    name = "User category",
                    searchTerms = "sunset",
                    embedding = floatArrayOf(0.1f, 0.2f),
                    isUserCreated = true,
                ),
            )
            listOf(MEDIA_ID, OTHER_MEDIA_ID).forEach { mediaId ->
                database.getImageEmbeddingDao().addImageEmbedding(
                    ImageEmbedding(
                        id = mediaId,
                        date = 10L,
                        embedding = floatArrayOf(0.3f, 0.4f),
                    ),
                )
                database.getCategoryDao().insertMediaCategory(
                    mediaCategory = MediaCategory(
                        mediaId = mediaId,
                        categoryId = CATEGORY_ID,
                        similarityScore = 0.9f,
                        isManuallyAdded = mediaId == MEDIA_ID,
                    ),
                )
            }

            repository.invalidateGeneratedData(mediaIds = setOf(MEDIA_ID))

            assertNull(database.getImageEmbeddingDao().getRecord(id = MEDIA_ID))
            assertEquals(
                setOf(OTHER_MEDIA_ID),
                database.getImageEmbeddingDao().getRecords().first().map { record -> record.id }
                    .toSet(),
            )
            assertEquals(
                setOf(MEDIA_ID, OTHER_MEDIA_ID),
                database.getCategoryDao().getAllClassifiedMediaIds().toSet(),
            )
        }
    }

    @Test
    fun removeMediaData_removesGeneratedAndManualMappingsForDeletedMedia() {
        runTest {
            insertCategoryAndMembership(isManuallyAdded = true)

            repository.removeMediaData(mediaIds = setOf(MEDIA_ID))

            assertTrue(database.getCategoryDao().getAllClassifiedMediaIds().isEmpty())
        }
    }

    @Test
    fun clearCategoryGeneratedData_preservesManualMembership() {
        runTest {
            insertCategoryAndMembership(isManuallyAdded = true)
            database.getCategoryDao().insertMediaCategory(
                mediaCategory = MediaCategory(
                    mediaId = OTHER_MEDIA_ID,
                    categoryId = CATEGORY_ID,
                    similarityScore = 0.8f,
                ),
            )

            repository.clearCategoryGeneratedData()

            assertEquals(
                listOf(MEDIA_ID),
                database.getCategoryDao().getAllClassifiedMediaIds(),
            )
        }
    }

    @Test
    fun reclassifyMediaForCategory_preservesManualMembershipAndAddsGeneratedMatches() {
        runTest {
            insertCategoryAndMembership(isManuallyAdded = true)

            database.getCategoryDao().reclassifyMediaForCategory(
                categoryId = CATEGORY_ID,
                mediaCategories = listOf(
                    MediaCategory(
                        mediaId = MEDIA_ID,
                        categoryId = CATEGORY_ID,
                        similarityScore = 0.1f,
                    ),
                    MediaCategory(
                        mediaId = OTHER_MEDIA_ID,
                        categoryId = CATEGORY_ID,
                        similarityScore = 0.8f,
                    ),
                ),
            )

            assertEquals(
                setOf(MEDIA_ID, OTHER_MEDIA_ID),
                database.getCategoryDao().getAllClassifiedMediaIds().toSet(),
            )
            assertEquals(
                0.9f,
                database.getCategoryDao().getSimilarityScore(
                    mediaId = MEDIA_ID,
                    categoryId = CATEGORY_ID,
                ),
            )
        }
    }

    @Test
    fun largeChangedAndRemovedIdSets_areProcessedWithoutExceedingSqliteBindLimits() {
        runTest {
            database.getCategoryDao().insertCategory(
                category = Category(
                    id = CATEGORY_ID,
                    name = "Generated category",
                    searchTerms = "term",
                ),
            )
            val mediaIds = (1L..LARGE_ID_SET_SIZE.toLong()).toSet()
            val embeddings = mediaIds.map { mediaId ->
                ImageEmbedding(
                    id = mediaId,
                    date = 1L,
                    embedding = floatArrayOf(0.1f),
                )
            }
            val mappings = mediaIds.map { mediaId ->
                MediaCategory(
                    mediaId = mediaId,
                    categoryId = CATEGORY_ID,
                    similarityScore = 0.5f,
                )
            }
            database.getImageEmbeddingDao().addImageEmbeddings(imageEmbeddings = embeddings)
            database.getCategoryDao().insertGeneratedMediaCategories(mediaCategories = mappings)

            repository.invalidateGeneratedData(mediaIds = mediaIds)

            assertEquals(0, database.getImageEmbeddingDao().getCount())
            assertTrue(database.getCategoryDao().getAllClassifiedMediaIds().isEmpty())

            database.getImageEmbeddingDao().addImageEmbeddings(imageEmbeddings = embeddings)
            database.getCategoryDao().insertGeneratedMediaCategories(mediaCategories = mappings)

            repository.removeMediaData(mediaIds = mediaIds)

            assertEquals(0, database.getImageEmbeddingDao().getCount())
            assertTrue(database.getCategoryDao().getAllClassifiedMediaIds().isEmpty())
        }
    }

    @Test
    fun disablingAnalysis_persistsCleanupPendingUntilCleanupCompletes() {
        runTest {
            repository.setAnalysisEnabled(enabled = false)

            val pendingPreferences = repository.getPreferences()
            assertTrue(pendingPreferences.analysisCleanupPending)
            assertTrue(pendingPreferences.categoryCleanupPending)

            repository.completeAnalysisCleanup()

            val completedPreferences = repository.getPreferences()
            assertEquals(false, completedPreferences.analysisCleanupPending)
            assertEquals(false, completedPreferences.categoryCleanupPending)
        }
    }

    @Test
    fun disablingCategories_persistsCleanupPendingUntilCleanupCompletes() {
        runTest {
            repository.setCategoryClassificationEnabled(enabled = false)

            assertTrue(repository.getPreferences().categoryCleanupPending)

            repository.completeCategoryCleanup()

            assertEquals(false, repository.getPreferences().categoryCleanupPending)
        }
    }

    @Test
    fun reenablingAnalysis_preservesPendingCleanupWhenCategoriesRemainDisabled() {
        runTest {
            repository.setCategoryClassificationEnabled(enabled = false)
            repository.setAnalysisEnabled(enabled = false)

            repository.setAnalysisEnabled(enabled = true)
            repository.completeAnalysisCleanup()

            val preferences = repository.getPreferences()
            assertTrue(preferences.analysisEnabled)
            assertEquals(false, preferences.analysisCleanupPending)
            assertEquals(false, preferences.categoryClassificationEnabled)
            assertTrue(preferences.categoryCleanupPending)
        }
    }

    @Test
    fun getClassifiedMediaIdPage_returnsManualAndGeneratedMappingsInKeysetOrder() {
        runTest {
            insertCategoryAndMembership(isManuallyAdded = true)
            database.getCategoryDao().insertMediaCategory(
                mediaCategory = MediaCategory(
                    mediaId = OTHER_MEDIA_ID,
                    categoryId = CATEGORY_ID,
                    similarityScore = 0.8f,
                    isManuallyAdded = true,
                ),
            )

            val firstPage = repository.getClassifiedMediaIdPage(
                afterId = Long.MIN_VALUE,
                limit = 1,
            )
            val secondPage = repository.getClassifiedMediaIdPage(
                afterId = firstPage.last(),
                limit = 1,
            )

            assertEquals(listOf(MEDIA_ID), firstPage)
            assertEquals(listOf(OTHER_MEDIA_ID), secondPage)
        }
    }

    private suspend fun insertCategoryAndMembership(isManuallyAdded: Boolean) {
        database.getCategoryDao().insertCategory(
            category = Category(
                id = CATEGORY_ID,
                name = "User category",
                searchTerms = "sunset",
                embedding = floatArrayOf(0.1f, 0.2f),
                isUserCreated = true,
            ),
        )
        database.getCategoryDao().insertMediaCategory(
            mediaCategory = MediaCategory(
                mediaId = MEDIA_ID,
                categoryId = CATEGORY_ID,
                similarityScore = 0.9f,
                isManuallyAdded = isManuallyAdded,
            ),
        )
    }

    companion object {
        private const val CATEGORY_ID = 42L
        private const val LARGE_ID_SET_SIZE = 1_001
        private const val MEDIA_ID = 7L
        private const val OTHER_MEDIA_ID = 8L
    }
}
