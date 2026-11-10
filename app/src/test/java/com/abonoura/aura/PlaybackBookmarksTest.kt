package com.abonoura.aura

import org.junit.Assert.*
import org.junit.Test

class PlaybackBookmarksTest {
    @Test fun firstBookmarkCanBeAtTheBeginningOfTheVideo() {
        assertEquals(listOf(0L), addBookmark(emptyList(), 0L))
    }

    @Test fun insertsBetweenExistingPositionsInSortedOrder() {
        assertEquals(listOf(2_000L, 5_000L, 9_000L), addBookmark(listOf(9_000L, 2_000L), 5_000L))
    }

    @Test fun suppressesDuplicateAndNearDuplicatePositionsInBothDirections() {
        val existing = listOf(10_000L)
        for (position in listOf(9_000L, 9_500L, 10_000L, 10_500L, 11_000L)) {
            assertEquals(existing, addBookmark(existing, position))
        }
        assertEquals(listOf(10_000L, 11_001L), addBookmark(existing, 11_001L))
    }

    @Test fun keepsExistingMarksWhenTheThirtyPositionLimitIsReached() {
        val existing = (0 until MAX_PLAYBACK_BOOKMARKS).map { it * 2_000L }
        assertEquals(existing, addBookmark(existing, 100_000L))
        assertEquals(MAX_PLAYBACK_BOOKMARKS, addBookmark(existing, 100_000L).size)
    }

    @Test fun acceptsTheThirtiethPosition() {
        val existing = (0 until MAX_PLAYBACK_BOOKMARKS - 1).map { it * 2_000L }
        val result = addBookmark(existing, 100_000L)
        assertEquals(MAX_PLAYBACK_BOOKMARKS, result.size)
        assertEquals(100_000L, result.last())
    }

    @Test fun ignoresInvalidExistingPositionsAndNormalizesOldDuplicates() {
        assertEquals(listOf(0L, 5_000L, 10_000L), addBookmark(listOf(-1L, 5_000L, 0L, 5_500L, 5_000L), 10_000L))
    }

    @Test fun rejectsNegativeNewPositions() {
        assertThrows(IllegalArgumentException::class.java) { addBookmark(emptyList(), -1L) }
    }

    @Test fun largePositionsDoNotOverflowTheDuplicateCheck() {
        assertEquals(listOf(0L, Long.MAX_VALUE), addBookmark(listOf(0L), Long.MAX_VALUE))
        assertEquals(listOf(Long.MAX_VALUE - 500L), addBookmark(listOf(Long.MAX_VALUE - 500L), Long.MAX_VALUE))
    }

    @Test fun doesNotMutateTheCallersCollection() {
        val existing = mutableListOf(6_000L, 2_000L)
        addBookmark(existing, 10_000L)
        assertEquals(listOf(6_000L, 2_000L), existing)
    }
}
