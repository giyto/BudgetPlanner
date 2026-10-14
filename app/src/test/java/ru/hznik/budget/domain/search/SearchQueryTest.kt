package ru.hznik.budget.domain.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchQueryTest {
    @Test
    fun `normalization lowercases cyrillic and replaces yo`() {
        assertEquals("кафе елка", normalizeForSearch("  Кафе ЁЛКА "))
        assertEquals("пятерочка", normalizeForSearch("Пятёрочка"))
    }

    @Test
    fun `whole number is also an amount in rubles`() {
        assertEquals(450L, SearchQuery("450").rubles)
        assertEquals(450L, SearchQuery(" 450 ").rubles)
    }

    @Test
    fun `non numbers and too long numbers are not amounts`() {
        assertNull(SearchQuery("45а").rubles)
        assertNull(SearchQuery("450,5").rubles)
        assertNull(SearchQuery("12345678").rubles)
        assertNull(SearchQuery("").rubles)
    }

    @Test
    fun `blank query is empty`() {
        assertTrue(SearchQuery("   ").isEmpty)
    }
}
