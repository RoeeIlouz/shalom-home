package com.saba.home

import com.saba.llama.LlamaLib

/** What Grandpa asked for: one of his contacts, a tile on his screen, or any other installed app by name. */
sealed interface Target {
    val spokenName: String

    data class OfContact(val contact: Contact) : Target {
        override val spokenName get() = contact.name
    }

    data class OfTile(val tile: Tile) : Target {
        override val spokenName get() = tile.label
    }

    data class OfApp(val app: AppInfo) : Target {
        override val spokenName get() = app.label
    }
}

data class Resolution(val target: Target?, val via: String)

/**
 * Turns a transcript into an action in two steps:
 * 1. Fuzzy keyword matching against contact names, aliases, tile names and app names (instant).
 * 2. If nothing matches clearly, a small on-device LLM picks one option. Its output is grammar-constrained
 *    to the option ids, so it can never invent a contact or an action.
 */
class Resolver(
    private val contacts: List<Contact>,
    private val tiles: List<Tile>,
    private val apps: List<AppInfo>,
) {
    // Call tiles are just shortcuts to contacts, so voice works on the contact list instead.
    private val appTiles = tiles.filter { it.kind != TileKind.CALL }

    fun byKeywords(transcript: String): Target? {
        val words = HebrewText.words(transcript)
        if (words.isEmpty()) return null

        data class Hit(val target: Target, val score: Int)

        fun score(phrases: List<String>, fuzzy: Boolean = true) =
            phrases.maxOfOrNull { HebrewText.matchScore(it, words, fuzzy) } ?: 0

        val hits = mutableListOf<Hit>()
        for (c in contacts) {
            score(listOf(c.name) + c.aliases).takeIf { it > 0 }?.let { hits += Hit(Target.OfContact(c), it + 100) }
        }
        for (tile in appTiles) {
            score(listOf(tile.label) + tile.aliases + KnownAppAliases[tile.target].orEmpty()).takeIf { it > 0 }
                ?.let { hits += Hit(Target.OfTile(tile), it + 100) } // his own buttons beat same-named apps
        }
        val tilePkgs = appTiles.map { it.target }.toSet()
        for (app in apps) {
            if (app.packageName in tilePkgs) continue
            // Exact words only: dozens of app names fuzzily match ordinary speech ("fille" ~ "Files").
            score(listOf(app.label) + KnownAppAliases[app.packageName].orEmpty(), fuzzy = false).takeIf { it > 0 }
                ?.let { hits += Hit(Target.OfApp(app), it) }
        }
        val best = hits.maxByOrNull { it.score } ?: return null
        // Two different targets matching equally well is a real ambiguity; let the model decide.
        if (hits.any { it.score == best.score && it.target != best.target }) return null
        return best.target
    }

    fun byModel(llm: LlamaLib, transcript: String): Target? {
        // Step 1: is this a request at all? Without this gate a 1B model maps "what time is it" to some app.
        val gate = llm.chat(
            "Decide whether an elderly man's sentence asks his phone to call a person or to open an app " +
                "(camera, photos, WhatsApp, YouTube...). Questions, statements and small talk are not requests. " +
                "The sentence may be in Hebrew or French. Answer yes or no.",
            "Sentence: \"$transcript\"",
            "root ::= \"yes\" | \"no\"",
            2,
        )
        if (gate != "yes") return null

        // Well-known apps he has no tile for (camera, YouTube...) are valid answers too.
        val tilePkgs = appTiles.map { it.target }.toSet()
        val knownApps = apps.filter { it.packageName in KnownAppAliases && it.packageName !in tilePkgs }
        val options = contacts.mapIndexed { i, c ->
            val also = if (c.aliases.isEmpty()) "" else " (he also calls them: ${c.aliases.joinToString(", ")})"
            Triple("call$i", "phone call to ${c.name}$also", Target.OfContact(c) as Target)
        } + appTiles.mapIndexed { i, t ->
            val what = if (t.kind == TileKind.ALL_APPS) "show the list of all apps" else "open the app ${t.label}"
            Triple("app$i", what, Target.OfTile(t))
        } + knownApps.mapIndexed { i, app ->
            Triple("more$i", "open the app ${app.label} (${KnownAppAliases[app.packageName]!!.first()})", Target.OfApp(app))
        }
        val menu = options.joinToString("\n") { "${it.first} = ${it.second}" }
        // A 1B model leans towards the first option, so "none" comes first and the examples show when to use it.
        val system = """
            You help an elderly man use his phone. He speaks Hebrew, sometimes French.
            You get what he said, transcribed by speech recognition, which may contain mistakes.
            Reply with the id of the one option he is asking for. Family words matter: "my daughter",
            "the girl", "ma fille" mean the contact he calls his daughter.
            If the thing he wants is not in the list, reply none. Never guess a phone call.

            Options:
            none = he is not asking for any option below
            $menu

            Examples of the idea (names here are not his):
            "תתקשר לבן שלי" -> the call option whose contact he calls "הבן שלי"
            "מה השעה" -> none
            "תפתח את הוועצה" -> none, unless an option is close to that word
        """.trimIndent()
        val grammar = "root ::= " + (listOf("\"none\"") + options.map { "\"${it.first}\"" }).joinToString(" | ")
        val answer = llm.chat(system, "He said: \"$transcript\"\nAnswer with the option id only.", grammar, 6)
        return options.firstOrNull { it.first == answer }?.third
    }
}

object HebrewText {
    private val niqqud = Regex("[\\u0591-\\u05C7]")
    private val nonWord = Regex("[^\\p{L}\\p{Nd}]+")
    private val finals = mapOf('ך' to 'כ', 'ם' to 'מ', 'ן' to 'נ', 'ף' to 'פ', 'ץ' to 'צ')
    private const val PREFIXES = "והבלכמש"

    fun normalize(s: String): String = s.lowercase()
        .replace(niqqud, "")
        .replace(nonWord, " ")
        .map { finals[it] ?: it }
        .joinToString("")
        .trim()

    fun words(s: String): List<String> = normalize(s).split(' ').filter { it.isNotEmpty() }

    /** Hebrew spelling varies in vowel letters (אמא / אימא), so also compare without ו and י. */
    private fun skeleton(w: String) = if (w.length > 2) w.filter { it != 'ו' && it != 'י' } else w

    /** "לאמא", "ולאמא", "שהוואטסאפ" -> also try the word with one or two prefix letters removed. */
    private fun variants(w: String): List<String> {
        val out = mutableListOf(w)
        var cur = w
        repeat(2) {
            if (cur.length > 3 && cur[0] in PREFIXES) {
                cur = cur.substring(1)
                out += cur
            }
        }
        return out
    }

    private fun close(a: String, b: String): Boolean {
        if (a == b) return true
        val sa = skeleton(a)
        val sb = skeleton(b)
        if (sa == sb && sa.length >= 2) return true
        val limit = when {
            minOf(a.length, b.length) >= 7 -> 2
            minOf(a.length, b.length) >= 4 -> 1
            else -> 0
        }
        return limit > 0 && (levenshtein(a, b) <= limit || levenshtein(sa, sb) <= limit)
    }

    /** Length of [phrase] if every one of its words appears (fuzzily) in [words], else 0. */
    fun matchScore(phrase: String, words: List<String>, fuzzy: Boolean = true): Int {
        val pw = words(phrase)
        if (pw.isEmpty()) return 0
        // Whisper sometimes splits a word ("למי חל" for "למיכל"), so also try each pair of neighbours joined.
        val joined = words.zipWithNext { x, y -> x + y }
        val candidates = (words + joined).flatMap(::variants)
        val allFound = pw.all { p -> candidates.any { if (fuzzy) close(p, it) else p == it } }
        return if (allFound) pw.sumOf { it.length } else 0
    }

    private fun levenshtein(a: String, b: String): Int {
        val prev = IntArray(b.length + 1) { it }
        val cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            }
            cur.copyInto(prev)
        }
        return prev[b.length]
    }
}
