package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.PreferencesRepository
import com.example.data.model.Track
import com.example.data.model.TrackSourceType
import com.example.data.remote.InnerTubeFormatCandidate
import com.example.data.remote.SessionAwareNewPipeDownloader
import com.example.data.remote.YouTubeAuthState
import com.example.data.remote.YouTubeHttpException
import com.example.data.remote.YouTubeMusicClient
import com.example.data.remote.YouTubeSessionManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.schabi.newpipe.extractor.downloader.Request as NewPipeRequest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.schabi.newpipe.extractor.exceptions.AgeRestrictedContentException
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException
import org.schabi.newpipe.extractor.exceptions.GeographicRestrictionException
import org.schabi.newpipe.extractor.exceptions.PaidContentException
import org.schabi.newpipe.extractor.exceptions.PrivateContentException
import org.schabi.newpipe.extractor.exceptions.SignInConfirmNotBotException
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class YouTubeMusicClientTest {

    @Test
    fun extractVideoId_handlesUrlsAndRawIds() {
        assertEquals("NJAv_7lHUIU", YouTubeMusicClient.extractVideoId("https://www.youtube.com/watch?v=NJAv_7lHUIU"))
        assertEquals("NJAv_7lHUIU", YouTubeMusicClient.extractVideoId("https://music.youtube.com/watch?v=NJAv_7lHUIU&si=abc123"))
        assertEquals("NJAv_7lHUIU", YouTubeMusicClient.extractVideoId("https://youtu.be/NJAv_7lHUIU?t=30"))
        assertEquals("NJAv_7lHUIU", YouTubeMusicClient.extractVideoId("https://youtube.com/shorts/NJAv_7lHUIU"))
        assertEquals("NJAv_7lHUIU", YouTubeMusicClient.extractVideoId("pulseyt://watch?v=NJAv_7lHUIU"))
        assertEquals("NJAv_7lHUIU", YouTubeMusicClient.extractVideoId("NJAv_7lHUIU"))
        assertNull(YouTubeMusicClient.extractVideoId("Arijit Singh Kesariya"))
    }

    @Test
    fun parseDurationTextMs_parsesMinutesAndHours() {
        assertEquals(269_000L, YouTubeMusicClient.parseDurationTextMs("4:29"))
        assertEquals(3_730_000L, YouTubeMusicClient.parseDurationTextMs("1:02:10"))
        assertEquals(0L, YouTubeMusicClient.parseDurationTextMs("Live"))
    }

    @Test
    fun parseWebRemixSearchJson_extractsTracksAndUpscalesArt() {
        val sampleJson = """
            {
              "contents": {
                "tabbedSearchResultsRenderer": {
                  "tabs": [
                    {
                      "tabRenderer": {
                        "content": {
                          "sectionListRenderer": {
                            "contents": [
                              {
                                "musicShelfRenderer": {
                                  "contents": [
                                    {
                                      "musicResponsiveListItemRenderer": {
                                        "playlistItemData": { "videoId": "NJAv_7lHUIU" },
                                        "flexColumns": [
                                          {
                                            "musicResponsiveListItemFlexColumnRenderer": {
                                              "text": { "runs": [ { "text": "Kesariya" } ] }
                                            }
                                          },
                                          {
                                            "musicResponsiveListItemFlexColumnRenderer": {
                                              "text": {
                                                "runs": [
                                                  { "text": "Arijit Singh" },
                                                  { "text": " • " },
                                                  { "text": "Brahmāstra" },
                                                  { "text": " • " },
                                                  { "text": "4:29" }
                                                ]
                                              }
                                            }
                                          }
                                        ],
                                        "thumbnail": {
                                          "musicThumbnailRenderer": {
                                            "thumbnail": {
                                              "thumbnails": [
                                                { "url": "https://lh3.googleusercontent.com/abc=w120-h120-l90-rj" }
                                              ]
                                            }
                                          }
                                        }
                                      }
                                    }
                                  ]
                                }
                              }
                            ]
                          }
                        }
                      }
                    }
                  ]
                }
              }
            }
        """.trimIndent()

        val tracks = YouTubeMusicClient.parseWebRemixSearchJson(sampleJson, "Kesariya Arijit")
        assertEquals(1, tracks.size)
        val t = tracks.first()
        assertEquals("yt_NJAv_7lHUIU", t.id)
        assertEquals("Kesariya", t.title)
        assertEquals("Arijit Singh", t.artist)
        assertEquals("Brahmāstra", t.album)
        assertEquals(269_000L, t.durationMs)
        assertEquals("https://lh3.googleusercontent.com/abc=w544-h544-l90-rj", t.artworkUrl)
        assertEquals(TrackSourceType.YOUTUBE, t.sourceType)
        assertTrue(t.isYouTubeTrack)
        assertEquals("NJAv_7lHUIU", t.youtubeVideoId)
        assertEquals("pulseyt://watch?v=NJAv_7lHUIU", t.playableUri)
        assertTrue(t.canDownloadOffline)
    }

    @Test
    fun trackPlayableUri_prefersDownloadedAndLocalOverLazyYouTubeUri() {
        val remoteYt = Track(
            id = "yt_NJAv_7lHUIU",
            title = "Kesariya",
            artist = "Arijit Singh",
            sourceType = TrackSourceType.YOUTUBE
        )
        assertEquals("pulseyt://watch?v=NJAv_7lHUIU", remoteYt.playableUri)
        assertTrue(remoteYt.canDownloadOffline)

        val downloadedYt = remoteYt.copy(
            isDownloaded = true,
            downloadedFilePath = "/data/user/0/app/files/offline_audio/yt_NJAv_7lHUIU.m4a"
        )
        assertEquals("file:///data/user/0/app/files/offline_audio/yt_NJAv_7lHUIU.m4a", downloadedYt.playableUri)
        assertFalse(downloadedYt.canDownloadOffline)
    }

    @Test
    fun selectBestInnerTubeCandidate_prefersMp4AndRespectsQualitySetting() {
        val lowMp4 = InnerTubeFormatCandidate(139, "https://example.com/139", "audio/mp4", 48_000)
        val highMp4 = InnerTubeFormatCandidate(140, "https://example.com/140", "audio/mp4", 129_000)
        val opusHigh = InnerTubeFormatCandidate(251, "https://example.com/251", "audio/webm", 160_000)

        val pickedHigh = YouTubeMusicClient.selectBestInnerTubeCandidate(
            listOf(lowMp4, opusHigh, highMp4),
            preferHighQuality = true
        )
        assertEquals(140, pickedHigh?.itag)

        val pickedSaver = YouTubeMusicClient.selectBestInnerTubeCandidate(
            listOf(lowMp4, highMp4),
            preferHighQuality = false
        )
        // Closer to 96kbps data-saver target is 129k (diff 33k) vs 48k (diff 48k)
        assertEquals(140, pickedSaver?.itag)
    }

    @Test
    fun inferUserAgentForStreamUrl_matchesClientParamAndHlsSegments() {
        val visionOsUrl = "https://rr1---sn-abc.googlevideo.com/videoplayback?c=VISIONOS&expire=1900000000"
        val visionOsHlsUrl = "https://manifest.googlevideo.com/api/manifest/hls_playlist/expire/1900000000/c/VISIONOS/playlist/index.m3u8"
        val iosUrl = "https://rr1---sn-abc.googlevideo.com/videoplayback?c=IOS&expire=1900000000"
        val vrUrl = "https://rr1---sn-abc.googlevideo.com/videoplayback?c=ANDROID_VR&expire=1900000000"
        val webRemixUrl = "https://rr1---sn-abc.googlevideo.com/videoplayback?c=WEB_REMIX&expire=1900000000"
        val webHlsUrl = "https://manifest.googlevideo.com/api/manifest/hls_playlist/expire/1900000000/c/WEB/playlist/index.m3u8"
        val hlsVariantUrl = "https://manifest.googlevideo.com/api/manifest/hls_variant/expire/1900000000/playlist/index.m3u8"
        val hlsSegmentUrl = "https://rr2---sn-xyz.googlevideo.com/videoplayback/id/abc/itag/234/file/seg.ts"
        val visionUa = YouTubeMusicClient.inferUserAgentForStreamUrl(visionOsUrl)
        assertTrue(visionUa.contains("com.google.visionos.youtube/"))
        assertTrue(visionUa.contains("visionOS"))
        assertEquals(visionUa, YouTubeMusicClient.inferUserAgentForStreamUrl(visionOsHlsUrl))
        assertTrue(YouTubeMusicClient.inferUserAgentForStreamUrl(iosUrl).contains("iOS"))
        val vrUa = YouTubeMusicClient.inferUserAgentForStreamUrl(vrUrl)
        assertTrue(vrUa.contains("vr.oculus"))
        assertTrue(vrUa.contains("1.65.10"))
        assertTrue(YouTubeMusicClient.inferUserAgentForStreamUrl(webRemixUrl).contains("Chrome/"))
        assertTrue(YouTubeMusicClient.inferUserAgentForStreamUrl(webHlsUrl).contains("Safari"))
        assertTrue(YouTubeMusicClient.inferUserAgentForStreamUrl(hlsVariantUrl).contains("Safari"))
        assertTrue(YouTubeMusicClient.inferUserAgentForStreamUrl(hlsSegmentUrl).contains("Safari"))
    }

    @Test
    fun buildDiscoveryQueries_includesRecentArtistsAndGenres() {
        val queries = YouTubeMusicClient.buildDiscoveryQueries(
            moodPrompt = null,
            preferredGenres = listOf("Bollywood"),
            recentArtists = listOf("Katy Perry")
        )
        assertTrue(queries.any { it.contains("Katy Perry", ignoreCase = true) })
        assertTrue(queries.any { it.contains("Bollywood", ignoreCase = true) })
    }

    @Test
    fun sessionManager_rotateSessionCyclesProfiles() {
        val mgr = YouTubeSessionManager()
        val first = mgr.currentHeaderProfile()
        val second = mgr.rotateSession()
        assertTrue(first.desktopUserAgent != second.desktopUserAgent)
        assertTrue(first.consentCookie != second.consentCookie)
    }

    @Test
    fun humanReadableExtractionError_formatsHttp402And429And403() {
        val err402 = YouTubeMusicClient.humanReadableExtractionError(
            YouTubeHttpException(402, "InnerTube ANDROID_VR player HTTP 402"),
            hasAuthenticatedSession = false
        )
        assertTrue(err402.contains("402"))

        val err429 = YouTubeMusicClient.humanReadableExtractionError(
            YouTubeHttpException(429, "YouTube Music request HTTP 429"),
            hasAuthenticatedSession = false
        )
        assertTrue(err429.contains("429"))

        val err403 = YouTubeMusicClient.humanReadableExtractionError(
            YouTubeHttpException(403, "YouTube stream probe HTTP 403"),
            hasAuthenticatedSession = false
        )
        assertTrue(err403.contains("403"))
    }

    @Test
    fun humanReadableSearchError_formatsHttp402And429() {
        val search402 = YouTubeMusicClient.humanReadableSearchError(
            YouTubeHttpException(402, "YouTube Music search HTTP 402")
        )
        assertTrue(search402.contains("402"))

        val search429 = YouTubeMusicClient.humanReadableSearchError(
            YouTubeHttpException(429, "YouTube Music search HTTP 429")
        )
        assertTrue(search429.contains("429"))
    }

    @Test
    fun sapisidHashAndSignInErrorDetection_workCorrectly() {
        val cookie = "SID=xyz; SAPISID=mySecretValue123/ABC; HSID=def"
        assertEquals("mySecretValue123/ABC", YouTubeMusicClient.extractSapisidFromCookie(cookie))
        val header = YouTubeMusicClient.buildSapisidHashHeader(
            cookieHeader = cookie,
            origin = "https://www.youtube.com",
            timestampSeconds = 1_700_000_000L
        )
        assertTrue(header != null && header.startsWith("SAPISIDHASH 1700000000_"))
        assertEquals(40, header!!.substringAfter("_").length)

        assertTrue(
            YouTubeMusicClient.isYouTubeSignInSuggestionError(
                "YouTube requires sign-in on this network (bot check). Tap 'Connect YouTube' below or in Settings."
            )
        )
        assertFalse(YouTubeMusicClient.isYouTubeSignInSuggestionError("Network timeout"))
    }

    @Test
    fun buildLikedRecommendationQueries_seedsFromLikedSongsAndArtists() {
        val liked = listOf(
            Track(
                id = "yt_NJAv_7lHUIU",
                title = "Kesariya",
                artist = "Arijit Singh",
                genre = "Bollywood",
                sourceType = TrackSourceType.YOUTUBE,
                isLiked = true
            ),
            Track(
                id = "device_ms_1",
                title = "Blinding Lights",
                artist = "The Weeknd",
                genre = "Synthpop",
                sourceType = TrackSourceType.LOCAL_SAF,
                isLiked = true
            )
        )
        val queries = YouTubeMusicClient.buildLikedRecommendationQueries(
            likedTracks = liked,
            preferredGenres = listOf("Pop")
        )
        assertTrue(queries.any { it.contains("Arijit Singh", ignoreCase = true) })
        assertTrue(queries.any { it.contains("The Weeknd", ignoreCase = true) })
        assertTrue(queries.any { it.contains("Kesariya", ignoreCase = true) })
    }

    @Test
    fun parseWebRemixNextJson_extractsPlaylistPanelTracksAsYouTubeOnly() {
        val nextJson = """
            {
              "contents": {
                "singleColumnMusicWatchNextResultsRenderer": {
                  "tabbedRenderer": {
                    "watchNextTabbedResultsRenderer": {
                      "tabs": [
                        {
                          "tabRenderer": {
                            "content": {
                              "musicQueueRenderer": {
                                "content": {
                                  "playlistPanelRenderer": {
                                    "contents": [
                                      {
                                        "playlistPanelVideoRenderer": {
                                          "videoId": "4NRXx6U8ABQ",
                                          "title": { "runs": [ { "text": "Blinding Lights" } ] },
                                          "longBylineText": {
                                            "runs": [
                                              { "text": "The Weeknd" },
                                              { "text": " • " },
                                              { "text": "After Hours" }
                                            ]
                                          },
                                          "lengthText": { "runs": [ { "text": "3:22" } ] },
                                          "thumbnail": {
                                            "thumbnails": [
                                              { "url": "https://lh3.googleusercontent.com/weeknd=w120-h120-l90-rj" }
                                            ]
                                          }
                                        }
                                      }
                                    ]
                                  }
                                }
                              }
                            }
                          }
                        }
                      ]
                    }
                  }
                }
              }
            }
        """.trimIndent()

        val tracks = YouTubeMusicClient.parseWebRemixNextJson(nextJson, "Liked Mix")
        assertEquals(1, tracks.size)
        val rec = tracks.first()
        assertEquals("yt_4NRXx6U8ABQ", rec.id)
        assertEquals("Blinding Lights", rec.title)
        assertEquals("The Weeknd", rec.artist)
        assertEquals("After Hours", rec.album)
        assertEquals(202_000L, rec.durationMs)
        assertEquals(TrackSourceType.YOUTUBE, rec.sourceType)
        assertEquals("https://lh3.googleusercontent.com/weeknd=w544-h544-l90-rj", rec.artworkUrl)
    }

    @Test
    fun extractPlayabilityFailure_capturesExactUnsupportedClientErrorAndIgnoresOk() {
        val errorRoot = JSONObject(
            """
            {
              "playabilityStatus": {
                "status": "ERROR",
                "reason": "YouTube is no longer supported in this application or device."
              }
            }
            """.trimIndent()
        )
        val failure = YouTubeMusicClient.extractPlayabilityFailure(errorRoot)
        assertNotNull(failure)
        assertEquals("ERROR: YouTube is no longer supported in this application or device.", failure?.message)

        val okRoot = JSONObject("""{"playabilityStatus":{"status":"OK"}}""")
        assertNull(YouTubeMusicClient.extractPlayabilityFailure(okRoot))
    }

    @Test
    fun errorPriorityAndHumanReadableExtractionError_handleUnsupportedClientWithoutMasking402Or429() {
        val unsupported = IOException("ERROR: YouTube is no longer supported in this application or device.")
        val timeout = SocketTimeoutException("NEWPIPE_SESSION timed out after 18000ms")
        val err402 = YouTubeHttpException(402, "YouTube Music request rejected (HTTP 402)")
        val err429 = YouTubeHttpException(429, "YouTube rate-limited")

        assertEquals(unsupported, YouTubeMusicClient.selectMoreInformativeError(timeout, unsupported))
        assertEquals(err402, YouTubeMusicClient.selectMoreInformativeError(unsupported, err402))
        assertEquals(err429, YouTubeMusicClient.selectMoreInformativeError(unsupported, err429))

        val unauthMsg = YouTubeMusicClient.humanReadableExtractionError(
            unsupported,
            hasAuthenticatedSession = false
        )
        assertTrue(unauthMsg.contains("YouTube is no longer supported in this application or device"))
        assertTrue(YouTubeMusicClient.isYouTubeSignInSuggestionError(unauthMsg))

        val authMsg = YouTubeMusicClient.humanReadableExtractionError(
            unsupported,
            hasAuthenticatedSession = true
        )
        assertTrue(authMsg.contains("YouTube is no longer supported in this application or device"))
        assertTrue(authMsg.contains("Settings"))
    }

    @Test
    fun resolveAudioStream_propagatesCancellationExceptionWithoutSwallowing() = runBlocking {
        val client = YouTubeMusicClient()
        val deferred = async {
            client.resolveAudioStream("dQw4w9WgXcQ")
        }
        deferred.cancel(CancellationException("User skipped track"))
        var caughtCancellation = false
        try {
            deferred.await()
        } catch (_: CancellationException) {
            caughtCancellation = true
        }
        assertTrue(caughtCancellation)
    }

    @Test
    fun cookieValidationAndMerging_requireBothSapisidAndSessionSidAndDeduplicate() {
        assertFalse(YouTubeMusicClient.isValidAuthenticatedCookie(""))
        assertFalse(YouTubeMusicClient.isValidAuthenticatedCookie("CONSENT=YES+1; PREF=tz=UTC"))
        // SAPISID alone without a SID/__Secure-3PSID/HSID/SSID session cookie is incomplete
        assertFalse(YouTubeMusicClient.isValidAuthenticatedCookie("SAPISID=only_sapisid_value"))

        assertTrue(YouTubeMusicClient.isValidAuthenticatedCookie("SID=abc; SAPISID=xyz"))
        assertTrue(YouTubeMusicClient.isValidAuthenticatedCookie("__Secure-3PSID=abc; __Secure-3PAPISID=xyz"))
        assertTrue(YouTubeMusicClient.isValidAuthenticatedCookie("__Secure-1PAPISID=xyz; __Secure-1PSID=abc"))
        assertTrue(YouTubeMusicClient.isValidAuthenticatedCookie("SAPISID=xyz; HSID=h1; SSID=s1"))

        val merged = YouTubeMusicClient.mergeCookieHeaders(
            "CONSENT=YES+1; SAPISID=old_sapi; PREF=f1=1",
            "SAPISID=fresh_sapi; __Secure-3PSID=sid_3p"
        )
        val parsed = YouTubeMusicClient.parseCookieHeader(merged)
        assertEquals("fresh_sapi", parsed["SAPISID"])
        assertEquals("sid_3p", parsed["__Secure-3PSID"])
        assertEquals("YES+1", parsed["CONSENT"])
        assertTrue(YouTubeMusicClient.isValidAuthenticatedCookie(merged))
    }

    @Test
    fun preferencesRepository_persistsRestoresAndClearsYouTubeAuthSession() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefsRepo = PreferencesRepository(context)
        prefsRepo.clearYouTubeSession()

        val initial = prefsRepo.settingsFlow.first()
        assertFalse(initial.isYouTubeConnected)

        val validCookie = "SID=session_id_1; SAPISID=sapi_val_1; HSID=h1; SSID=s1"
        prefsRepo.setYouTubeSession(
            cookie = validCookie,
            visitorData = "Cgt0ZXN0dmlzaXRvcg==",
            accountLabel = "YouTube Music Connected"
        )

        val saved = prefsRepo.settingsFlow.first()
        assertTrue(saved.isYouTubeConnected)
        assertEquals(validCookie, saved.youtubeCookie)
        assertEquals("Cgt0ZXN0dmlzaXRvcg==", saved.youtubeVisitorData)

        // Hydrate a fresh client instance from DataStore and verify propagation
        val freshClient = YouTubeMusicClient()
        assertFalse(freshClient.hasAuthenticatedYouTubeSession())
        assertEquals(YouTubeAuthState.NOT_CONNECTED, freshClient.getAuthDiagnostics().state)

        freshClient.ensureAuthSessionHydrated(context)
        assertTrue(freshClient.hasAuthenticatedYouTubeSession())
        val diag = freshClient.getAuthDiagnostics()
        assertEquals(YouTubeAuthState.CONFIGURED, diag.state)
        assertTrue(diag.hasSapisid)
        assertTrue(diag.hasSid)
        assertTrue(diag.hasHsidOrSsid)
        assertTrue(diag.hasVisitorData)
        assertEquals(4, diag.cookieCount)

        prefsRepo.clearYouTubeSession()
        val cleared = prefsRepo.settingsFlow.first()
        assertFalse(cleared.isYouTubeConnected)
        freshClient.setYouTubeAuthSession("", "", source = "test_disconnect")
        assertFalse(freshClient.hasAuthenticatedYouTubeSession())
        assertEquals(YouTubeAuthState.NOT_CONNECTED, freshClient.getAuthDiagnostics().state)
    }

    @Test
    fun humanReadableExtractionError_doesNotMisclassifyPageOrMessageWordsAsAgeOrBot() {
        val webPageErr = IOException("Could not get web page")
        val reloadPageErr = IOException("The page needs to be reloaded")
        val imageErr = IOException("Failed to decode image response message")
        val bottomErr = IOException("Unexpected bottom payload token")
        val msTimingErr = IOException("Request failed after 14023ms")

        listOf(webPageErr, reloadPageErr, imageErr, bottomErr).forEach { err ->
            val msg = YouTubeMusicClient.humanReadableExtractionError(
                error = err,
                hasAuthenticatedSession = false,
                hasConfiguredCookie = false,
                wasSessionRejected = false
            )
            assertFalse("Should NOT classify '${err.message}' as age-restricted: $msg", msg.contains("age-restricted", true))
            assertFalse("Should NOT classify '${err.message}' as bot check: $msg", msg.contains("bot check", true))
            assertFalse("Should NOT suggest Connect YouTube for '${err.message}': $msg", YouTubeMusicClient.isYouTubeSignInSuggestionError(msg))
            assertTrue(msg.contains(err.message!!))
        }

        val timingMsg = YouTubeMusicClient.humanReadableExtractionError(
            error = msTimingErr,
            hasAuthenticatedSession = false,
            hasConfiguredCookie = false,
            wasSessionRejected = false
        )
        assertFalse("Timing number 14023ms must not match HTTP 402: $timingMsg", timingMsg.contains("HTTP 402"))
    }

    @Test
    fun humanReadableExtractionError_distinguishesTrueAgeRestrictionAndAuthStates() {
        val ageEx = AgeRestrictedContentException("This video may be inappropriate for some users.")
        val unauthAgeMsg = YouTubeMusicClient.humanReadableExtractionError(
            error = ageEx,
            hasAuthenticatedSession = false,
            hasConfiguredCookie = false,
            wasSessionRejected = false
        )
        assertTrue(unauthAgeMsg.contains("age-restricted", true))
        assertTrue(unauthAgeMsg.contains("requires connecting your YouTube account", true))
        assertTrue(YouTubeMusicClient.isYouTubeSignInSuggestionError(unauthAgeMsg))

        val connectedAgeMsg = YouTubeMusicClient.humanReadableExtractionError(
            error = ageEx,
            hasAuthenticatedSession = true,
            hasConfiguredCookie = true,
            wasSessionRejected = false
        )
        assertTrue(connectedAgeMsg.contains("age-restricted", true))
        assertFalse(connectedAgeMsg.contains("requires connecting your YouTube account", true))
        assertTrue(connectedAgeMsg.contains("connected session", true))

        val rejectedSessionMsg = YouTubeMusicClient.humanReadableExtractionError(
            error = IOException("LOGIN_REQUIRED: Sign in to confirm you're not a bot"),
            hasAuthenticatedSession = false,
            hasConfiguredCookie = true,
            wasSessionRejected = true
        )
        assertTrue(rejectedSessionMsg.contains("rejected or expired", true))
        assertTrue(YouTubeMusicClient.isYouTubeSignInSuggestionError(rejectedSessionMsg))

        val unavailableMsg = YouTubeMusicClient.humanReadableExtractionError(
            error = ContentNotAvailableException("Video unavailable"),
            hasAuthenticatedSession = true,
            hasConfiguredCookie = true,
            wasSessionRejected = false
        )
        assertTrue(unavailableMsg.contains("Video unavailable", true))
        assertFalse("Generic unavailable must NOT falsely claim region lock: $unavailableMsg", unavailableMsg.contains("in your region", true))
        assertFalse("Generic unavailable must NOT falsely claim uploader restriction: $unavailableMsg", unavailableMsg.contains("restricted by the uploader", true))
        assertFalse(YouTubeMusicClient.isYouTubeSignInSuggestionError(unavailableMsg))
    }

    @Test
    fun humanReadableExtractionError_distinguishesGenericUnavailableVsRegionPrivatePaidNetworkAndWrappedCauses() {
        val genericAppBlock = YouTubeMusicClient.humanReadableExtractionError(
            error = ContentNotAvailableException("Got error: \"The following content is not available on this app\""),
            hasAuthenticatedSession = false,
            hasConfiguredCookie = false,
            wasSessionRejected = false
        )
        assertFalse("Client app-block must NOT claim region block: $genericAppBlock", genericAppBlock.contains("in your region", true))
        assertFalse("Client app-block must NOT claim uploader block: $genericAppBlock", genericAppBlock.contains("restricted by the uploader", true))
        assertTrue(genericAppBlock.contains("not available on this app", true))

        val unplayableWatchOnYt = YouTubeMusicClient.humanReadableExtractionError(
            error = IOException("UNPLAYABLE: Watch on YouTube"),
            hasAuthenticatedSession = false,
            hasConfiguredCookie = false,
            wasSessionRejected = false
        )
        assertFalse("Generic UNPLAYABLE must NOT claim region block: $unplayableWatchOnYt", unplayableWatchOnYt.contains("in your region", true))
        assertTrue(unplayableWatchOnYt.contains("UNPLAYABLE", true))

        val geoWrapped = IOException("wrapped", GeographicRestrictionException("Video not available in your country"))
        val geoMsg = YouTubeMusicClient.humanReadableExtractionError(
            error = geoWrapped,
            hasAuthenticatedSession = false,
            hasConfiguredCookie = false,
            wasSessionRejected = false
        )
        assertTrue("True geo restriction should mention region: $geoMsg", geoMsg.contains("in your region", true))
        assertFalse(YouTubeMusicClient.isYouTubeSignInSuggestionError(geoMsg))

        val explicitCountryMsg = YouTubeMusicClient.humanReadableExtractionError(
            error = IOException("UNPLAYABLE: The uploader has not made this video available in your country"),
            hasAuthenticatedSession = false,
            hasConfiguredCookie = false,
            wasSessionRejected = false
        )
        assertTrue(explicitCountryMsg.contains("in your region", true))

        val privateMsg = YouTubeMusicClient.humanReadableExtractionError(
            error = IOException("wrapped", PrivateContentException("This video is private")),
            hasAuthenticatedSession = false,
            hasConfiguredCookie = false,
            wasSessionRejected = false
        )
        assertTrue(privateMsg.contains("private or restricted by the uploader", true))

        val paidMsg = YouTubeMusicClient.humanReadableExtractionError(
            error = PaidContentException("Requires YouTube Music Premium"),
            hasAuthenticatedSession = false,
            hasConfiguredCookie = false,
            wasSessionRejected = false
        )
        assertTrue(paidMsg.contains("YouTube Music Premium", true))

        val botWrapped = IOException("wrapped", SignInConfirmNotBotException("Confirm you're not a bot"))
        val botMsg = YouTubeMusicClient.humanReadableExtractionError(
            error = botWrapped,
            hasAuthenticatedSession = false,
            hasConfiguredCookie = false,
            wasSessionRejected = false
        )
        assertTrue(botMsg.contains("bot check", true))
        assertTrue(YouTubeMusicClient.isYouTubeSignInSuggestionError(botMsg))

        val dnsMsg = YouTubeMusicClient.humanReadableExtractionError(
            error = UnknownHostException("Unable to resolve host \"www.youtube.com\": No address associated with hostname"),
            hasAuthenticatedSession = false,
            hasConfiguredCookie = false,
            wasSessionRejected = false
        )
        assertTrue(dnsMsg.contains("Network error", true))
        assertFalse(dnsMsg.contains("Unable to resolve host", true))
        assertFalse(dnsMsg.contains("www.youtube.com", true))
        assertFalse(dnsMsg.contains("No address associated with hostname", true))

        val wrappedDnsMsg = YouTubeMusicClient.humanReadablePlaybackError(
            error = IOException("Unable to resolve host \"rr3---sn-test.googlevideo.com\": No address associated with hostname"),
            isYouTubeTrack = false
        )
        assertFalse(wrappedDnsMsg.contains("Unable to resolve host", true))
        assertFalse(wrappedDnsMsg.contains("googlevideo.com", true))

        val searchDnsMsg = YouTubeMusicClient.humanReadableSearchError(
            UnknownHostException("Unable to resolve host \"music.youtube.com\": No address associated with hostname")
        )
        assertFalse(searchDnsMsg.contains("music.youtube.com", true))
        assertFalse(searchDnsMsg.contains("Unable to resolve host", true))

        val firestoreMsg = YouTubeMusicClient.humanReadableError(
            error = IOException("UNAVAILABLE: Unable to resolve host firestore.googleapis.com:443"),
            fallback = "Action unavailable offline"
        )
        assertFalse(firestoreMsg.contains("firestore.googleapis.com", true))
        assertFalse(firestoreMsg.contains("Unable to resolve host", true))

        val http403 = YouTubeHttpException(403, "YouTube stream probe HTTP 403")
        val genericUnavailable = ContentNotAvailableException("Video unavailable")
        val chosen = YouTubeMusicClient.selectMoreInformativeError(genericUnavailable, http403)
        assertEquals(http403, chosen)
    }

    @Test
    fun parseWebRemixSearchJson_fallsBackToWatchEndpointWhenPlaylistItemVideoIdIsBlank() {
        val sampleJson = """
            {
              "contents": {
                "musicShelfRenderer": {
                  "contents": [
                    {
                      "musicResponsiveListItemRenderer": {
                        "playlistItemData": { "videoId": "" },
                        "overlay": {
                          "musicItemThumbnailOverlayRenderer": {
                            "content": {
                              "musicPlayButtonRenderer": {
                                "playNavigationEndpoint": {
                                  "watchEndpoint": { "videoId": "NdSJ1pyaAH8" }
                                }
                              }
                            }
                          }
                        },
                        "flexColumns": [
                          {
                            "musicResponsiveListItemFlexColumnRenderer": {
                              "text": { "runs": [{ "text": "Him & I" }] }
                            }
                          },
                          {
                            "musicResponsiveListItemFlexColumnRenderer": {
                              "text": {
                                "runs": [
                                  { "text": "G-Eazy" },
                                  { "text": " • " },
                                  { "text": "4:29" }
                                ]
                              }
                            }
                          }
                        ]
                      }
                    }
                  ]
                }
              }
            }
        """.trimIndent()

        val tracks = YouTubeMusicClient.parseWebRemixSearchJson(sampleJson, "him and I")
        assertEquals(1, tracks.size)
        assertEquals("yt_NdSJ1pyaAH8", tracks[0].id)
        assertEquals("NdSJ1pyaAH8", tracks[0].youtubeVideoId)
        assertEquals("Him & I", tracks[0].title)
        assertEquals("G-Eazy", tracks[0].artist)
        assertEquals(269_000L, tracks[0].durationMs)
        assertEquals("pulseyt://watch?v=NdSJ1pyaAH8", tracks[0].playableUri)
    }

    @Test
    fun signatureCipherHelpers_decodeParamsAndAssembleSignedUrl() {
        val raw = "s=ABC123%3Dxyz&sp=sig&url=https%3A%2F%2Frr1.googlevideo.com%2Fvideoplayback%3Fid%3D123%26mime%3Daudio%252Fmp4"
        val parsed = YouTubeMusicClient.parseSignatureCipherParams(raw)
        assertEquals("ABC123=xyz", parsed["s"])
        assertEquals("sig", parsed["sp"])
        assertEquals("https://rr1.googlevideo.com/videoplayback?id=123&mime=audio%2Fmp4", parsed["url"])

        val assembled = YouTubeMusicClient.assembleSignedStreamUrl(
            parsed["url"]!!,
            parsed["sp"]!!,
            "DECODED_SIG+1"
        )
        assertTrue(assembled.startsWith("https://rr1.googlevideo.com/videoplayback?id=123"))
        assertTrue(assembled.endsWith("&sig=DECODED_SIG%2B1"))
    }

    @Test
    fun sessionAwareDownloader_stripsDesktopCookiesForVisionOsAndAttachesSapisidOnWeb() {
        val capturedHeaders = mutableListOf<Map<String, String>>()
        val fakeClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val req = chain.request()
                val headerMap = LinkedHashMap<String, String>()
                for (i in 0 until req.headers.size) {
                    headerMap[req.headers.name(i)] = req.headers.value(i)
                }
                capturedHeaders.add(headerMap)
                Response.Builder()
                    .request(req)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body("{}".toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()

        val downloader = SessionAwareNewPipeDownloader(
            client = fakeClient,
            sessionManager = YouTubeSessionManager(),
            authCookieProvider = { "SID=sid_val; SAPISID=sapi_val; HSID=h; SSID=s" }
        )

        // Non-web VISIONOS request must NOT receive desktop CONSENT or auth cookies
        val visionReq = NewPipeRequest.Builder()
            .post("https://www.youtube.com/youtubei/v1/player?prettyPrint=false", "{}".toByteArray())
            .headers(
                mapOf(
                    "User-Agent" to listOf("com.google.visionos.youtube/1.04(RealityDevice17,1; U; CPU visionOS 26_6_0 like Mac OS X; US)")
                )
            )
            .build()
        downloader.execute(visionReq)

        assertNull("VISIONOS request must not get Cookie header", capturedHeaders[0]["Cookie"])
        assertNull("VISIONOS request must not get Authorization header", capturedHeaders[0]["Authorization"])

        // Web innertube request MUST receive auth Cookie + SAPISIDHASH + X-Goog-AuthUser
        val webReq = NewPipeRequest.Builder()
            .post("https://www.youtube.com/youtubei/v1/player?prettyPrint=false", "{}".toByteArray())
            .build()
        downloader.execute(webReq)

        assertTrue(capturedHeaders[1]["Cookie"].orEmpty().contains("SAPISID=sapi_val"))
        assertTrue(capturedHeaders[1]["Authorization"].orEmpty().startsWith("SAPISIDHASH "))
        assertEquals("0", capturedHeaders[1]["X-Goog-AuthUser"])
    }

    @Test
    fun likedArtistRecommendationHelpers_extractQueriesAndPrioritizeNonCompilationSongs() {
        val seed1 = Track(
            id = "yt_seed1",
            title = "Blinding Lights",
            artist = "The Weeknd feat. Daft Punk",
            durationMs = 200_000L,
            sourceType = TrackSourceType.YOUTUBE,
            genre = "Pop"
        )
        val seed2 = Track(
            id = "yt_seed2",
            title = "Levitating",
            artist = "Dua Lipa, DaBaby",
            durationMs = 203_000L,
            sourceType = TrackSourceType.YOUTUBE,
            genre = "Pop"
        )
        val unknownArtistTrack = Track(
            id = "yt_seed3",
            title = "Unknown Demo",
            artist = "Unknown Artist",
            durationMs = 180_000L,
            sourceType = TrackSourceType.YOUTUBE
        )

        val seeds = YouTubeMusicClient.extractLikedArtistSeeds(listOf(seed1, seed2, unknownArtistTrack))
        assertEquals(listOf("The Weeknd", "Dua Lipa"), seeds)

        val queries = YouTubeMusicClient.buildLikedRecommendationQueries(listOf(seed1, seed2))
        assertTrue(queries.contains("The Weeknd songs"))
        assertTrue(queries.contains("Dua Lipa songs"))
        assertTrue(queries.any { it.contains("Blinding Lights") })

        val weekndFresh1 = Track(
            id = "yt_w1",
            title = "Starboy",
            artist = "The Weeknd",
            durationMs = 230_000L,
            sourceType = TrackSourceType.YOUTUBE
        )
        val weekndFresh2 = Track(
            id = "yt_w2",
            title = "Save Your Tears",
            artist = "The Weeknd",
            durationMs = 215_000L,
            sourceType = TrackSourceType.YOUTUBE
        )
        val duaFresh1 = Track(
            id = "yt_d1",
            title = "Don't Start Now",
            artist = "Dua Lipa",
            durationMs = 183_000L,
            sourceType = TrackSourceType.YOUTUBE
        )
        val compilationSpam = Track(
            id = "yt_spam1",
            title = "3 Hours of Chill Lofi Music for DEEP Sleep",
            artist = "The Weeknd",
            durationMs = 10_800_000L,
            sourceType = TrackSourceType.YOUTUBE
        )
        val radioFresh = Track(
            id = "yt_r1",
            title = "Midnight City",
            artist = "M83",
            durationMs = 243_000L,
            sourceType = TrackSourceType.YOUTUBE
        )

        assertTrue(YouTubeMusicClient.isCompilationSpamTrack(compilationSpam))
        assertFalse(YouTubeMusicClient.isCompilationSpamTrack(weekndFresh1))

        val rankedTop4 = YouTubeMusicClient.rankLikedArtistRecommendations(
            likedArtists = seeds,
            seedVideoIds = setOf("seed1", "seed2"),
            seedTrackKeys = setOf(seed1.trackKey, seed2.trackKey),
            artistSearchBatches = listOf(
                listOf(seed1, compilationSpam, weekndFresh1, weekndFresh2),
                listOf(seed2, duaFresh1)
            ),
            radioBatches = listOf(listOf(radioFresh)),
            limit = 4
        )
        assertEquals(listOf("yt_w1", "yt_d1", "yt_w2", "yt_r1"), rankedTop4.map { it.id })

        val rankedFull = YouTubeMusicClient.rankLikedArtistRecommendations(
            likedArtists = seeds,
            seedVideoIds = setOf("seed1", "seed2"),
            seedTrackKeys = setOf(seed1.trackKey, seed2.trackKey),
            artistSearchBatches = listOf(
                listOf(seed1, compilationSpam, weekndFresh1, weekndFresh2),
                listOf(seed2, duaFresh1)
            ),
            radioBatches = listOf(listOf(radioFresh)),
            limit = 24
        )
        assertFalse(rankedFull.any { it.id == "yt_spam1" })
        assertEquals(listOf("yt_w1", "yt_d1", "yt_w2", "yt_r1"), rankedFull.take(4).map { it.id })
    }
}
