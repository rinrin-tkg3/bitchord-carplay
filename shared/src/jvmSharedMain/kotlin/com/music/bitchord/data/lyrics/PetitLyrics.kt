package com.music.bitchord.data.lyrics

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.StringReader
import java.text.Normalizer
import java.util.Base64
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.abs

/**
 * Word- and character-timed lyrics from PetitLyrics (プチリリ).
 *
 * Particularly strong for Japanese pop, anime, Vocaloid, and game soundtracks,
 * returning `<wsy>` XML with millisecond start and end timestamps per character
 * or word.
 */
object PetitLyrics {

    private const val ENDPOINT = "https://p1.petitlyrics.com/api/GetPetitLyricsData.php"
    private const val CLIENT_APP_ID = "p1110417"
    private const val TERMINAL_TYPE = "10"
    private const val DURATION_TOLERANCE_MS = 15_000L

    private val PLACEHOLDER_ARTISTS = setOf(
        "曲", "song", "video", "動画", "unknown artist", "various artists",
    )

    suspend fun lyrics(
        title: String,
        artist: String,
        durationMs: Long,
        album: String? = null,
    ): List<LyricLine>? = withContext(Dispatchers.IO) {
        val cleanTitle = title.trim()
        val rawArtist = artist.trim()
        val cleanArtist = if (rawArtist.lowercase() in PLACEHOLDER_ARTISTS) "" else rawArtist
        if (cleanTitle.isEmpty()) return@withContext null

        // 1. Direct match on title + artist
        if (cleanArtist.isNotEmpty()) {
            querySingle(cleanTitle, cleanArtist)?.let { return@withContext it }
        }

        // 2. Retry with parentheticals (e.g. "(CV: ...)", "【...】") stripped
        val strippedTitle = cleanTitle.stripBrackets()
        val strippedArtist = cleanArtist.stripBrackets()
        if (strippedArtist.isNotEmpty() && (strippedTitle != cleanTitle || strippedArtist != cleanArtist)) {
            querySingle(strippedTitle, strippedArtist)?.let { return@withContext it }
        }

        // 3. Multi-artist / CV split: try the primary artist token directly
        //    (e.g. "いきづらい部！, 春宮ゆくり(CV.奥村優季)" -> "いきづらい部！" / "いきづらい部!")
        val artistTokens = extractArtistTokens(cleanArtist)
        val primaryArtist = artistTokens.firstOrNull()
        if (!primaryArtist.isNullOrBlank() && primaryArtist != strippedArtist) {
            querySingle(strippedTitle, primaryArtist)?.let { return@withContext it }
            val nfkcPrimary = primaryArtist.nfkc()
            if (nfkcPrimary != primaryArtist) {
                querySingle(strippedTitle.nfkc(), nfkcPrimary)?.let { return@withContext it }
            }
        }

        // 4. Fallback: search by title variants and match artist tokens / album / duration
        val titleVariants = listOf(cleanTitle, strippedTitle, strippedTitle.nfkc()).distinct()
        for (queryTitle in titleVariants) {
            queryByTitleFallback(
                title = queryTitle,
                artistTokens = artistTokens,
                durationMs = durationMs,
                album = album,
            )?.let { return@withContext it }
        }
        null
    }

    private fun querySingle(title: String, artist: String): List<LyricLine>? {
        val xml = lyricsPostForm(
            url = ENDPOINT,
            form = buildMap {
                put("key_title", title)
                if (artist.isNotEmpty()) put("key_artist", artist)
                put("clientAppId", CLIENT_APP_ID)
                put("lyricsType", "3")
                put("terminalType", TERMINAL_TYPE)
                put("maxCount", "1")
            },
        ) ?: return null
        val song = parseSongs(xml).firstOrNull() ?: return null
        return decodeOrFetchSong(song)
    }

    private fun queryByTitleFallback(
        title: String,
        artistTokens: List<String>,
        durationMs: Long,
        album: String?,
    ): List<LyricLine>? {
        val xml = lyricsPostForm(
            url = ENDPOINT,
            form = mapOf(
                "key_title" to title,
                "clientAppId" to CLIENT_APP_ID,
                "lyricsType" to "3",
                "terminalType" to TERMINAL_TYPE,
                "maxCount" to "10",
            ),
        ) ?: return null
        val candidates = parseSongs(xml)
        if (candidates.isEmpty()) return null

        val normTargetTitle = title.normalizeForMatch()
        val normAlbum = album?.normalizeForMatch().orEmpty()
        val normArtistTokens = artistTokens.map { it.normalizeForMatch() }.filter { it.isNotEmpty() }

        val match = candidates.firstOrNull { candidate ->
            val candTitleNorm = candidate.title.normalizeForMatch()
            val candArtistNorm = candidate.artist.normalizeForMatch()
            val candArtistTokens = extractArtistTokens(candidate.artist)
                .map { it.normalizeForMatch() }
                .filter { it.isNotEmpty() }

            val artistMatches = normArtistTokens.isEmpty() ||
                normArtistTokens.any { token ->
                    candArtistNorm.contains(token) ||
                        candArtistTokens.any { candToken -> candToken.contains(token) || token.contains(candToken) }
                } ||
                (normAlbum.isNotEmpty() && candidate.album.normalizeForMatch().contains(normAlbum))

            val durationMatches = durationMs <= 0L || candidate.durationMs <= 0L ||
                abs(candidate.durationMs - durationMs) <= DURATION_TOLERANCE_MS

            // Also accept if the normalized title matches and the duration is a close match
            // (handles cases where YouTube credits a character/actor differently from PetitLyrics)
            val titleAndDurationExact = candTitleNorm == normTargetTitle &&
                durationMs > 0L && candidate.durationMs > 0L &&
                abs(candidate.durationMs - durationMs) <= DURATION_TOLERANCE_MS

            (artistMatches && durationMatches) || titleAndDurationExact
        } ?: candidates.singleOrNull { candidate ->
            candidate.title.normalizeForMatch() == normTargetTitle
        } ?: return null

        return decodeOrFetchSong(match)
    }

    private fun decodeOrFetchSong(song: SongEntry): List<LyricLine>? {
        if (song.lyricsData.isNotBlank()) {
            decodeSongLyrics(song)?.let { return it }
        }
        if (song.lyricsId.isBlank()) return null

        // Fetch full <wsy> word-synced data by lyricsId if available
        if (song.availableLyricsType == 0 || song.availableLyricsType >= 3) {
            val detailXml = lyricsPostForm(
                url = ENDPOINT,
                form = mapOf(
                    "key_lyricsId" to song.lyricsId,
                    "clientAppId" to CLIENT_APP_ID,
                    "lyricsType" to "3",
                    "terminalType" to TERMINAL_TYPE,
                    "maxCount" to "1",
                ),
            )
            val detailSong = detailXml?.let { parseSongs(it).firstOrNull() }
            if (detailSong != null && detailSong.lyricsData.isNotBlank()) {
                decodeSongLyrics(detailSong)?.let { return it }
            }
        }

        // Fallback to plain text (lyricsType = 1) when the track on PetitLyrics
        // only has non-wsy lyrics (availableLyricsType 1 or 2)
        val plainXml = lyricsPostForm(
            url = ENDPOINT,
            form = mapOf(
                "key_lyricsId" to song.lyricsId,
                "clientAppId" to CLIENT_APP_ID,
                "lyricsType" to "1",
                "terminalType" to TERMINAL_TYPE,
                "maxCount" to "1",
            ),
        ) ?: return null
        val plainSong = parseSongs(plainXml).firstOrNull() ?: return null
        return decodeSongLyrics(plainSong)
    }

    private fun decodeSongLyrics(song: SongEntry): List<LyricLine>? {
        if (song.lyricsData.isBlank()) return null
        val decoded = runCatching {
            Base64.getDecoder().decode(song.lyricsData.trim()).toString(Charsets.UTF_8)
        }.getOrNull()?.trim() ?: return null
        if (decoded.isEmpty()) return null

        return when {
            decoded.startsWith("<wsy", ignoreCase = true) || song.lyricsType == 3 ->
                parseWsy(decoded).takeIf { it.isNotEmpty() }
            else ->
                EnhancedLrc.parse(decoded)
                    .ifEmpty { LrcLib.parseLrc(decoded) }
                    .ifEmpty { plainLines(decoded) }
                    .takeIf { it.isNotEmpty() }
        }
    }

    internal fun parseWsy(wsyXml: String): List<LyricLine> = runCatching {
        val document = newXmlDocument(wsyXml) ?: return emptyList()
        val lineNodes = document.getElementsByTagName("line")
        val lines = ArrayList<LyricLine>(lineNodes.length)

        for (i in 0 until lineNodes.length) {
            val lineEl = lineNodes.item(i) as? Element ?: continue
            val rawLineString = lineEl.childText("linestring")
            val lineText = rawLineString.trim()
            if (lineText.isEmpty()) continue

            val wordNodes = lineEl.getElementsByTagName("word")
            val timedWords = ArrayList<TimedRun>(wordNodes.length)
            var firstStartMs: Long? = null

            for (w in 0 until wordNodes.length) {
                val wordEl = wordNodes.item(w) as? Element ?: continue
                val startMs = wordEl.childText("starttime").trim().toLongOrNull() ?: continue
                val endMs = wordEl.childText("endtime").trim().toLongOrNull() ?: startMs
                val wordString = wordEl.childText("wordstring")
                if (startMs > 0L && firstStartMs == null) {
                    firstStartMs = startMs
                }
                if (startMs > 0L || endMs > 0L) {
                    timedWords += TimedRun(startMs, maxOf(startMs, endMs), wordString)
                }
            }

            val words = wordsFromRuns(timedWords, spacingIsExplicit = true)
            when {
                // Whole-line timestamp dressed as a single <word>
                words.size == 1 && timedWords.size == 1 && lineText.length > 4 -> {
                    val only = words.first()
                    lines += LyricLine(
                        timeMs = only.startMs,
                        text = lineText,
                        sungUntilMs = only.endMs.takeIf { it > only.startMs },
                    )
                }
                words.isNotEmpty() -> {
                    val lineStart = words.first().startMs
                    val lineEnd = words.last().endMs
                    lines += LyricLine(
                        timeMs = lineStart,
                        text = lineText,
                        words = words,
                        sungUntilMs = lineEnd.takeIf { it > lineStart },
                    )
                }
                firstStartMs != null -> {
                    lines += LyricLine(timeMs = firstStartMs, text = lineText)
                }
                else -> {
                    lines += LyricLine(timeMs = 0L, text = lineText)
                }
            }
        }

        if (lines.any { it.timeMs > 0L }) {
            lines.sortedBy { it.timeMs }.withInstrumentalGaps()
        } else {
            lines
        }
    }.getOrDefault(emptyList())

    private fun parseSongs(xml: String): List<SongEntry> = runCatching {
        val document = newXmlDocument(xml) ?: return emptyList()
        val nodes = document.getElementsByTagName("song")
        val songs = ArrayList<SongEntry>(nodes.length)
        for (i in 0 until nodes.length) {
            val el = nodes.item(i) as? Element ?: continue
            songs += SongEntry(
                lyricsId = el.childText("lyricsId").trim(),
                title = el.childText("title").trim(),
                artist = el.childText("artist").trim(),
                album = el.childText("album").trim(),
                lyricsType = el.childText("lyricsType").trim().toIntOrNull() ?: 0,
                availableLyricsType = el.childText("availableLyricsType").trim().toIntOrNull() ?: 0,
                durationMs = el.childText("duration").trim().toLongOrNull() ?: 0L,
                lyricsData = el.childText("lyricsData").trim(),
            )
        }
        songs
    }.getOrDefault(emptyList())

    private fun plainLines(text: String): List<LyricLine> =
        text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { LyricLine(timeMs = 0L, text = it) }
            .toList()

    private fun newXmlDocument(xml: String): org.w3c.dom.Document? = runCatching {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
            runCatching { setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true) }
            runCatching { isExpandEntityReferences = false }
        }
        factory.newDocumentBuilder().parse(InputSource(StringReader(xml)))
    }.getOrNull()

    private fun Element.childText(tagName: String): String {
        val children = childNodes
        for (i in 0 until children.length) {
            val node = children.item(i) as? Element ?: continue
            if (node.tagName.equals(tagName, ignoreCase = true)) {
                return node.textContent.orEmpty()
            }
        }
        return ""
    }

    private fun String.nfkc(): String =
        Normalizer.normalize(this, Normalizer.Form.NFKC)

    private fun String.stripBrackets(): String =
        replace(Regex("""[(（\[【].*?[)）\]】]"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
            .ifBlank { trim() }

    private fun String.normalizeForMatch(): String =
        nfkc()
            .stripBrackets()
            .lowercase()
            .replace(Regex("""[\s!！?？,、.&＆/／・×~〜\-ー'"`’”]+"""), "")

    private fun extractArtistTokens(rawArtist: String): List<String> {
        if (rawArtist.isBlank()) return emptyList()
        return rawArtist
            .stripBrackets()
            .split(Regex("""[,、&＆/／・×]|\s+(?i)(feat|ft|featuring|with|cv)\.?\s+"""))
            .map { it.trim() }
            .filter { it.isNotEmpty() && it.lowercase() !in PLACEHOLDER_ARTISTS }
            .distinct()
    }

    private data class SongEntry(
        val lyricsId: String,
        val title: String,
        val artist: String,
        val album: String,
        val lyricsType: Int,
        val availableLyricsType: Int,
        val durationMs: Long,
        val lyricsData: String,
    )
}
