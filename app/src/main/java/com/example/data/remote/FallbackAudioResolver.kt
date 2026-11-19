package com.example.data.remote

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.ByteString.Companion.decodeBase64
import org.json.JSONObject
import java.net.URLEncoder
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import kotlin.math.abs

private const val TAG = "FallbackAudioResolver"

internal object JioSaavnDecryptor {
    private const val DES_TRANSFORMATION = "DES/ECB/PKCS5Padding"
    private const val SECRET_KEY_STR = "38346591"

    fun decryptUrl(encryptedMediaUrl: String?): String? {
        val cleaned = encryptedMediaUrl?.trim().orEmpty()
        if (cleaned.isEmpty()) return null
        return try {
            val decoded = cleaned.decodeBase64()?.toByteArray() ?: return null
            val keySpec = SecretKeySpec(SECRET_KEY_STR.toByteArray(Charsets.UTF_8), "DES")
            val cipher = Cipher.getInstance(DES_TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, keySpec)
            val decrypted = cipher.doFinal(decoded)
            String(decrypted, Charsets.UTF_8)
                .trim()
                .replace("http://", "https://")
                .takeIf { it.startsWith("https://", ignoreCase = true) }
        } catch (e: Exception) {
            Log.w(TAG, "DES decrypt failed: ${e.message}")
            null
        }
    }

    fun candidateBitrateUrls(
        decryptedUrl: String,
        has320Kbps: Boolean,
        preferHighQuality: Boolean
    ): List<String> {
        val urls = LinkedHashSet<String>()
        if (preferHighQuality) {
            if (has320Kbps) {
                urls.add(decryptedUrl.replace(QUALITY_SUFFIX_REGEX, "_320.mp4"))
            }
            urls.add(decryptedUrl.replace(QUALITY_SUFFIX_REGEX, "_160.mp4"))
            urls.add(decryptedUrl)
        } else {
            urls.add(decryptedUrl.replace(QUALITY_SUFFIX_REGEX, "_160.mp4"))
            urls.add(decryptedUrl)
        }
        return urls.toList()
    }

    private val QUALITY_SUFFIX_REGEX = Regex("_(96|160|320)\\.mp4$")
}

/**
 * Automatic alternative audio resolver used when YouTube InnerTube / Proxy / NewPipe
 * rejects playback with LOGIN_REQUIRED / 403 on a carrier or shared network IP.
 * Resolves exact-matched direct CDN audio via JioSaavn (AAC 160/320kbps) or Audius.
 */
internal class FallbackAudioResolver(
    private val httpClient: OkHttpClient,
    private val verifyProbe: (url: String, headers: Map<String, String>) -> Boolean
) {
    suspend fun resolveMatchingStream(
        title: String,
        artist: String,
        expectedDurationMs: Long,
        preferHighQuality: Boolean
    ): ResolvedAudioStream? = withContext(Dispatchers.IO) {
        val cleanedTitle = cleanTrackTitle(title)
        if (cleanedTitle.length < 2) return@withContext null
        val primaryArtist = extractPrimaryArtist(artist)
        val searchQuery = listOf(cleanedTitle, primaryArtist)
            .filter { it.isNotBlank() }
            .joinToString(" ")
            .trim()

        withTimeoutOrNull(4_500L) {
            coroutineScope {
                val saavnJob = async {
                    runCatching {
                        searchJioSaavnMatch(
                            query = searchQuery,
                            fallbackTitleQuery = cleanedTitle,
                            targetTitle = cleanedTitle,
                            targetArtist = artist,
                            expectedDurationMs = expectedDurationMs,
                            preferHighQuality = preferHighQuality
                        )
                    }.getOrNull()
                }
                val audiusJob = async {
                    runCatching {
                        searchAudiusMatch(
                            query = searchQuery,
                            targetTitle = cleanedTitle,
                            targetArtist = artist,
                            expectedDurationMs = expectedDurationMs
                        )
                    }.getOrNull()
                }

                saavnJob.await()?.also {
                    audiusJob.cancel()
                    return@coroutineScope it
                }
                audiusJob.await()
            }
        }
    }

    private fun searchJioSaavnMatch(
        query: String,
        fallbackTitleQuery: String,
        targetTitle: String,
        targetArtist: String,
        expectedDurationMs: Long,
        preferHighQuality: Boolean
    ): ResolvedAudioStream? {
        val firstBatch = fetchJioSaavnSearchResults(query)
        val bestFirst = pickBestJioSaavnMatch(
            items = firstBatch,
            targetTitle = targetTitle,
            targetArtist = targetArtist,
            expectedDurationMs = expectedDurationMs,
            preferHighQuality = preferHighQuality
        )
        if (bestFirst != null) return bestFirst

        if (fallbackTitleQuery != query) {
            val titleBatch = fetchJioSaavnSearchResults(fallbackTitleQuery)
            return pickBestJioSaavnMatch(
                items = titleBatch,
                targetTitle = targetTitle,
                targetArtist = targetArtist,
                expectedDurationMs = expectedDurationMs,
                preferHighQuality = preferHighQuality
            )
        }
        return null
    }

    private fun fetchJioSaavnSearchResults(query: String): List<SaavnSongCandidate> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val url = "https://www.jiosaavn.com/api.php" +
            "?__call=search.getResults&_format=json&_marker=0&api_version=4&ctx=web6dot0&p=1&n=6&q=$encoded"
        val req = Request.Builder()
            .url(url)
            .get()
            .header("User-Agent", DESKTOP_UA)
            .header("Accept", "application/json, text/plain, */*")
            .build()

        val body = httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return emptyList()
            resp.body?.string().orEmpty()
        }
        if (body.isBlank()) return emptyList()

        val root = runCatching { JSONObject(body) }.getOrNull() ?: return emptyList()
        val results = root.optJSONArray("results") ?: return emptyList()
        val parsed = mutableListOf<SaavnSongCandidate>()

        for (i in 0 until results.length()) {
            val obj = results.optJSONObject(i) ?: continue
            val rawTitle = unescapeBasicHtml(
                obj.optString("title").ifBlank { obj.optString("song") }
            ).trim()
            if (rawTitle.isBlank()) continue

            val moreInfo = obj.optJSONObject("more_info")
            val encryptedUrl = moreInfo?.optString("encrypted_media_url")
                ?.takeIf { it.isNotBlank() }
                ?: obj.optString("encrypted_media_url").takeIf { it.isNotBlank() }
                ?: continue

            val has320 = (moreInfo?.optString("320kbps") ?: obj.optString("320kbps"))
                .equals("true", ignoreCase = true)
            val durationSec = (moreInfo?.optString("duration") ?: obj.optString("duration"))
                .toLongOrNull() ?: 0L

            val artistNames = buildList {
                val primaryArr = moreInfo?.optJSONObject("artistMap")?.optJSONArray("primary_artists")
                if (primaryArr != null) {
                    for (j in 0 until primaryArr.length()) {
                        primaryArr.optJSONObject(j)?.optString("name")?.trim()?.takeIf { it.isNotBlank() }?.let { add(it) }
                    }
                }
                if (isEmpty()) {
                    moreInfo?.optString("singers")?.takeIf { it.isNotBlank() }?.let { add(it) }
                    obj.optString("subtitle").takeIf { it.isNotBlank() }?.let { add(it) }
                }
            }.joinToString(", ")

            parsed.add(
                SaavnSongCandidate(
                    title = rawTitle,
                    artist = unescapeBasicHtml(artistNames),
                    durationMs = durationSec * 1000L,
                    encryptedMediaUrl = encryptedUrl,
                    has320Kbps = has320
                )
            )
        }
        return parsed
    }

    private fun pickBestJioSaavnMatch(
        items: List<SaavnSongCandidate>,
        targetTitle: String,
        targetArtist: String,
        expectedDurationMs: Long,
        preferHighQuality: Boolean
    ): ResolvedAudioStream? {
        val ranked = items
            .map { candidate ->
                candidate to computeTrackMatchScore(
                    queryTitle = targetTitle,
                    queryArtist = targetArtist,
                    queryDurationMs = expectedDurationMs,
                    candidateTitle = candidate.title,
                    candidateArtist = candidate.artist,
                    candidateDurationMs = candidate.durationMs
                )
            }
            .filter { it.second >= MIN_MATCH_SCORE }
            .sortedByDescending { it.second }

        val headers = mapOf("User-Agent" to DESKTOP_UA)
        for ((candidate, _) in ranked.take(2)) {
            val decrypted = JioSaavnDecryptor.decryptUrl(candidate.encryptedMediaUrl) ?: continue
            val urls = JioSaavnDecryptor.candidateBitrateUrls(
                decryptedUrl = decrypted,
                has320Kbps = candidate.has320Kbps,
                preferHighQuality = preferHighQuality
            )
            for (streamUrl in urls) {
                if (verifyProbe(streamUrl, headers)) {
                    val bps = when {
                        streamUrl.contains("_320.") -> 320_000
                        streamUrl.contains("_160.") -> 160_000
                        else -> 96_000
                    }
                    return ResolvedAudioStream(
                        url = streamUrl,
                        mimeType = "audio/mp4",
                        bitrateBps = bps,
                        expiresAtMs = System.currentTimeMillis() + 6L * 60L * 60L * 1000L,
                        userAgent = DESKTOP_UA,
                        isHls = false,
                        requestHeaders = headers
                    )
                }
            }
        }
        return null
    }

    private fun searchAudiusMatch(
        query: String,
        targetTitle: String,
        targetArtist: String,
        expectedDurationMs: Long
    ): ResolvedAudioStream? {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val url = "https://api.audius.co/v1/tracks/search?query=$encoded&app_name=PulseWave&limit=6"
        val req = Request.Builder()
            .url(url)
            .get()
            .header("User-Agent", DESKTOP_UA)
            .header("Accept", "application/json")
            .build()

        val body = httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            resp.body?.string().orEmpty()
        }
        val root = runCatching { JSONObject(body) }.getOrNull() ?: return null
        val data = root.optJSONArray("data") ?: return null

        val ranked = buildList {
            for (i in 0 until data.length()) {
                val obj = data.optJSONObject(i) ?: continue
                val id = obj.optString("id").trim()
                val title = obj.optString("title").trim()
                if (id.isBlank() || title.isBlank()) continue
                val artist = obj.optJSONObject("user")?.optString("name").orEmpty().trim()
                val durMs = obj.optInt("duration", 0).toLong() * 1000L
                val score = computeTrackMatchScore(
                    queryTitle = targetTitle,
                    queryArtist = targetArtist,
                    queryDurationMs = expectedDurationMs,
                    candidateTitle = title,
                    candidateArtist = artist,
                    candidateDurationMs = durMs
                )
                if (score >= 0.62) {
                    add(id to score)
                }
            }
        }.sortedByDescending { it.second }

        val headers = mapOf("User-Agent" to DESKTOP_UA)
        for ((trackId, _) in ranked.take(2)) {
            val streamUrl = "https://api.audius.co/v1/tracks/$trackId/stream?app_name=PulseWave"
            if (verifyProbe(streamUrl, headers)) {
                return ResolvedAudioStream(
                    url = streamUrl,
                    mimeType = "audio/mpeg",
                    bitrateBps = 320_000,
                    expiresAtMs = System.currentTimeMillis() + 6L * 60L * 60L * 1000L,
                    userAgent = DESKTOP_UA,
                    isHls = false,
                    requestHeaders = headers
                )
            }
        }
        return null
    }

    private data class SaavnSongCandidate(
        val title: String,
        val artist: String,
        val durationMs: Long,
        val encryptedMediaUrl: String,
        val has320Kbps: Boolean
    )

    companion object {
        private const val MIN_MATCH_SCORE = 0.58
        private const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/135.0.0.0 Safari/537.36"

        private val BRACKET_NOISE_REGEX = Regex("\\s*[(\\[](?:from|official|lyric|lyrical|audio|video|full|hd|4k|feat|ft|with)[^)\\]]*[)\\]]", RegexOption.IGNORE_CASE)
        private val FEAT_SUFFIX_REGEX = Regex("\\s+(?:feat\\.?|ft\\.?)\\s+.*$", RegexOption.IGNORE_CASE)
        private val PIPE_SUFFIX_REGEX = Regex("\\s*[|–—-]\\s*(?:official|full|lyrical|video|audio|song).*$", RegexOption.IGNORE_CASE)
        private val FILLER_TOKENS = setOf("the", "a", "an", "from", "official", "video", "audio", "song", "lyrics", "lyrical", "full", "hd", "4k", "music")

        internal fun cleanTrackTitle(raw: String): String {
            return raw
                .replace(BRACKET_NOISE_REGEX, " ")
                .replace(FEAT_SUFFIX_REGEX, " ")
                .replace(PIPE_SUFFIX_REGEX, " ")
                .replace(Regex("\\s+"), " ")
                .trim()
        }

        internal fun extractPrimaryArtist(raw: String): String {
            val cleaned = raw
                .removeSuffix("- Topic")
                .trim()
            if (cleaned.equals("YouTube Music", true) || cleaned.equals("YouTube", true)) return ""
            return cleaned
                .split(Regex("[,&]|\\bfeat\\b|\\bft\\b|\\bx\\b", RegexOption.IGNORE_CASE))
                .firstOrNull()
                ?.trim()
                .orEmpty()
        }

        internal fun computeTrackMatchScore(
            queryTitle: String,
            queryArtist: String,
            queryDurationMs: Long,
            candidateTitle: String,
            candidateArtist: String,
            candidateDurationMs: Long
        ): Double {
            val qTokens = tokenize(cleanTrackTitle(queryTitle))
            val cTokens = tokenize(cleanTrackTitle(candidateTitle))
            if (qTokens.isEmpty() || cTokens.isEmpty()) return 0.0

            val intersection = qTokens.intersect(cTokens).size.toDouble()
            val union = qTokens.union(cTokens).size.toDouble()
            val jaccard = intersection / union
            val containment = intersection / minOf(qTokens.size, cTokens.size).toDouble()
            val titleScore = maxOf(jaccard, containment * 0.85)
            if (titleScore < 0.50) return 0.0

            val qArtistTokens = tokenize(queryArtist)
            val cArtistTokens = tokenize(candidateArtist)
            val artistScore = if (qArtistTokens.isNotEmpty() && cArtistTokens.isNotEmpty()) {
                val overlap = qArtistTokens.intersect(cArtistTokens).size.toDouble()
                if (overlap > 0.0) (overlap / minOf(qArtistTokens.size, cArtistTokens.size).toDouble()).coerceAtMost(1.0)
                else 0.0
            } else {
                0.35
            }

            val durationPenalty = if (queryDurationMs > 30_000L && candidateDurationMs > 30_000L) {
                val diff = abs(queryDurationMs - candidateDurationMs)
                when {
                    diff <= 15_000L -> 0.08
                    diff <= 45_000L -> 0.0
                    else -> -0.25
                }
            } else {
                0.0
            }

            return (titleScore * 0.72) + (artistScore * 0.28) + durationPenalty
        }

        private fun tokenize(input: String): Set<String> {
            return input
                .lowercase()
                .replace(Regex("[^a-z0-9\\s]"), " ")
                .split(Regex("\\s+"))
                .filter { it.length >= 2 && it !in FILLER_TOKENS }
                .toSet()
        }

        private fun unescapeBasicHtml(text: String): String {
            return text
                .replace("&amp;", "&")
                .replace("&quot;", "\"")
                .replace("&#039;", "'")
                .replace("&apos;", "'")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
        }
    }
}
