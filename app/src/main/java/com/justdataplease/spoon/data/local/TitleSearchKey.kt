package com.justdataplease.spoon.data.local

/** Bump whenever [titleSearchKey] changes, so installed catalogs rebuild their title-key table. */
internal const val TITLE_SEARCH_KEY_VERSION = "title-translit-v1"

/**
 * One spelling-insensitive form shared by Greek, Greeklish and Latin titles.
 *
 * After [normalizedCatalogToken] removes case, accents and the final sigma, Greek letters become
 * Latin, sound-alike vowels and digraphs merge (η/ι/υ/ει/οι → i, ω → o, αι → e, ου → u, μπ → b,
 * ντ → d, χ/x/ch → h) and doubled letters collapse. «Ράμεν» and «ramen», «χοιρινό», «xoirino» and
 * «choirino» therefore share a key. Rules never cross a space, so the key of a title is the
 * space-joined key of each of its words, which makes whole-word matching exact.
 */
internal fun String.titleSearchKey(): String {
    val normalized = normalizedCatalogToken()
    if (normalized.isEmpty()) return normalized
    // Greek is first spelled the way a Greeklish typist would write it, so both scripts reach
    // the shared Latin rules below in the same form.
    val latin = StringBuilder(normalized.length + 8)
    var index = 0
    while (index < normalized.length) {
        val character = normalized[index]
        // Υ after a vowel is part of a digraph: ου is written ou, αυ/ευ/ηυ av/ev/iv.
        if (index + 1 < normalized.length && normalized[index + 1] == 'υ') {
            val digraph = when (character) {
                'ο' -> "ou"
                'α' -> "av"
                'ε' -> "ev"
                'η' -> "iv"
                else -> null
            }
            if (digraph != null) {
                latin.append(digraph)
                index += 2
                continue
            }
        }
        latin.append(GreekToLatin[character] ?: character.toString())
        index++
    }
    var key = latin.toString()
    for ((spelling, sound) in LatinSpellings) key = key.replace(spelling, sound)
    return key.collapseRepeatedLetters()
}

/** A title query split into keyed terms; every term must appear in the title. */
internal class TitleQuery(query: String, val wholeWords: Boolean = false) {
    val terms: List<String> = query.titleSearchKey()
        .split(' ')
        .filter(String::isNotBlank)
        .distinct()

    fun isEmpty(): Boolean = terms.isEmpty()

    fun matches(titleKey: String): Boolean =
        if (wholeWords) " $titleKey ".let { padded -> terms.all { padded.contains(" $it ") } }
        else terms.all(titleKey::contains)

    /** Ranks titles where every term begins a word above titles that only contain it. */
    fun startsWords(titleKey: String): Boolean =
        " $titleKey".let { padded -> terms.all { padded.contains(" $it") } }
}

private val GreekToLatin: Map<Char, String> = mapOf(
    'α' to "a", 'β' to "v", 'γ' to "g", 'δ' to "d", 'ε' to "e", 'ζ' to "z", 'η' to "i",
    'θ' to "th", 'ι' to "i", 'κ' to "k", 'λ' to "l", 'μ' to "m", 'ν' to "n", 'ξ' to "ks",
    'ο' to "o", 'π' to "p", 'ρ' to "r", 'σ' to "s", 'τ' to "t", 'υ' to "i", 'φ' to "f",
    'χ' to "h", 'ψ' to "ps", 'ω' to "o",
)

/**
 * Ordered Latin rewrites applied after Greek letters are transliterated. Order matters: ch must
 * become h before c becomes k, and soft ce/ci before the hard c.
 */
private val LatinSpellings: List<Pair<String, String>> = listOf(
    "ch" to "h", "kh" to "h", "ph" to "f", "sh" to "s",
    "ce" to "se", "ci" to "si", "cy" to "si", "c" to "k", "q" to "k",
    "w" to "o", "y" to "i", "j" to "tz", "x" to "h",
    "au" to "av", "eu" to "ev", "ou" to "u", "oo" to "u", "ee" to "i",
    "ei" to "i", "oi" to "i", "ai" to "e",
    "mp" to "b", "nt" to "d", "gk" to "g",
)

private fun String.collapseRepeatedLetters(): String {
    val collapsed = StringBuilder(length)
    for (character in this) {
        if (character.isLetter() && collapsed.isNotEmpty() && collapsed[collapsed.length - 1] == character) continue
        collapsed.append(character)
    }
    return collapsed.toString()
}
