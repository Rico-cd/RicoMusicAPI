package com.ricomusic.app.data

import android.content.Context
import android.util.Base64
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

enum class SongSource { LOCAL, NET, NETEASE, QQ, KUWO, DEMO }

enum class NetProvider(val label: String, val hint: String) {
    KUGOU("酷狗音乐", "原版最全，320k / 无损，推荐"),
    KUWO("酷我音乐", "官方接口不防风控，热门版权曲仅试听"),
    QQ("QQ 音乐", "独家曲目只给试听片段"),
    NETEASE("网易云音乐", "匿名可用，版权曲目受限")
}

val NetProvider.shortTag: String
    get() = when (this) {
        NetProvider.KUGOU -> "kg"
        NetProvider.KUWO -> "kw"
        NetProvider.QQ -> "qq"
        NetProvider.NETEASE -> "ne"
    }

data class SourceRef(
    val provider: NetProvider,
    val id: String,
    val durationMs: Long,
    val quality: Int = 0
) {
    fun encode(): String = "${provider.name.lowercase()}:$id:${durationMs / 1000}s"

    companion object {
        fun decode(raw: String): SourceRef? {
            val parts = raw.split(':')
            if (parts.size < 3) return null
            val prov = runCatching { NetProvider.valueOf(parts[0].uppercase()) }.getOrNull()
                ?: return null
            val id = parts[1].ifBlank { return null }
            val dur = parts[2].removeSuffix("s").toLongOrNull() ?: 0L
            return SourceRef(prov, id, dur * 1000L)
        }
    }
}

data class Song(
    val key: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val uri: String,
    val cover: String? = null,
    val source: SongSource = SongSource.LOCAL,
    val albumId: Long = 0L,
    val extHash: String? = null,
    val refs: List<SourceRef> = emptyList()
) {
    val subtitle: String
        get() = if (artist.isBlank() || artist == "<unknown>") "未知艺人" else artist
}

data class Chart(
    val id: String,
    val name: String,
    val note: String,
    val updatedAt: Long,
    val cover: String?,
    val total: Int,
    val songs: List<Song>
)

data class LyricWord(
    val text: String,
    val startMs: Long,
    val endMs: Long
)

data class LyricLine(
    val startMs: Long,
    val endMs: Long,
    val text: String,
    val words: List<LyricWord>
)

data class LyricDoc(
    val lines: List<LyricLine>,
    val title: String? = null,
    val artist: String? = null,
    val nativeWordTiming: Boolean = false
) {
    val isEmpty: Boolean get() = lines.isEmpty()

    fun indexAt(positionMs: Long): Int {
        if (lines.isEmpty()) return -1
        var lo = 0
        var hi = lines.size - 1
        var ans = -1
        while (lo <= hi) {
            val mid = (lo + hi) / 2
            if (lines[mid].startMs <= positionMs) {
                ans = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return ans
    }
}

object LyricParser {

    private val lineTag = Regex("""\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?]""")
    private val wordTag = Regex("""<(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?>""")
    private val metaTag = Regex("""^\[(ti|ar|al|by|offset|length):(.*)]$""", RegexOption.IGNORE_CASE)

    private const val TAIL_MS = 6000L

    fun parse(raw: String): LyricDoc {
        if (raw.isBlank()) return LyricDoc(emptyList())

        var title: String? = null
        var artist: String? = null
        var offsetMs = 0L
        val collected = ArrayList<Pair<Long, String>>()
        var hasNativeWords = false

        raw.lineSequence().forEach { original ->
            val line = original.trim()
            if (line.isEmpty()) return@forEach

            metaTag.find(line)?.let { m ->
                val key = m.groupValues[1].lowercase()
                val value = m.groupValues[2].trim()
                when (key) {
                    "ti" -> title = value
                    "ar" -> artist = value
                    "offset" -> offsetMs = value.toLongOrNull() ?: 0L
                }
                return@forEach
            }

            val tags = lineTag.findAll(line).toList()
            if (tags.isEmpty()) return@forEach

            val content = lineTag.replace(line, "")
            if (content.isBlank() && !wordTag.containsMatchIn(content)) return@forEach
            if (wordTag.containsMatchIn(content)) hasNativeWords = true

            tags.forEach { tag ->
                collected.add(parseTime(tag.groupValues[1], tag.groupValues[2], tag.groupValues[3]) to content)
            }
        }

        if (collected.isEmpty()) return LyricDoc(emptyList(), title, artist)

        collected.sortBy { it.first }
        val merged = ArrayList<Pair<Long, String>>(collected.size)
        collected.forEach { item ->
            val adjusted = (item.first - offsetMs).coerceAtLeast(0L)
            if (merged.isNotEmpty() && merged.last().first == adjusted) {
                return@forEach
            }
            merged.add(adjusted to item.second)
        }

        val lines = ArrayList<LyricLine>(merged.size)
        merged.forEachIndexed { index, (start, content) ->
            val nextStart = if (index + 1 < merged.size) merged[index + 1].first else start + TAIL_MS
            val end = nextStart.coerceAtLeast(start + 200L)
            lines.add(buildLine(start, end, content, hasNativeWords))
        }

        return LyricDoc(lines, title, artist, hasNativeWords)
    }

    private fun buildLine(
        start: Long,
        end: Long,
        content: String,
        allowNative: Boolean
    ): LyricLine {
        val clean = content.trim()
        val words = if (allowNative && wordTag.containsMatchIn(clean)) {
            parseWords(clean, start, end)
        } else {
            emptyList()
        }
        val text = if (words.isEmpty()) clean else words.joinToString("") { it.text }
        val finalWords = if (words.isEmpty()) interpolate(text, start, end) else words
        return LyricLine(start, end, text, finalWords)
    }

    private fun parseWords(content: String, lineStart: Long, lineEnd: Long): List<LyricWord> {
        val matches = wordTag.findAll(content).toList()
        if (matches.isEmpty()) return emptyList()

        val out = ArrayList<LyricWord>(matches.size)
        matches.forEachIndexed { i, m ->
            val start = parseTime(m.groupValues[1], m.groupValues[2], m.groupValues[3])
            val textStart = m.range.last + 1
            val textEnd = if (i + 1 < matches.size) matches[i + 1].range.first else content.length
            val text = content.substring(textStart, textEnd)
            if (text.isEmpty()) return@forEachIndexed

            val end = if (i + 1 < matches.size) {
                parseTime(
                    matches[i + 1].groupValues[1],
                    matches[i + 1].groupValues[2],
                    matches[i + 1].groupValues[3]
                )
            } else {
                lineEnd.coerceAtLeast(start + 300L)
            }
            out.add(LyricWord(text, start, end.coerceAtLeast(start + 60L)))
        }
        if (out.isNotEmpty() && out.first().startMs > lineStart + 100L) {
            out.add(0, LyricWord("", lineStart, out.first().startMs))
        }
        return out
    }

    fun interpolate(text: String, start: Long, end: Long): List<LyricWord> {
        if (text.isEmpty()) return emptyList()

        val tokens = tokenize(text)
        if (tokens.isEmpty()) return emptyList()

        val weights = tokens.map { tokenWeight(it) }
        val totalWeight = weights.sum().coerceAtLeast(0.01f)
        val span = (end - start).coerceAtLeast(100L)

        val out = ArrayList<LyricWord>(tokens.size)
        var acc = 0f
        tokens.forEachIndexed { i, token ->
            val w = weights[i]
            val s = start + (span * (acc / totalWeight)).toLong()
            acc += w
            val e = start + (span * (acc / totalWeight)).toLong()
            out.add(LyricWord(token, s, e.coerceAtLeast(s + 30L)))
        }
        return out
    }

    private fun tokenize(text: String): List<String> {
        val out = ArrayList<String>()
        val buffer = StringBuilder()

        fun flush() {
            if (buffer.isNotEmpty()) {
                out.add(buffer.toString())
                buffer.clear()
            }
        }

        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            val charCount = Character.charCount(cp)
            val ch = String(Character.toChars(cp))

            when {
                isLatinWordChar(cp) -> buffer.append(ch)
                else -> {
                    flush()
                    out.add(ch)
                }
            }
            i += charCount
        }
        flush()
        return out
    }

    private fun isLatinWordChar(cp: Int): Boolean {
        return (cp in 0x30..0x39) || (cp in 0x41..0x5A) || (cp in 0x61..0x7A) ||
            cp == 0x27
    }

    private fun tokenWeight(token: String): Float {
        if (token.isBlank()) return 0.35f
        val first = token.codePointAt(0)
        val isCjk = when (first) {
            in 0x4E00..0x9FFF, in 0x3040..0x30FF, in 0x3400..0x4DBF -> true
            else -> false
        }
        return when {
            isCjk -> 1.0f
            isLatinWordChar(first) -> 0.55f + token.length * 0.16f
            else -> 0.35f
        }
    }

    private fun parseTime(min: String, sec: String, frac: String?): Long {
        val m = min.toLongOrNull() ?: 0L
        val s = sec.toLongOrNull() ?: 0L
        val f = when {
            frac.isNullOrEmpty() -> 0L
            frac.length == 1 -> frac.toLong() * 100
            frac.length == 2 -> frac.toLong() * 10
            else -> frac.take(3).toLong()
        }
        return m * 60_000 + s * 1000 + f
    }
}

internal data class HttpBody(
    val code: Int,
    val text: String?
) {
    val ok: Boolean get() = code in 200..299 && text != null

    val gatewayDown: Boolean
        get() = code == 0 || (code in 400..499 && code != 404) || code >= 500
}

internal fun httpGetRaw(
    url: String,
    ua: String,
    referer: String? = null,
    timeoutMs: Int = 12_000,
    accept: String = "application/json"
): HttpBody {
    var conn: HttpURLConnection? = null
    return try {
        conn = GatewayTls.open(url).apply {
            requestMethod = "GET"
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            setRequestProperty("Accept", accept)
            setRequestProperty("User-Agent", ua)
            referer?.let { setRequestProperty("Referer", it) }
            instanceFollowRedirects = true
        }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = stream?.let {
            BufferedReader(InputStreamReader(it, Charsets.UTF_8)).use { r -> r.readText() }
        }
        HttpBody(code, text)
    } catch (e: Exception) {
        HttpBody(0, null)
    } finally {
        runCatching { conn?.disconnect() }
    }
}

object GatewayTls {

    private val RELAXED_HOSTS = setOf(
        "yy.zddyr.top",
        "api.vkeys.cn",
        "music-api.gdstudio.xyz"
    )

    private val relaxedFactory: SSLSocketFactory by lazy {
        val trustAll = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        SSLContext.getInstance("TLS").apply {
            init(null, arrayOf<TrustManager>(trustAll), SecureRandom())
        }.socketFactory
    }

    private fun isRelaxed(url: String): Boolean {
        val host = runCatching { URL(url).host }.getOrNull() ?: return false
        return RELAXED_HOSTS.any { host == it || host.endsWith(".$it") }
    }

    fun open(url: String): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        if (conn is HttpsURLConnection && isRelaxed(url)) {
            runCatching {
                conn.sslSocketFactory = relaxedFactory
                conn.hostnameVerifier = HostnameVerifier { _, _ -> true }
            }
        }
        return conn
    }
}

enum class StreamFail {
    GATEWAY,
    NO_RESOURCE,
    BAD_SONG
}

sealed class StreamResult {
    data class Ok(val url: String, val from: String) : StreamResult()

    data class Fail(
        val kind: StreamFail,
        val detail: String = "",
        val tried: String = "",
        val steps: List<String> = emptyList()
    ) : StreamResult()

    val urlOrNull: String? get() = (this as? Ok)?.url
}

object SourceHealth {

    private const val THRESHOLD = 3
    private const val COOLDOWN_MS = 30_000L

    private val failStreak = ConcurrentHashMap<NetProvider, Int>()
    private val blockedUntil = ConcurrentHashMap<NetProvider, Long>()
    private val lastDetail = ConcurrentHashMap<NetProvider, String>()

    fun isAvailable(src: NetProvider, now: Long = System.currentTimeMillis()): Boolean =
        (blockedUntil[src] ?: 0L) <= now

    fun markOk(src: NetProvider) {
        failStreak.remove(src)
        blockedUntil.remove(src)
    }

    fun markGatewayFail(src: NetProvider, detail: String) {
        lastDetail[src] = detail
        val n = (failStreak[src] ?: 0) + 1
        failStreak[src] = n
        if (n >= THRESHOLD) {
            blockedUntil[src] = System.currentTimeMillis() + COOLDOWN_MS
            failStreak[src] = 0
        }
    }

    fun markNoResource(src: NetProvider) {
        if ((failStreak[src] ?: 0) > 0) failStreak[src] = 0
    }

    fun statusText(src: NetProvider): String? {
        val until = blockedUntil[src] ?: return null
        if (until <= System.currentTimeMillis()) return null
        val sec = ((until - System.currentTimeMillis()) / 1000).coerceAtLeast(1)
        val why = lastDetail[src]?.takeIf { it.isNotBlank() } ?: "网关拒绝请求"
        return "$why，${sec}s 后重试"
    }

    fun describeAll(): String = NetProvider.entries.joinToString(" · ") { p ->
        val s = statusText(p)
        if (s == null) "${p.name}:正常" else "${p.name}:不可用($s)"
    }
}

object SourceThrottle {

    private val MIN_INTERVAL = mapOf(
        NetProvider.NETEASE to 900L,
        NetProvider.KUGOU to 120L,
        NetProvider.KUWO to 120L,
        NetProvider.QQ to 120L
    )

    private val gates = NetProvider.entries.associateWith { Mutex() }
    private val lastHit = ConcurrentHashMap<NetProvider, Long>()

    suspend fun await(p: NetProvider) {
        val gate = gates[p] ?: return
        val minGap = MIN_INTERVAL[p] ?: 100L
        gate.withLock {
            val last = lastHit[p] ?: 0L
            val elapsed = System.currentTimeMillis() - last
            if (elapsed < minGap) delay(minGap - elapsed)
            lastHit[p] = System.currentTimeMillis()
        }
    }
}

object SongMatcher {

    const val MERGE_THRESHOLD = 0.80

    const val TITLE_HARD_MIN = 0.55

    private const val DURATION_HARD_GAP_MS = 30_000L

    private val BRACKET = Regex("[（(【\\[［][^）)】\\]］]*[）)】\\]］]")

    private val NOISE = listOf(
        "instrumental version", "acoustic version", "live version",
        "explicit version", "extended version", "remastered version",
        "伴奏版", "纯音乐版", "现场版", "完整版", "高音质版", "无损版",
        "原唱版", "原版", "新版", "翻唱版", "女声版", "男声版",
        "instrumental", "acoustic", "live", "remix", "cover", "explicit",
        "extended", "remastered", "伴奏", "纯音乐", "纯歌", "翻唱", "现场",
        "完整", "高音质", "无损", "官方", "原唱", "新版", "女声", "男声",
        "demo", "version", "edit", "mix", "feat", "ft"
    )

    private val PUNCT = Regex("[\\p{P}\\p{S}\\s]+")

    fun normTitle(raw: String): String {
        if (raw.isBlank()) return ""
        var s = toHalfWidth(raw).lowercase()
        s = BRACKET.replace(s, " ")
        for (w in NOISE) {
            s = s.replace(w, " ")
        }
        s = PUNCT.replace(s, "")
        return s.trim()
    }

    fun normArtist(raw: String): String {
        if (raw.isBlank()) return ""
        var s = toHalfWidth(raw).lowercase()
        for (sep in listOf(" feat. ", " feat ", " ft. ", " ft ", "/", "、", "，", ",", "&", "×", "x")) {
            val i = s.indexOf(sep)
            if (i > 0) {
                s = s.substring(0, i)
                break
            }
        }
        s = BRACKET.replace(s, " ")
        s = PUNCT.replace(s, "")
        return s.trim()
    }

    private fun toHalfWidth(s: String): String {
        val sb = StringBuilder(s.length)
        for (c in s) {
            val code = c.code
            if (code == 0x3000) {
                sb.append(' ')
            } else if (code in 0xFF01..0xFF5E) {
                sb.append((code - 0xFEE0).toChar())
            } else {
                sb.append(c)
            }
        }
        return sb.toString()
    }

    fun titleSim(a: String, b: String): Double {
        val x = normTitle(a)
        val y = normTitle(b)
        if (x.isEmpty() || y.isEmpty()) return 0.0
        if (x == y) return 1.0
        if (x.length >= 2 && y.length >= 2 && (x.contains(y) || y.contains(x))) {
            val minLen = minOf(x.length, y.length)
            if (minLen >= 3 || minOf(x.length, y.length) >= maxOf(x.length, y.length) / 2) {
                return 0.88
            }
        }
        val ratio = levenshteinRatio(x, y)
        return ratio * 0.80
    }

    fun artistSim(a: String, b: String): Double {
        val x = normArtist(a)
        val y = normArtist(b)
        if (x.isEmpty() || y.isEmpty()) return 0.0
        if (x == y) return 1.0
        if (x.length >= 2 && y.length >= 2 && (x.contains(y) || y.contains(x))) return 0.85
        return levenshteinRatio(x, y) * 0.75
    }

    fun durationSim(aMs: Long, bMs: Long): Double {
        if (aMs <= 0L || bMs <= 0L) return 0.6
        val diff = kotlin.math.abs(aMs - bMs)
        return when {
            diff <= 2_000L -> 1.0
            diff <= 5_000L -> 0.9
            diff <= 15_000L -> 0.55
            diff <= DURATION_HARD_GAP_MS -> 0.2
            else -> 0.0
        }
    }

    fun score(
        aTitle: String, aArtist: String, aDuration: Long,
        bTitle: String, bArtist: String, bDuration: Long
    ): Double {
        val t = titleSim(aTitle, bTitle)
        if (t < TITLE_HARD_MIN) return 0.0
        val ar = artistSim(aArtist, bArtist)
        val d = durationSim(aDuration, bDuration)
        return 0.58 * t + 0.30 * ar + 0.12 * d
    }

    fun levenshteinRatio(a: String, b: String): Double {
        if (a == b) return 1.0
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val n = a.length
        val m = b.length
        if (n > 120 || m > 120) return 0.0
        var prev = IntArray(m + 1) { it }
        val cur = IntArray(m + 1)
        for (i in 1..n) {
            cur[0] = i
            for (j in 1..m) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(cur[j - 1] + 1, prev[j] + 1, prev[j - 1] + cost)
            }
            prev = cur.copyOf()
        }
        val dist = prev[m]
        return 1.0 - dist.toDouble() / maxOf(n, m)
    }

    fun pickSameSong(target: Song, candidates: List<Song>): Song? {
        if (candidates.isEmpty()) return null
        var best: Song? = null
        var bestScore = 0.0

        for (c in candidates) {
            val t = titleSim(target.title, c.title)
            if (t < CROSS_TITLE_MIN) continue
            val a = artistSim(target.artist, c.artist)
            if (a < CROSS_ARTIST_MIN) continue
            val d = durationSim(target.durationMs, c.durationMs)
            if (d < CROSS_DURATION_MIN) continue

            val score = 0.58 * t + 0.30 * a + 0.12 * d
            if (score > bestScore) {
                bestScore = score
                best = c
            }
        }
        return best
    }

    const val CROSS_TITLE_MIN = 0.80

    const val CROSS_ARTIST_MIN = 0.70

    const val CROSS_DURATION_MIN = 0.20
}

object KuGouApi {

    const val STREAM_BASE = "https://yy.zddyr.top/lx/api/"

    const val SEARCH_BASE = "https://songsearch.kugou.com/song_search_v2"

    private const val LYRIC_SEARCH = "http://krcs.kugou.com/search"
    private const val LYRIC_DOWN = "http://lyrics.kugou.com/download"

    private const val TIMEOUT = 12_000

    private const val UA =
        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/120.0.0.0 Mobile Safari/537.36"

    private val QUALITY_CHAIN = listOf("320kmp3", "flac", "128kmp3")

    suspend fun search(keyword: String, page: Int = 1, pageSize: Int = 20): List<Song> =
        withContext(Dispatchers.IO) {
            if (keyword.isBlank()) return@withContext emptyList()

            val q = URLEncoder.encode(keyword, "UTF-8")
            val url = "$SEARCH_BASE?keyword=$q&page=$page&pagesize=$pageSize" +
                "&platform=WebFilter&userid=-1&clientver=2000" +
                "&iscorrection=1&privilege_filter=0"
            val raw = getText(url) ?: return@withContext emptyList()
            val json = runCatching { JSONObject(raw) }.getOrNull()
                ?: return@withContext emptyList()
            val lists = json.optJSONObject("data")?.optJSONArray("lists")
                ?: return@withContext emptyList()

            val rows = ArrayList<Pair<Song, Boolean>>()
            val seen = HashSet<String>()

            for (i in 0 until lists.length()) {
                val o = lists.optJSONObject(i) ?: continue
                val hash = o.optString("FileHash")
                if (hash.isBlank()) continue

                val title = o.optString("SongName").ifBlank { "未知曲目" }
                val artist = o.optString("SingerName").ifBlank { "未知艺人" }

                if (title.contains("伴奏") || title.contains("纯音乐")) continue

                val sig = "$title|$artist"
                if (!seen.add(sig)) continue

                val song = Song(
                    key = "kg:$hash",
                    title = title,
                    artist = artist,
                    album = o.optString("AlbumName").ifBlank { "未知专辑" },
                    durationMs = o.optInt("Duration", 0).toLong() * 1000,
                    uri = "",
                    cover = o.optString("Image")
                        .replace("{size}", "400")
                        .takeIf { it.startsWith("http") },
                    source = SongSource.NET,
                    extHash = hash
                )
                rows.add(song to (o.optInt("IsOriginal", 0) == 1))
            }

            rows.sortedByDescending { it.second }.map { it.first }
        }

    suspend fun streamUrlEx(hash: String, name: String = ""): StreamResult =
        withContext(Dispatchers.IO) {
            if (hash.isBlank()) {
                return@withContext StreamResult.Fail(StreamFail.BAD_SONG, "缺少 hash")
            }

            val qName = URLEncoder.encode(name, "UTF-8")
            val qHash = URLEncoder.encode(hash, "UTF-8")

            var gatewayIssue: String? = null
            val tried = StringBuilder()

            for (quality in QUALITY_CHAIN) {
                val url = "$STREAM_BASE?source=kg&name=$qName&songmid=$qHash&quality=$quality"
                val res = httpGetRaw(url, UA)

                if (!res.ok) {
                    if (res.gatewayDown) {
                        gatewayIssue = "HTTP ${res.code}"
                        break
                    }
                    tried.append("$quality:${res.code} ")
                    continue
                }

                val json = runCatching { JSONObject(res.text ?: "") }.getOrNull()
                if (json == null) {
                    tried.append("$quality:非JSON ")
                    continue
                }

                val play = json.optString("url")
                val code = readCode(json)

                if (code == 200 && play.startsWith("http")) {
                    return@withContext StreamResult.Ok(play, "kg")
                }

                if (code == 403 || code == 429 || code >= 500) {
                    gatewayIssue = json.optString("msg")
                        .ifBlank { json.optString("upstream_msg") }
                        .ifBlank { "code=$code" }
                    break
                }

                tried.append("$quality:${json.optString("msg").take(10)} ")
            }

            if (gatewayIssue != null) {
                StreamResult.Fail(StreamFail.GATEWAY, gatewayIssue, "kg:$gatewayIssue")
            } else {
                StreamResult.Fail(StreamFail.NO_RESOURCE, "网关没给直链", "kg:${tried.toString().trim()}")
            }
        }

    suspend fun streamUrl(hash: String, name: String = ""): String? =
        streamUrlEx(hash, name).urlOrNull

    private fun readCode(json: JSONObject): Int {
        val raw = json.opt("code") ?: return -1
        return when (raw) {
            is Number -> raw.toInt()
            is String -> raw.trim().toIntOrNull() ?: -1
            else -> -1
        }
    }

    suspend fun lyric(hash: String, title: String, durationMs: Long): LyricDoc? =
        withContext(Dispatchers.IO) {
            if (hash.isBlank()) return@withContext null

            val sec = (durationMs / 1000).coerceAtLeast(0)
            val qName = URLEncoder.encode(title, "UTF-8")
            val qHash = URLEncoder.encode(hash, "UTF-8")

            val searchRaw = getText(
                "$LYRIC_SEARCH?ver=1&man=yes&client=mobi&keyword=$qName" +
                    "&duration=$sec&hash=$qHash"
            ) ?: return@withContext null

            val cands = runCatching {
                JSONObject(searchRaw).optJSONArray("candidates")
            }.getOrNull() ?: return@withContext null
            if (cands.length() == 0) return@withContext null

            val best = pickCandidate(cands, title) ?: return@withContext null
            val id = best.optString("id")
            val key = best.optString("accesskey")
            if (id.isBlank() || key.isBlank()) return@withContext null

            val raw = getText(
                "$LYRIC_DOWN?ver=1&client=pc&id=$id&accesskey=$key&fmt=lrc&charset=utf8"
            ) ?: return@withContext null

            val b64 = runCatching {
                JSONObject(raw).optString("content")
            }.getOrNull()
            if (b64.isNullOrBlank()) return@withContext null

            val text = runCatching {
                String(Base64.decode(b64, Base64.DEFAULT), Charsets.UTF_8)
            }.getOrNull() ?: return@withContext null

            val doc = LyricParser.parse(text)
            if (doc.isEmpty) null else doc
        }

    private fun pickCandidate(cands: JSONArray, title: String): JSONObject? {
        val want = normal(title)
        for (i in 0 until cands.length()) {
            val o = cands.optJSONObject(i) ?: continue
            if (normal(o.optString("song")) == want) return o
        }
        return cands.optJSONObject(0)
    }

    private fun normal(s: String): String = s
        .replace(Regex("[(（\\[【].*?[)）\\]】]"), "")
        .replace(Regex("[\\s\\-—_·.,，。!！?？'\"“”]"), "")
        .lowercase()

    suspend fun ping(): Boolean = withContext(Dispatchers.IO) {
        val raw = getText("$SEARCH_BASE?keyword=test&page=1&pagesize=1" +
            "&platform=WebFilter&userid=-1&clientver=2000")
        raw != null && raw.contains("\"status\":1")
    }

    private fun getText(url: String): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = GatewayTls.open(url).apply {
                requestMethod = "GET"
                connectTimeout = TIMEOUT
                readTimeout = TIMEOUT
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", UA)
                instanceFollowRedirects = true
            }
            if (conn.responseCode !in 200..299) return null
            BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use {
                it.readText()
            }
        } catch (e: Exception) {
            null
        } finally {
            runCatching { conn?.disconnect() }
        }
    }
}

data class KuwoInfo(
    val cover: String?,
    val album: String?,
    val lyric: LyricDoc?
)

object KuwoApi {

    const val SEARCH_BASE = "http://search.kuwo.cn/r.s"
    const val STREAM_BASE = "http://antiserver.kuwo.cn/anti.s"
    private const val INFO_BASE = "http://m.kuwo.cn/newh5/singles/songinfoandlrc"

    private const val COVER_BASE = "http://img1.kwcdn.kuwo.cn/star/albumcover"

    private const val REFERER = "https://www.kuwo.cn/"
    private const val TIMEOUT = 12_000

    private const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    private const val TRIAL_RATIO = 0.5

    private const val BYTES_PER_SEC = 16_000.0

    private val searchCache = ConcurrentHashMap<String, Pair<List<Song>, Long>>()
    private const val SEARCH_TTL = 3 * 60 * 1000L

    suspend fun search(keyword: String, page: Int = 1, pageSize: Int = 20): List<Song> =
        withContext(Dispatchers.IO) {
            if (keyword.isBlank()) return@withContext emptyList()

            val cacheKey = "$keyword|$page|$pageSize"
            searchCache[cacheKey]
                ?.takeIf { System.currentTimeMillis() - it.second < SEARCH_TTL }
                ?.let { return@withContext it.first }

            val q = URLEncoder.encode(keyword, "UTF-8")
            val url = "$SEARCH_BASE?client=kt&all=$q" +
                "&pn=${((page - 1) * pageSize).coerceAtLeast(0)}&rn=$pageSize&uid=0" +
                "&ver=kwplayer_ar_9.2.2.1&vipver=1&show_copyright_off=1&newver=1" +
                "&ft=music&cluster=0&strategy=2012&encoding=utf8&rformat=json&vermerge=1&mobi=1"

            val res = httpGetRaw(url, UA, REFERER, TIMEOUT, accept = "*/*")
            if (!res.ok) return@withContext emptyList()

            val songs = parseAbsList(res.text.orEmpty())
            if (songs.isNotEmpty()) {
                if (searchCache.size > 40) searchCache.clear()
                searchCache[cacheKey] = songs to System.currentTimeMillis()
            }
            songs
        }

    private fun parseAbsList(raw: String): List<Song> {
        val start = raw.indexOf("abslist")
        if (start < 0) return emptyList()
        val open = raw.indexOf('[', start)
        if (open < 0) return emptyList()

        val out = ArrayList<Song>()
        val seen = HashSet<String>()
        var i = open + 1
        var depth = 0

        while (i < raw.length) {
            val c = raw[i]
            when (c) {
                '{' -> {
                    if (depth == 0) {
                        val end = matchBrace(raw, i)
                        if (end < 0) break
                        val block = raw.substring(i, end + 1)
                        buildSong(block)?.let { s ->
                            if (seen.add(s.key)) out.add(s)
                        }
                        i = end + 1
                        continue
                    }
                    depth++
                }

                ']' -> if (depth == 0) break
            }
            i++
        }
        return out
    }

    private fun matchBrace(s: String, from: Int): Int {
        var depth = 0
        var i = from
        var quote = 0.toChar()
        while (i < s.length) {
            val c = s[i]
            if (quote != 0.toChar()) {
                if (c == '\\') i++
                else if (c == quote) quote = 0.toChar()
            } else when (c) {
                '\'', '"' -> quote = c
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return i
                }
            }
            i++
        }
        return -1
    }

    private fun buildSong(block: String): Song? {
        val rid = field(block, "MUSICRID").replace("MUSIC_", "").trim()
        if (rid.isBlank()) return null

        val title = cleanHtml(field(block, "SONGNAME").ifBlank { field(block, "NAME") })
            .ifBlank { return null }
        val artist = cleanHtml(field(block, "ARTIST")).ifBlank { "未知艺人" }

        if (title.contains("伴奏") || title.contains("纯音乐")) return null

        val sec = field(block, "DURATION").trim().toLongOrNull() ?: 0L

        return Song(
            key = "kw:$rid",
            title = title,
            artist = artist,
            album = cleanHtml(field(block, "ALBUM")).ifBlank { "未知专辑" },
            durationMs = sec * 1000,
            uri = "",
            cover = albumCover(block),
            source = SongSource.KUWO,
            extHash = rid
        )
    }

    private fun albumCover(block: String): String? {
        val short = field(block, "web_albumpic_short").trim()
        if (short.isBlank() || !short.contains('/')) return null
        val tail = short.substringAfter('/')
        if (tail.isBlank() || !tail.contains('.')) return null
        return "$COVER_BASE/500/$tail"
    }

    private fun field(block: String, key: String): String {
        Regex("'$key'\\s*:\\s*'((?:\\\\.|[^'\\\\])*)'").find(block)?.let {
            return it.groupValues[1].replace("\\'", "'").replace("\\\\", "\\")
        }
        Regex("\"$key\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"").find(block)?.let {
            return it.groupValues[1].replace("\\\"", "\"").replace("\\\\", "\\")
        }
        Regex("['\"]$key['\"]\\s*:\\s*(-?[\\d.]+)").find(block)?.let { return it.groupValues[1] }
        return ""
    }

    private fun cleanHtml(s: String): String =
        s.replace("&nbsp;", " ").replace("\\u0026", "&").replace("\\\\", "").trim()

    suspend fun streamUrlEx(rid: String, durationMs: Long): StreamResult =
        withContext(Dispatchers.IO) {
            if (rid.isBlank()) {
                return@withContext StreamResult.Fail(StreamFail.BAD_SONG, "缺少 rid")
            }

            val q = URLEncoder.encode("MUSIC_$rid", "UTF-8")
            val res = httpGetRaw(
                "$STREAM_BASE?type=convert_url&format=mp3&response=url&rid=$q",
                UA, REFERER, TIMEOUT, accept = "*/*"
            )

            if (!res.ok) {
                return@withContext StreamResult.Fail(
                    StreamFail.GATEWAY, "HTTP ${res.code}", "kw:${res.code}"
                )
            }

            val body = res.text.orEmpty().trim()
            if (!body.startsWith("http")) {
                return@withContext StreamResult.Fail(
                    StreamFail.NO_RESOURCE,
                    body.take(20).ifBlank { "无直链" },
                    "kw:${body.take(12)}"
                )
            }

            val url = body.lineSequence().firstOrNull { it.startsWith("http") }?.trim() ?: body
            if (!url.startsWith("http")) {
                return@withContext StreamResult.Fail(StreamFail.NO_RESOURCE, "无直链", "kw:空")
            }

            val total = probeTotalBytes(url)
            if (total > 0) {
                val expect = if (durationMs > 0) {
                    durationMs / 1000.0 * BYTES_PER_SEC
                } else {
                    2_000_000.0
                }
                val ratio = if (expect > 0) total / expect else 1.0
                if (ratio < TRIAL_RATIO) {
                    val gotSec = (total / BYTES_PER_SEC).toInt().coerceAtLeast(1)
                    return@withContext StreamResult.Fail(
                        StreamFail.NO_RESOURCE,
                        "仅试听 ${gotSec}s",
                        "kw:试听${gotSec}s"
                    )
                }
            }

            StreamResult.Ok(url, "kw")
        }

    suspend fun streamUrl(rid: String, durationMs: Long = 0L): String? =
        streamUrlEx(rid, durationMs).urlOrNull

    private fun probeTotalBytes(url: String): Long {
        var conn: HttpURLConnection? = null
        return try {
            conn = GatewayTls.open(url).apply {
                requestMethod = "GET"
                connectTimeout = 8_000
                readTimeout = 8_000
                setRequestProperty("Range", "bytes=0-0")
                setRequestProperty("User-Agent", UA)
                setRequestProperty("Referer", REFERER)
            }
            val code = conn.responseCode
            if (code !in 200..299) return 0L

            val range = conn.getHeaderField("Content-Range")
            val fromRange = range?.substringAfterLast('/')?.trim()?.toLongOrNull()
            runCatching { conn.inputStream?.use { it.read() } }

            fromRange
                ?: conn.getRequestProperty("Content-Length")?.toLongOrNull()
                ?: conn.getHeaderField("Content-Length")?.toLongOrNull()
                ?: 0L
        } catch (e: Exception) {
            0L
        } finally {
            runCatching { conn?.disconnect() }
        }
    }

    suspend fun songInfo(rid: String): KuwoInfo? = withContext(Dispatchers.IO) {
        if (rid.isBlank()) return@withContext null

        repeat(3) { attempt ->
            val res = httpGetRaw("$INFO_BASE?musicId=$rid", UA, REFERER, TIMEOUT)
            if (res.ok) {
                val text = res.text.orEmpty()
                if (text.contains("lineLyric") || text.contains("\"pic\"")) {
                    return@withContext parseInfo(text)
                }
            }
            if (attempt < 2) delay(320)
        }
        null
    }

    private fun parseInfo(text: String): KuwoInfo {
        val cover = pick(text, "pic")
            ?.takeIf { it.startsWith("http") }
            ?.replace("/240/", "/500/")

        val album = pick(text, "album")?.let { cleanHtml(it) }

        val times = Regex("[\"']time[\"']\\s*:\\s*[\"']?([\\d.]+)")
            .findAll(text).map { it.groupValues[1] }.toList()
        val lines = Regex("[\"']lineLyric[\"']\\s*:\\s*[\"']([^\"']*)")
            .findAll(text).map { it.groupValues[1] }.toList()

        val lrc = StringBuilder()
        val n = minOf(times.size, lines.size)
        for (i in 0 until n) {
            val sec = times[i].toDoubleOrNull() ?: continue
            val line = lines[i]
                .replace("\\n", "\n")
                .replace("\\\"", "\"")
                .replace("\\\\", "")
                .trim()
            if (line.isEmpty()) continue
            lrc.append(formatStamp(sec)).append(line).append('\n')
        }

        val doc = if (lrc.isNotBlank()) {
            runCatching { LyricParser.parse(lrc.toString()) }.getOrNull()
                ?.takeIf { !it.isEmpty }
        } else null

        return KuwoInfo(cover = cover, album = album, lyric = doc)
    }

    private fun pick(text: String, key: String): String? {
        Regex("\"$key\"\\s*:\\s*\"([^\"]*)\"").find(text)?.let { return it.groupValues[1] }
        Regex("'$key'\\s*:\\s*'([^']*)'").find(text)?.let { return it.groupValues[1] }
        return null
    }

    private fun formatStamp(sec: Double): String {
        val total = sec.coerceAtLeast(0.0)
        val m = (total / 60).toInt()
        val s = total - m * 60
        return String.format(java.util.Locale.US, "[%02d:%05.2f]", m, s)
    }

    suspend fun ping(): Boolean = withContext(Dispatchers.IO) {
        val q = URLEncoder.encode("test", "UTF-8")
        val res = httpGetRaw(
            "$SEARCH_BASE?client=kt&all=$q&pn=0&rn=1&uid=0&ver=kwplayer_ar_9.2.2.1" +
                "&vipver=1&show_copyright_off=1&newver=1&ft=music&cluster=0" +
                "&strategy=2012&encoding=utf8&rformat=json&vermerge=1&mobi=1",
            UA, REFERER, TIMEOUT, accept = "*/*"
        )
        res.ok && res.text.orEmpty().contains("abslist")
    }
}

object NeteaseApi {

    const val DEFAULT_BASE = "https://music-api.gdstudio.xyz/api.php"

    private const val NETEASE_ORIGIN = "https://music.163.com"

    private const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/120.0.0.0 Safari/537.36"

    private const val TIMEOUT = 12_000

    @Volatile
    var baseUrl: String = DEFAULT_BASE

    @Volatile
    var customBase: String = ""

    val enabled: Boolean get() = baseUrl.isNotBlank() || customBase.isNotBlank()

    suspend fun search(keyword: String, page: Int = 1, pageSize: Int = 20): List<Song> =
        withContext(Dispatchers.IO) {
            if (!enabled || keyword.isBlank()) return@withContext emptyList()

            val q = URLEncoder.encode(keyword, "UTF-8")
            val url = "$baseUrl?types=search&source=netease&name=$q" +
                "&count=$pageSize&pages=$page"
            val raw = getText(url, UA) ?: return@withContext emptyList()

            val arr = runCatching { JSONArray(raw) }.getOrNull()
                ?: runCatching { JSONObject(raw).optJSONArray("data") }.getOrNull()
                ?: return@withContext emptyList()

            data class Raw(val id: String, val title: String, val artist: String, val album: String)

            val raws = ArrayList<Raw>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.optString("id").ifBlank { o.optString("url_id") }
                if (id.isBlank()) continue

                val artist = run {
                    val a = o.opt("artist")
                    when (a) {
                        is JSONArray -> (0 until a.length())
                            .joinToString(" / ") { a.optString(it) }
                        is String -> a
                        else -> ""
                    }
                }

                raws.add(
                    Raw(
                        id = id,
                        title = o.optString("name").ifBlank { "未知曲目" },
                        artist = artist.ifBlank { "未知艺人" },
                        album = o.optString("album").ifBlank { "未知专辑" }
                    )
                )
            }
            if (raws.isEmpty()) return@withContext emptyList()

            val details = fetchDetails(raws.map { it.id })

            raws.map { r ->
                val d = details[r.id]
                Song(
                    key = "ne:${r.id}",
                    title = d?.title?.takeIf { it.isNotBlank() } ?: r.title,
                    artist = d?.artist?.takeIf { it.isNotBlank() } ?: r.artist,
                    album = d?.album?.takeIf { it.isNotBlank() } ?: r.album,
                    durationMs = d?.durationMs ?: 0L,
                    uri = "",
                    cover = d?.cover,
                    source = SongSource.NETEASE,
                    extHash = r.id
                )
            }
        }

    suspend fun officialSearch(
        keyword: String,
        page: Int = 1,
        pageSize: Int = 20
    ): List<Song> = withContext(Dispatchers.IO) {
        if (keyword.isBlank()) return@withContext emptyList()
        val q = URLEncoder.encode(keyword, "UTF-8")
        val offset = (page - 1).coerceAtLeast(0) * pageSize
        val url = "$NETEASE_ORIGIN/api/search/get?s=$q&type=1&limit=$pageSize&offset=$offset"

        val raw = getText(url, UA, referer = "$NETEASE_ORIGIN/") ?: return@withContext emptyList()
        val json = runCatching { JSONObject(raw) }.getOrNull() ?: return@withContext emptyList()
        val songs = json.optJSONObject("result")?.optJSONArray("songs")
            ?: return@withContext emptyList()

        val out = ArrayList<Song>()
        for (i in 0 until songs.length()) {
            val s = songs.optJSONObject(i) ?: continue
            val id = s.optLong("id", 0L)
            if (id <= 0) continue

            val artist = buildString {
                val ar = s.optJSONArray("artists")
                if (ar != null) {
                    for (j in 0 until ar.length()) {
                        if (j > 0) append(" / ")
                        append(ar.optJSONObject(j)?.optString("name").orEmpty())
                    }
                }
            }
            val al = s.optJSONObject("album")

            out.add(
                Song(
                    key = "ne:$id",
                    title = s.optString("name").ifBlank { "未知曲目" },
                    artist = artist.ifBlank { "未知艺人" },
                    album = al?.optString("name").orEmpty(),
                    durationMs = s.optLong("duration", 0L),
                    uri = "",
                    cover = al?.optString("picUrl")?.takeIf { it.startsWith("http") },
                    source = SongSource.NETEASE,
                    extHash = id.toString()
                )
            )
        }
        out
    }

    private data class Detail(
        val title: String,
        val artist: String,
        val album: String,
        val durationMs: Long,
        val cover: String?
    )

    private fun fetchDetails(ids: List<String>): Map<String, Detail> {
        val out = HashMap<String, Detail>()
        for (chunk in ids.chunked(30)) {
            val param = chunk.joinToString(",", "[", "]")
            val enc = URLEncoder.encode(param, "UTF-8")
            val raw = getText(
                "$NETEASE_ORIGIN/api/song/detail?ids=$enc",
                UA,
                referer = "$NETEASE_ORIGIN/"
            ) ?: continue

            val json = runCatching { JSONObject(raw) }.getOrNull() ?: continue
            val songs = json.optJSONArray("songs") ?: continue

            for (i in 0 until songs.length()) {
                val s = songs.optJSONObject(i) ?: continue
                val id = s.optString("id")
                if (id.isBlank()) continue

                val al = s.optJSONObject("album")
                val ar = s.optJSONArray("artists")
                val artist = buildString {
                    if (ar != null) {
                        for (j in 0 until ar.length()) {
                            if (j > 0) append(" / ")
                            append(ar.optJSONObject(j)?.optString("name").orEmpty())
                        }
                    }
                }

                out[id] = Detail(
                    title = s.optString("name"),
                    artist = artist,
                    album = al?.optString("name").orEmpty(),
                    durationMs = s.optLong("duration", 0L),
                    cover = al?.optString("picUrl")?.takeIf { it.startsWith("http") }
                )
            }
        }
        return out
    }

    private suspend fun customStreamUrl(id: String, preferBr: Int): String? {
        val base = customBase.trim().trimEnd('/')
        if (base.isEmpty()) return null

        val levels = if (preferBr >= 320) listOf("exhigh", "standard") else listOf("standard")
        for (level in levels) {
            val res = httpGetRaw("$base/song/url/v1?id=$id&level=$level", UA, null, 8_000)
            if (!res.ok) continue
            val arr = runCatching { JSONObject(res.text ?: "") }.getOrNull()
                ?.optJSONArray("data") ?: continue
            val url = arr.optJSONObject(0)?.optString("url").orEmpty()
            if (url.startsWith("http")) return url
        }
        return null
    }

    suspend fun streamUrlEx(id: String, preferBr: Int = 320): StreamResult =
        withContext(Dispatchers.IO) {
            if (id.isBlank()) {
                return@withContext StreamResult.Fail(StreamFail.BAD_SONG, "缺少 id")
            }

            if (!enabled) {
                officialOuterUrl(id)?.let {
                    return@withContext StreamResult.Ok(it, "ne-official")
                }
                return@withContext StreamResult.Fail(
                    StreamFail.BAD_SONG, "缺少 id 或网关未配置", "ne:未配置"
                )
            }

            customStreamUrl(id, preferBr)?.let {
                return@withContext StreamResult.Ok(it, "ne-custom")
            }

            var gatewayIssue: String? = null

            for (br in intArrayOf(preferBr, 128, 0)) {
                val url = "$baseUrl?types=url&source=netease&id=$id" +
                    if (br > 0) "&br=$br" else ""
                val res = httpGetRaw(url, UA)

                if (!res.ok) {
                    if (res.gatewayDown) {
                        gatewayIssue = "HTTP ${res.code}"
                        break
                    }
                    continue
                }

                val json = runCatching { JSONObject(res.text ?: "") }.getOrNull() ?: continue

                val direct = json.optString("url").takeIf { it.startsWith("http") }
                val dataStr = json.optString("data").takeIf { it.startsWith("http") }
                val dataArr = json.optJSONArray("data")
                    ?.optJSONObject(0)
                    ?.optString("url")
                    ?.takeIf { it.startsWith("http") }

                val play = direct ?: dataStr ?: dataArr
                if (!play.isNullOrBlank()) return@withContext StreamResult.Ok(play, "ne")

                val code = json.optInt("code", 0)
                if (code == 403 || code == 429 || code >= 500) {
                    gatewayIssue = json.optString("message").ifBlank { "code=$code" }
                    break
                }
            }

            officialOuterUrl(id)?.let {
                return@withContext StreamResult.Ok(it, "ne-official")
            }

            if (gatewayIssue != null) {
                StreamResult.Fail(StreamFail.GATEWAY, gatewayIssue, "ne:$gatewayIssue")
            } else {
                StreamResult.Fail(StreamFail.NO_RESOURCE, "网关没给直链", "ne:无直链")
            }
        }

    private fun officialOuterUrl(id: String): String? {
        val outer = "https://music.163.com/song/media/outer/url?id=$id.mp3"
        var conn: java.net.HttpURLConnection? = null
        return try {
            conn = GatewayTls.open(outer).apply {
                requestMethod = "GET"
                connectTimeout = 6000
                readTimeout = 6000
                instanceFollowRedirects = false
                setRequestProperty(
                    "User-Agent",
                    "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 " +
                        "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
                )
                setRequestProperty("Referer", "https://music.163.com/")
            }
            val code = conn.responseCode
            when {
                code in 300..399 -> {
                    val loc = conn.getHeaderField("Location").orEmpty()
                    if (loc.startsWith("http") && !loc.contains("/404")) loc else null
                }
                code == 200 -> {
                    val type = conn.contentType.orEmpty().lowercase()
                    if (type.startsWith("audio")) outer else null
                }
                else -> null
            }
        } catch (t: Throwable) {
            null
        } finally {
            runCatching { conn?.disconnect() }
        }
    }

    suspend fun streamUrl(id: String, preferBr: Int = 320): String? =
        streamUrlEx(id, preferBr).urlOrNull

    suspend fun lyric(id: String): LyricDoc? = withContext(Dispatchers.IO) {
        if (id.isBlank()) return@withContext null

        val url = "$NETEASE_ORIGIN/api/song/lyric?id=$id&lv=-1&kv=-1&tv=-1"
        val raw = getText(url, UA, referer = "$NETEASE_ORIGIN/") ?: return@withContext null

        val json = runCatching { JSONObject(raw) }.getOrNull() ?: return@withContext null

        val candidates = listOf(
            json.optJSONObject("klyric")?.optString("lyric"),
            json.optJSONObject("lrc")?.optString("lyric"),
            json.optJSONObject("tlyric")?.optString("lyric")
        )

        for (c in candidates) {
            if (c.isNullOrBlank()) continue
            val doc = LyricParser.parse(c)
            if (!doc.isEmpty) return@withContext doc
        }
        null
    }

    suspend fun ping(url: String): Boolean = withContext(Dispatchers.IO) {
        val base = normalize(url)
        if (base.isEmpty()) return@withContext false
        val raw = getText("$base?types=search&source=netease&name=test&count=1&pages=1", UA)
        raw != null && raw.trimStart().startsWith("[")
    }

    fun normalize(url: String): String {
        var u = url.trim()
        if (u.isEmpty()) return ""
        if (!u.startsWith("http://") && !u.startsWith("https://")) u = "https://$u"
        return u
    }

    suspend fun searchCount(keyword: String): Int = withContext(Dispatchers.IO) {
        if (keyword.isBlank()) return@withContext 0
        runCatching {
            val q = URLEncoder.encode(keyword, "UTF-8")
            val url = "$NETEASE_ORIGIN/api/search/get/web" +
                "?s=$q&type=1&offset=0&total=true&limit=1"
            val raw = getText(url, UA, "$NETEASE_ORIGIN/") ?: return@runCatching 0
            val json = JSONObject(raw)
            json.optJSONObject("result")?.optInt("songCount", 0) ?: 0
        }.getOrDefault(0)
    }

    private fun getText(
        url: String,
        ua: String,
        referer: String? = null
    ): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = GatewayTls.open(url).apply {
                requestMethod = "GET"
                connectTimeout = TIMEOUT
                readTimeout = TIMEOUT
                setRequestProperty("Accept", "*/*")
                setRequestProperty("User-Agent", ua)
                referer?.let { setRequestProperty("Referer", it) }
                instanceFollowRedirects = true
            }
            if (conn.responseCode !in 200..299) return null
            BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use {
                it.readText()
            }
        } catch (e: Exception) {
            null
        } finally {
            runCatching { conn?.disconnect() }
        }
    }

    internal fun decodeMaybeBase64(raw: String): String {
        val s = raw.trim()
        if (s.contains("[") && s.contains("]")) return s
        return runCatching {
            String(Base64.decode(s, Base64.DEFAULT), Charsets.UTF_8)
        }.getOrDefault(s)
    }
}

object QqMusicApi {

    const val BASE = "https://api.vkeys.cn/v2/music/tencent"

    private const val MIN_KBPS = 96

    private const val TIMEOUT = 12_000

    private const val UA =
        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/120.0.0.0 Mobile Safari/537.36"

    suspend fun search(keyword: String, num: Int = 20, page: Int = 1): List<Song> =
        withContext(Dispatchers.IO) {
            if (keyword.isBlank()) return@withContext emptyList()

            val p = page.coerceAtLeast(1)
            val want = (num * p).coerceAtMost(200)
            val q = URLEncoder.encode(keyword, "UTF-8")
            val raw = getText("$BASE?word=$q&num=$want") ?: return@withContext emptyList()
            val json = runCatching { JSONObject(raw) }.getOrNull() ?: return@withContext emptyList()
            val arr = json.optJSONArray("data") ?: return@withContext emptyList()

            val out = ArrayList<Song>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.optString("id")
                if (id.isBlank()) continue

                out.add(
                    Song(
                        key = "qq:$id",
                        title = o.optString("song").ifBlank { "未知曲目" },
                        artist = o.optString("singer").ifBlank { "未知艺人" },
                        album = o.optString("album").ifBlank { "未知专辑" },
                        durationMs = parseInterval(o.optString("interval")),
                        uri = "",
                        cover = o.optString("cover").takeIf { it.startsWith("http") },
                        source = SongSource.QQ,
                        extHash = id
                    )
                )
            }
            val from = ((p - 1) * num).coerceAtMost(out.size)
            val to = (from + num).coerceAtMost(out.size)
            out.subList(from, to)
        }

    suspend fun streamUrlEx(id: String): StreamResult = withContext(Dispatchers.IO) {
        if (id.isBlank()) return@withContext StreamResult.Fail(StreamFail.BAD_SONG, "缺少 id")

        val res = httpGetRaw("$BASE?id=$id", UA)
        if (!res.ok) {
            return@withContext if (res.gatewayDown) {
                StreamResult.Fail(StreamFail.GATEWAY, "HTTP ${res.code}", "qq:HTTP${res.code}")
            } else {
                StreamResult.Fail(StreamFail.NO_RESOURCE, "接口无响应", "qq:${res.code}")
            }
        }

        val json = runCatching { JSONObject(res.text ?: "") }.getOrNull()
            ?: return@withContext StreamResult.Fail(StreamFail.NO_RESOURCE, "非 JSON", "qq:非JSON")

        val data = json.optJSONObject("data")
            ?: return@withContext StreamResult.Fail(
                StreamFail.NO_RESOURCE,
                json.optString("message").ifBlank { "无 data" },
                "qq:${json.optInt("code", -1)}"
            )

        val url = data.optString("url").takeIf { it.startsWith("http") }
            ?: return@withContext StreamResult.Fail(StreamFail.NO_RESOURCE, "无 url", "qq:无url")

        val quality = data.optString("quality")
        val kbps = parseKbps(data.opt("kbps"))

        val preview = quality.contains("试听") || (kbps in 1 until MIN_KBPS)
        if (preview) {
            val why = quality.ifBlank { "${kbps}kbps" }
            return@withContext StreamResult.Fail(
                StreamFail.NO_RESOURCE, "仅试听（$why）", "qq:试听"
            )
        }

        StreamResult.Ok(url, "qq")
    }

    suspend fun streamUrl(id: String): String? = streamUrlEx(id).urlOrNull

    private fun parseKbps(raw: Any?): Int = when (raw) {
        is Number -> raw.toInt()
        is String -> raw.filter { it.isDigit() }.toIntOrNull() ?: 0
        else -> 0
    }

    suspend fun lyric(id: String): LyricDoc? = withContext(Dispatchers.IO) {
        if (id.isBlank()) return@withContext null
        val raw = getText("$BASE/lyric?id=$id") ?: return@withContext null
        val lrc = runCatching {
            JSONObject(raw).optJSONObject("data")?.optString("lrc")
        }.getOrNull()
        if (lrc.isNullOrBlank()) return@withContext null

        val doc = LyricParser.parse(lrc)
        if (doc.isEmpty) null else doc
    }

    private fun parseInterval(s: String): Long {
        if (s.isBlank()) return 0L
        s.trim().toLongOrNull()?.let { return it * 1000 }
        s.trim().split(":").takeIf { it.size == 2 }?.let { parts ->
            val m = parts[0].toLongOrNull()
            val sec = parts[1].toDoubleOrNull()
            if (m != null && sec != null) return (m * 60 + sec.toLong()) * 1000
        }
        val m = Regex("(\\d+)\\s*分").find(s)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        val sec = Regex("(\\d+)\\s*秒").find(s)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        return (m * 60 + sec) * 1000
    }

    suspend fun ping(): Boolean = withContext(Dispatchers.IO) {
        val raw = getText("$BASE?word=test&num=1")
        raw != null && raw.contains("\"code\": 200")
    }

    private fun getText(url: String): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = GatewayTls.open(url).apply {
                requestMethod = "GET"
                connectTimeout = TIMEOUT
                readTimeout = TIMEOUT
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", UA)
                instanceFollowRedirects = true
            }
            if (conn.responseCode !in 200..299) return null
            BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use {
                it.readText()
            }
        } catch (e: Exception) {
            null
        } finally {
            runCatching { conn?.disconnect() }
        }
    }
}

object ChartApi {

    data class Def(
        val id: String,
        val name: String,
        val note: String,
        val cadence: String
    )

    val DEFS = listOf(
        Def("3778678", "热歌榜", "全站播放最多的歌", "每天更新"),
        Def("19723756", "飙升榜", "近期热度涨得最快", "每天更新"),
        Def("3779629", "新歌榜", "刚发行就上榜的新歌", "每天更新"),
        Def("2884035", "原创榜", "独立音乐人的原创作品", "每周更新")
    )

    const val SHOW_LIMIT = 50

    private const val OFFICIAL = "https://music.163.com/api/playlist/detail?id="

    private const val HOT_URL = "https://music.163.com/api/search/hot?type=1111"

    private const val CACHE_TTL = 20 * 60 * 1000L
    private const val TIMEOUT = 12_000

    private const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/120.0.0.0 Safari/537.36"

    suspend fun load(context: Context, def: Def, force: Boolean = false): Chart? =
        withContext(Dispatchers.IO) {
            val cacheFile = File(context.cacheDir, "chart_${def.id}.json")
            val fresh = cacheFile.exists() &&
                System.currentTimeMillis() - cacheFile.lastModified() < CACHE_TTL

            if (!force && fresh) {
                readFile(cacheFile)?.let { raw -> parse(raw, def)?.let { return@withContext it } }
            }

            httpGet(OFFICIAL + def.id)?.let { raw ->
                parse(raw, def)?.let { chart ->
                    if (chart.songs.isNotEmpty()) {
                        runCatching { cacheFile.writeText(raw) }
                        return@withContext chart
                    }
                }
            }

            val gw = "${NeteaseApi.baseUrl}?types=playlist&id=${def.id}&source=netease"
            httpGet(gw)?.let { raw ->
                parse(raw, def)?.let { chart ->
                    if (chart.songs.isNotEmpty()) {
                        runCatching { cacheFile.writeText(raw) }
                        return@withContext chart
                    }
                }
            }

            readFile(cacheFile)?.let { raw -> parse(raw, def)?.let { return@withContext it } }
            null
        }

    private fun parse(raw: String, def: Def): Chart? {
        val json = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        val pl = json.optJSONObject("result") ?: json.optJSONObject("playlist") ?: return null
        val tracks = pl.optJSONArray("tracks") ?: return null

        val songs = ArrayList<Song>(tracks.length())
        for (i in 0 until tracks.length()) {
            val o = tracks.optJSONObject(i) ?: continue
            val sid = o.optLong("id", 0L)
            if (sid <= 0L) continue

            val album = o.optJSONObject("album")
            val artists = o.optJSONArray("artists")
            val artist = if (artists != null) {
                (0 until artists.length())
                    .mapNotNull { artists.optJSONObject(it)?.optString("name") }
                    .filter { it.isNotBlank() }
                    .joinToString("/")
            } else ""

            songs.add(
                Song(
                    key = "ne:$sid",
                    title = o.optString("name").ifBlank { "未知曲目" },
                    artist = artist.ifBlank { "未知艺人" },
                    album = album?.optString("name")?.ifBlank { "未知专辑" } ?: "未知专辑",
                    durationMs = o.optLong("duration", 0L),
                    uri = "",
                    cover = album?.optString("picUrl")?.takeIf { it.startsWith("http") },
                    source = SongSource.NETEASE,
                    extHash = sid.toString()
                )
            )
        }
        if (songs.isEmpty()) return null

        return Chart(
            id = def.id,
            name = def.name,
            note = def.note,
            updatedAt = pl.optLong("updateTime", 0L),
            cover = pl.optString("coverImgUrl").takeIf { it.startsWith("http") },
            total = pl.optInt("trackCount", songs.size),
            songs = songs
        )
    }

    suspend fun loadHotKeywords(context: Context, force: Boolean = false): List<String> =
        withContext(Dispatchers.IO) {
            val cacheFile = File(context.cacheDir, "hot_search.json")
            val fresh = cacheFile.exists() &&
                System.currentTimeMillis() - cacheFile.lastModified() < CACHE_TTL

            if (!force && fresh) {
                readFile(cacheFile)?.let { raw ->
                    parseHot(raw)?.takeIf { it.isNotEmpty() }?.let { return@withContext it }
                }
            }

            httpGet(HOT_URL)?.let { raw ->
                parseHot(raw)?.takeIf { it.isNotEmpty() }?.let { list ->
                    runCatching { cacheFile.writeText(raw) }
                    return@withContext list
                }
            }

            readFile(cacheFile)?.let { raw -> parseHot(raw) } ?: emptyList()
        }

    private fun parseHot(raw: String): List<String>? {
        val json = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        val hots = json.optJSONObject("result")?.optJSONArray("hots") ?: return null

        val out = ArrayList<String>(hots.length())
        for (i in 0 until hots.length()) {
            val k = hots.optJSONObject(i)?.optString("first")?.trim().orEmpty()
            if (k.isBlank() || k.length > 24) continue
            if (!looksLikeKeyword(k)) continue
            if (out.contains(k)) continue
            out.add(k)
        }
        return out
    }

    private fun looksLikeKeyword(s: String): Boolean {
        var n = 0
        for (c in s) {
            if (c in '\u4e00'..'\u9fff') n++
            else if (c.code < 128 && c.isLetterOrDigit()) n++
            if (n >= 2) return true
        }
        return false
    }

    private fun readFile(f: File): String? = runCatching {
        if (f.exists()) f.readText() else null
    }.getOrNull()

    private fun httpGet(url: String): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = GatewayTls.open(url).apply {
                requestMethod = "GET"
                connectTimeout = TIMEOUT
                readTimeout = TIMEOUT
                setRequestProperty("Accept", "*/*")
                setRequestProperty("User-Agent", UA)
                setRequestProperty("Referer", "https://music.163.com/")
                instanceFollowRedirects = true
            }
            if (conn.responseCode !in 200..299) return null
            conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (e: Exception) {
            null
        } finally {
            runCatching { conn?.disconnect() }
        }
    }
}

data class NetSearchResult(
    val songs: List<Song>,
    val diagnostics: List<SourceDiagnostic>
)

data class SourceDiagnostic(
    val provider: NetProvider,
    val ok: Boolean,
    val count: Int,
    val error: String?,
    val elapsedMs: Long
) {
    override fun toString(): String =
        "${provider.name.lowercase()}:${if (ok) "ok($count)" else "fail(${error ?: "?"})"} ${elapsedMs}ms"
}

object NetSearch {

    const val PAGE_SIZE = 20

    suspend fun search(
        keyword: String,
        page: Int = 1,
        pageSize: Int = PAGE_SIZE,
        provider: NetProvider = RicoApi.provider
    ): NetSearchResult = withContext(Dispatchers.IO) {
        if (keyword.isBlank()) return@withContext NetSearchResult(emptyList(), emptyList())

        val started = System.currentTimeMillis()
        val r = runCatching {
            SourceThrottle.await(provider)
            rawSearch(provider, keyword, page, pageSize)
        }
        val songs = r.getOrDefault(emptyList())

        val diag = SourceDiagnostic(
            provider = provider,
            ok = r.isSuccess,
            count = songs.size,
            error = r.exceptionOrNull()?.let { it.message ?: it::class.java.simpleName },
            elapsedMs = System.currentTimeMillis() - started
        )

        val withRefs = songs.map { s ->
            val id = s.extHash?.takeIf { it.isNotBlank() }
                ?: s.key.substringAfter(':', "").takeIf { it.isNotBlank() }
            if (id.isNullOrBlank()) {
                s
            } else {
                s.copy(
                    refs = listOf(
                        SourceRef(
                            provider = provider,
                            id = id,
                            durationMs = s.durationMs,
                            quality = qualityOf(provider, s)
                        )
                    )
                )
            }
        }

        NetSearchResult(withRefs, listOf(diag))
    }

    private suspend fun rawSearch(
        provider: NetProvider,
        keyword: String,
        page: Int,
        pageSize: Int
    ): List<Song> = when (provider) {
        NetProvider.KUGOU -> KuGouApi.search(keyword, page, pageSize)
        NetProvider.KUWO -> KuwoApi.search(keyword, page, pageSize)
        NetProvider.QQ -> QqMusicApi.search(keyword, pageSize, page)
        NetProvider.NETEASE -> NeteaseApi.search(keyword, page, pageSize)
    }

    private fun qualityOf(provider: NetProvider, song: Song): Int {
        var s = when (provider) {
            NetProvider.KUWO -> 18
            NetProvider.KUGOU -> 15
            NetProvider.QQ -> 12
            NetProvider.NETEASE -> 10
        }
        if (song.durationMs > 180_000L) s += 12
        else if (song.durationMs > 60_000L) s += 8
        return s.coerceIn(0, 100)
    }
}

private data class StreamCandidate(
    val provider: NetProvider,
    val id: String?,
    val durationMs: Long,
    val from: String
)

private data class CandidateResult(
    val candidate: StreamCandidate,
    val result: StreamResult
)

private data class RaceResult(
    val ok: StreamResult.Ok?,
    val steps: List<String>,
    val sawGateway: Boolean,
    val sawNoResource: Boolean
)

object RicoApi {

    @Volatile
    var provider: NetProvider = NetProvider.KUGOU

    @Volatile
    var neteaseBase: String = NeteaseApi.DEFAULT_BASE
        set(value) {
            field = if (value.isBlank()) NeteaseApi.DEFAULT_BASE else value
            NeteaseApi.baseUrl = field
        }

    val enabled: Boolean
        get() = true

    val currentBase: String
        get() = when (provider) {
            NetProvider.KUGOU -> KuGouApi.STREAM_BASE
            NetProvider.KUWO -> KuwoApi.STREAM_BASE
            NetProvider.QQ -> QqMusicApi.BASE
            NetProvider.NETEASE -> neteaseBase
        }

    fun normalize(url: String): String {
        var u = url.trim()
        if (u.isEmpty()) return ""
        if (!u.startsWith("http://") && !u.startsWith("https://")) {
            u = "http://$u"
        }
        return u.trimEnd('/')
    }

    suspend fun search(keyword: String, page: Int = 1, pageSize: Int = 20): List<Song> {
        if (!enabled || keyword.isBlank()) return emptyList()
        SourceThrottle.await(provider)
        return when (provider) {
            NetProvider.KUGOU -> runCatching {
                KuGouApi.search(keyword, page, pageSize)
            }.getOrDefault(emptyList())

            NetProvider.KUWO -> runCatching {
                KuwoApi.search(keyword, page, pageSize)
            }.getOrDefault(emptyList())

            NetProvider.QQ -> runCatching {
                QqMusicApi.search(keyword, pageSize, page)
            }.getOrDefault(emptyList())

            NetProvider.NETEASE -> runCatching {
                NeteaseApi.search(keyword, page, pageSize)
            }.getOrDefault(emptyList())
        }
    }

    private val Song.extId: String?
        get() = extHash?.takeIf { it.isNotBlank() }
            ?: key.substringAfter(':', "").takeIf { it.isNotBlank() }

    private fun homeSource(song: Song): NetProvider = when {
        song.key.startsWith("qq:") || song.source == SongSource.QQ -> NetProvider.QQ
        song.key.startsWith("ne:") || song.source == SongSource.NETEASE -> NetProvider.NETEASE
        song.key.startsWith("kw:") || song.source == SongSource.KUWO -> NetProvider.KUWO
        else -> NetProvider.KUGOU
    }

    private fun fallbackOrder(home: NetProvider): List<NetProvider> =
        listOf(NetProvider.KUWO, NetProvider.QQ, NetProvider.KUGOU, NetProvider.NETEASE)
            .filter { it != home }
            .sortedBy { if (SourceHealth.isAvailable(it)) 0 else 1 }

    private val NetProvider.tag: String
        get() = when (this) {
            NetProvider.KUGOU -> "kg"
            NetProvider.KUWO -> "kw"
            NetProvider.QQ -> "qq"
            NetProvider.NETEASE -> "ne"
        }

    private const val URL_TTL = 10 * 60 * 1000L
    private const val FAIL_TTL = 30_000L

    private val urlCache = HashMap<String, Pair<String, Long>>()
    private val failCache = HashMap<String, Pair<StreamResult.Fail, Long>>()

    private val altCache = HashMap<String, Triple<NetProvider, String, Long>>()

    private fun songName(song: Song): String = listOf(song.title, song.artist)
        .filter { it.isNotBlank() }
        .joinToString(" ")

    private fun rememberUrl(key: String, url: String) {
        synchronized(urlCache) {
            if (urlCache.size > 48) urlCache.clear()
            urlCache[key] = url to System.currentTimeMillis()
        }
        synchronized(failCache) { failCache.remove(key) }
    }

    suspend fun resolveStreamUrlEx(song: Song): StreamResult = withContext(Dispatchers.IO) {
        val id = song.extId
        if (id.isNullOrBlank() && song.refs.isEmpty()) {
            return@withContext StreamResult.Fail(
                StreamFail.BAD_SONG, "没有可用 id", song.key
            )
        }

        val now = System.currentTimeMillis()
        val ck = "${provider.name}|${song.key}"

        synchronized(urlCache) { urlCache[ck] }
            ?.takeIf { now - it.second < URL_TTL }
            ?.let { return@withContext StreamResult.Ok(it.first, "cache") }

        synchronized(failCache) { failCache[ck] }
            ?.takeIf { now - it.second < FAIL_TTL }
            ?.let { return@withContext it.first }

        val home = homeSource(song)
        val steps = ArrayList<String>()
        var sawGateway = false
        var sawNoResource = false

        val candidates = ArrayList<StreamCandidate>()
        val covered = HashSet<NetProvider>()

        if (!id.isNullOrBlank() && covered.add(home)) {
            candidates += StreamCandidate(home, id, song.durationMs, "src:${home.tag}")
        }

        for (ref in song.refs) {
            if (ref.id.isBlank()) continue
            if (!covered.add(ref.provider)) continue
            val dur = if (ref.durationMs > 0) ref.durationMs else song.durationMs
            candidates += StreamCandidate(ref.provider, ref.id, dur, "ref:${ref.provider.tag}")
        }

        val needLookup = buildList {
            if (provider != home) add(provider)
            add(home)
            addAll(fallbackOrder(home).filter { it != provider })
        }
        for (src in needLookup) {
            if (!covered.add(src)) continue
            candidates += StreamCandidate(src, null, song.durationMs, "src:${src.tag}")
        }

        candidates.sortBy { if (it.provider == provider) 0 else 1 }

        if (candidates.isEmpty()) {
            return@withContext StreamResult.Fail(
                StreamFail.BAD_SONG, "没有可用 id", song.key
            )
        }

        val race = raceResolve(song, candidates, provider)
        steps.addAll(race.steps)
        sawGateway = race.sawGateway
        sawNoResource = race.sawNoResource

        race.ok?.let { ok ->
            rememberUrl(ck, ok.url)
            return@withContext ok
        }

        val gatewayOnly = sawGateway && !sawNoResource
        val detail = when {
            steps.isEmpty() -> "没有可播放资源"
            gatewayOnly -> "音源通道暂时不可用"
            sawNoResource && sawGateway -> "部分通道不可用，且没有可用资源"
            else -> "没有可播放资源"
        }
        val fail = StreamResult.Fail(
            kind = if (gatewayOnly) StreamFail.GATEWAY else StreamFail.NO_RESOURCE,
            detail = detail,
            tried = steps.joinToString(" "),
            steps = steps.toList()
        )
        synchronized(failCache) {
            if (failCache.size > 48) failCache.clear()
            failCache[ck] = fail to System.currentTimeMillis()
        }
        fail
    }

    private suspend fun raceResolve(
        song: Song,
        candidates: List<StreamCandidate>,
        preferred: NetProvider
    ): RaceResult {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val channel = Channel<CandidateResult>(Channel.UNLIMITED)
        val steps = ArrayList<String>()
        var sawGateway = false
        var sawNoResource = false
        var fallback: StreamResult.Ok? = null
        var deadline = Long.MAX_VALUE

        try {
            val jobs = candidates.map { c ->
                scope.launch {
                    val hedge = when {
                        c.id != null -> 0L
                        c.provider == preferred -> 0L
                        else -> HEDGE_DELAY_MS
                    }
                    if (hedge > 0) delay(hedge)

                    val res = try {
                        runCandidate(song, c)
                    } catch (t: Throwable) {
                        if (t is kotlinx.coroutines.CancellationException) throw t
                        StreamResult.Fail(
                            StreamFail.GATEWAY,
                            t.message ?: "异常",
                            "${c.provider.tag}:异常"
                        )
                    }

                    try {
                        channel.send(CandidateResult(c, res))
                    } catch (t: Throwable) {
                        if (t is kotlinx.coroutines.CancellationException) throw t
                    }
                }
            }

            var pending = jobs.size
            while (pending > 0) {
                val got = if (fallback != null) {
                    val remain = deadline - System.currentTimeMillis()
                    if (remain <= 0L) null
                    else withTimeoutOrNull(remain) { channel.receive() }
                } else {
                    channel.receive()
                }
                if (got == null) break

                pending--
                val (c, res) = got
                when (res) {
                    is StreamResult.Ok -> {
                        if (c.provider == preferred) {
                            return RaceResult(res, steps, sawGateway, sawNoResource)
                        }
                        if (fallback == null) {
                            fallback = res
                            deadline = System.currentTimeMillis() + PREFERRED_GRACE_MS
                        }
                    }
                    is StreamResult.Fail -> {
                        when (res.kind) {
                            StreamFail.GATEWAY -> sawGateway = true
                            StreamFail.NO_RESOURCE -> sawNoResource = true
                            else -> Unit
                        }
                        steps.add(res.tried.ifBlank { "${c.provider.tag}:失败" })
                        if (c.provider == preferred && fallback != null) break
                    }
                }
            }

            return RaceResult(
                ok = fallback,
                steps = steps,
                sawGateway = sawGateway,
                sawNoResource = sawNoResource
            )
        } finally {
            scope.cancel()
            channel.close()
        }
    }

    private suspend fun runCandidate(song: Song, c: StreamCandidate): StreamResult {
        if (!SourceHealth.isAvailable(c.provider)) {
            return StreamResult.Fail(
                StreamFail.GATEWAY, "通道冷却中", "${c.provider.tag}:冷却中"
            )
        }

        var id = c.id
        var dur = c.durationMs
        if (id.isNullOrBlank()) {
            val hit = altLookup(song, c.provider)
                ?: return StreamResult.Fail(
                    StreamFail.NO_RESOURCE, "未匹配到同一首", "${c.provider.tag}:未匹配"
                )
            id = hit.first
            dur = hit.second
        }

        val r = attempt(c.provider, id, songName(song), dur, c.from)
        if (r is StreamResult.Ok) {
            if (!urlMatchesSource(c.provider, r.url) ||
                (c.provider != NetProvider.KUWO && !probePlayable(r.url))
            ) {
                return StreamResult.Fail(
                    StreamFail.NO_RESOURCE, "地址不可靠", "${c.provider.tag}:地址不可靠"
                )
            }
        }
        return r
    }

    private fun urlMatchesSource(src: NetProvider, url: String): Boolean {
        val host = runCatching {
            java.net.URI(url).host.orEmpty().lowercase()
        }.getOrDefault("")
        if (host.isEmpty()) return true
        return when (src) {
            NetProvider.NETEASE -> host.contains("126.net") || host.contains("163.com")
            NetProvider.QQ -> host.contains("qq.com")
            else -> true
        }
    }

    fun refererFor(url: String): String? = when {
        url.contains("126.net") || url.contains("163.com") -> "https://music.163.com/"
        url.contains("kugou.com") || url.contains("kgimg.com") -> "https://www.kugou.com/"
        url.contains("kuwo.cn") || url.contains("kw-bj") -> "http://www.kuwo.cn/"
        url.contains("qq.com") -> "https://y.qq.com/"
        else -> null
    }

    private fun probePlayable(url: String): Boolean {
        var conn: java.net.HttpURLConnection? = null
        return try {
            conn = GatewayTls.open(url).apply {
                requestMethod = "GET"
                connectTimeout = 5000
                readTimeout = 5000
                instanceFollowRedirects = true
                setRequestProperty(
                    "User-Agent",
                    "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 " +
                        "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
                )
                refererFor(url)?.let { setRequestProperty("Referer", it) }
                setRequestProperty("Range", "bytes=0-2047")
            }
            val code = conn.responseCode
            if (code !in 200..299) return false
            val type = conn.contentType.orEmpty().lowercase()
            when {
                type.startsWith("audio") -> true
                type.startsWith("video") -> true
                type.startsWith("application/octet-stream") -> true
                type.contains("mpegurl") -> true
                type.isBlank() -> true
                else -> false
            }
        } catch (t: Throwable) {
            false
        } finally {
            runCatching { conn?.disconnect() }
        }
    }

    private suspend fun attempt(
        src: NetProvider,
        id: String,
        name: String,
        durationMs: Long,
        from: String
    ): StreamResult {
        val r = when (src) {
            NetProvider.KUGOU -> KuGouApi.streamUrlEx(id, name)
            NetProvider.KUWO -> KuwoApi.streamUrlEx(id, durationMs)
            NetProvider.QQ -> QqMusicApi.streamUrlEx(id)
            NetProvider.NETEASE -> NeteaseApi.streamUrlEx(id)
        }
        when (r) {
            is StreamResult.Ok -> SourceHealth.markOk(src)
            is StreamResult.Fail ->
                if (r.kind == StreamFail.GATEWAY) SourceHealth.markGatewayFail(src, r.detail)
                else SourceHealth.markNoResource(src)
        }
        return if (r is StreamResult.Ok) r.copy(from = from) else r
    }

    private suspend fun altLookup(song: Song, src: NetProvider): Pair<String, Long>? {
        val ck = "${song.key}|${src.name}"
        synchronized(altCache) { altCache[ck] }
            ?.let { return it.second to it.third }

        val keyword = buildString {
            append(song.title.replace(Regex("[(（\\[【].*?[)）\\]】]"), "").trim())
            val a = song.artist.substringBefore('/').substringBefore('、').trim()
            if (a.isNotBlank() && a != "未知艺人") {
                if (isNotEmpty()) append(' ')
                append(a)
            }
        }.trim()
        if (keyword.isBlank()) return null

        val hits = runCatching {
            SourceThrottle.await(src)
            when (src) {
                NetProvider.KUGOU -> KuGouApi.search(keyword, 1, 12)
                NetProvider.KUWO -> KuwoApi.search(keyword, 1, 12)
                NetProvider.QQ -> QqMusicApi.search(keyword, 12)
                NetProvider.NETEASE -> {
                    val viaGateway = NeteaseApi.search(keyword, 1, 12)
                    if (viaGateway.isNotEmpty()) viaGateway
                    else NeteaseApi.officialSearch(keyword, 1, 12)
                }
            }
        }.getOrDefault(emptyList())

        val best = SongMatcher.pickSameSong(song, hits) ?: return null
        val altId = (best.extHash?.takeIf { it.isNotBlank() }
            ?: best.key.substringAfter(':', "").takeIf { it.isNotBlank() })
            ?: return null

        synchronized(altCache) {
            if (altCache.size > 128) altCache.clear()
            altCache[ck] = Triple(src, altId, best.durationMs)
        }
        return altId to best.durationMs
    }

    suspend fun resolveStreamUrl(song: Song): String? = resolveStreamUrlEx(song).urlOrNull

    private const val HEDGE_DELAY_MS = 400L

    private const val PREFERRED_GRACE_MS = 1_200L

    fun forget(songKey: String) {
        if (songKey.isBlank()) return
        synchronized(urlCache) { urlCache.keys.removeAll { it.endsWith("|$songKey") } }
        synchronized(failCache) { failCache.keys.removeAll { it.endsWith("|$songKey") } }
    }

    suspend fun lyric(song: Song): LyricDoc? = withContext(Dispatchers.IO) {
        val id = song.extId ?: return@withContext null

        when {
            song.key.startsWith("qq:") || song.source == SongSource.QQ ->
                QqMusicApi.lyric(id)

            song.key.startsWith("ne:") || song.source == SongSource.NETEASE ->
                NeteaseApi.lyric(id)

            song.key.startsWith("kw:") || song.source == SongSource.KUWO ->
                KuwoApi.songInfo(id)?.lyric

            else -> KuGouApi.lyric(id, song.title, song.durationMs)
        }
    }

    suspend fun coverFor(song: Song): String? = withContext(Dispatchers.IO) {
        if (!(song.key.startsWith("kw:") || song.source == SongSource.KUWO)) return@withContext null
        val id = song.extId ?: return@withContext null
        KuwoApi.songInfo(id)?.cover
    }

    suspend fun ping(provider: NetProvider, url: String): Boolean =
        when (provider) {
            NetProvider.KUGOU -> KuGouApi.ping()
            NetProvider.KUWO -> KuwoApi.ping()
            NetProvider.QQ -> QqMusicApi.ping()
            NetProvider.NETEASE -> NeteaseApi.ping(url)
        }
}
