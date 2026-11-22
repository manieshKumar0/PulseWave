package com.example.data.remote

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.GET
import retrofit2.http.Query
import java.util.concurrent.TimeUnit
import kotlin.math.abs

@JsonClass(generateAdapter = true)
data class ItunesSearchResponse(
    @Json(name = "resultCount") val resultCount: Int = 0,
    @Json(name = "results") val results: List<ItunesTrackDto> = emptyList()
)

@JsonClass(generateAdapter = true)
data class ItunesTrackDto(
    @Json(name = "trackName") val trackName: String? = null,
    @Json(name = "artistName") val artistName: String? = null,
    @Json(name = "collectionName") val collectionName: String? = null,
    @Json(name = "primaryGenreName") val primaryGenreName: String? = null,
    @Json(name = "trackTimeMillis") val trackTimeMillis: Long? = null,
    @Json(name = "artworkUrl100") val artworkUrl100: String? = null
)

interface ItunesMetadataApi {
    @GET("search")
    suspend fun searchTracks(
        @Query("term") term: String,
        @Query("media") media: String = "music",
        @Query("entity") entity: String = "song",
        @Query("limit") limit: Int = 6
    ): ItunesSearchResponse
}

data class ResolvedTrackMetadata(
    val title: String,
    val artist: String,
    val album: String = "",
    val genre: String = "",
    val artworkUrl: String = "",
    val durationMs: Long = 0L
)

class LocalTrackMetadataResolver {
    private val okHttp = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private val moshi = Moshi.Builder()
        .addLast(KotlinJsonAdapterFactory())
        .build()

    private val api: ItunesMetadataApi = Retrofit.Builder()
        .baseUrl("https://itunes.apple.com/")
        .client(okHttp)
        .addConverterFactory(MoshiConverterFactory.create(moshi))
        .build()
        .create(ItunesMetadataApi::class.java)

    suspend fun resolveMetadata(
        rawTitle: String,
        rawArtist: String,
        durationMs: Long
    ): ResolvedTrackMetadata? {
        val cleanTitle = cleanSearchText(rawTitle)
        if (cleanTitle.isBlank()) return null
        val validArtist = rawArtist.trim().takeUnless { isPlaceholderArtist(it) }.orEmpty()
        val term = if (validArtist.isNotBlank()) "$cleanTitle $validArtist" else cleanTitle

        return try {
            val response = api.searchTracks(term = term)
            val candidates = response.results.filter {
                !it.trackName.isNullOrBlank() && !it.artistName.isNullOrBlank()
            }
            if (candidates.isEmpty()) return null

            val targetDur = durationMs.takeIf { it > 10_000L }
            val best = candidates.minByOrNull { dto ->
                val titleMatchBonus = if (dto.trackName.orEmpty().contains(cleanTitle, ignoreCase = true) ||
                    cleanTitle.contains(dto.trackName.orEmpty(), ignoreCase = true)
                ) 0.0 else 30.0
                val durPenalty = if (targetDur != null && dto.trackTimeMillis != null && dto.trackTimeMillis > 0L) {
                    abs(dto.trackTimeMillis - targetDur) / 1000.0
                } else {
                    8.0
                }
                titleMatchBonus + durPenalty
            } ?: return null

            val hiResArt = best.artworkUrl100
                ?.replace("100x100bb", "600x600bb")
                ?.replace("100x100", "600x600")
                .orEmpty()

            ResolvedTrackMetadata(
                title = best.trackName.orEmpty().trim(),
                artist = best.artistName.orEmpty().trim(),
                album = best.collectionName.orEmpty().trim(),
                genre = best.primaryGenreName.orEmpty().trim(),
                artworkUrl = hiResArt,
                durationMs = best.trackTimeMillis ?: durationMs
            )
        } catch (_: Exception) {
            null
        }
    }

    suspend fun lookupArtworkUrl(title: String, artist: String): String? {
        return resolveMetadata(rawTitle = title, rawArtist = artist, durationMs = 0L)
            ?.artworkUrl
            ?.takeIf { it.isNotBlank() }
    }

    private fun isPlaceholderArtist(artist: String): Boolean {
        val norm = artist.trim().lowercase()
        return norm.isBlank() ||
            norm == "local device" ||
            norm == "cloud drive" ||
            norm == "on device" ||
            norm == "unknown" ||
            norm == "unknown artist" ||
            norm == "<unknown>"
    }

    private fun cleanSearchText(input: String): String {
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
}
