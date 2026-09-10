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
    fun newPublishersUseOfficialIconsForKeysDomainsAndNames() {
        listOf(
            Triple("tsoulis", "https://www.giorgostsoulis.com/syntages/glyka/keik", R.drawable.source_tsoulis),
            Triple("lucacos", "https://www.yiannislucacos.gr/recipe/123/pie", R.drawable.source_lucacos),
            Triple("funkycook", "https://funkycook.gr/keik/", R.drawable.source_funkycook),
            Triple("cookpad", "https://cookpad.com/gr/sintages/123", R.drawable.source_cookpad),
        ).forEach { (key, url, icon) ->
            assertEquals(key, icon, publisherIconResource(key, "", ""))
            assertEquals(url, icon, publisherIconResource("", "", url))
        }
        assertEquals(R.drawable.source_tsoulis, publisherIconResource("", "ΓΙΩΡΓΟΣ ΤΣΟΥΛΗΣ", ""))
        assertEquals(R.drawable.source_lucacos, publisherIconResource("", "Γιάννης Λουκάκος", ""))
        assertEquals(R.drawable.source_funkycook, publisherIconResource("", "Funky Cook", ""))
    }

    @Test
    fun unrelatedNamesAndUrlTextDoNotCreatePublisherBadges() {
        assertNull(publisherIconResource("", "Makis", ""))
        assertNull(publisherIconResource("", "", "https://example.com/argiro?source=akis"))
        assertNull(publisherIconResource("", "", "https://argiro.gr.example.com/recipe"))
        assertNull(publisherIconResource("", "", "not a URL"))
        assertNull(publisherIconResource("personal", "Προσωπική συνταγή", ""))
    }

    @Test
    fun everyPublisherBadgeOpensItsWebsiteFromKeysOrRecipeUrls() {
        listOf(
            Triple("akis", "https://akispetretzikis.com/", "https://akispetretzikis.com/recipe/argiro-pie"),
            Triple("argiro", "https://www.argiro.gr/", "https://www.argiro.gr/recipe/akis-pie"),
            Triple("gastronomos", "https://www.gastronomos.gr/", "https://www.gastronomos.gr/syntages/123"),
            Triple("tsoulis", "https://www.giorgostsoulis.com/", "https://www.giorgostsoulis.com/syntages/glyka/pie"),
            Triple("cookpad", "https://cookpad.com/gr", "https://cookpad.com/gr/sintages/123"),
            Triple("lucacos", "https://www.yiannislucacos.gr/", "https://www.yiannislucacos.gr/recipe/123/pie"),
            Triple("funkycook", "https://funkycook.gr/", "https://funkycook.gr/pie/"),
        ).forEach { (key, website, recipeUrl) ->
            assertEquals(key, website, recipePublisher(key, "", "")?.websiteUrl)
            assertEquals(recipeUrl, website, recipePublisher("", "", recipeUrl)?.websiteUrl)
        }
    }

    @Test
    fun websiteDestinationKeepsCatalogIdentityDespiteConflictingMetadata() {
        assertEquals(
            "https://akispetretzikis.com/",
            recipePublisher(" AKIS ", "Αργυρώ", "https://www.gastronomos.gr/recipe")?.websiteUrl,
        )
        assertEquals(
            "https://www.argiro.gr/",
            recipePublisher("", "Άκης", "https://www.argiro.gr/recipe/akis")?.websiteUrl,
        )
        assertEquals(
            "https://www.yiannislucacos.gr/",
            recipePublisher("", "ΓΙΑΝΝΗΣ ΛΟΥΚΑΚΟΣ", "")?.websiteUrl,
        )
    }

    @Test
    fun unrelatedOrPersonalMetadataCannotBecomeAWebsiteTarget() {
        assertNull(recipePublisher("personal", "Άκης Πετρετζίκης", "https://akispetretzikis.com/recipe/123"))
        assertNull(recipePublisher("custom", "Αργυρώ", "https://www.argiro.gr/recipe/123"))
        assertNull(recipePublisher("unknown", "Προσωπική συνταγή", ""))
        assertNull(recipePublisher("", "", "https://argiro.gr.example.com/recipe"))
        assertNull(recipePublisher("", "", "https://example.com/argiro.gr"))
        assertNull(recipePublisher("", "", "javascript:alert('argiro')"))
    }

}
