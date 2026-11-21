# RicoMusic 音源 API（单文件版）

RicoMusic 用到的全部音源相关代码，合并为一个自包含的 Kotlin 文件 `RicoMusicSourcesApi.kt`（约 2475 行，无注释）。

覆盖：酷狗 / 酷我 / QQ 音乐 / 网易云 四个音源，加榜单、跨源匹配、统一门面。

不含：播放器、UI、本地曲库扫描、账号与云同步。

---

## 环境要求

| 项 | 要求 |
|---|---|
| 平台 | Android（`minSdk 26`） |
| 语言 | Kotlin |
| 依赖 | `kotlinx-coroutines`、`org.json`（Android 内置） |
| 网络 | `HttpURLConnection`，无 OkHttp / Retrofit 依赖 |
| 权限 | `android.permission.INTERNET` |

`ChartApi` 需要 `Context`（用于本地缓存文件），其余模块无需 Context。

---

## 接入方式

1. 把 `RicoMusicSourcesApi.kt` 放进你的源码目录。
2. 改文件首行的包名：

```kotlin
package your.app.data
```

3. 清单里加网络权限：

```xml
<uses-permission android:name="android.permission.INTERNET" />
```

4. 确保 gradle 里有协程依赖：

```kotlin
implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
```

---

## 模块一览

| 模块 | 作用 |
|---|---|
| `Song` / `SourceRef` / `Chart` | 数据模型 |
| `NetProvider` | 音源枚举：`KUGOU` / `KUWO` / `QQ` / `NETEASE` |
| `SongSource` | 歌曲来源标记：`LOCAL` / `NET` / `NETEASE` / `QQ` / `KUWO` / `DEMO` |
| `KuGouApi` | 酷狗：搜索、取流、歌词、ping |
| `KuwoApi` | 酷我：搜索、取流、歌曲信息（含歌词/封面）、ping |
| `QqMusicApi` | QQ 音乐：搜索、取流、歌词、ping |
| `NeteaseApi` | 网易云：搜索、取流、歌词、ping、热搜词计数 |
| `ChartApi` | 网易云榜单（热歌/飙升/新歌/原创）+ 热搜词，带本地缓存 |
| `RicoApi` | 统一门面：按 key 前缀路由、并行竞速取流、跨源兜底、缓存 |
| `NetSearch` | 单源搜索 + 诊断信息（耗时、失败原因） |
| `SongMatcher` | 跨源同一首歌判定（标题/歌手/时长加权 + 编辑距离） |
| `SourceHealth` / `SourceThrottle` | 音源健康度熔断 + 请求节流 |
| `GatewayTls` | 部分聚合网关的 TLS 兼容（信任所有证书） |
| `LyricParser` | LRC 解析 + 逐字插值（`LyricDoc` / `LyricLine` / `LyricWord`） |

---

## key 与 id 约定

`Song.key` 是全局唯一键，前缀决定音源：

| 前缀 | 音源 | 例 |
|---|---|---|
| `kg:` | 酷狗 | `kg:ABC123HASH` |
| `kw:` | 酷我 | `kw:12345678` |
| `qq:` | QQ 音乐 | `qq:0039MnYb0qxYhV` |
| `ne:` | 网易云 | `ne:186892` |

`key` 冒号后的部分就是该音源内部 id，`RicoApi` 靠它决定去哪个音源取流。跨源兜底用的备用 id 放在 `Song.refs: List<SourceRef>` 里。

---

## 基础用法

### 1. 搜索

```kotlin
val songs = RicoApi.search("晴天", page = 1, pageSize = 20)
```

指定音源：

```kotlin
RicoApi.provider = NetProvider.KUWO
val songs = RicoApi.search("晴天")
```

只要诊断信息（哪个源成功、耗时多少）：

```kotlin
val result = NetSearch.search("晴天", provider = NetProvider.QQ)
result.songs.forEach { println("${it.title} - ${it.artist}") }
result.diagnostics.forEach { println(it.toString()) }
// 输出示例：qq:ok(20) 412ms
```

单源直调：

```kotlin
val songs = KuGouApi.search("晴天", page = 1, pageSize = 30)
val songs = KuwoApi.search("晴天")
val songs = QqMusicApi.search("晴天", num = 30, page = 1)
val songs = NeteaseApi.search("晴天")
```

### 2. 取播放地址

推荐用带失败原因的重载：

```kotlin
when (val r = RicoApi.resolveStreamUrlEx(song)) {
    is StreamResult.Ok -> player.play(r.url)
    is StreamResult.Fail -> toast("播放失败：${r.kind} ${r.detail}")
}
```

只要地址：

```kotlin
val url: String? = RicoApi.resolveStreamUrl(song)
```

`StreamResult.Ok` 带 `url` 和 `from`（命中的音源标识，如 `src:kg`、`ref:kw`、`cache`）。
`StreamResult.Fail` 带 `kind` / `detail` / `tried` / `steps`，其中 `kind: StreamFail` 取值：

| 值 | 含义 |
|---|---|
| `GATEWAY` | 聚合网关异常或返回不了地址 |
| `NO_RESOURCE` | 音源确实没有这首歌 |
| `BAD_SONG` | `Song` 里没有可用 id |

`tried` 是尝试过的音源列表，`steps` 是每一步的追踪记录，调试时很好用。

取流内部做的事：

1. 先查 URL 缓存（成功 10 分钟、失败 30 秒）。
2. 用 `key` 前缀 + `refs` 生成候选（音源, id）列表。
3. 按健康度排序依次尝试，直达成功。
4. 其它音源都没有时，用 `SongMatcher` 跨源搜同名歌兜底。
5. 记录每个音源的成败，供 `SourceHealth` 熔断。

### 3. 歌词

```kotlin
val doc: LyricDoc? = RicoApi.lyric(song)
doc?.lines?.forEach { line ->
    println("${line.startMs} ${line.text}")
}
val idx = doc?.indexAt(playerPositionMs) ?: -1
```

只要解析器：

```kotlin
val doc = LyricParser.parse(lrcRawText)
val words = LyricParser.interpolate(text, startMs, endMs)  // 逐字时间轴
```

### 4. 榜单

```kotlin
val chart = ChartApi.load(context, ChartApi.DEFS.first(), force = false)
chart?.songs?.forEach { println("${it.title} - ${it.artist}") }

val hotWords = ChartApi.loadHotKeywords(context)
```

`ChartApi.DEFS` 四个榜单：热歌榜 `3778678`、飙升榜 `19723756`、新歌榜 `3779629`、原创榜 `2884035`。
缓存写在 `context.cacheDir`，TTL 20 分钟。

### 5. 封面

```kotlin
val cover: String? = RicoApi.coverFor(song)
```

酷我源可直接取到封面；其它源请用搜索返回的 `Song.cover`。

### 6. 可用性探测

```kotlin
val ok = RicoApi.ping(NetProvider.KUGOU, KuGouApi.STREAM_BASE)
val all = SourceHealth.describeAll()
```

---

## 配置项

```kotlin
RicoApi.provider = NetProvider.KUGOU      // 默认音源，默认 KUGOU
RicoApi.neteaseBase = "https://你的代理/"  // 网易云不可直连时替换
```

`RicoApi.enabled` 恒为 `true`，如需总开关自行改。

---

## 可调常量

下表都是源码里的 `private const`，直接改文件中的值即可（不是运行时 API）：

| 常量 | 位置 | 含义 |
|---|---|---|
| `MERGE_THRESHOLD` | `SongMatcher` | 跨源判同分数阈值，默认 0.80 |
| `CROSS_TITLE_MIN` / `CROSS_ARTIST_MIN` | `SongMatcher` | 跨源兜底的最低标题 / 歌手相似度 |
| `SourceHealth.THRESHOLD` / `COOLDOWN_MS` | `SourceHealth` | 连续失败几次熔断、熔断多久 |
| `URL_TTL` / `FAIL_TTL` | `RicoApi` | 播放地址缓存、失败缓存时长 |
| `SHOW_LIMIT` | `ChartApi` | 单个榜单返回条数上限，默认 50 |

---

## 注意事项

**接口地址**：文件内是各平台公开接口与第三方聚合网关地址，不含任何账号、Cookie、密钥。

**版权限制**：QQ 独家曲目、酷我热门版权曲、网易云版权曲通常只返回试听片段或不返回，这是上游限制，`StreamResult.Fail` 会给出对应原因。

**稳定性**：上游接口随时可能变更或加风控。`SourceHealth` 会自动降权连续失败的源，但接口本身失效需要自行更新。

**TLS**：`GatewayTls` 为兼容部分聚合网关做了信任所有证书处理。若你只用自己的域名，建议删掉该对象并改用系统默认校验。

**合规**：仅供学习与个人研究，请遵守各平台服务条款与当地法律，不要用于商业分发。

---

## 不包含的部分

播放器、通知栏/后台播放、均衡器与频谱、UI 与主题、本地曲库扫描、收藏与历史、账号登录与云同步、AI 模块。这些在 RicoMusic 主工程里，不在本文件范围。
