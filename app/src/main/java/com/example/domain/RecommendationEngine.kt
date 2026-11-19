package com.example.domain

import android.util.Log
import com.example.data.local.ListeningSignalEntity
import com.example.data.model.DiscoveryFeedBundle
import com.example.data.model.FeedPostDoc
import com.example.data.model.RecommendedPick
import com.example.data.model.SonicDnaProfile
import com.example.data.model.Track
import com.example.data.model.TrackSourceType
import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.Schema
import com.google.firebase.ai.type.generationConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.util.Calendar
import kotlin.math.abs

private const val TAG = "RecommendationEngine"

class RecommendationEngine {

    private val rankingSchema = Schema.obj(
        properties = mapOf(
            "djHeadline" to Schema.string(description = "Short vivid 6-10 word AI Mood DJ headline"),
            "djSubheadline" to Schema.string(description = "One sentence explaining the current listening wave"),
            "picks" to Schema.array(
                items = Schema.obj(
                    properties = mapOf(
                        "trackId" to Schema.string(description = "Exact track ID from candidates"),
                        "category" to Schema.enumeration(listOf("For You", "Replay Radar", "Friend Wave", "Mood DJ")),
                        "reasonTag" to Schema.string(description = "Compact 2-5 word signal badge"),
                        "explanation" to Schema.string(description = "One concise sentence explaining why this track fits the user's habits")
                    )
                ),
                description = "Ranked personalized tracks"
            )
        )
    )

    private val aiModel by lazy {
        Firebase.ai.generativeModel(
            modelName = "gemini-3.8-flash",
            generationConfig = generationConfig {
                responseMimeType = "application/json"
                responseSchema = rankingSchema
                temperature = 0.65f
            }
        )
    }

    private val storyModel by lazy {
        Firebase.ai.generativeModel(
            modelName = "gemini-3.5-flash-lite"
        )
    }

    fun currentTimeBucket(): String {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return when (hour) {
            in 5..11 -> "Morning"
            in 12..16 -> "Focus"
            in 17..21 -> "Evening"
            else -> "Late Night"
        }
    }

    fun computeSonicDna(signals: List<ListeningSignalEntity>, tracks: List<Track>): SonicDnaProfile {
        if (signals.isEmpty() && tracks.isEmpty()) return SonicDnaProfile()

        val genreScores = mutableMapOf<String, Double>()
        val moodScores = mutableMapOf<String, Double>()
        var weightedBpmSum = 0.0
        var weightTotal = 0.0
        var completionSum = 0.0
        var energySum = 0.0
        var chillSum = 0.0
        var electronicSum = 0.0
        var acousticSum = 0.0

        signals.forEachIndexed { idx, sig ->
            val recencyWeight = 1.0 / (1.0 + idx * 0.08)
            val engagement = (sig.completionRatio * 1.6) +
                (sig.replayCount * 0.9) +
                (if (sig.liked) 1.4 else 0.0) +
                (if (sig.savedOffline) 1.1 else 0.0) +
                (if (sig.sharedInDm) 1.3 else 0.0) -
                (if (sig.earlySkip) 1.5 else 0.0)
            val w = (engagement.coerceAtLeast(0.15)) * recencyWeight

            if (sig.genre.isNotBlank()) {
                genreScores[sig.genre] = (genreScores[sig.genre] ?: 0.0) + w
            }
            if (sig.mood.isNotBlank()) {
                moodScores[sig.mood] = (moodScores[sig.mood] ?: 0.0) + w
            }
            weightedBpmSum += sig.bpm * w
            weightTotal += w
            completionSum += sig.completionRatio

            val g = sig.genre.lowercase()
            val m = sig.mood.lowercase()
            if (g.contains("electro") || g.contains("house") || g.contains("synth") || g.contains("techno") || g.contains("dance")) {
                electronicSum += w
            }
            if (g.contains("acoustic") || g.contains("folk") || g.contains("indie") || g.contains("classical") || g.contains("jazz")) {
                acousticSum += w
            }
            if (m.contains("chill") || m.contains("dream") || m.contains("atmospheric") || sig.bpm < 105) {
                chillSum += w
            } else {
                energySum += w
            }
        }

        val fallbackGenres = tracks.map { it.genre }.filter { it.isNotBlank() }.distinct().take(3)
        val topGenres = genreScores.entries.sortedByDescending { it.value }.map { it.key }.take(4)
            .ifEmpty { fallbackGenres.ifEmpty { listOf("Electronic", "Synthwave", "Indie") } }
        val topMoods = moodScores.entries.sortedByDescending { it.value }.map { it.key }.take(3)
            .ifEmpty { listOf("Atmospheric", "Energetic", "Dreamy") }

        val norm = weightTotal.coerceAtLeast(1.0)
        val avgBpm = if (weightTotal > 0) weightedBpmSum / weightTotal else 118.0
        val tempoScore = (((avgBpm - 65.0) / 100.0) * 100.0).toInt().coerceIn(25, 96)
        val energyScore = ((energySum / norm) * 100.0).toInt().coerceIn(32, 95)
        val chillScore = ((chillSum / norm) * 100.0).toInt().coerceIn(25, 92)
        val electronicScore = ((electronicSum / norm) * 100.0).toInt().coerceIn(30, 96)
        val acousticScore = ((acousticSum / norm) * 100.0).toInt().coerceIn(18, 90)
        val avgCompletion = if (signals.isNotEmpty()) ((completionSum / signals.size) * 100).toInt().coerceIn(20, 99) else 84

        return SonicDnaProfile(
            energy = energyScore,
            valence = ((energyScore + (100 - chillScore / 2)) / 2).coerceIn(30, 95),
            tempo = tempoScore,
            acoustic = acousticScore,
            electronic = electronicScore,
            chill = chillScore,
            topGenres = topGenres,
            topMoods = topMoods,
            totalPlays = signals.size,
            avgCompletionPct = avgCompletion
        )
    }

    /**
     * Stage 1: Multi-signal candidate scorer inspired by Instagram/YouTube ranking pipelines.
     */
    fun scoreCandidatesStage1(
        candidates: List<Track>,
        signals: List<ListeningSignalEntity>,
        friendFeedPosts: List<FeedPostDoc>,
        sonicDna: SonicDnaProfile,
        moodFilter: String? = null
    ): List<RecommendedPick> {
        val nowBucket = currentTimeBucket()
        val signalByTrack = signals.groupBy { it.trackId }
        val genreAffinity = mutableMapOf<String, Double>()
        val moodAffinity = mutableMapOf<String, Double>()

        signals.forEach { sig ->
            val delta = (sig.completionRatio * 2.0) + (sig.replayCount * 1.4) +
                (if (sig.liked) 1.8 else 0.0) + (if (sig.savedOffline) 1.2 else 0.0) +
                (if (sig.sharedInDm) 1.5 else 0.0) + (if (sig.dwellMs > 5000L) 0.8 else 0.0) -
                (if (sig.earlySkip) 2.2 else 0.0)
            genreAffinity[sig.genre.lowercase()] = (genreAffinity[sig.genre.lowercase()] ?: 0.0) + delta
            moodAffinity[sig.mood.lowercase()] = (moodAffinity[sig.mood.lowercase()] ?: 0.0) + delta
        }

        val friendTrackCounts = friendFeedPosts.groupingBy { it.trackId }.eachCount()
        val targetBpm = 65.0 + sonicDna.tempo

        return candidates.distinctBy { it.id }.map { track ->
            val history = signalByTrack[track.id].orEmpty()
            val gScore = (genreAffinity[track.genre.lowercase()] ?: 0.5).coerceIn(-3.0, 10.0)
            val mScore = (moodAffinity[track.mood.lowercase()] ?: 0.4).coerceIn(-3.0, 8.0)
            val bpmFit = (1.0 - (abs(track.bpm - targetBpm) / 80.0)).coerceIn(0.0, 1.0) * 2.5
            val replayBonus = history.sumOf { it.replayCount } * 1.8
            val skipPenalty = history.count { it.earlySkip } * 2.4
            val friendBoost = (friendTrackCounts[track.id] ?: 0) * 2.2
            val timeFit = if (history.any { it.timeBucket == nowBucket && !it.earlySkip }) 1.6 else 0.4
            val moodPromptBoost = if (!moodFilter.isNullOrBlank() &&
                (track.mood.contains(moodFilter, true) || track.genre.contains(moodFilter, true) || track.title.contains(moodFilter, true))
            ) 5.5 else 0.0
            val likeBoost = if (track.isLiked) 1.4 else 0.0
            val ytDiscoveryBoost = if (track.sourceType == TrackSourceType.YOUTUBE && skipPenalty == 0.0) 1.6 else 0.0

            val total = (gScore + mScore + bpmFit + replayBonus + friendBoost + timeFit + moodPromptBoost + likeBoost + ytDiscoveryBoost - skipPenalty)
                .coerceAtLeast(0.2)

            val (category, tag, reason) = when {
                !moodFilter.isNullOrBlank() && moodPromptBoost > 0 -> Triple(
                    "Mood DJ",
                    "DJ: $moodFilter",
                    "Matched to your live '$moodFilter' soundscape request at ${track.bpm} BPM."
                )
                replayBonus >= 1.8 -> Triple(
                    "Replay Radar",
                    "High Replay Signal",
                    "Ranked up because you replay ${track.genre} tracks with similar tempo."
                )
                friendBoost > 0 -> Triple(
                    "Friend Wave",
                    "Trending With Friends",
                    "Friends are sharing this ${track.genre} pick in your PulseWave feed."
                )
                else -> Triple(
                    "For You",
                    "${track.bpm} BPM · ${track.mood}",
                    "Strong ${track.genre} affinity tuned for your $nowBucket listening window."
                )
            }

            RecommendedPick(
                track = track,
                score = total,
                reasonTag = tag,
                explanation = reason,
                category = category
            )
        }.sortedByDescending { it.score }
    }

    /**
     * Stage 2: Gemini AI Re-Ranker & Context Explainer (with Stage 1 deterministic fallback).
     */
    suspend fun buildRecommendations(
        candidates: List<Track>,
        signals: List<ListeningSignalEntity>,
        friendFeedPosts: List<FeedPostDoc>,
        moodPrompt: String? = null,
        offlineMode: Boolean = false
    ): DiscoveryFeedBundle {
        val sonicDna = computeSonicDna(signals, candidates)
        val stage1 = scoreCandidatesStage1(candidates, signals, friendFeedPosts, sonicDna, moodPrompt)

        if (stage1.isEmpty()) {
            return DiscoveryFeedBundle(
                djHeadline = "Import local tracks or go online to tune Pulse DJ",
                djSubheadline = "Your behavioral recommendation engine updates with every listen",
                sonicDna = sonicDna,
                poweredByGemini = false
            )
        }

        if (offlineMode) {
            return packageBundle(
                picks = stage1,
                sonicDna = sonicDna,
                headline = "Offline Behavioral Mix · ${currentTimeBucket()}",
                subheadline = "Ranked locally from your completion rates, replays, and BPM affinity",
                poweredByGemini = false
            )
        }

        return try {
            val ytShortlist = stage1.filter { it.track.sourceType == TrackSourceType.YOUTUBE }.take(12)
            val nonYtShortlist = stage1.filter { it.track.sourceType != TrackSourceType.YOUTUBE }.take(12)
            val shortlist = (ytShortlist + nonYtShortlist + stage1).distinctBy { it.track.id }.take(24)
            val candidateLines = shortlist.joinToString("\n") { p ->
                "- id=${p.track.id} | title=${p.track.title} | artist=${p.track.artist} | source=${p.track.sourceType.label} | genre=${p.track.genre} | mood=${p.track.mood} | bpm=${p.track.bpm} | stage1=${"%.1f".format(p.score)}"
            }
            val skippedGenres = signals.filter { it.earlySkip }.map { it.genre }.distinct().take(4).joinToString()
            val prompt = buildString {
                appendLine("You are PulseWave's Instagram/YouTube-style Stage-2 Music Re-Ranker & AI Mood DJ.")
                appendLine("Time of day: ${currentTimeBucket()}")
                appendLine("User Sonic DNA: energy=${sonicDna.energy}/100, chill=${sonicDna.chill}/100, avgCompletion=${sonicDna.avgCompletionPct}%, topGenres=${sonicDna.topGenres.joinToString()}, topMoods=${sonicDna.topMoods.joinToString()}.")
                if (skippedGenres.isNotBlank()) appendLine("Recently skipped early (<15s): $skippedGenres.")
                if (!moodPrompt.isNullOrBlank()) appendLine("User's active Mood DJ prompt: \"$moodPrompt\". Prioritize matching vibes first.")
                appendLine("Candidates:")
                appendLine(candidateLines)
                appendLine("Re-rank the best 12 tracks with crisp reasonTags and personalized 1-sentence explanations.")
            }

            val response = withTimeout(60_000L) {
                aiModel.generateContent(prompt)
            }
            val rawJson = response.text ?: throw IllegalStateException("Empty Gemini ranking response")
            val json = JSONObject(rawJson)
            val headline = json.optString("djHeadline").ifBlank { "Pulse DJ · Tuned to Your Sonic DNA" }
            val subheadline = json.optString("djSubheadline").ifBlank { "Re-ranked by Gemini from your listening habits & friend waves" }
            val picksJson = json.optJSONArray("picks")
            val byId = stage1.associateBy { it.track.id }
            val reRanked = mutableListOf<RecommendedPick>()
            val seen = mutableSetOf<String>()

            if (picksJson != null) {
                for (i in 0 until picksJson.length()) {
                    val obj = picksJson.optJSONObject(i) ?: continue
                    val id = obj.optString("trackId")
                    val base = byId[id] ?: continue
                    if (!seen.add(id)) continue
                    reRanked.add(
                        base.copy(
                            category = obj.optString("category", base.category),
                            reasonTag = obj.optString("reasonTag", base.reasonTag),
                            explanation = obj.optString("explanation", base.explanation),
                            score = 100.0 - i
                        )
                    )
                }
            }
            stage1.forEach { pick ->
                if (seen.add(pick.track.id)) reRanked.add(pick)
            }

            packageBundle(
                picks = reRanked,
                sonicDna = sonicDna,
                headline = headline,
                subheadline = subheadline,
                poweredByGemini = true
            )
        } catch (e: TimeoutCancellationException) {
            Log.w(TAG, "Gemini Stage-2 timed out; serving Stage-1 behavioral ranking", e)
            packageBundle(
                picks = stage1,
                sonicDna = sonicDna,
                headline = "Behavioral Mix · ${currentTimeBucket()} Wave",
                subheadline = "Scored in real time from your replays, skips, and friend signals",
                poweredByGemini = false
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Gemini Stage-2 unavailable (${e.message}); serving Stage-1 ranking", e)
            packageBundle(
                picks = stage1,
                sonicDna = sonicDna,
                headline = "Behavioral Mix · ${currentTimeBucket()} Wave",
                subheadline = "Scored from your completion rates, replays, and BPM affinity",
                poweredByGemini = false
            )
        }
    }

    suspend fun generateTrackVibeStory(track: Track): Result<String> {
        return try {
            val response = withTimeout(45_000L) {
                storyModel.generateContent(
                    "Write 4 short atmospheric synced-style liner note lines (plain text, no markdown) describing the sonic mood and texture for a listener playing '${track.title}' by ${track.artist} (${track.genre}, ${track.mood}, ${track.bpm} BPM)."
                )
            }
            val text = response.text?.trim().orEmpty()
            if (text.isBlank()) Result.failure(IllegalStateException("Empty AI response"))
            else Result.success(text)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun packageBundle(
        picks: List<RecommendedPick>,
        sonicDna: SonicDnaProfile,
        headline: String,
        subheadline: String,
        poweredByGemini: Boolean
    ): DiscoveryFeedBundle {
        val ytPicks = picks.filter { it.track.sourceType == TrackSourceType.YOUTUBE }
        val nonYtPicks = picks.filter { it.track.sourceType != TrackSourceType.YOUTUBE }
        val hero = if (ytPicks.isNotEmpty() && nonYtPicks.isNotEmpty()) {
            // Blend YouTube Music and local/Audius picks in Top Personalized Picks
            (ytPicks.take(3) + nonYtPicks.take(3) + picks).distinctBy { it.track.id }.take(6)
        } else {
            picks.take(6)
        }
        val heroIds = hero.mapTo(HashSet()) { it.track.id }
        val replays = picks.filter { it.category == "Replay Radar" }.ifEmpty { picks.drop(2).take(5) }
        val friends = picks.filter { it.category == "Friend Wave" }.ifEmpty { picks.drop(4).take(5) }
        val explore = picks.filter { it.track.id !in heroIds }.ifEmpty { picks }
        return DiscoveryFeedBundle(
            djHeadline = headline,
            djSubheadline = subheadline,
            heroPicks = hero,
            replayPicks = replays,
            friendWavePicks = friends,
            explorePicks = explore,
            sonicDna = sonicDna,
            poweredByGemini = poweredByGemini
        )
    }
}
