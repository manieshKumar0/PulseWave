package com.example.data.remote

import com.example.data.model.LyricLine
import com.example.domain.HindiHinglishTransliterator
import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Query
import java.util.concurrent.TimeUnit

@JsonClass(generateAdapter = true)
data class LrclibRecordDto(
    @Json(name = "id") val id: Long = 0L,
    @Json(name = "trackName") val trackName: String = "",
    @Json(name = "artistName") val artistName: String = "",
    @Json(name = "albumName") val albumName: String? = null,
    @Json(name = "duration") val duration: Double? = null,
    @Json(name = "instrumental") val instrumental: Boolean? = null,
    @Json(name = "plainLyrics") val plainLyrics: String? = null,
    @Json(name = "syncedLyrics") val syncedLyrics: String? = null
)

interface LrclibApi {
    @GET("api/get")
    suspend fun getLyrics(
        @Query("track_name") trackName: String,
        @Query("artist_name") artistName: String,
        @Query("album_name") albumName: String? = null,
        @Query("duration") durationSeconds: Int? = null,
        @Header("User-Agent") userAgent: String = "PulseWave/1.0 (https://pulsewave.app)"
    ): LrclibRecordDto

    @GET("api/search")
    suspend fun searchLyrics(
        @Query("q") query: String,
        @Header("User-Agent") userAgent: String = "PulseWave/1.0 (https://pulsewave.app)"
    ): List<LrclibRecordDto>
}

data class LyricsPayload(
    val syncedLrc: String = "",
    val plainLyrics: String = "",
    val instrumental: Boolean = false
)

class LrclibClient {
    private val okHttp = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .build()

    private val moshi = Moshi.Builder()
        .addLast(KotlinJsonAdapterFactory())
        .build()

    private val api: LrclibApi = Retrofit.Builder()
        .baseUrl("https://lrclib.net/")
        .client(okHttp)
        .addConverterFactory(MoshiConverterFactory.create(moshi))
        .build()
        .create(LrclibApi::class.java)

    suspend fun fetchLyrics(
        title: String,
        artist: String,
        durationMs: Long,
        albumName: String? = null
    ): LyricsPayload {
        val durationSec = (durationMs / 1000L).toInt().takeIf { it > 0 }
        val rawTitle = title.trim()
        val rawArtist = artist.trim()
        val validAlbum = albumName?.trim()?.takeIf {
            it.isNotBlank() &&
                !it.equals("On Device", true) &&
                !it.equals("Cloud Synced", true) &&
                !it.equals("Single", true) &&
                !it.equals("<unknown>", true)
        }

        val cleanedFromTitle = cleanQueryText(rawTitle)
        val isPlaceholderArtist = isPlaceholderArtistName(rawArtist)

        var resolvedTitle = cleanedFromTitle
        var resolvedArtist = if (isPlaceholderArtist) "" else cleanArtistText(rawArtist)

        // Handle "Artist - Title" packed into filename or title tag
        if (resolvedArtist.isBlank()) {
            val split = splitArtistAndTitle(cleanedFromTitle)
            if (split != null) {
                resolvedArtist = split.first
                resolvedTitle = split.second
            }
        }

        val candidateQueries = buildList {
            if (rawArtist.isNotBlank() && !isPlaceholderArtist) {
                add(rawTitle to rawArtist)
                if (resolvedTitle != rawTitle || resolvedArtist != rawArtist) {
                    add(resolvedTitle to resolvedArtist)
                }
            }
            if (resolvedArtist.isNotBlank()) {
                add(resolvedTitle to resolvedArtist)
            }
            val titleWithoutFeat = stripFeaturing(resolvedTitle)
            if (titleWithoutFeat != resolvedTitle && titleWithoutFeat.isNotBlank()) {
                add(titleWithoutFeat to resolvedArtist)
            }
        }.distinct()

        // 1. Direct LRCLIB /api/get attempts (with album if available, then without)
        var directDevanagariFallback: LyricsPayload? = null
        for ((t, a) in candidateQueries) {
            if (t.isBlank() || a.isBlank()) continue
            try {
                val direct = api.getLyrics(
                    trackName = t,
                    artistName = a,
                    albumName = validAlbum,
                    durationSeconds = durationSec
                )
                if (!direct.syncedLyrics.isNullOrBlank() || !direct.plainLyrics.isNullOrBlank()) {
                    val payload = direct.toPayload()
                    val combined = payload.syncedLrc + payload.plainLyrics
                    if (!HindiHinglishTransliterator.containsDevanagari(combined)) {
                        return payload
                    }
                    directDevanagariFallback = payload
                    break
                }
            } catch (_: Exception) {
                if (validAlbum != null) {
                    try {
                        val fallbackDirect = api.getLyrics(
                            trackName = t,
                            artistName = a,
                            albumName = null,
                            durationSeconds = durationSec
                        )
                        if (!fallbackDirect.syncedLyrics.isNullOrBlank() || !fallbackDirect.plainLyrics.isNullOrBlank()) {
                            val payload = fallbackDirect.toPayload()
                            val combined = payload.syncedLrc + payload.plainLyrics
                            if (!HindiHinglishTransliterator.containsDevanagari(combined)) {
                                return payload
                            }
                            directDevanagariFallback = payload
                            break
                        }
                    } catch (_: Exception) {
                    }
                }
            }
        }

        // 2. Broader LRCLIB /api/search attempts (prefers Romanized/Hinglish over Devanagari when available)
        val searchStrings = buildList {
            if (resolvedTitle.isNotBlank() && resolvedArtist.isNotBlank()) {
                add("$resolvedTitle $resolvedArtist")
            }
            if (resolvedTitle.isNotBlank()) {
                add(resolvedTitle)
            }
            val titleNoFeat = stripFeaturing(resolvedTitle)
            if (titleNoFeat.isNotBlank() && titleNoFeat != resolvedTitle) {
                add(titleNoFeat)
            }
        }.distinct()

        for (query in searchStrings) {
            try {
                val results = api.searchLyrics(query)
                val best = pickBestRecord(results, durationSec)
                if (best != null) {
                    val bestCombined = best.syncedLyrics.orEmpty() + best.plainLyrics.orEmpty()
                    if (directDevanagariFallback != null &&
                        HindiHinglishTransliterator.containsDevanagari(bestCombined) &&
                        directDevanagariFallback.syncedLrc.isNotBlank()
                    ) {
                        return normalizeHindiLyrics(directDevanagariFallback)
                    }
                    return normalizeHindiLyrics(best.toPayload())
                }
            } catch (_: Exception) {
                // Try next fallback query
            }
        }

        return directDevanagariFallback?.let { normalizeHindiLyrics(it) } ?: LyricsPayload()
    }

    private fun LrclibRecordDto.toPayload(): LyricsPayload = LyricsPayload(
        syncedLrc = syncedLyrics.orEmpty(),
        plainLyrics = plainLyrics.orEmpty(),
        instrumental = instrumental == true
    )

    private fun normalizeHindiLyrics(payload: LyricsPayload): LyricsPayload {
        return payload.copy(
            syncedLrc = HindiHinglishTransliterator.convertLyricsTextToHinglish(payload.syncedLrc),
            plainLyrics = HindiHinglishTransliterator.convertLyricsTextToHinglish(payload.plainLyrics)
        )
    }

    private fun pickBestRecord(
        results: List<LrclibRecordDto>,
        targetDurationSec: Int?
    ): LrclibRecordDto? {
        val withLyrics = results.filter {
            !it.syncedLyrics.isNullOrBlank() || !it.plainLyrics.isNullOrBlank()
        }
        if (withLyrics.isEmpty()) return null

        return withLyrics.minByOrNull { record ->
            val syncedPenalty = if (!record.syncedLyrics.isNullOrBlank()) 0.0 else 50.0
            val textSample = record.syncedLyrics ?: record.plainLyrics.orEmpty()
            val devanagariPenalty = if (HindiHinglishTransliterator.containsDevanagari(textSample)) 18.0 else 0.0
            val durDiff = if (targetDurationSec != null && record.duration != null) {
                kotlin.math.abs(record.duration - targetDurationSec)
            } else {
                10.0
            }
            syncedPenalty + devanagariPenalty + durDiff
        }
    }

    private fun isPlaceholderArtistName(artist: String): Boolean {
        val norm = artist.trim().lowercase()
        return norm.isBlank() ||
            norm == "local device" ||
            norm == "cloud drive" ||
            norm == "on device" ||
            norm == "unknown" ||
            norm == "unknown artist" ||
            norm == "<unknown>" ||
            norm == "various artists"
    }

    private fun cleanQueryText(input: String): String {
        return input
            .replace('_', ' ')
            .replace(Regex("^\\d{1,3}[\\s.\\-_]+"), "")
            .replace(Regex("\\[[^\\]]*]"), " ")
            .replace(
                Regex(
                    "\\((?:official\\s*(?:audio|video|music\\s*video|lyric\\s*video)|lyrics?|audio|hd|hq|remastered[^)]*|explicit|clean)\\)",
                    RegexOption.IGNORE_CASE
                ),
                " "
            )
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun cleanArtistText(input: String): String {
        return input
            .replace('_', ' ')
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun stripFeaturing(input: String): String {
        return input
            .replace(Regex("\\s*(?:\\(|\\[)?(?:feat\\.?|ft\\.?)\\s+[^)\\]]*(?:\\)|\\])?", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun splitArtistAndTitle(text: String): Pair<String, String>? {
        val separators = listOf(" - ", " – ", " — ")
        for (sep in separators) {
            val idx = text.indexOf(sep)
            if (idx > 1 && idx + sep.length < text.length - 1) {
                val left = text.substring(0, idx).trim()
                val right = text.substring(idx + sep.length).trim()
                if (left.isNotBlank() && right.isNotBlank()) {
                    return left to right
                }
            }
        }
        return null
    }

    companion object {
        private val LRC_REGEX = Regex("\\[(\\d{2}):(\\d{2})(?:\\.(\\d{2,3}))?](.*)")

        fun parseLrc(lrcText: String): List<LyricLine> {
            if (lrcText.isBlank()) return emptyList()
            return lrcText.lineSequence()
                .mapNotNull { line ->
                    val match = LRC_REGEX.find(line.trim()) ?: return@mapNotNull null
                    val min = match.groupValues[1].toLongOrNull() ?: 0L
                    val sec = match.groupValues[2].toLongOrNull() ?: 0L
                    val fracStr = match.groupValues[3]
                    val millis = when (fracStr.length) {
                        2 -> (fracStr.toLongOrNull() ?: 0L) * 10L
                        3 -> fracStr.toLongOrNull() ?: 0L
                        else -> 0L
                    }
                    val text = match.groupValues[4].trim()
                    if (text.isBlank()) return@mapNotNull null
                    LyricLine(
                        timestampMs = (min * 60_000L) + (sec * 1_000L) + millis,
                        text = text
                    )
                }
                .sortedBy { it.timestampMs }
                .toList()
        }

        fun toDisplayLyrics(
            syncedLrc: String,
            plainLyrics: String,
            durationMs: Long
        ): List<LyricLine> {
            val normalizedSynced = HindiHinglishTransliterator.convertLyricsTextToHinglish(syncedLrc)
            val parsedSynced = parseLrc(normalizedSynced)
            if (parsedSynced.isNotEmpty()) return parsedSynced

            val normalizedPlain = HindiHinglishTransliterator.convertLyricsTextToHinglish(plainLyrics)
            val rawLines = normalizedPlain.lines().map { it.trim() }.filter { it.isNotBlank() }
            if (rawLines.isEmpty()) return emptyList()

            val effectiveDuration = durationMs.coerceAtLeast(60_000L)
            val startOffsetMs = 3_500L
            val usableSpanMs = ((effectiveDuration * 0.88).toLong() - startOffsetMs).coerceAtLeast(rawLines.size * 2_000L)
            val stepMs = (usableSpanMs / rawLines.size.coerceAtLeast(1)).coerceAtLeast(1_800L)

            return rawLines.mapIndexed { index, text ->
                LyricLine(
                    timestampMs = startOffsetMs + (index * stepMs),
                    text = text
                )
            }
        }
    }
}
