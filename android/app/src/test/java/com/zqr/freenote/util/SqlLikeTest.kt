package com.zqr.freenote.util

import org.junit.Assert.assertEquals
import org.junit.Test

class SqlLikeTest {

    @Test
    fun escapesLikeWildcards() {
        assertEquals("50\\%", SqlLike.escape("50%"))
        assertEquals("a\\_b", SqlLike.escape("a_b"))
        assertEquals("a\\\\b", SqlLike.escape("a\\b"))
        assertEquals("100\\%\\_done", SqlLike.escape("100%_done"))
        assertEquals("普通便签 hello", SqlLike.escape("普通便签 hello"))
    }
}
