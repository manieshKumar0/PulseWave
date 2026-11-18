package com.example

import com.example.data.remote.LrclibClient
import com.example.domain.HindiHinglishTransliterator
import com.example.ui.components.defaultPulseRoomName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExampleUnitTest {

    @Test
    fun hindiDevanagariLyrics_convertToNaturalHinglishPreservingLrcTimestamps() {
        val lrc = """
            [00:12.40] हम तेरे बिन अब रह नहीं सकते
            [00:17.20] तेरे बिना क्या वजूद मेरा
            [00:22.10] क्योंकि तुम ही हो, अब तुम ही हो
            [00:27.00] ज़िंदगी अब तुम ही हो
        """.trimIndent()

        val displayLines = LrclibClient.toDisplayLyrics(
            syncedLrc = lrc,
            plainLyrics = "",
            durationMs = 240_000L
        )

        assertEquals(4, displayLines.size)
        assertEquals(12_400L, displayLines[0].timestampMs)
        assertEquals("Hum tere bin ab reh nahi sakte", displayLines[0].text)
        assertEquals("Tere bina kya wajood mera", displayLines[1].text)
        assertEquals("Kyunki tum hi ho, ab tum hi ho", displayLines[2].text)
        assertEquals("Zindagi ab tum hi ho", displayLines[3].text)
        displayLines.forEach { line ->
            assertFalse(HindiHinglishTransliterator.containsDevanagari(line.text))
        }
    }

    @Test
    fun otherLanguageLyrics_remainInTheirOriginalLanguage() {
        val multiLangLrc = """
            [00:05.00] Like a whisper in the midnight rain
            [00:10.00] 빛나는 밤하늘 아래
            [00:15.00] 夢の中で君を探してる
            [00:20.00] Bailando bajo la luna llena
            [00:25.00] يا حبيبي قلبي معك
            [00:30.00] காதல் நீ தானே என் உயிரே
            [00:35.00] ਦਿਲ ਦੀਆਂ ਗੱਲਾਂ ਦੱਸਾਂਗੇ
        """.trimIndent()

        val displayLines = LrclibClient.toDisplayLyrics(
            syncedLrc = multiLangLrc,
            plainLyrics = "",
            durationMs = 210_000L
        )

        assertEquals(7, displayLines.size)
        assertEquals("Like a whisper in the midnight rain", displayLines[0].text)
        assertEquals("빛나는 밤하늘 아래", displayLines[1].text)
        assertEquals("夢の中で君を探してる", displayLines[2].text)
        assertEquals("Bailando bajo la luna llena", displayLines[3].text)
        assertEquals("يا حبيبي قلبي معك", displayLines[4].text)
        assertEquals("காதல் நீ தானே என் உயிரே", displayLines[5].text)
        assertEquals("ਦਿਲ ਦੀਆਂ ਗੱਲਾਂ ਦੱਸਾਂਗੇ", displayLines[6].text)
    }

    @Test
    fun generalHindiPhoneticWords_transliterateCleanly() {
        val sample = "दिलबर कमरा धड़कन मुस्कुराना"
        val converted = HindiHinglishTransliterator.convertLineToHinglish(sample)
        assertTrue(converted.contains("dilbar", ignoreCase = true))
        assertTrue(converted.contains("kamra", ignoreCase = true))
        assertTrue(converted.contains("dhadkan", ignoreCase = true))
        assertTrue(converted.contains("muskuraana", ignoreCase = true))
    }

    @Test
    fun defaultPulseRoomName_derivesExpectedRoomTitleFromUserName() {
        assertEquals("Manish Kumar's Room", defaultPulseRoomName("Manish Kumar Paswa"))
        assertEquals("Manish Kumar's Room", defaultPulseRoomName("Manish Kumar"))
        assertEquals("Manish's Room", defaultPulseRoomName("Manish"))
        assertEquals("My Listening Room", defaultPulseRoomName("Pulse Listener"))
        assertEquals("My Listening Room", defaultPulseRoomName("   "))
    }
}
