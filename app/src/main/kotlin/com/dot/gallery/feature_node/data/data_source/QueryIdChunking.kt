package com.dot.gallery.feature_node.data.data_source

/**
 * Upper bound on the ids bound into a single `IN (:ids)` query.
 *
 * SQLite caps the number of bound variables per statement at 999 on the versions shipped with
 * older API levels, so id collections have to be split before they reach a DAO. The value is
 * deliberately below the cap to leave room for the other parameters a query may bind.
 */
private const val MAX_QUERY_IDS = 900

/**
 * Runs [action] once per chunk of [ids], each chunk small enough to bind into a single query.
 */
internal suspend fun forEachIdChunk(ids: Collection<Long>, action: suspend (Set<Long>) -> Unit) {
    ids.chunked(size = MAX_QUERY_IDS).forEach { chunk -> action(chunk.toSet()) }
}

/**
 * Queries [ids] one chunk at a time via [query] and concatenates the rows each chunk returns.
 */
internal suspend fun <T> flatMapIdChunks(
    ids: Collection<Long>,
    query: suspend (Set<Long>) -> List<T>,
): List<T> {
    return ids.chunked(size = MAX_QUERY_IDS).flatMap { chunk -> query(chunk.toSet()) }
}
