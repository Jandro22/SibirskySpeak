package com.sibirskyspeak.data

/** Reader persistence boundary: text metadata and token bookmarks stay out of
 * the session/model orchestration code. */
class ReaderTextService(
    private val textDao: ReaderTextDao,
    private val scheduleDao: ReadingScheduleDao? = null,
    private val bookmarkDao: ReaderBookmarkDao? = null
) {
    suspend fun add(
        title: String,
        body: String,
        source: String = "local",
        translationBody: String? = null
    ): Long {
        val id = textDao.insert(
            ReaderText(
                title = title.ifBlank { "Imported Text" },
                body = body,
                translationBody = translationBody?.trim()?.takeIf { it.isNotBlank() },
                source = source.trim().ifBlank { "local" }
            )
        )
        scheduleDao?.insert(ReadingSchedule(readerTextId = id))
        return id
    }

    suspend fun updateSource(textId: Long, source: String): Boolean =
        textDao.updateSource(textId, source.trim().ifBlank { "local" }) > 0

    suspend fun bookmarks(textId: Long): List<ReaderBookmark> = bookmarkDao?.getForText(textId).orEmpty()

    suspend fun toggleBookmark(textId: Long, tokenIndex: Int, label: String = ""): Boolean {
        val dao = bookmarkDao ?: return false
        val existing = dao.getAt(textId, tokenIndex)
        return if (existing == null) {
            dao.insert(ReaderBookmark(readerTextId = textId, tokenIndex = tokenIndex, label = label.trim()))
            true
        } else {
            dao.deleteById(existing.id)
            false
        }
    }
}
