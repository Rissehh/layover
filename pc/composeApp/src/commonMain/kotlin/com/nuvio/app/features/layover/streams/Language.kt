package com.nuvio.app.features.layover.streams

import com.nuvio.app.features.streams.StreamItem

// Release names use `.`, `-` and `_` as separators; bound on letters/digits (see StreamFacts).
private fun langToken(body: String) =
    Regex("(?<![A-Za-z0-9])(?:$body)(?![A-Za-z0-9])", RegexOption.IGNORE_CASE)

/**
 * Tags that mean a non-English dub or burned-in foreign subtitles. Only unambiguous ones: short tags
 * like LAT, ESP, PL or GER also show up in English release names, so they are left out.
 */
private val foreignTags = langToken(
    "french|truefrench|vff|vfq|vfi|vf2|vostfr|subfrench|ita|italian|german|spanish|castellano|latino|" +
        "russian|rus|hindi|tamil|telugu|portuguese|polish|dutch|turkish|ukrainian",
)

/** Tags that say English audio is in the file (on its own or among several tracks). */
private val englishTags = langToken("eng|english|multi|dual|dual[ .-]?audio|multi[ .-]?audio")

// Flags some addons put in the description (Torrentio, Comet, AIOStreams).
private val foreignFlags = listOf(
    "🇫🇷", // FR
    "🇮🇹", // IT
    "🇩🇪", // DE
    "🇪🇸", // ES
    "🇲🇽", // MX
    "🇷🇺", // RU
    "🇮🇳", // IN
    "🇵🇹", // PT
    "🇧🇷", // BR
    "🇵🇱", // PL
    "🇳🇱", // NL
    "🇹🇷", // TR
    "🇺🇦", // UA
)
private val englishFlags = listOf(
    "🇬🇧", // GB
    "🇺🇸", // US
)

private fun StreamItem.languageTexts(): List<String> =
    listOfNotNull(behaviorHints.filename, title, description, name).filter { it.isNotBlank() }

/**
 * True when the release is tagged as a foreign dub (or foreign subs) and nothing says English is in it.
 * "MULTI", "Dual Audio" or an English tag next to a foreign one keeps it: those files carry English too.
 * Untagged releases count as English, which is what nearly every result from English-language addons is.
 */
fun StreamItem.isForeignOnly(): Boolean {
    val texts = languageTexts()
    val foreign = texts.any { text -> foreignTags.containsMatchIn(text) || foreignFlags.any { it in text } }
    if (!foreign) return false
    val english = texts.any { text -> englishTags.containsMatchIn(text) || englishFlags.any { it in text } }
    return !english
}

/** Same order, foreign-only releases moved to the end. Nothing is dropped, so a title with only dubs still plays. */
fun List<StreamItem>.foreignOnlyLast(): List<StreamItem> {
    val (foreign, rest) = partition { it.isForeignOnly() }
    return if (foreign.isEmpty()) this else rest + foreign
}
