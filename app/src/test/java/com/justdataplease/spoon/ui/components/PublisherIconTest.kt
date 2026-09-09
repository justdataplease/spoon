package com.justdataplease.spoon.ui.components

import com.justdataplease.spoon.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PublisherIconTest {
    @Test
    fun canonicalKeysTakePrecedenceOverOtherMetadata() {
        assertEquals(
            R.drawable.source_akis,
            publisherIconResource(" AKIS ", "Αργυρώ", "https://gastronomos.gr/recipe"),
        )
        assertEquals(R.drawable.source_argiro, publisherIconResource("argiro", "", ""))
        assertEquals(R.drawable.source_gastronomos, publisherIconResource("gastronomos", "", ""))
    }

    @Test
    fun legacyPublisherDomainsRemainSupported() {
        assertEquals(R.drawable.source_akis, publisherIconResource("www.akispetretzikis.com", "", ""))
        assertEquals(R.drawable.source_argiro, publisherIconResource("", "argiro.gr", ""))
        assertEquals(R.drawable.source_gastronomos, publisherIconResource("gastronomos.gr", "", ""))
    }

    @Test
    fun publisherHostTakesPrecedenceOverRecipePathAndDisplayName() {
        assertEquals(
            R.drawable.source_akis,
            publisherIconResource("", "", "https://www.akispetretzikis.com/recipe/argiro-pie"),
        )
        assertEquals(
            R.drawable.source_argiro,
            publisherIconResource("", "Άκης", "https://www.argiro.gr/recipe/pie"),
        )
        assertEquals(
            R.drawable.source_gastronomos,
            publisherIconResource("", "", "www.gastronomos.gr/recipe/akis"),
        )
    }

    @Test
    fun greekPublisherNamesMatchWithOrWithoutAccents() {
        listOf("Άκης Πετρετζίκης", "ΑΚΗΣ ΠΕΤΡΕΤΖΙΚΗΣ", "Πετρετζίκης").forEach { name ->
            assertEquals(name, R.drawable.source_akis, publisherIconResource("", name, ""))
        }
        listOf("Αργυρώ Μπαρμπαρίγου", "ΑΡΓΥΡΩ ΜΠΑΡΜΠΑΡΙΓΟΥ").forEach { name ->
            assertEquals(name, R.drawable.source_argiro, publisherIconResource("", name, ""))
        }
        listOf("Γαστρονόμος", "ΓΑΣΤΡΟΝΟΜΟΣ").forEach { name ->
            assertEquals(name, R.drawable.source_gastronomos, publisherIconResource("", name, ""))
        }
    }

    @Test
    fun unrelatedNamesAndUrlTextDoNotCreatePublisherBadges() {
        assertNull(publisherIconResource("", "Makis", ""))
        assertNull(publisherIconResource("", "", "https://example.com/argiro?source=akis"))
        assertNull(publisherIconResource("", "", "https://argiro.gr.example.com/recipe"))
        assertNull(publisherIconResource("", "", "not a URL"))
        assertNull(publisherIconResource("personal", "Προσωπική συνταγή", ""))
    }
}
