package com.example

import com.example.data.model.Track
import com.example.data.model.TrackSourceType
import com.example.data.remote.FallbackAudioResolver
import com.example.data.remote.InnerTubeFormatCandidate
import com.example.data.remote.JioSaavnDecryptor
import com.example.data.remote.YouTubeMusicClient
import com.example.data.remote.YouTubeProxyResolver
import com.example.data.remote.YouTubeSessionManager
import okio.ByteString.Companion.toByteString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

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
    fun inferUserAgentForStreamUrl_matchesClientParamAndHlsSafari() {
        val iosUrl = "https://rr1---sn-abc.googlevideo.com/videoplayback?c=IOS&expire=1900000000"
        val vrUrl = "https://rr1---sn-abc.googlevideo.com/videoplayback?c=ANDROID_VR&expire=1900000000"
        val webHlsUrl = "https://manifest.googlevideo.com/api/manifest/hls_playlist/expire/1900000000/c/WEB/playlist/index.m3u8"
        assertTrue(YouTubeMusicClient.inferUserAgentForStreamUrl(iosUrl).contains("iOS"))
        val vrUa = YouTubeMusicClient.inferUserAgentForStreamUrl(vrUrl)
        assertTrue(vrUa.contains("vr.oculus"))
        assertTrue(vrUa.contains("1.65.10"))
        assertTrue(YouTubeMusicClient.inferUserAgentForStreamUrl(webHlsUrl).contains("Safari"))
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
    fun parseInvidiousFormatsJson_generatesLocalProxyAndDirectAudioCandidates() {
        val sample = """
            {
              "adaptiveFormats": [
                { "itag": "251", "type": "audio/webm; codecs=\"opus\"", "bitrate": "160000", "url": "https://rr1.googlevideo.com/videoplayback?itag=251" },
                { "itag": "140", "type": "audio/mp4; codecs=\"mp4a.40.2\"", "bitrate": "129000", "url": "https://rr1.googlevideo.com/videoplayback?itag=140" }
              ]
            }
        """.trimIndent()
        val list = YouTubeProxyResolver.parseInvidiousFormatsJson(
            rawJson = sample,
            instanceBaseUrl = "https://inv.nadeko.net",
            videoId = "NJAv_7lHUIU",
            preferHighQuality = true
        )
        assertTrue(list.isNotEmpty())
        assertTrue(list.first().isProxied)
        assertEquals("https://inv.nadeko.net/latest_version?id=NJAv_7lHUIU&itag=140&local=true", list.first().url)
        assertTrue(list.any { !it.isProxied && it.url.contains("googlevideo.com") })
    }

    @Test
    fun parsePipedStreamsJson_prefersMp4AndProxiedStreams() {
        val sample = """
            {
              "audioStreams": [
                { "url": "https://rr1.googlevideo.com/videoplayback?itag=251", "mimeType": "audio/webm", "bitrate": 160000, "itag": 251 },
                { "url": "https://pipedproxy.kavin.rocks/videoplayback?itag=140", "mimeType": "audio/mp4", "bitrate": 129000, "itag": 140 }
              ]
            }
        """.trimIndent()
        val list = YouTubeProxyResolver.parsePipedStreamsJson(
            rawJson = sample,
            instanceBaseUrl = "https://pipedapi.kavin.rocks",
            preferHighQuality = true
        )
        assertEquals(2, list.size)
        assertEquals(140, list.first().itag)
        assertTrue(list.first().isProxied)
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
    fun jioSaavnDecryptor_decryptsDesEcbUrlAndUpgradesQuality() {
        val plain = "https://aac.saavncdn.com/123/abcde_96.mp4"
        val cipher = Cipher.getInstance("DES/ECB/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec("38346591".toByteArray(Charsets.UTF_8), "DES"))
        val encryptedBase64 = cipher.doFinal(plain.toByteArray(Charsets.UTF_8)).toByteString().base64()

        val decrypted = JioSaavnDecryptor.decryptUrl(encryptedBase64)
        assertEquals(plain, decrypted)

        val candidates = JioSaavnDecryptor.candidateBitrateUrls(
            decryptedUrl = decrypted!!,
            has320Kbps = true,
            preferHighQuality = true
        )
        assertEquals("https://aac.saavncdn.com/123/abcde_320.mp4", candidates.first())
        assertTrue(candidates.contains("https://aac.saavncdn.com/123/abcde_160.mp4"))
    }

    @Test
    fun fallbackAudioResolver_matchesTrackTitlesAndRejectsMismatches() {
        assertEquals("Naal Nachna", FallbackAudioResolver.cleanTrackTitle("Naal Nachna (From \"Dhurandhar\")"))
        assertEquals("Shashwat Sachdev", FallbackAudioResolver.extractPrimaryArtist("Shashwat Sachdev, Afsana Khan"))

        val goodScore = FallbackAudioResolver.computeTrackMatchScore(
            queryTitle = "Naal Nachna (From \"Dhurandhar\")",
            queryArtist = "Shashwat Sachdev, Afsana Khan",
            queryDurationMs = 156_000L,
            candidateTitle = "Naal Nachna",
            candidateArtist = "Afsana Khan, Shashwat Sachdev",
            candidateDurationMs = 155_000L
        )
        assertTrue("Expected high match score >= 0.7, got $goodScore", goodScore >= 0.7)

        val mismatchScore = FallbackAudioResolver.computeTrackMatchScore(
            queryTitle = "Naal Nachna",
            queryArtist = "Shashwat Sachdev",
            queryDurationMs = 156_000L,
            candidateTitle = "Kesariya",
            candidateArtist = "Arijit Singh",
            candidateDurationMs = 268_000L
        )
        assertEquals(0.0, mismatchScore, 0.001)
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
}
