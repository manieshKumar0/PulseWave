package com.example.data.remote

import com.example.data.model.Track
import com.example.data.model.TrackSourceType
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

@JsonClass(generateAdapter = true)
data class AudiusTrendingResponse(
    @Json(name = "data") val data: List<AudiusTrackDto> = emptyList()
)

@JsonClass(generateAdapter = true)
data class AudiusTrackDto(
    @Json(name = "id") val id: String = "",
    @Json(name = "title") val title: String = "",
    @Json(name = "genre") val genre: String? = null,
    @Json(name = "mood") val mood: String? = null,
    @Json(name = "bpm") val bpm: Double? = null,
    @Json(name = "duration") val duration: Int? = null,
    @Json(name = "artwork") val artwork: AudiusArtworkDto? = null,
    @Json(name = "user") val user: AudiusUserDto? = null
)

@JsonClass(generateAdapter = true)
data class AudiusArtworkDto(
    @Json(name = "150x150") val small: String? = null,
    @Json(name = "480x480") val medium: String? = null,
    @Json(name = "1000x1000") val large: String? = null
)

@JsonClass(generateAdapter = true)
data class AudiusUserDto(
    @Json(name = "id") val id: String = "",
    @Json(name = "name") val name: String = "Independent Artist",
    @Json(name = "handle") val handle: String = ""
)

interface AudiusApi {
    @GET("v1/tracks/trending")
    suspend fun getTrendingTracks(
        @Query("app_name") appName: String = "PulseWave",
        @Query("limit") limit: Int = 30,
        @Query("genre") genre: String? = null
    ): AudiusTrendingResponse

    @GET("v1/tracks/search")
    suspend fun searchTracks(
        @Query("query") query: String,
        @Query("app_name") appName: String = "PulseWave",
        @Query("limit") limit: Int = 25
    ): AudiusTrendingResponse
}

class AudiusCatalogClient {
    private val okHttp = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private val moshi = Moshi.Builder()
        .addLast(KotlinJsonAdapterFactory())
        .build()

    private val api: AudiusApi = Retrofit.Builder()
        .baseUrl("https://api.audius.co/")
        .client(okHttp)
        .addConverterFactory(MoshiConverterFactory.create(moshi))
        .build()
        .create(AudiusApi::class.java)

    suspend fun fetchTrending(genre: String? = null, limit: Int = 30): List<Track> {
        val response = api.getTrendingTracks(limit = limit, genre = genre)
        return response.data.mapNotNull { it.toDomainTrack() }
    }

    suspend fun search(query: String, limit: Int = 25): List<Track> {
        val response = api.searchTracks(query = query, limit = limit)
        return response.data.mapNotNull { it.toDomainTrack() }
    }

    suspend fun lookupArtworkUrl(title: String, artist: String): String? =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val cleanTitle = title.replace(Regex("\\(.*?\\)|\\[.*?]"), " ").trim()
            if (cleanTitle.isBlank()) return@withContext null
            val cleanArtist = artist
                .takeUnless { it.equals("Local Device", true) || it.equals("Cloud Drive", true) || it.equals("Unknown", true) }
                ?.trim()
                .orEmpty()
            val query = listOf(cleanTitle, cleanArtist).filter { it.isNotBlank() }.joinToString(" ")
            val encoded = java.net.URLEncoder.encode(query, "UTF-8")
            val req = okhttp3.Request.Builder()
                .url("https://itunes.apple.com/search?term=$encoded&media=music&entity=song&limit=1")
                .build()
            try {
                okHttp.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@withContext null
                    val body = resp.body?.string().orEmpty()
                    val results = org.json.JSONObject(body).optJSONArray("results") ?: return@withContext null
                    if (results.length() == 0) return@withContext null
                    val art = results.getJSONObject(0).optString("artworkUrl100")
                    if (art.isBlank()) null else art.replace("100x100bb", "600x600bb")
                }
            } catch (_: Exception) {
                null
            }
        }

    private fun AudiusTrackDto.toDomainTrack(): Track? {
        if (id.isBlank() || title.isBlank()) return null
        val art = artwork?.large ?: artwork?.medium ?: artwork?.small ?: ""
        val durMs = ((duration ?: 180).coerceAtLeast(10)) * 1000L
        return Track(
            id = "audius_$id",
            title = title.trim(),
            artist = user?.name?.trim()?.ifBlank { "Independent Artist" } ?: "Independent Artist",
            album = genre?.takeIf { it.isNotBlank() } ?: "Audius Discovery",
            genre = genre?.takeIf { it.isNotBlank() } ?: "Electronic",
            mood = mood?.takeIf { it.isNotBlank() } ?: inferMoodFromGenre(genre),
            bpm = (bpm?.toInt() ?: inferBpmFromGenre(genre)).coerceIn(60, 200),
            durationMs = durMs,
            artworkUrl = art,
            streamUrl = "https://api.audius.co/v1/tracks/$id/stream?app_name=PulseWave",
            sourceType = TrackSourceType.STREAM
        )
    }

    private fun inferMoodFromGenre(genre: String?): String = when (genre?.lowercase()) {
        "electronic", "techno", "house", "dubstep" -> "Energetic"
        "ambient", "lo-fi", "chill", "classical" -> "Chill"
        "hip-hop/rap", "trap" -> "Bold"
        "pop", "dance" -> "Euphoric"
        else -> "Atmospheric"
    }

    private fun inferBpmFromGenre(genre: String?): Int = when (genre?.lowercase()) {
        "electronic", "house", "dance" -> 124
        "techno", "drum & bass" -> 136
        "ambient", "lo-fi" -> 84
        "hip-hop/rap", "r&b/soul" -> 96
        else -> 114
    }
}
