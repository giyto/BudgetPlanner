package ru.hznik.budget.domain.categorize

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Нормализация названия магазина и словарь популярных магазинов (spec auto-categorization). */
class MerchantKeyTest {
    @Test
    fun `terminal number and case do not matter`() {
        assertEquals("pyaterochka", MerchantKey.of("PYATEROCHKA 1234"))
        assertEquals(MerchantKey.of("PYATEROCHKA 1234"), MerchantKey.of("Pyaterochka 5678"))
    }

    @Test
    fun `yo quotes and stars are normalized`() {
        assertEquals("пятерочка", MerchantKey.of("Пятёрочка"))
        assertEquals("пятерочка", MerchantKey.of("ПЯТЕРОЧКА"))
        assertEquals("кофейня зерно", MerchantKey.of("Кофейня «Зерно»"))
        assertEquals("yandex go", MerchantKey.of("YANDEX*GO"))
        assertEquals("ип смирнова а в", MerchantKey.of("ИП Смирнова А. В."))
    }

    @Test
    fun `names without letters give no key`() {
        assertNull(MerchantKey.of("796*1234"))
        assertNull(MerchantKey.of(""))
        assertNull(MerchantKey.of("   "))
        assertNull(MerchantKey.of(null))
    }

    @Test
    fun `dictionary knows popular stores in both alphabets`() {
        assertEquals("products", MerchantDictionary.categoryKeyFor("PYATEROCHKA 1234"))
        assertEquals("products", MerchantDictionary.categoryKeyFor("Пятёрочка"))
        assertEquals("transport", MerchantDictionary.categoryKeyFor("YANDEX*GO"))
        assertEquals("cafe", MerchantDictionary.categoryKeyFor("Яндекс Еда"))
        assertEquals("health", MerchantDictionary.categoryKeyFor("Аптека Ригла"))
        assertEquals("subscriptions", MerchantDictionary.categoryKeyFor("Кинопоиск"))
    }

    @Test
    fun `longer phrase wins over a shorter one`() {
        // «яндекс еда» — кафе, а не продукты из «яндекс лавки» и не транспорт.
        assertEquals("cafe", MerchantDictionary.categoryKeyFor("YANDEX EDA"))
        assertEquals("products", MerchantDictionary.categoryKeyFor("YANDEX LAVKA"))
    }

    @Test
    fun `match only whole words`() {
        // «магнит» внутри слова и в начале более длинного слова не считается.
        assertNull(MerchantDictionary.categoryKeyFor("ЭЛЕКТРОМАГНИТ"))
        assertNull(MerchantDictionary.categoryKeyFor("Магнитогорск"))
        assertEquals("products", MerchantDictionary.categoryKeyFor("Магнит у дома"))
    }

    @Test
    fun `similar words and banks are not taken for stores`() {
        assertNull(MerchantDictionary.categoryKeyFor("MAGNITOGORSK RUS"))
        assertNull(MerchantDictionary.categoryKeyFor("Спартак"))
        assertNull(MerchantDictionary.categoryKeyFor("Яндекс Город"))
        assertNull(MerchantDictionary.categoryKeyFor("MTS BANK"))
        assertEquals("connection", MerchantDictionary.categoryKeyFor("MTS"))
    }

    @Test
    fun `unknown store gives no hint`() {
        assertNull(MerchantDictionary.categoryKeyFor("ИП Смирнова А. В."))
        assertNull(MerchantDictionary.categoryKeyFor("Ozon"))
        assertNull(MerchantDictionary.categoryKeyFor(null))
    }

    @Test
    fun `dictionary has enough entries`() {
        assertTrue(MerchantDictionary.size >= 60)
    }
}
