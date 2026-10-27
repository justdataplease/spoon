package com.justdataplease.spoon.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TitleSearchKeyTest {
    @Test
    fun `Greek Greeklish and Latin spellings share one key`() {
        listOf(
            listOf("Ράμεν", "ramen", "RAMEN"),
            listOf("ΣΟΥΒΛΆΚΙ", "souvlaki", "suvlaki"),
            listOf("χοιρινό", "xoirino", "choirino", "hoirino"),
            listOf("ντομάτες", "ntomates", "domates"),
            listOf("καρμπονάρα", "carbonara"),
            listOf("σούσι", "sushi"),
            listOf("χούμους", "hummus"),
            listOf("γιαούρτι", "giaourti"),
            listOf("αυγολέμονο", "avgolemono", "augolemono"),
            listOf("τζατζίκι", "tzatziki"),
            listOf("μουσακάς", "mousakas", "musakas"),
        ).forEach { spellings ->
            val keys = spellings.map(String::titleSearchKey).toSet()
            assertEquals("$spellings → $keys", 1, keys.size)
        }
    }

    @Test
    fun `case accents and final sigma never matter`() {
        assertEquals("fakes".titleSearchKey(), "ΦΑΚΈΣ".titleSearchKey())
        assertEquals("φακες".titleSearchKey(), "Φακές!".titleSearchKey())
    }

    @Test
    fun `the key of a title is the key of each word`() {
        val title = "Κοτόπουλο με ρύζι στον φούρνο"
        assertEquals(
            title.split(' ').joinToString(" ") { it.titleSearchKey() },
            title.titleSearchKey(),
        )
    }

    @Test
    fun `whole words match only complete title words`() {
        val ramenSoup = "Σούπα ράμεν".titleSearchKey()
        val driedFruit = "Αποξηραμένα φρούτα".titleSearchKey()

        assertTrue(TitleQuery("ραμεν").matches(ramenSoup))
        assertTrue(TitleQuery("ραμεν").matches(driedFruit))
        assertTrue(TitleQuery("ramen", wholeWords = true).matches(ramenSoup))
        assertFalse(TitleQuery("ramen", wholeWords = true).matches(driedFruit))
    }

    @Test
    fun `every query word must appear and word starts rank higher`() {
        val title = "Ramen με κοτόπουλο".titleSearchKey()

        assertTrue(TitleQuery("κοτοπουλο ραμεν").matches(title))
        assertFalse(TitleQuery("ramen μοσχάρι").matches(title))
        assertTrue(TitleQuery("kotop").startsWords(title))
        assertFalse(TitleQuery("topoulo").startsWords(title))
    }

    @Test
    fun `blank queries have no terms`() {
        assertTrue(TitleQuery("  — ").isEmpty())
    }
}
