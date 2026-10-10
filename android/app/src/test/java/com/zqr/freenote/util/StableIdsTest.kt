package com.zqr.freenote.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StableIdsTest {

    @Test
    fun stableIdsAreDeterministicAndNamespaced() {
        val id = "note-uuid-123"
        assertEquals(StableIds.forNote(id), StableIds.forNote(id))
        assertEquals(101, (0..100).map { StableIds.forNote("note-$it") }.toSet().size)
        listOf("a", "b", "some-note-1", "note-uuid-123").forEach {
            assertTrue(StableIds.forNote(it) >= 0)
        }
        assertNotEquals(StableIds.forNote("abc"), StableIds.forKey("abc"))
        assertNotEquals(StableIds.forNote("a/b"), StableIds.forNote("a\\b"))
        assertNotEquals(StableIds.forNote(""), StableIds.forNote(" "))
    }
}
