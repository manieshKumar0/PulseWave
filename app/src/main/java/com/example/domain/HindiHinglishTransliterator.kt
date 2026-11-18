package com.example.domain

/**
 * Converts Hindi (Devanagari script) lyrics into natural Hinglish (Romanized Hindi),
 * while leaving all non-Devanagari scripts (English, Korean, Japanese, Spanish, Arabic,
 * Tamil, Telugu, Punjabi Gurmukhi, Bengali, etc.) and LRC timestamps completely untouched.
 */
object HindiHinglishTransliterator {

    private val DEVANAGARI_WORD_REGEX = Regex("[\\u0900-\\u097F]+")
    private val LRC_LINE_PREFIX_REGEX = Regex("^((?:\\[\\d{2}:\\d{2}(?:\\.\\d{2,3})?])+)(.*)$")

    private const val HALANT = '\u094D'
    private const val NUKTA = '\u093C'
    private const val ANUSVARA = '\u0902'
    private const val CHANDRABINDU = '\u0901'
    private const val VISARGA = '\u0903'

    fun containsDevanagari(text: String): Boolean {
        for (ch in text) {
            if (ch in '\u0900'..'\u097F') return true
        }
        return false
    }

    fun convertLyricsTextToHinglish(text: String): String {
        if (text.isBlank() || !containsDevanagari(text)) return text
        return text.lines().joinToString("\n") { rawLine ->
            convertLineToHinglish(rawLine)
        }
    }

    fun convertLineToHinglish(rawLine: String): String {
        if (rawLine.isBlank() || !containsDevanagari(rawLine)) return rawLine

        val lrcMatch = LRC_LINE_PREFIX_REGEX.find(rawLine)
        val prefix = lrcMatch?.groupValues?.get(1).orEmpty()
        val body = lrcMatch?.groupValues?.get(2) ?: rawLine

        val converted = DEVANAGARI_WORD_REGEX.replace(body) { match ->
            transliterateWord(match.value)
        }.replace(Regex("।|॥"), ".")

        val formatted = capitalizeFirstLetter(converted)
        return if (prefix.isNotEmpty()) "$prefix$formatted" else formatted
    }

    private fun capitalizeFirstLetter(input: String): String {
        val chars = input.toCharArray()
        for (i in chars.indices) {
            if (chars[i].isLetter()) {
                chars[i] = chars[i].uppercaseChar()
                break
            }
        }
        return String(chars)
    }

    private fun normalizeNukta(word: String): String {
        if (!word.contains(NUKTA)) return word
        val sb = StringBuilder(word.length)
        var i = 0
        while (i < word.length) {
            val ch = word[i]
            val next = word.getOrNull(i + 1)
            if (next == NUKTA) {
                val composed = when (ch) {
                    'क' -> '\u0958' // क़
                    'ख' -> '\u0959' // ख़
                    'ग' -> '\u095A' // ग़
                    'ज' -> '\u095B' // ज़
                    'ड' -> '\u095C' // ड़
                    'ढ' -> '\u095D' // ढ़
                    'फ' -> '\u095E' // फ़
                    'य' -> '\u095F' // य़
                    'न' -> '\u0929' // ऩ
                    'र' -> '\u0931' // ऱ
                    'ळ' -> '\u0934' // ऴ
                    else -> ch
                }
                sb.append(composed)
                i += 2
            } else {
                sb.append(ch)
                i++
            }
        }
        return sb.toString()
    }

    fun transliterateWord(rawWord: String): String {
        val word = normalizeNukta(rawWord)
        COMMON_HINGLISH_WORDS[word]?.let { return it }

        // Parse into token units (clusters) so schwa deletion can look ahead accurately
        val tokens = tokenize(word)
        if (tokens.isEmpty()) return word

        val out = StringBuilder()
        for (i in tokens.indices) {
            val tok = tokens[i]
            val prev = tokens.getOrNull(i - 1)
            val next = tokens.getOrNull(i + 1)
            val isFirst = i == 0
            val isLast = i == tokens.lastIndex

            when (tok) {
                is UnitToken.IndepVowel -> {
                    out.append(tok.roman)
                    appendNasalSuffix(out, tok.nasal, next)
                }
                is UnitToken.PunctOrDigit -> {
                    out.append(tok.value)
                }
                is UnitToken.Consonant -> {
                    val cons = resolveConsonantSound(tok.base, isFirst = isFirst, hasMatra = tok.matra != null)
                    out.append(cons)

                    when {
                        tok.hasHalant -> {
                            // Suppresses inherent 'a'
                        }
                        tok.matra != null -> {
                            out.append(resolveMatra(tok.matra, isLast = isLast && tok.nasal == null))
                        }
                        else -> {
                            // Bare consonant (inherent schwa): apply Hindi schwa-deletion rules
                            val keepSchwa = shouldKeepInherentSchwa(tokens, i)
                            if (keepSchwa) {
                                // 'ह' before final consonant often sounds like 'e' in reh/keh/seh/beh
                                val schwa = if (tok.base == "ह" && !isFirst && next is UnitToken.Consonant && next.matra == null && !next.hasHalant && i + 1 == tokens.lastIndex) {
                                    "e"
                                } else {
                                    "a"
                                }
                                out.append(schwa)
                            }
                        }
                    }

                    appendNasalSuffix(out, tok.nasal, next)
                }
            }
        }

        return out.toString()
            .replace("aaee", "aayi")
            .replace("aaai", "aayi")
            .replace("eei", "eeyi")
    }

    private sealed interface UnitToken {
        data class IndepVowel(val char: Char, val roman: String, val nasal: Char? = null) : UnitToken
        data class Consonant(
            val base: String,
            val matra: Char? = null,
            val hasHalant: Boolean = false,
            val nasal: Char? = null
        ) : UnitToken
        data class PunctOrDigit(val value: String) : UnitToken
    }

    private fun tokenize(word: String): List<UnitToken> {
        val list = mutableListOf<UnitToken>()
        var i = 0
        while (i < word.length) {
            val ch = word[i]
            when {
                ch in INDEP_VOWELS -> {
                    var nasal: Char? = null
                    if (word.getOrNull(i + 1) in NASAL_SIGNS) {
                        nasal = word[i + 1]
                        i++
                    }
                    list.add(UnitToken.IndepVowel(ch, INDEP_VOWELS[ch]!!, nasal))
                    i++
                }
                ch in CONSONANTS -> {
                    var baseStr = ch.toString()
                    // Recognize common Hindi ligatures: क्ष, त्र, ज्ञ, श्र
                    if (word.getOrNull(i + 1) == HALANT && word.getOrNull(i + 2) != null) {
                        val combined = "$ch$HALANT${word[i + 2]}"
                        if (combined in SPECIAL_LIGATURES) {
                            baseStr = combined
                            i += 2
                        }
                    }
                    var matra: Char? = null
                    var hasHalant = false
                    var nasal: Char? = null

                    val next1 = word.getOrNull(i + 1)
                    if (next1 == HALANT) {
                        hasHalant = true
                        i++
                    } else if (next1 != null && next1 in MATRAS) {
                        matra = next1
                        i++
                    }
                    val afterMatra = word.getOrNull(i + 1)
                    if (afterMatra != null && afterMatra in NASAL_SIGNS) {
                        nasal = afterMatra
                        i++
                    }
                    list.add(UnitToken.Consonant(baseStr, matra, hasHalant, nasal))
                    i++
                }
                ch in DEVANAGARI_DIGITS -> {
                    list.add(UnitToken.PunctOrDigit(DEVANAGARI_DIGITS[ch]!!))
                    i++
                }
                ch == '\u093D' -> { // Avagraha
                    list.add(UnitToken.PunctOrDigit("a"))
                    i++
                }
                else -> i++
            }
        }
        return list
    }

    private fun shouldKeepInherentSchwa(tokens: List<UnitToken>, index: Int): Boolean {
        if (tokens.size == 1) return true
        val tok = tokens[index] as? UnitToken.Consonant ?: return false
        if (tok.hasHalant || tok.matra != null) return false

        // Keep if followed by nasal sign on the same bare syllable (e.g. रंग -> rang, संत -> sant)
        if (tok.nasal != null) return true

        val prev = tokens.getOrNull(index - 1)
        val next = tokens.getOrNull(index + 1)

        // Word-final bare consonant drops schwa unless preceded by halant cluster
        if (index == tokens.lastIndex) {
            return prev is UnitToken.Consonant && prev.hasHalant && tokens.size == 2
        }

        // Followed immediately by a consonant with halant? Keep schwa so cluster stays pronounceable (e.g., भक्त -> bhakt, वक्त -> waqt, मस्त -> mast)
        if (next is UnitToken.Consonant && next.hasHalant) return true

        // First syllable keeps schwa (e.g. कमल -> kamal, दिल -> dil already has matra, समझ -> samajh)
        if (index == 0) return true

        // Preceded by a consonant with halant? Keep schwa to break heavy cluster (e.g. नम्रता -> namrata)
        if (prev is UnitToken.Consonant && prev.hasHalant) return true

        // Medial schwa deletion rule:
        // If previous syllable had a vowel (matra, indep vowel, or kept schwa) AND next syllable is an open consonant with a matra, drop schwa (e.g., कमरा -> kamra, सपना -> sapna, मिलना -> milna)
        val prevHasVowel = when (prev) {
            is UnitToken.IndepVowel -> true
            is UnitToken.Consonant -> !prev.hasHalant
            else -> false
        }
        if (prevHasVowel && next is UnitToken.Consonant && (next.matra != null || next.nasal != null)) {
            return false
        }

        // 4+ syllable words like धड़कन (dh-d-k-n), उलझन (u-l-jh-n), अजनबी:
        // If previous had vowel AND next is followed by a final dropped-schwa consonant, drop medial schwa
        val afterNext = tokens.getOrNull(index + 2)
        if (prevHasVowel && next is UnitToken.Consonant && next.matra == null && index + 1 < tokens.lastIndex) {
            return false
        }
        if (prevHasVowel && next is UnitToken.Consonant && next.matra == null && afterNext == null && index >= 2) {
            return true
        }

        return true
    }

    private fun resolveConsonantSound(base: String, isFirst: Boolean, hasMatra: Boolean): String {
        if (base == "व") {
            return if (isFirst || !hasMatra) "w" else "v"
        }
        return SPECIAL_LIGATURES[base] ?: CONSONANTS[base.first()] ?: base
    }

    private fun resolveMatra(matra: Char, isLast: Boolean): String = when (matra) {
        '\u093E' -> if (isLast) "a" else "aa" // ा
        '\u093F' -> "i"                       // ि
        '\u0940' -> if (isLast) "i" else "ee" // ी
        '\u0941' -> "u"                       // ु
        '\u0942' -> if (isLast) "u" else "oo" // ू
        '\u0943' -> "ri"                      // ृ
        '\u0945', '\u0947' -> "e"             // ॅ, े
        '\u0948' -> "ai"                      // ै
        '\u0949', '\u094B' -> "o"             // ॉ, ो
        '\u094C' -> "au"                      // ौ
        else -> ""
    }

    private fun appendNasalSuffix(sb: StringBuilder, nasal: Char?, next: UnitToken?) {
        if (nasal == null) return
        when (nasal) {
            VISARGA -> sb.append("h")
            CHANDRABINDU, ANUSVARA -> {
                val nextBase = (next as? UnitToken.Consonant)?.base?.firstOrNull()
                if (nextBase != null && nextBase in LABIAL_CONSONANTS) {
                    sb.append("m")
                } else {
                    sb.append("n")
                }
            }
        }
    }

    private val NASAL_SIGNS = setOf(ANUSVARA, CHANDRABINDU, VISARGA)
    private val LABIAL_CONSONANTS = setOf('प', 'फ', 'ब', 'भ', 'म')

    private val INDEP_VOWELS = mapOf(
        'अ' to "a",
        'आ' to "aa",
        'इ' to "i",
        'ई' to "ee",
        'उ' to "u",
        'ऊ' to "oo",
        'ऋ' to "ri",
        'ए' to "e",
        'ऐ' to "ai",
        'ओ' to "o",
        'औ' to "au",
        'ऑ' to "o",
        'ऍ' to "e"
    )

    private val MATRAS = setOf(
        '\u093E', '\u093F', '\u0940', '\u0941', '\u0942', '\u0943',
        '\u0945', '\u0947', '\u0948', '\u0949', '\u094B', '\u094C'
    )

    private val SPECIAL_LIGATURES = mapOf(
        "क\u094Dष" to "ksh",
        "त\u094Dर" to "tr",
        "ज\u094Dञ" to "gy",
        "श\u094Dर" to "shr"
    )

    private val CONSONANTS: Map<Char, String> = mapOf(
        'क' to "k", '\u0958' to "q", 'ख' to "kh", '\u0959' to "kh",
        'ग' to "g", '\u095A' to "gh", 'घ' to "gh", 'ङ' to "ng",
        'च' to "ch", 'छ' to "chh", 'ज' to "j", '\u095B' to "z",
        'झ' to "jh", 'ञ' to "ny",
        'ट' to "t", 'ठ' to "th", 'ड' to "d", '\u095C' to "d",
        'ढ' to "dh", '\u095D' to "dh", 'ण' to "n",
        'त' to "t", 'थ' to "th", 'द' to "d", 'ध' to "dh",
        'न' to "n", '\u0929' to "n",
        'प' to "p", 'फ' to "ph", '\u095E' to "f", 'ब' to "b",
        'भ' to "bh", 'म' to "m",
        'य' to "y", '\u095F' to "y", 'र' to "r", '\u0931' to "r",
        'ल' to "l", 'ळ' to "l", '\u0934' to "l", 'व' to "v",
        'श' to "sh", 'ष' to "sh", 'स' to "s", 'ह' to "h"
    )

    private val DEVANAGARI_DIGITS = mapOf(
        '०' to "0", '१' to "1", '२' to "2", '३' to "3", '४' to "4",
        '५' to "5", '६' to "6", '७' to "7", '८' to "8", '९' to "9"
    )

    private val COMMON_HINGLISH_WORDS: Map<String, String> by lazy {
        RAW_HINGLISH_WORDS.mapKeys { normalizeNukta(it.key) }
    }

    private val RAW_HINGLISH_WORDS = mapOf(
        "मैं" to "main", "में" to "mein", "हैं" to "hain", "है" to "hai",
        "हूँ" to "hoon", "हूं" to "hoon", "हो" to "ho", "था" to "tha",
        "थी" to "thi", "थे" to "the", "थीं" to "theen",
        "नहीं" to "nahi", "नही" to "nahi", "ना" to "na", "न" to "na",
        "क्यों" to "kyun", "क्यूँ" to "kyun", "क्यूं" to "kyun", "क्योंकि" to "kyunki",
        "क्या" to "kya", "कैसे" to "kaise", "कैसा" to "kaisa", "कैसी" to "kaisi",
        "कोई" to "koi", "कुछ" to "kuch", "कभी" to "kabhi", "अभी" to "abhi",
        "सभी" to "sabhi", "यहीं" to "yahin", "वहीं" to "wahin", "कहीं" to "kahin",
        "यहाँ" to "yahan", "यहां" to "yahan", "वहाँ" to "wahan", "वहां" to "wahan",
        "कहाँ" to "kahan", "कहां" to "kahan", "जहाँ" to "jahan", "जहां" to "jahan",
        "तुम" to "tum", "तुम्हें" to "tumhein", "तुम्हारा" to "tumhara",
        "तुम्हारे" to "tumhare", "तुम्हारी" to "tumhari", "तुमको" to "tumko",
        "तुमसे" to "tumse", "तुझको" to "tujhko", "तुझसे" to "tujhse", "तुझे" to "tujhe",
        "तू" to "tu", "तेरा" to "tera", "तेरे" to "tere", "तेरी" to "teri",
        "हम" to "hum", "हमें" to "humein", "हमारा" to "humara", "हमारे" to "humare",
        "हमारी" to "humari", "हमको" to "humko", "हमसे" to "humse",
        "मैं" to "main", "मुझे" to "mujhe", "मुझको" to "mujhko", "मुझसे" to "mujhse",
        "मेरा" to "mera", "मेरे" to "mere", "मेरी" to "meri",
        "वो" to "woh", "वह" to "woh", "ये" to "ye", "यह" to "yeh",
        "उसको" to "usko", "उससे" to "usse", "उसे" to "usey",
        "इसको" to "isko", "इससे" to "isse", "इसे" to "isey",
        "किसको" to "kisko", "जिसको" to "jisko", "जिसके" to "jiske",
        "अपना" to "apna", "अपने" to "apne", "अपनी" to "apni",
        "सपना" to "sapna", "सपने" to "sapne", "सपनों" to "sapno",
        "दिल" to "dil", "प्यार" to "pyaar", "इश्क़" to "ishq", "इश्क" to "ishq",
        "मोहब्बत" to "mohabbat", "ज़िंदगी" to "zindagi", "ज़िन्दगी" to "zindagi",
        "जिंदगी" to "zindagi", "जिन्दगी" to "zindagi",
        "ख़ुदा" to "khuda", "खुदा" to "khuda", "दुआ" to "dua", "रब" to "rabb",
        "वक्त" to "waqt", "वक़्त" to "waqt", "इंतज़ार" to "intezaar", "इंतजार" to "intezaar",
        "आँखें" to "aankhein", "आंखें" to "aankhein", "आँखों" to "aankhon", "आंखों" to "aankhon",
        "साँसें" to "saansein", "सांसें" to "saansein", "साँस" to "saans", "सांस" to "saans",
        "आँसू" to "aansoo", "आंसू" to "aansoo", "चाँद" to "chaand", "चांद" to "chaand",
        "ज़माना" to "zamaana", "जमाना" to "zamaana", "ज़माने" to "zamaane",
        "दुनिया" to "duniya", "ख़्वाब" to "khwaab", "ख्वाब" to "khwaab",
        "आसमान" to "aasmaan", "आसमाँ" to "aasmaan", "ज़मीन" to "zameen", "ज़मीं" to "zameen",
        "रात" to "raat", "रातें" to "raatein", "दिन" to "din",
        "सुबह" to "subah", "शाम" to "shaam", "धड़कन" to "dhadkan",
        "याद" to "yaad", "यादें" to "yaadein", "साथ" to "saath", "बात" to "baat",
        "बातें" to "baatein", "मुलाक़ात" to "mulaqaat", "मुलाकात" to "mulaqaat",
        "दोस्त" to "dost", "सफ़र" to "safar", "सफर" to "safar",
        "हमसफ़र" to "humsafar", "हमसफर" to "humsafar",
        "ख़ामोशी" to "khamoshi", "खामोशी" to "khamoshi", "बारिश" to "baarish",
        "मौसम" to "mausam", "लम्हे" to "lamhein", "लम्हें" to "lamhein", "लम्हा" to "lamha",
        "पल" to "pal", "कल" to "kal", "आज" to "aaj", "हर" to "har",
        "पर" to "par", "मगर" to "magar", "लेकिन" to "lekin", "और" to "aur",
        "फिर" to "phir", "भी" to "bhi", "ही" to "hi", "तो" to "toh",
        "से" to "se", "को" to "ko", "का" to "ka", "की" to "ki", "के" to "ke",
        "पे" to "pe", "लिए" to "liye", "लिये" to "liye", "बिन" to "bin", "बिना" to "bina",
        "अब" to "ab", "जब" to "jab", "तब" to "tab", "कब" to "kab", "सब" to "sab",
        "इक" to "ik", "एक" to "ek", "दो" to "do", "जान" to "jaan", "मन" to "mann",
        "रह" to "reh", "रहा" to "raha", "रही" to "rahi", "रहे" to "rahe",
        "कह" to "keh", "कहा" to "kaha", "कहीं" to "kahin",
        "जा" to "ja", "जाना" to "jaana", "जाएंगे" to "jaayenge", "जाएँगे" to "jaayenge",
        "आ" to "aa", "आना" to "aana", "आएंगे" to "aayenge", "आएँगे" to "aayenge",
        "चाहूं" to "chahoon", "चाहूँ" to "chahoon", "चाहूंगा" to "chahunga", "चाहूँगा" to "chahunga",
        "देखूं" to "dekhoon", "देखूँ" to "dekhoon", "सोचूं" to "sochoon", "सोचूँ" to "sochoon",
        "करूं" to "karoon", "करूँ" to "karoon", "बोलूं" to "boloon", "बोलूँ" to "boloon",
        "गाऊं" to "gaoon", "गाऊँ" to "gaoon", "रोऊं" to "rooon", "रोऊँ" to "rooon",
        "मुस्कान" to "muskaan", "पहचान" to "pehchaan", "तन्हा" to "tanha", "तन्हाई" to "tanhaai",
        "जुदा" to "juda", "जुदाई" to "judaai", "वफ़ा" to "wafa", "बेवफ़ा" to "bewafa",
        "सज़ा" to "saza", "मज़ा" to "maza", "नज़र" to "nazar", "असर" to "asar",
        "ख़बर" to "khabar", "खबर" to "khabar", "शहर" to "shehar", "लहर" to "lehar",
        "हवा" to "hawa", "पानी" to "paani", "सितारे" to "sitaare", "उजाला" to "ujaala",
        "अँधेरा" to "andhera", "अंधेरा" to "andhera", "नाम" to "naam", "काम" to "kaam",
        "गम" to "gham", "ग़म" to "gham", "ख़ुशी" to "khushi", "खुशी" to "khushi",
        "अगर" to "agar", "बगैर" to "bagair", "बग़ैर" to "bagair", "पास" to "paas",
        "दूर" to "door", "छोड़" to "chhod", "तोड़" to "tod", "जोड़" to "jod",
        "मोड़" to "mod", "ख़ुद" to "khud", "खुद" to "khud", "सकते" to "sakte",
        "सकता" to "sakta", "सकती" to "sakti", "होगी" to "hogi", "होगा" to "hoga",
        "होंगे" to "honge", "करना" to "karna", "मरना" to "marna", "जीना" to "jeena",
        "पीना" to "peena", "देना" to "dena", "लेना" to "lena", "रोना" to "rona", "सोना" to "sona"
    )
}
