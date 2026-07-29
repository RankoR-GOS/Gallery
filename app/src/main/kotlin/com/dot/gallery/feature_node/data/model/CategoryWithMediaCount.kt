package com.dot.gallery.feature_node.data.model

data class CategoryWithMediaCount(
    val id: Long,
    val name: String,
    val searchTerms: String,
    val embedding: FloatArray?,
    val referenceImageIds: List<Long>,
    val threshold: Float,
    val isUserCreated: Boolean,
    val isPinned: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
    val mediaCount: Int,
    val thumbnailMediaId: Long?,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CategoryWithMediaCount) return false
        return id == other.id &&
                name == other.name &&
                searchTerms == other.searchTerms &&
                embedding.contentEquals(other.embedding) &&
                referenceImageIds == other.referenceImageIds &&
                threshold == other.threshold &&
                isUserCreated == other.isUserCreated &&
                isPinned == other.isPinned &&
                createdAt == other.createdAt &&
                updatedAt == other.updatedAt &&
                mediaCount == other.mediaCount &&
                thumbnailMediaId == other.thumbnailMediaId
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + name.hashCode()
        result = 31 * result + searchTerms.hashCode()
        result = 31 * result + (embedding?.contentHashCode() ?: 0)
        result = 31 * result + referenceImageIds.hashCode()
        result = 31 * result + threshold.hashCode()
        result = 31 * result + isUserCreated.hashCode()
        result = 31 * result + isPinned.hashCode()
        result = 31 * result + createdAt.hashCode()
        result = 31 * result + updatedAt.hashCode()
        result = 31 * result + mediaCount
        result = 31 * result + (thumbnailMediaId?.hashCode() ?: 0)
        return result
    }
}
