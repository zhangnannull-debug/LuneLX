# LuneLX 交接包审计报告

> 对象：`lunelx-handoff.tar.gz` 源码树 + `LuneLX-v0.1.2-debug.apk`（versionCode 3）
> 方式：读码 + 可脱离 Android 执行的运行时验证（Node `vm` 沙箱）+ **实际构建验证**。
>
> **状态更新：所有改动已在真实工具链编译通过，并在真机上完成验证。**
> 构建：JDK 21 + Android SDK 37.0 + Build-Tools 37.0.0 + Gradle 9.7.1 + AGP 9.4.0
> → `BUILD SUCCESSFUL`，**23/23 单测通过**，APK V2 签名校验通过。
> 真机：Xiaomi 设备 / Android 16 / API 36 —— 安装启动、本地播放、**搜索链路端到端跑通**、
> audioSessionId > 0、**无 CLEARTEXT 报错**（详见 §0.2）。
> 仍未验证：**在线歌曲是否真的出声**——需要一个可用的真实音源脚本。

---

## 0. 结论

交接文档 §6 把「搜不出内容」整体归因于 lx-music 的架构（脚本没有 search 能力）。
**这个归因不成立。** 在线搜索被本项目自身的**三个独立缺陷**依次堵死，任何一个都足以让它永远返回空；
另外还有一个**会让在线播放直接失败**的平台级配置缺失。这四处已修复。

最关键的一点：**搜索桥本身一直是通的**——脚本返回的结果能正确透传给原生。
坏的只是①能力上报被过滤、②搜索结果解析取错层级。也就是说，方案 A 从来不是「能力缺口」，
而是三个我们自己写错的地方。

| # | 严重度 | 问题 | 位置 | 状态 |
|---|---|---|---|---|
| 1 | **CRITICAL** | 缺明文流量放行 → 本地流代理连不上 → 在线播放必失败 | Manifest / `res/xml/` | ✅ 已修 |
| 2 | **CRITICAL** | 403 重试解析的是**别的歌**，会把 A 的音频当 B 播 | `LxStreamProxy.kt` | ✅ 已修 |
| 3 | **HIGH** | 能力白名单吞掉 `search` → 搜索在入口就被判死 | `user-api-preload.js` | ✅ 已修 |
| 4 | **HIGH** | 搜索结果解析没拆信封 → **无条件返回 null** | `LxScriptEngine.kt` | ✅ 已修 |
| 5 | **HIGH** | 交付的 APK 里 preload 是旧版，根本没有 search 分支 | APK 资产 | ✅ 已重新构建并验证 |
| 6 | **HIGH** | 在线歌时长按「秒」写进「毫秒」字段，全部变成 0.2 秒 | `LxRepository.kt` | ✅ 已修 |
| 7 | **MEDIUM** | `onPrepared` 不是一次性的，会造成「暂停了自己又播」「播完重放」 | `ExoPlayerWrapper.kt` | ✅ 已修 |
| 8 | LOW | `LxSources.ALL` 漏了咪咕 | `LxModels.kt` | ✅ 已修 |
| 9 | LOW | `gradle.properties` 写死容器 aapt2 路径，桌面构建必失败 | `gradle.properties` | ✅ 已修 |
| 10 | — | 新增无工具链回归测试 `tools/preload-check.js` | 新增 | ✅ |
| 11 | **HIGH** | 搜索结果行**没有挂 clickable**，看得见点不动，只能走「更多」菜单 | `LxSearchScreen.kt` | ✅ 已修（真机发现） |
| 12 | **HIGH** | 脚本不逐条返回 `source` 时 `source=""`，uid 损坏、播放必失败 | `LxRepository.kt` | ✅ 已修（真机发现） |
| 13 | **MEDIUM** | `onlineEnabled` 只在内存里，每次重启静默关回 false | `LxRepository.kt` / `LxScriptStore.kt` | ✅ 已修（真机发现） |
| 14 | MEDIUM | 在线未开启时提示「还没有可用的音源脚本」，与实际原因不符 | `LxSearchScreen.kt` | ⬜ 未修（文案） |
| 15 | **HIGH** | **重开 App 会自动播放上次那首**：`restorePlayback` 无条件 `player.start()`，把 `andPlay=false` 的"恢复但暂停"变成自动播放 | `MusicService.kt` / `ExoPlayerWrapper.kt` | ✅ 已修（真机发现并验证） |
| 16 | **CRITICAL** | **流代理不跟随跨协议重定向**：Android `HttpURLConnection` 不跟 http→https，而直链第一跳就是 301 到 https → 永远 502 | `LxStreamProxy.kt` | ✅ 已修（真机发现并验证） |
| 17 | — | **新增内置酷狗搜索**（方案 B 的第一个平台），脚本只需提供 `musicUrl` | `lx/search/LxBuiltinSearch.kt` | ✅ 新增并真机跑通 |

---

## 0.1 构建验证结果（本次实测，不是推断）

在有网后于本机搭了一套**独立工具链**（全部装在 workspace 内，不污染系统）：
Temurin JDK 21.0.12.1 + Android SDK `platforms;android-37.0` / `build-tools;37.0.0` /
platform-tools + Gradle 9.7.1（wrapper 自带）+ AGP 9.4.0。

| 验证项 | 结果 |
|---|---|
| `:app:compileDebugKotlin`（含本次全部改动） | ✅ 通过，仅 6 条 deprecated/unnecessary-safe-call 警告，均为既有代码 |
| `:app:testDebugUnitTest` | ✅ **23/23 通过**（SongUid 10、LxScriptDetector 8、上游 5） |
| `:app:assembleDebug` | ✅ `Lune-debug.apk` 32.6 MB |
| `apksigner verify` | ✅ V2 签名有效（Android Debug 证书） |
| 新 APK 内 `user-api-preload.js` | ✅ 22741 B，与源码**逐字节一致**，含 `case 'search'` |
| 新 APK 内 `network_security_config` | ✅ `res/xml/network_security_config.xml` 存在，清单已引用 |
| 对新 APK 里的 preload 跑 `preload-check.js` | ✅ **6/6 通过**（旧 APK 是 4/6） |

构建环境上踩到的两个坑（与项目无关，属本机环境）：

1. 用户名目录含中文 → AGP 直接拒绝构建
   （`Your project path contains non-ASCII characters`）。
   解法：在 **Gradle 用户目录**的 `gradle.properties` 里加 `android.overridePathCheck=true`，
   **不必**改项目自己的 `gradle.properties`。
2. 沙箱只允许写 workspace → 必须把 `GRADLE_USER_HOME` / `HOME` / `TMP` 指到 workspace，
   否则 AGP 建 `.android` 目录时报 `AndroidLocationsException`。
   另注意 `ANDROID_PREFS_ROOT` 与 `ANDROID_USER_HOME` 同时设置且不一致时 AGP 会报错，只设后者即可。

---

## 0.2 真机验证结果（Xiaomi 设备 / Android 16 / API 36 / arm64-v8a）

在用户的真机上通过 adb 安装并实测。验证手段：logcat + `uiautomator dump`（读 UI 树）+
推入一个**自带 search 的测试脚本**到 `files/lx_scripts/`（debug 包可 `run-as` 写入）。

| 验证项 | 结果 |
|---|---|
| 安装 / 启动 | ✅ 冷启动 814 ms，无崩溃 |
| 本地播放 | ✅ `MusicService` 正常抽取歌词并出声 |
| **audioSessionId** | ✅ `32065` / `32177` / `32193`，**全部 > 0**，且 AudioTrack 实际绑定该 session |
| 脚本加载 | ✅ 2 个脚本，`inited sources=[kg]` / `[kw,kg,tx,wy,mg]` |
| **能力上报含 search** | ✅ 在线页出现「**酷狗**」chip —— 该 chip 只在 `supportsAction(...,"search")` 为真时渲染 |
| **搜索链路** | ✅ 输入关键词 → 出现两条结果，时长显示 **3:45 / 4:10**（同时证明 §6 的毫秒换算正确） |
| **明文流量** | ✅ 代理 `listening on 127.0.0.1:38987`，ExoPlayer 正常连上并拿到 HTTP 响应，**全程无 CLEARTEXT 报错** |
| 取流链路 | ✅ `resolved kg/test-001 q=320k` → 代理转发（测试地址是故意写死的死链，故 `ConnectException`，属预期） |
| 开关持久化 | ✅ 强制停止重启后仍保留「酷狗」chip |
| **重开 App 不自动播放** | ✅ 启动后 `state=PAUSED, speed=0.0`，位置 6 秒内冻结在 216898；点播放 → `PLAYING` 且位置推进；再暂停 → `PAUSED` |

**同时确认了审计里预言的串味问题**：几次在线播放后，Resume 页出现「测试歌手 / 13 plays」——
所有在线歌 `id` 都是 0，统计行 `SONG_0` 被累加，且该行出现在本地音乐库里。

> 说明：真机上**没有**可用的真实音源（用户导入的「👖裤佬SVIP音源」按原交接文档 §6 已失效，且只声明 `musicUrl`），
> 所以「在线歌曲真的出声」仍需一个可用的 search/play 脚本才能最终确认。
> 本次验证的是**本项目自身的链路**：能力上报 → 搜索 → 结果解析 → 直链解析 → 本地代理 → 播放器。

---

## 0.3 在线播放：真机全链路跑通（2026-10-19 追加）

用户指出「裤佬SVIP音源没失效，在 lx 音乐里能搜索播放」。核查后**用户是对的，我之前的转述有误**：

1. **脚本本身仍然有效**。它返回的直链是
   `http://mcp.nianxinxz.com/share/ceshi/kg.php?id=<hash>&level=exhigh&type=mp3`，
   实测该地址能取到真实音频（`audio/mpeg`，带 ID3 头）。
2. **但脚本确实没有 `search`**：脚本全文 `search` 出现 0 次，每个音源 `actions` 只有 1 个元素。
   lx 音乐能搜，是因为**它自己内置了平台搜索 API**（即方案 B）；搜到 songmid 后，
   再把 hash 交给脚本的 `musicUrl`。所以「lx 能搜」证明的是 lx 有内置搜索，不是脚本有 search 能力。

据此补做了两件事：

### (a) 内置酷狗搜索（方案 B，第一个平台）
新增 `lx/search/LxBuiltinSearch.kt`，调 `songsearch.kugou.com/song_search_v2`，
把结果转成 `LxSong` 后交给**原有的** `LxRepository.search` 流程 —— UI、取流、播放一行都没改。
`hasSearchableSource()` 现在对「有内置搜索的音源」直接返回 true，不再依赖脚本声明。

真机实测（关键词 `jay`）：

```
LxRepository: builtin search kg 'jay' -> 30 results
晴天   周杰伦 · 叶惠美   4:29
青花瓷 周杰伦 · 我很忙   3:59
搁浅   周杰伦 · 七里香   4:00
```

### (b) 流代理的跨协议重定向 bug（CRITICAL）
点播放后一直 `upstream returned 301`。实测该直链的真实链路是：

```
http://mcp.../kg.php   →301→  https://mcp.../kg.php   →302→  https://car-er.kuwo.cn/....mp3  →200 audio/mpeg
```

**Android 的 `HttpURLConnection` 不跟随跨协议重定向（http → https）**，所以代理卡在第一个 301。
改为手动跟随（`instanceFollowRedirects=false` + 自己循环，最多 5 跳，保留 Referer/Cookie/Range）。

修复后真机日志：

```
LxStreamProxy: redirect 301 -> https://mcp.nianxinxz.com/share/ceshi/kg.php?...
LxStreamProxy: redirect 302 -> https://car-er.kuwo.cn/.../M800000bYDlc2XxKLs.mp3
state=PLAYING(3), position=20756 → 29825 (7 秒内推进)
```

**在线歌曲真的出声了。** 这是本项目第一次端到端跑通「搜索 → 取流 → 播放」。

> 顺带修正 §1 的结论：因为直链本身是 `http://`，只放行回环地址不够，
> `network_security_config.xml` 已改为 `<base-config cleartextTrafficPermitted="true" />`
> （安全降级，文件里写明了理由与回退方式）。

---

## 0.4 五平台搜索 + 封面 + 歌词（2026-10-19 追加）

### 新增能力

| 平台 | 搜索 | 封面 | 歌词 |
|---|---|---|---|
| 网易云 wy | ✅ `cloudsearch/pc`（自带 `al.picUrl`/`dt`） | ✅ `al.picUrl` | ✅ `api/song/lyric`（含翻译 `tlyric`） |
| QQ tx | ✅ `client_search_cp` | ✅ `y.gtimg.cn/.../T002R300x300M000{albummid}.jpg` | ✅ `fcg_query_lyric_new.fcg`（需 Referer） |
| 酷狗 kg | ✅ `song_search_v2` | ✅ `Image`（`{size}` 占位；`AlbumImage` 实测为空） | ✅ 两步 krcs→download，base64 |
| 酷我 kw | ✅ `search.kuwo.cn/r.s`（**伪 JSON**） | ✅ `web_albumpic_short`（`PICPATH` 实测为空） | ❌ 所有歌词入口均返回 `TP=ERROR REQUEST`/301，已封 |
| 咪咕 mg | ✅ `search_all.do` | ✅ `imgItems[0].img` | ✅ 搜索结果自带 `lyricUrl`，直接 GET 即纯文本 LRC |

封面 URL 已逐个校验返回 `image/*`（wy/tx/kg/kw/mg 五张全部 200）。

### 真机实测

```
builtin search wy 'qingtian' -> 30 results
resolved wy/1412022124 q=320k
redirect 301 -> https://mcp.nianxinxz.com/share/ceshi/wy.php?...
redirect 302 -> https://m8.music.126.net/.../ymusic/.../xxx.mp3
builtin lyric wy/1412022124 ok
state=PLAYING(3)

builtin search kg 'qingtian' -> 23 results
resolved kg/6DCE0785AE97F57D02DAAF48578C2E80 q=320k
builtin lyric kg/6DCE0785AE97F57D02DAAF48578C2E80 ok
state=PLAYING(3)
```

其余平台搜索均已跑通：`tx 30` / `kw 30` / `mg 20` 条。

### 本轮顺带修掉的三个 UI/解析问题

1. **深色模式下歌名是黑色**（用户报告）。根因：这些 `Text` 没指定颜色，而 Material3 的
   `LocalContentColor` **默认就是黑色**，该页面又没挂在 `Surface` 里 → 深色模式下不可见。
   已给歌名 + 两个空状态标题 + 脚本管理页两处补上 `colorScheme.onSurface`。
   验证方式：截图像素分析，标题区域亮像素 13.8%，与已知正常的副标题（14.0%）持平，
   纯背景仅 1.0%。
2. **音源 chip 行放不下五个音源**：原来是不滚动的 `Row`，第 5 个（咪咕）被裁到屏幕外，
   根本点不到。已改为 `horizontalScroll`。
3. **`&nbsp;` 未解码**：酷我返回 `晴天&nbsp;(Live)`。已在清洗函数里解码常见 HTML 实体。

### 仍未验证

- **tx / kw / mg 的播放**（取决于裤佬脚本对各平台 `musicUrl` 的实现），本次只验证了
  wy 与 kg 的完整链路。
- **kw 歌词**（接口已封，需要换源或第三方歌词库才能补）。

---

## 0.5 搜索框内切换「本地 / 在线」+ 统一列表样式（2026-10-19 追加）

原来在线搜索是独立整页（`LxSearchScreen`），和本地搜索（`SearchScreen`）两套界面。现在合并：

- **同一个搜索框**，右侧新增切换按钮：显示「在线」= 当前是本地模式，点一下切到在线；
  显示「本地」= 当前在线模式，点一下切回。
- 切到在线后，搜索框下方出现音源 chip（可横向滚动），输入即搜（350ms 防抖），不用回车。
- **结果列表复用本地那套 `SongItem`**（`ui/components/SharedComponents.kt:314`），
  所以两侧的行样式天然一致：标题、`时长 • 歌手`、`在线` 角标、相同的圆角分组。
  不存在"第二套在线专用行布局"要同步维护。
- 点击在线结果走与本地相同的 `onSongClick(song, list, "在线搜索", -300L)`，
  播放/队列/统计等后续行为完全复用。

实现要点：在 `SearchScreen` 的 Scaffold 内容 lambda 顶部加一个分支
（`if (ONLINE) { OnlineSearchPanel(...); return@Scaffold }`），本地那套代码**一行未改**，
所以不存在回归风险。

真机验证：

```
搜索框内按钮: 在线  [887,222][986,286]
切到在线后 → 网易云/QQ音乐/酷狗/酷我 chip 出现
输入 qingtian → 30 条结果，行样式与本地一致（"晴天 / 3:22 • 空匪 / 在线"）
点第一条 → resolved wy/1412022124 → builtin lyric ok → PLAYING(3)
切回本地 → 输入 burn → 全部/Burning、专辑/Not Going Under、艺术家/Maria Arredondo 正常
```

### 顺带修掉的一个老问题

在线结果里出现 `晴天&nbsp;(Live)`：酷我返回的是带 HTML 实体的名字。已在清洗函数里解码
`&nbsp; &amp; &lt; &gt; &quot; &#39;`。

---

## 0.6 切换按钮显示当前状态 + 在线歌曲下载（2026-10-19 追加）

### 切换按钮语义修正

原来按钮显示的是「点了会切到哪」（本地模式显示"在线"），容易误解成当前状态。
现在显示**当前模式**：本地模式显示「本地 + 手机图标」，在线模式显示「在线 + 地球图标」。
真机确认：本地模式 `[887,222][986,286]` = `本地`，切到在线后同一位置 = `在线`。

### 新增：在线歌曲下载

- 入口：在线搜索结果的每一行，在「更多」按钮左侧多一个下载图标
  （通过给 `SongItem` 增加可选的 `trailingContent` 插槽实现，**默认 null，其他所有调用点不受影响**）。
- 下载中该位置变成转圈，且不可重复点击。
- 完成后 Toast 提示，文件落到系统音乐库。

实现要点（`lx/download/LxDownloadManager.kt`）：

1. **复用流代理**：直接 GET `LxRepository.streamUrlFor()` 返回的
   `http://127.0.0.1:<port>/stream/<token>`，把「解析直链 + 带 Referer/Cookie + 跟随重定向」
   全部交给已有的代理逻辑，不重复实现防盗链。
2. **落到哪**：Android 10+ 用 MediaStore 写 `Music/Lune/`，并用 `IS_PENDING`
   保证「写完才可见」（避免半截文件被扫描到）；Android 9 及以下退回公共 Music 目录 +
   `MediaScannerConnection`（为此在清单里补了 `WRITE_EXTERNAL_STORAGE`，
   `maxSdkVersion="28"`，高版本不需要）。
3. **文件名**：`歌手 - 歌名.<ext>`，扩展名按响应 `Content-Type` 推断（flac/m4a/ogg/wav/mp3）。

真机实测：

```
LxDownload: start 晴天 <- http://127.0.0.1:39285/stream/lx_wy_1412022124_320k
LxRepository: resolved wy/1412022124 q=320k
LxDownload: done 晴天 -> content://media/external/audio/media/150826

content query: _display_name=空匪 - 晴天.mp3, relative_path=Music/Lune/, _size=8082329
```

即：8 MB 的 mp3 正常落盘、进入系统媒体库（其他播放器和本 App 的本地列表都能看到）。

### 已知限制

- 没有下载进度百分比（只有"转圈 / 完成 Toast"）；大文件（flac 30 MB+）期间只能等。
- 没有"下载队列/任务列表"，同时点多首会各自独立下载。
- 下载中断不会续传（重新下载会生成第二个文件，文件名相同则系统自动加序号）。

---

## 0.7 下载移入「更多」弹窗 + 注入封面歌词 + QQ 音乐置顶（2026-10-19 追加）

### 1) 下载挪进「更多」弹窗

- 在线搜索结果行上的下载图标**已移除**（`SongItem` 的 `trailingContent` 临时插槽也一并回退，
  避免留下死代码）。
- 下载改为 `SongOptionsBottomSheet` 里的一项，仅在 `song.isOnline` 时出现。
- 顺带修正：**在线歌曲不再显示「编辑信息 / 从设备删除」**——这两项对没有本地文件的在线歌
  毫无意义（删除会去删一个不存在的文件）。

真机验证：

```
在线歌曲弹窗： 下一首播放 / 播放列表 / 下载
本地歌曲弹窗： 下一首播放 / 播放列表 / 编辑信息 / 从设备删除   ← 回归通过，未受影响
```

### 2) 下载时注入封面与歌词

下载流程改成：**先下到缓存临时文件 → 用 jaudiotagger 写标签 → 再入库 → 删临时文件**。
标签内容：

| 字段 | 来源 |
|---|---|
| 标题 / 歌手 / 专辑 | `Song` |
| 封面（APIC） | `song.lxPicUrl`（搜索阶段带回的平台封面，限 5 MB，自动识别 jpeg/png） |
| 歌词（USLT） | `LxRepository.fetchLyric()`（内置歌词，纯文本 LRC） |

这样即使脱离本 App，其他播放器也能看到封面和歌词。

真机实测（网易云《晴天》）：

```
LxDownload: start 晴天 <- http://127.0.0.1:42929/stream/lx_wy_1412022124_320k
LxRepository: builtin lyric wy/1412022124 ok
LxDownload: tags ok title=晴天 cover=574431B lyric=1656字符
LxDownload: done 晴天 -> content://media/external/audio/media/150833
```

把文件拉回本地直接验 ID3 帧：

```
ID3 header: b'ID3\x04'          (ID3v2.4)
APIC present: True              (封面，PNG magic 在偏移 28)
USLT present: True              (歌词，能读到 "[00:00.000] 作词 : ...")
TIT2 / TPE1 present: True
```

### 3) QQ 音乐置顶

`LxSources.ALL` 顺序改为 `tx, wy, kg, kw, mg`，所以 chip 行第一个是「QQ音乐」，
默认选中源也随之变成 QQ 音乐。真机 dump 确认 chip 顺序：`QQ音乐 → 网易云 → 酷狗 → 酷我 → 咪咕`。

> ⚠️ 但实测 **QQ 音乐目前播不了**：脚本对 tx 返回
> `https://mcp.nianxinxz.com/share/ceshi/None`（资源 id 是 `None`），代理拿到 404。
> 这是「裤佬音源」脚本 tx 分支自身的问题（它的 kg / wy 分支都正常），不是本项目的 bug。
> 想让 QQ 音乐可用，需要脚本作者修 tx 分支，或改回用网易云/酷狗作为默认源。

---

## 0.8 入口整合：酷狗置顶 / 去掉重复在线入口 / 音源页进设置（2026-10-19 追加）

### 1) 酷狗置顶

`LxSources.ALL` 改为 `kg, tx, wy, kw, mg`，默认选中源随之变成酷狗。
真机 dump 确认 chip 顺序：`酷狗 → QQ音乐 → 网易云 → 酷我 → 咪咕`。

> 选酷狗做默认是有依据的：在「裤佬音源」上实测 **kg 的搜索→取流→播放→歌词全通**，
> 而 tx 分支脚本自身返回 `None` 会 404（见 §0.7）。

### 2) 去掉重复的在线搜索入口

主界面底部胶囊里的地球图标（`contentDescription="在线音源"`）已移除，
连同 `UnifiedHeaderPill` 的 `onOpenLxSearch` 参数、`showLxSearch` / `showLxScripts` 状态、
两段 `AnimatedVisibility` 包装一并删除；独立的 `LxSearchScreen.kt`（约 500 行）已**删除**——
它的能力已被 `SearchScreen` 里的「本地/在线」切换完全覆盖。

真机确认主界面 content-desc 里不再有 `在线音源`。

### 3) 「音源」页移入设置

- 新增 `ui/activities/LxScriptManagerActivity.kt`，宿主 `LxScriptManagerScreen`（导入/启用/删除
  + 在线总开关），已注册进清单（`exported=false`）。
- 设置页「通用」分区里，紧跟「音频」之后新增一行：
  **音源 / 导入并启用在线音源脚本**，点击进入上述页面。

真机验证：

```
设置 → 通用：音频 | 音源 | 保持屏幕常亮 | 重新扫描音乐库 ...
点击「音源」 → topResumedActivity = ...LxScriptManagerActivity
页面内容：在线音源开关 / 从 URL 导入 / 👖裤佬SVIP音源（支持：酷我、酷狗、QQ音乐、网易云、咪咕）
```

---

## 0.9 手势：播放页左滑进歌词 / 歌词页右滑回播放（2026-10-19 追加）

### 实现

- **播放页（`FullPlayer`）**：这个页面本来就有一个覆盖全屏的 `detectDragGestures`
  在处理「下滑收起 / 上滑执行 `swipeUpAction`」。没有新增第二套手势识别器，而是**在同一个
  手势里加了一个水平分支**：横向位移 > 90dp、明显大于纵向位移（1.8 倍）、且方向向左时
  调用 `onShowLyrics()`。这样不会出现两套手势抢事件的情况。
  `pointerInput` 的 key 也补上了 `onShowLyrics`，避免闭包捕获旧回调。
- **歌词页（`LyricsScreen`）**：在最外层 `Box(fillMaxSize)` 上加
  `detectHorizontalDragGestures`，累计水平位移超过 90dp 且向右时 `onBack()`。
  只消费水平拖动，纵向滚动照常。

阈值取 90dp（纵向是原有的 110dp），因为横向滑动通常幅度更小。

### 真机验证

```
播放页左滑  → topResumedActivity = ...LyricsActivity      ✅
歌词页右滑  → topResumedActivity = ...Lune                ✅
歌词页左滑  → 仍在 LyricsActivity（不会误触发返回）           ✅
歌词页上下滑→ 仍在 LyricsActivity（滚动不受影响）             ✅
```

---

## 0.10 手势开关进设置 + 搜索模式记忆（2026-10-19 追加）

### 1) 左右滑动切换歌词 → 可开关

- `SettingsManager` 新增 `isSwipeToLyricsEnabled`（键 `is_swipe_to_lyrics_enabled`，**默认开启**）。
- 设置路径：**设置 → 个性化 → 手势**，在「启用手势」和「更改手势」之间新增一行
  **「左右滑动切换歌词 / 播放页左滑进入歌词，歌词页右滑返回播放」**。
- 播放页（`FullPlayer`）与歌词页（`LyricsScreen`）两处手势都读这个开关；
  播放页那份还加进了 `pointerInput` 的 key 和 `ON_RESUME` 刷新，改完设置回来立即生效。

真机验证（直接读应用私有 SharedPreferences 确认落盘）：

```
点关 → <boolean name="is_swipe_to_lyrics_enabled" value="false" />
     播放页左滑 → 仍停留在 Lune（不进歌词）         ✅
点开 → value="true"
     播放页左滑 → LyricsActivity                    ✅
     歌词页右滑 → 回到 Lune                          ✅
```

### 2) 搜索页「本地 / 在线」记忆

- `SettingsManager` 新增 `searchOnlineMode`（键 `search_online_mode`）。
- 搜索页初始化时按这个值决定初始模式；每次点切换按钮时写回。

真机验证：

```
初次打开搜索           → 本地
切到在线后再打开搜索    → 在线        ✅（同进程内记忆）
杀掉进程重启后再打开    → 在线        ✅（持久化）
prefs: <boolean name="search_online_mode" value="true" />
```

---

## 0.11 在线播放中打开队列闪退 —— Compose key 冲突（2026-10-19 追加）

### 现象

在线歌曲播放中，点播放页的「队列」直接闪退。

### 根因（logcat 实抓，不是猜）

```
java.lang.IllegalArgumentException: Key "song_0" was already used.
If you are using LazyColumn/Row please make sure you provide a unique key for each item.
    at androidx.compose.foundation.lazy.layout.LazyLayoutMeasureScopeImpl.compose(...)
```

队列列表用 `key = "song_${song.id}"`。**在线歌曲的 `id` 恒为 `0L`**，于是播放列表里
每首在线歌都得到同一个 key `"song_0"`，Compose 在测量第二项时抛异常 → 主线程崩溃。

这是「id/uid 双轨」遗留问题的又一次爆发：底层早已改用 `uid`，但 UI 的列表 key 还留在 `id` 上。

### 修复

把这批列表的 key 全部换成 `song.uid`（本地 `local:<id>`、在线 `lx:<source>:<songmid>`，天然唯一）。
**没有只修一处**——全仓库扫了一遍 `key =` 里带 `.id` 的地方，凡是「歌曲列表」的一起修：

| 文件 | 位置 | 影响场景 |
|---|---|---|
| `BottomSheets.kt` | `"song_${s.id}"` → `uid` | 队列「即将播放」 |
| `BottomSheets.kt` | `"history_${s.id}"` → `uid` | 队列「最近播放」展开 |
| `DetailViews.kt` | 3 处 `it.id` → `it.uid` | 歌单/专辑/文件夹详情里的歌曲列表 |
| `Lune.kt` | 2 处 `it.id` → `it.uid` | 主界面歌曲列表/网格 |
| `PlaylistDialogs.kt` | `it.id` → `it.uid` | 「加入播放列表」选择列表 |
| `RecentlyAddedSection.kt` | `s.id` → `s.uid` | 首页「最近添加」 |

专辑/歌单/混音等**非歌曲**列表仍用各自的对象 id（它们本来就是唯一的），未改。

### 真机验证

```
在线歌播放中（pid=14938）→ 点「队列」→ 面板正常打开
  pid 仍为 14938（未重启），logcat 无 FATAL、无 "already used"
  面板内容：队列 / 最近播放(3) / 正在播放 / 即将播放 + 多条「在线」歌曲
展开「最近播放」→ 仍无崩溃
```

---

## 0.12 id → uid 全面归一（v0.1.3，2026-10-20 追加）

§0.11 修的是「Compose 列表 key 撞车」，那只是同一个病根的一个症状。
本节把病根拔掉：**底层早就用 `uid`，但所有"记住一首歌"的地方还留在 MediaStore `id` 上，
而在线歌的 id 恒为 `0`。**

### 病根清单（都在真机上复现过）

| 位置 | 旧写法 | 后果 |
|---|---|---|
| `SongOverride`（收藏/元数据） | PK = `songId: Long` | 所有在线歌共用一行：收藏一首 = 收藏全部；`song_overrides` 里实测出现 `songId=0, isFavorite=1` |
| `playback_stats` | `SONG_${song.id}` | 所有在线歌堆进 `SONG_0`，实测 **203 次播放**全记在同一"首"上 |
| 通知封面 | `currentNotificationArtSongId: Long?` | 两首在线歌 id 都是 0 → B 歌沿用 A 歌封面 |
| MediaSession / 可浏览媒体项 | `mediaId = currentSong.id.toString()`、`song_<id>` | 对外所有在线歌都是 `"0"`，车机/蓝牙分不清 |
| `QueueManager` 置顶/移尾 | `indexOfFirst { it.id == ... }` | 混合队列里拖 A 会移动 B |
| 恢复队列 | 只用本地库按 uid 反查 | 纯在线队列重启后退化成 1 首 |
| AI 偏好 `SongInteraction` | `songId: Long` + `Map<Long, …>` | 所有在线歌的偏好/转移概率合并 |
| 歌词缓存文件 | `${song.id}.lrc` | 所有在线歌共用 `0.lrc`，互相覆盖 |
| `SETTINGS.lastPlayedSongId` | `Long` | 记的永远是"0 号歌" |

### 改动

- **`tools/SongUid.kt`**：新增 `statsIdOf(uid)` / `uidFromStatsId(id)` / `fromLegacyIdKey(key)`。
  写和读共用同一对函数，避免两边格式漂移（这次就是靠它把"写 `SONG_<id>`、读 `SONG_<uid>`"钉死的）。
- **`tools/SongJson.kt`（新增）**：`Song ⇄ JSON` 快照（带 `UriTypeAdapter`）。
- **`data/MusicDatabase.kt`**：
  - `SongOverride.songId: Long` → **`songUid: String`（主键）**，并新增 `meta`（在线歌的 Song 快照）；
  - `PlaybackStats` 新增 `meta`（同上）；
  - `version = 8` + 手写 `MIGRATION_7_8`。
- **`tools/MetadataManager.kt`**：所有入口改成 uid；`setFavorite(song, fav)` 对在线歌同时写
  `meta`（整首 Song）与标题/歌手/封面；新增 `getFavoriteOnlineSongs()`；自定义封面文件名
  `cover_<uid>.jpg`（并清理老的 `cover_<id>.jpg`）。
- **`lx/LxRepository.kt`**：搜索结果回填收藏状态（`applyOverrides`，在线歌每次搜索都是新对象，
  不回填的话"上次收藏过"看起来像没收藏）；新增 `favoriteOnlineSongs()`。
- **`tools/PlaybackManager.kt`**：统计改 `SONG_<uid>` 并写 `meta`（4 处重复代码收敛成 `recordSongStats`）；
  收藏/`favoriteChanged` 用 uid；队列快照里附带在线歌的 Song 列表；`lastPlayedSongUid`。
- **`tools/MusicService.kt`**：通知封面判重改 uid；可浏览媒体项 id 改 `song_allsongs_<uid>` /
  `song_favs_<uid>` / `song_playlist_<pid>_<uid>`；解析端同步改。
- **`tools/LuneAudioPlayerAdapter.kt`**：MediaSession 的 `mediaId` / `MediaItemData` 用 uid。
- **`tools/playlist/QueueManager.kt`**：置顶/移尾按 uid。
- **`ai/model/AiModels.kt` / `ai/storage/AiTelemetryStorage.kt` / `ai/LuneAiEngine.kt`**：
  `SongInteraction.songUid: String`、内存 map 改 `Map<String, …>`，读盘时把老的纯数字 key
  迁移成 `local:<id>`。
- **`tools/LyricsStorageManager.kt`**：`<uid>.lrc`（`local_123.lrc` / `lx_wy_0039….lrc`），
  读取时回退老 `<id>.lrc`。
- **`ui/screens/ResumeScreen.kt`**：统计行按 uid 反查本地库，解析不出来时用 `meta` 快照重建
  （这样「最常播放」里终于能出现在线歌）；同时修掉一个**显示错位**：标题取"第一条能解析出歌的统计"、
  次数却取"原始第一条统计"，于是出现「你不要那样看着我的眼睛 · 203 plays」这种张冠李戴。
- **`ui/search/SearchScreen.kt`**：在线模式 + 空关键词时显示「收藏的在线歌曲」
  （这是在线收藏唯一入口，本地收藏仍在音乐库收藏页）；点爱心后同步更新结果列表，不会看起来没反应。
- **`ui/sheets/BottomSheets.kt`**：在线歌曲**隐藏「加入播放列表」**（见下方遗留）。`playlist_songs` 仍按 id。
- **`app/build.gradle.kts`**：versionCode 4 / versionName 0.1.3。

### Room 3 的迁移写法（踩坑记录）

`androidx.room3` 的 `Migration.migrate` 签名变了，**不再是** `migrate(SupportSQLiteDatabase)`：

```kotlin
object : Migration(7, 8) {
    override suspend fun migrate(connection: SQLiteConnection) {   // suspend + androidx.sqlite.SQLiteConnection
        connection.prepare("CREATE TABLE …").use { it.step() }     // 只能 prepare/step，没有 execSQL
    }
}
```

`androidx.sqlite.SQLiteConnection` 只暴露 `prepare(sql): SQLiteStatement`，所以语句执行是
`prepare(...).use { it.step() }`。

迁移 SQL 与 Room 导出的 `app/schemas/…/8.json` 逐字对齐（列顺序/类型/NOT NULL/PK 都一致）：

```sql
CREATE TABLE song_overrides_new (songUid TEXT NOT NULL, title TEXT, artist TEXT, album TEXT,
  genre TEXT, coverUri TEXT, isFavorite INTEGER NOT NULL, meta TEXT, PRIMARY KEY(songUid));
INSERT OR REPLACE INTO song_overrides_new (...)
  SELECT 'local:' || songId, title, artist, album, genre, coverUri, isFavorite, NULL FROM song_overrides;
DROP TABLE song_overrides; ALTER TABLE song_overrides_new RENAME TO song_overrides;
ALTER TABLE playback_stats ADD COLUMN meta TEXT;
UPDATE playback_stats SET id = 'SONG_local:' || substr(id, 6)
  WHERE type = 'SONG' AND id GLOB 'SONG_[0-9]*';
```

### 兼容性（升级不丢数据）

- **Gson 不使用 Kotlin 默认值**：`SavedPlaybackState.queueOnlineSongs` 声明成可空 + `orEmpty()`，
  否则老版本存下的 JSON 反序列化出 null，恢复队列时直接 NPE。
- AI 遥测：纯数字 key → `local:<id>`，历史偏好保住（真机 prefs 已验证）。
- 歌词：先读 `<uid>.lrc`，没有再读老的 `<id>.lrc` / `歌手_歌名.lrc`。
- 封面：写新命名时顺手删老的 `cover_<id>.jpg`；`clearMetadataOverride` 两种都清。

### 验证（本次实测）

构建：`BUILD SUCCESSFUL`，**34/34 单测通过**（新增 `SongUidTest` 5 条、`LyricsFileKeyTest` 3 条、
`SongInteractionTest` 3 条）。APK 34.1 MB，versionName 0.1.3。

**迁移（`adb install -r` 覆盖安装，保留数据，旧的 v7 库原样带过来）**：

```
迁移前：user_version=7
        song_overrides: 1 行 (songId=0, isFavorite=1)        ← 在线歌共用一行的实证
        playback_stats: 16 条 SONG 行，其中 SONG_0 = 203 plays
迁移后：user_version=8   identity=3bf6f520269252a6e7dbf530095e1911
        song_overrides: ('local:0', None, None, 1, meta=None)   ← 主键已改，meta 列存在
        SONG 行全部改写：SONG_local:0 / SONG_local:1204 … 共 16 条
        仍匹配 'SONG_[0-9]*' 的旧行：0 条
        启动无 FATAL、无 Room 校验异常
```

**收藏不再串味**：在「在线」搜索里收藏两首不同的酷狗歌：

```
('lx:kg:6DCE0785AE97F57D02DAAF48578C2E80', 'QingTian (小提琴版)', 'Y2002',              1, meta=575B)
('lx:kg:4CECB846B23AAAC3C54178471E3A549F', 'Qing Tian',            'TJ MUSIC HUNTERS', 1, meta=583B)
```

两行独立、各带完整 Song 快照（含 `lxHash` 等取流参数）。清空搜索框后，在线页显示
「收藏的在线歌曲」两条；杀掉进程重启后仍在。

**统计分列**：播放上面那首在线歌后：

```
SONG_lx:kg:4CECB846B23AAAC3C54178471E3A549F  playCount=1  meta 里有 title/artist/lxSource/songmid
SONG_local:0                                  playCount=203  ← 未再增长（旧的垃圾聚合行）
```

**队列跨重启**：`shared_prefs/lune_state.xml`

```
currentUid=lx:kg:4CECB846…, queueUids=[lx:kg:4CECB846…, lx:kg:6DCE0785…],
queueOnlineSongs=[Qing Tian, QingTian (小提琴版)], wasPlaying=false
```

重启后：正在播放卡片显示 `Qing Tian / TJ MUSIC HUNTERS`（从快照重建），
`state=PAUSED, position=24456, speed=0.0` —— **没有自动播放**（§0.2 的行为没有回归）。

**AI 遥测**：`lune_ai_telemetry.xml` 里的 key 全是 `local:1284`、`local:11062` 这种，
说明老的纯数字 key 已被迁移。

### 遗留

| 项 | 说明 |
|---|---|
| `playlist_songs` 仍按 MediaStore id | 在线歌加进播放列表会互相顶掉（PK 是 playlistId+songId）。所以「更多」里对在线歌**暂时隐藏**了「加入播放列表」。修法：该表加 `songUid`（或整表 uid 化），读的时候复用 Room 里的 Song 快照重建在线歌。 |
| `SONG_local:0` 垃圾行 | 迁移后仍有 203 plays 的聚合行。它没有任何单曲意义、无法拆分，UI 已忽略（`resolveStatsSong` 过滤 `local:0`），数据保留不删。 |
| 本地列表的"当前播放"高亮 | 仍按 id（`currentlyPlayingId: Long?`）。这些列表（歌单/专辑/文件夹）本来就只含本地歌，暂不改。 |
| 在线收藏的取消入口 | 收藏列表里点爱心即可取消；但列表刷新有 300ms 延迟（等 DB 写入），不是实时。 |

---

## 0.13 列表头「定位到正在播放」（v0.1.4，2026-10-20 追加）

需求：本地音乐「全部」页要有办法一键回到正在播放的那首歌。

第一版做成了悬浮球（右下角），用户否掉了——"不要悬浮的，放在我画圈的位置"。
画圈的位置就是列表头部动作行（网格切换 / 播放 / 排序）**最右边那个空位**，
所以改成头部第 4 个按钮，不再往列表上加浮层。

### 实现

- `SongsListHeader` 新增 `onLocateCurrent: (() -> Unit)?` + `isCurrentSongLocatable: Boolean`，
  在动作行末尾渲染一个 44dp 的 ◎ 按钮（`Icons.Default.MyLocation`，与排序/随机按钮同形状）。
  - `onLocateCurrent == null`（没有正在播放的歌）→ 不渲染，头部不出现空位；
  - `isCurrentSongLocatable == false`（歌已在屏幕上 / 不在本列表）→ 按钮仍在，只是 tint alpha 0.38、点了没反应。
    保持一排按钮不跳来跳去。
- 定位目标 `targetIndex` 在 `Lune.kt` 的 LIST 分支里算：`pageSortedSongs.indexOfFirst { it.uid == cs.uid }`。
  - 顺带修掉旧写法 `it.id == cs.id`：在线歌 id 恒为 0，会命中列表里第一首；
  - 也去掉了"正在播放的上下文必须等于本列表"的限制（`activePlaylistId == pageContextId`），
    否则从专辑/歌单播的歌在「全部」里永远定位不到（旧的头像按钮就是这个限制）。
- 滚动复用已有的 `fastSmoothScrollToItem`：列表用 `LazyListState`，网格用 `LazyGridState`，
  两种布局都支持（`isTargetOnScreen` 分别读各自的 `layoutInfo.visibleItemsInfo`）。
- 头部原有的「滚动到当前」图标（`onScrollToCurrent`，只出现在正在播放的那份列表）保留不动。

### 真机验证（Xiaomi 设备 / Android 16）

```
头部动作行（「全部」页）: Toggle Layout [227,451][292,516] | 播放 pill | 排序歌曲 [762,451][827,516] | 定位到正在播放 [931,451][996,516]
滚动到「一万次悲伤」附近 → 点 [963,483]
  → 列表回到「六月的雨」（正在播放，行首有播放指示、整行高亮），按钮随即变淡（因为已经在可视区）
网格模式（Toggle Layout 切换）重复同样操作 → 同样回到「六月的雨」，无崩溃
```

### 注意

这个按钮在**头部动作行**里，所以把「分区标题」隐藏之后（顶栏 ⌃/⌄ 开关）整行都会收起来，
按钮也一起消失——和播放/排序按钮的行为一致。要一直能看到它，就保持分区标题开启。

---

## 0.14 新分区「在线音乐」：平台发现（v0.1.5，2026-10-20 追加）

需求：分区里加一页「在线音乐」，显示五个平台的"发现"；平台标签放在顶部**折叠收纳**里，
位置与其它页的头部动作行一致（用户指出第一版把它做成了大卡片，位置不对）。

### 页面结构

```
┌ HeaderSurface（与「全部」页动作行同一位置/高度，一行）────────────────┐
│ [酷狗音乐] [QQ音乐] [网易云音乐] [酷我音乐] [咪咕音乐]（可横向滚动）      │  ← 展开
└──────────────────────────────────────────────────────────────────┘
   发现（官方榜单）两列卡片 → 点开榜单 → 歌曲列表（SongItem，与本地行一致）
```

- **收放用的是顶栏那个「分区标题」按钮（`showSectionHeaders`）**，和「全部」页等完全同一个开关：
  展开时这一行是五个平台标签，收起时整行消失、屏幕上只剩列表。
  （第一版在行内做了一对自绘的 ⌃/⌄，用户否掉了："用这个按钮收纳展开，跟全部页一样"。）
- 标签行 `horizontalScroll`，五个平台不用挤成一排。
- 榜单卡片：封面 + 榜名 + 「更新：每天 · 1036 万播放」这类副标题。
- 榜单里的歌**直接是 `Song`**（带 uid / 封面 / 收藏状态），所以点播、收藏、下载、队列、
  通知封面全部复用已有链路，没有第二套播放代码。

### 数据来源（[lx/discover/LxDiscover.kt](lunelx-handoff/app/src/main/java/com/lunelx/music/lx/discover/LxDiscover.kt)）

| 源 | 榜单列表 | 榜单歌曲 | 现状 |
|---|---|---|---|
| kg | `mobilecdn.kugou.com/api/v3/rank/list` | `/rank/song?...&version=8000` | ✅ 26 个榜，TOP500 取到 60 首 |
| wy | `music.163.com/api/toplist` | `api/playlist/detail?id=` | ✅ 飙升榜/热歌榜… |
| tx | `c.y.qq.com/v8/fcg-bin/fcg_myqq_toplist.fcg` | `fcg_v8_toplist_cp.fcg` | ✅ 巅峰榜系列 |
| kw | `www.kuwo.cn/api/www/bang/bang/bangList` | — | ❌ 匿名 `The request is illegal!`（只下发反爬 cookie，无 `kw_token`） |
| mg | 多个 bang 路径 | — | ❌ 全部「路由请求不支持」 |

播放依旧走音源脚本（`musicUrl`）：**能看不依赖脚本，能播才依赖**。

### 两个踩过的坑（都很隐蔽）

1. **酷狗 CDN 的 https 证书不匹配**：`mobilecdn.kugou.com` 的证书是 `*.cdn.myqcloud.com` 的，
   走 https 真机直接 `SSLPeerUnverifiedException: Hostname mobilecdn.kugou.com not verified`，
   界面上只表现为"没有取到榜单数据"。改回 `http://`（全局已放行明文）后正常。
2. **`rank/song` 必须带 `version=8000`**：不带它时接口只回 `total=2` 的"榜内变动"，
   榜单看上去只有两三首；带上才是完整榜单（TOP500 → `total=500`，一次取 60 首）。
3. **榜单歌曲的封面字段是 `album_sizable_cover`**：`album_cover` / `cover` 恒为 null，
   只取那两个字段的话"发现"里点进榜单后整列都是空封面（用户报的 bug）。
   该字段带 `{size}` 占位，替换成 400 即可。

### 真机验证（Xiaomi 设备 / Android 16）

```
分区菜单: 概览 / 全部 / 在线音乐 / 播放列表 / 专辑 …
在线音乐页（酷狗）: 标签行 → TOP500(更新：每天 · 1036 万播放)、国潮音乐榜、民谣榜、纯音乐榜、电音榜、DJ热歌榜…
点顶栏 ⌃/⌄：展开 → 标签行出现（酷狗/QQ/网易云…），列表下移；
              收起 → 整行消失，屏幕上只剩榜单列表
点 TOP500 → 60 首；点第一首 → LxRepository: resolved kg/9092283E… q=320k → 301→302→PLAYING(position 8840)
QQ音乐 → 巅峰榜·流行指数(795 万播放)；网易云音乐 → 飙升榜(每天更新)
酷我音乐 / 咪咕音乐 → 「该平台暂未提供可用的发现数据…」+「去搜索」
```

### 遗留

- 酷我 / 咪咕 的发现需要平台登录态（本 App 没有账号体系）；要补得先做登录或找可用的匿名代理。
- 榜单只取第一页（60 首）；深翻页没做（接口支持 `page`，需要 UI 上的"加载更多"）。
- 榜单卡片没做下拉刷新，只有失败时的「重试」。

---

## 0.15 「没有播放胶囊但音乐在响」与发现页空封面（v0.1.6，2026-10-20 追加）

用户报："播放胶囊没有，但是在播放音乐"。

### 根因（两条，互相独立）

1. **迷你播放器被"收起"了，而且这个状态是持久化的**
   - `SettingsManager.isMiniPlayerMinimized` 存进 SharedPreferences；收起后播放条会变成一个
     圆形小粒子（右下角那块专辑封面），看起来就像"胶囊没了"。
   - 更糟的是它**跨进程保留**：收起一次，之后每次冷启动都没有胶囊 —— 只剩音乐在响。
   - 现场证据：`shared_prefs/lune_settings.xml` 里 `is_mini_player_minimized = true`。
   - 修复（`LuneApplication.onCreate`）：**冷启动把该标记重置为 false**。
     收起只是临时视图偏好，不该跨重启保留。
2. **"分区被隐藏就清空播放"的判断误伤了在线音乐**
   - `Lune.kt` 里有个守卫：`activeCategory !in folders` 时调用 `stopAndClearQueue()`
     （本意是"这个分区被隐藏了，别继续放它"）。
   - 在线音乐第一版把播放上下文写成 `category = "在线发现"`，它永远不在 `folders` 里，
     于是只要 `folders` 重算（歌库同步、改默认分区、分区显隐设置…）就会把 `currentSong` 清成 null。
   - 而 `stopAndClearQueue()` 只调用 `stopSelf()`，不保证立刻销毁前台服务 →
     **状态清空了、音乐还在响**。
   - 修复三处：
     - 播放上下文改用合法分区 id `category = "ONLINE"`；
     - 守卫只对**已知分区 id**生效（新增文件级 `KNOWN_SECTION_IDS`），
       "在线搜索" 这类临时上下文不再触发清空；
     - `stopAndClearQueue()` 先 `musicService?.pause()` 再清状态，杜绝幽灵播放。
   - 另外给 Activity 重建/进程回收补了一条兜底：`MusicService.lastPlayedSong`
     （在 `setPlayerDataSource` 里记录）在恢复状态时被认领回 `PlaybackManager`，
     避免"服务在放、UI 不知道放的是哪首"。

### 顺手修掉：酷狗榜单歌曲没有封面

`rank/song` 里 `album_cover` / `cover` 恒为 null，真正的封面字段是 `album_sizable_cover`
（带 `{size}` 占位）。改成优先取它，点进榜单后歌曲行就有封面了。

### 真机验证（Xiaomi 设备 / Android 16）

```
安装后冷启动：底部播放胶囊回来（「我不难过 / 孙燕姿」），is_mini_player_minimized=false
在线音乐 → 播放榜单歌曲：active_category=ONLINE，胶囊在，state=PLAYING
分区菜单里把默认分区改成「全部」（触发 folders 重算）→ 胶囊仍在、currentSong 未清空、音乐未断
酷狗 TOP500 歌曲列表：天龙八部之宿敌 / 无奈的思绪 / 我不难过 … 每行都有专辑封面
```

---

## 0.16 播放"假活"看护 + 深色模式下的黑色标题（v0.1.7，2026-10-20 追加）

用户报两件事：
1. **在线音乐播放一会儿就不响了，但状态还是"播放中"**；
2. **深色模式下，榜单列表顶部的标题和返回按钮是黑色**（黑底黑字，看不见）。

### 2）深色模式黑色标题 —— 直接修掉

`OnlineMusicScreen` 没有挂在 `Surface` 里，Material3 的 `LocalContentColor` 默认是**黑色**
（不是主题的 onSurface）。所以 `Text` / `Icon` 不给 `color` 时，深色模式下就是黑的。
修复：整页算一个 `textColor`（blur+深色 → 白，blur 浅色 → 近黑，普通 → `onSurface`），
套到榜单标题、返回箭头、榜单卡片标题上。

真机验证（系统深色模式）：`TOP500 / 60 首` 与 ← 都是浅色，榜单卡片标题可读。

### 1）"没声了但还在放" —— 根因未能确定性复现，先加看护

排查记录（真机采样）：
- 位置持续推进、`AudioTrackImpl` 的 `maxAmplitude` 也有非零值，说明"能出声"这条链路本身没坏；
- 跨曲时会新建一个 `ExoPlayerWrapper`（新 audioSessionId）并重新解析直链，属于既有设计；
- 但 `PlaybackManager.isPlaying` 是**手动标志**，只是"用户按下播放"的意图，
  并不反映播放器真实状态；`LuneAudioPlayerAdapter` 报给系统媒体会话的状态也是由它推出来的。
  所以一旦底层停在 BUFFERING（例如本地代理到 CDN 的 TCP 已经断了、ExoPlayer 在退避重试），
  就会出现「没声音 + 状态仍是播放中」。

新增**播放假活看护**（`PlaybackManager.watchForStalledPlayback`，挂在原来每秒的统计循环里）：
- 位置连续 6 秒不动且 `isPlaying` 为真 → 判定卡死；
- 第 1 次：丢掉这首歌的直链缓存（`LxRepository.releaseStream`）+ `seekTo(当前位置)`，
  逼 ExoPlayer 重新发起 HTTP 请求（代理会重新解析直链）；
- 第 2 次仍不动 → `playNextFromService(true)` 跳过这首；
- 位置一旦前进，计数与恢复次数清零。

> 这是**兜底**而不是根因修复：真正的根因（CDN 掐流 / 连接空闲超时 / 代理侧读取超时）
> 需要更长的时间窗采样才能定位。看护至少能让"没声但状态播放"在 6~12 秒内自动恢复或跳过，
> 不会一直僵在那里。

---

## 1. CRITICAL — 本地流代理被明文流量策略拦截

`LxStreamProxy.kt:114` 把播放地址拼成明文 HTTP：

```kotlin
fun urlFor(token: String): String = "http://127.0.0.1:$port/stream/$token"
```

而 `AndroidManifest.xml` **既没有** `usesCleartextTraffic`，**也没有** `networkSecurityConfig`，
`res/xml/` 下也没有任何网络安全配置。项目 `targetSdk = 37`，从 API 28 起明文流量默认全禁，**回环地址也不例外**。

真机上链路第一步就断，ExoPlayer 抛：

```
java.net.UnknownServiceException: CLEARTEXT communication to 127.0.0.1
not permitted by network security policy
```

直接检查交付的 APK：清单里 `usesCleartextTraffic` / `networkSecurityConfig` /
`cleartextTrafficPermitted` 三个字符串**均不存在**，所以这是**交付物里就有的问题**，不是源码笔误。

**修复**：新增 `res/xml/network_security_config.xml`，只放行回环地址，其余仍强制 HTTPS，
并在 `<application>` 上挂 `android:networkSecurityConfig`。

> ⚠️ 后续风险：部分脚本拿到的直链是 `http://`（国内 CDN 常见），那些请求不受回环放行保护。
> 若真机上出现「本地歌正常、在线歌报 CLEARTEXT 到某个 CDN」，需要放宽到
> `<base-config cleartextTrafficPermitted="true" />`。那是一次安全性降级，
> 所以默认**不**打开，已在文件里注释好，等确认后再动。

---

## 2. CRITICAL — 403 重试会解析成另一首歌

```kotlin
// 旧代码
private suspend fun resolveForRetry(old: LxResolvedStream): LxResolvedStream? =
    registry.values.firstOrNull()?.let { resolve(it) }
```

传入的 `old` 根本没用，改从 `registry` 里**随便取第一个**。而 `registry` 只增不减
（`LxRepository.releaseStream()` 全仓库没有调用点），所以播放列表越长，`firstOrNull()`
越不可能是当前这首。

后果：B 歌直链 403 → 代理去解析 A 歌 → 拿到 200 → **把 A 的音频当成 B 推给播放器**。
用户听到串歌，或者"点了没反应然后突然放别的"。而且 A 的缓存若不巧也没过期，还会白跑一次完整脚本请求再 502。

**修复**：把 `LxStreamSpec` 一路传到 `forward()`，重试用**当前这首**的 spec；
并引入 `invalidate(token)` 钩子，重试前先清缓存——否则 `resolve()` 命中 10 分钟 TTL
会原样返回**那条已经死掉的 URL**，重试等于没做。

---

## 3. HIGH — 能力白名单吞掉 `search`（搜索的真正死因之一）

1. 脚本上报：`lx.send('inited', {sources: {kg: {actions: ['musicUrl','search']}}})`
2. `user-api-preload.js` 的 `handleInit` 不原样转发，而是拿白名单过滤：

   ```js
   const supportActions = { kw: ['musicUrl'], kg: ['musicUrl'], ... }  // 没有 'search'
   actions: actions.filter(a => userSource.actions.includes(a))
   ```

   → 上报结果被裁成 `['musicUrl']`
3. `LxScriptEngine:168` 的 `supportsAction(..., "search")` → **false**
4. `LxRepository:216-220` 判定「没有支持搜索的脚本」→ **直接 return emptyList()**，一次都不问脚本
5. UI 显示「脚本不支持搜索」

**无论脚本多完整，都不可能被调用。** 而文档把它记成了「架构限制」。

### 运行时证据

`tools/preload-check.js` 把真实 preload 放进 Node `vm` 沙箱，让声明了 `['musicUrl','search']` 的脚本走完 init + 一次 search：

```
修复前：reported actions for kg: ["musicUrl"]
        >>> "search" survived filtering: false
        >>> search result passed through to native: true   ← 桥是好的！
```

第二行是重点：**请求桥完全正常**，唯一的问题是白名单漏了一个字符串。

**修复**：五个音乐音源的白名单加上 `'search'`，并写明「漏项会静默降级」。

---

## 4. HIGH — 搜索结果解析没拆信封，导致无条件返回 null（第二个死因）

即使脚本能搜、桥也通了，结果照样丢。`handleEventResponse` 交给等待者的**是整个信封**：

```kotlin
eventWaiters.remove(requestKey)?.complete(obj)   // { requestKey, status, result: {...} }
```

`parseEventResult` / `parseLyricResult` 都正确地先取 `result`，唯独 `parseSearchResult`
直接在**顶层**找 `data` / `list`：

```kotlin
val data = obj.get("data")?.takeIf { it.isJsonObject }?.asJsonObject   // 永远为 null
```

信封里只有 `requestKey` / `status` / `result`，没有 `data`，所以这个函数**对任何脚本都返回 null**。
我的 Node 验证直接印证了形状：`{"source":"kg","action":"search","data":[...]}` —— 载荷在 `result` 里面。

**修复**：先拆 `result`（并读取 `status:false` / `errorMessage` 记日志），再在载荷里依次识别
`数组` / `{data:[...]}` / `{list:[...]}` / `{data:{list:[...]}}`。

---

## 5. HIGH — 交付的 APK 里没有 search 分支

- 源码 `user-api-preload.js`：22035 字节，含 `case 'search':`
- APK 内同名资产：21551 字节，**不含**
- diff 只有这一处

后果：APK 里 `handleRequest` 走到 `search` 没有 `case` 命中，`result` 保持 `undefined`，
回给原生 `{status:true}` 但没有 result，照样搜不到。

`preload-check.js` 跑交付版 preload，恰好只有搜索两项失败：

```
FAIL  能力上报不做静默裁剪     上报的 actions = ["musicUrl"]，缺少 'search'
FAIL  search 请求能把脚本结果透传   response.result 为空
4/6 通过
```

**是「整个 APK 太旧」还是「只有资产没刷新」？** 查了 DEX：`LxSearchScreen`、
`LxScriptManagerScreen`、`requestSearch`、`supportsAction` 全在，连「脚本不支持搜索」
这句 UI 文案都在字符串池里。**Kotlin 侧是新的，只有那个 JS 资产是旧的。**
增量打包没重新合并 `assets/`。

**已重新构建并验证**：新 APK 的 `assets/lxmusic/user-api-preload.js` 为 22741 字节，
与源码逐字节一致、含 `case 'search'`，`preload-check.js` 跑新 APK 的资产 **6/6 通过**
（旧 APK 4/6）。产物见 `lunelx/LuneLX-v0.1.2-debug-fixed.apk`。

---

## 6. HIGH — 在线歌时长单位错了 1000 倍

```kotlin
duration = interval,   // interval 是**秒**（"03:45" → 225）
```

而 `Song.duration` 全程按**毫秒**：`formatDuration` 除以 1000、`PlaybackManager`
用 `duration * 0.75` 判跳播、`buildExtra` 用 `duration / 1000` 回填脚本、
`LuneAiEngine` 用 `> 300_000L` 判「听了 5 分钟」。

于是每首在线歌都是 **225 毫秒**：进度条 ≈ 0、列表显示 0:00、一开播就被判成「已播 75%」
记成跳播、时长统计记 0。**修复**：`interval * 1000L`。

---

## 7. MEDIUM — `onPrepared` 不是一次性的

`MediaPlayer.onPrepared` 每次 prepare 只回调一次；ExoPlayer 的 `STATE_READY` 在
`BUFFERING→READY`（缓冲/拖动）和 `ENDED→READY`（播完再拖）时都会再次到达。
而 `MusicService` 的两处 `onPrepared` 都按「一次性」写：里面直接 `start()`、
重建整条音效链、`seekTo(保存的进度)`。

会出现的现象：**用户在缓冲时点了暂停，缓冲完成后歌自己播起来**；
**队列播完，最后一首从 0:00 自己重放**；恢复播放的歌每次卡顿都跳回原进度；
每次缓冲都重建 EQ/BassBoost/Reverb 并清空、重新下载歌词。

**修复**：加 `preparedOnce` 标志（`setDataSource` 时重置），每个数据源只回调一次。

---

## 8. LOW

- `LxSources.ALL` 漏了 `MIGU`（常量定义了却没进列表）。`availableSources()` /
  `isSourceAvailable()` 都以它为准，于是咪咕脚本声明了也不显示、`buildSongById("mg")` 返回 null。
- `gradle.properties` 里 `android.aapt2FromMavenOverride=/opt/aapt2shim/aapt2` **生效中**，
  桌面一构建就报一句很难懂的 AAPT2 错误。README 说「桌面可以删掉」，但默认值不该是坏的。已注释掉。

---

## 9. 新增：无工具链也能跑的回归测试

```bash
node tools/preload-check.js                                  # 查源码里的 preload
node tools/preload-check.js path/to/apk-preload.js           # 查某个产物
```

6 个用例，退出码可接 CI：能力上报不裁剪 / search 透传 / musicUrl 直链 / 歌词原文+翻译 /
脚本抛错回 status=false / 非 http(s) 地址被拒。

> 坑：preload 结尾 `freezeObjectProperty(globalThis)` 会冻结整个全局对象（连
> `Array.prototype` 都冻结），所以每个场景必须新建 `vm` context，捕获数组放在宿主机侧闭包，
> 不能挂 `globalThis`——否则连 `push` 都会抛 `Cannot assign to read only property 'length'`。

---

## 10. 未修复的问题（按优先级，供后续排期）

以下都是**可信的真缺陷**，但修复需要跨文件改造或并发重构，在没有编译器的环境里硬改风险高于收益，
因此只记录不动手。

### P0 — 会让在线播放「看起来能用其实不能用」

| 缺陷 | 位置 |
|---|---|
| 解析期间不向客户端写一个字节，而 Media3 `DefaultHttpDataSource` 默认 **8 秒**超时；解析可能要 30 秒+ → 首次播放/过期后首次拖动直接超时跳歌。修：给 `DefaultHttpDataSource.Factory` 配 ≥30s 超时，或 `streamUrlFor` 里预解析 | `LxStreamProxy.kt:151`、`ExoPlayerWrapper.kt:89` |
| 播放错误时无条件 `playNextFromService(true)`：断网时会把整个队列快速跳完，用户什么都看不到；恢复播放则相反，只记日志但 UI/通知一直显示「播放中」 | `MusicService.kt:864`、`1255` |
| 收藏在线歌按 `id` 走：所有在线歌 `id == 0`，`indexOfFirst { it.id == 0L }` 永不命中，于是走 else 分支把**在线歌追加进本地库缓存**（`songs_cache.json`），还会产生 `SongOverride(id=0)` 一条共享记录 | `PlaybackManager.kt:1478`、`MusicProvider.kt:77` |

### P1 — 队列/统计/界面串味

| 缺陷 | 位置 |
|---|---|
| 队列拖动/置顶仍按 `id`：混合队列里会删错歌、插入到错误位置（少一首、多一首） | `QueueManager.kt:8`、`PlaybackManager.kt:1578/1606` |
| 播放统计 `"SONG_${song.id}"` → 所有在线播放堆进同一行 `SONG_0`，Resume 页 Top 歌曲永远不含在线歌 | `PlaybackManager.kt:506/856/948/1077` |
| 通知/锁屏封面按 `id` 去重：两首在线歌 `id` 都是 0，B 歌会沿用 A 歌的封面 | `MusicService.kt:1655` |
| 恢复队列时 `lx:` uid 全部被 `mapNotNull` 丢掉，纯在线队列重启后退化成 1 首 | `PlaybackManager.kt:147` |
| MediaSession 的 `mediaId` 用 `id`，所有在线歌对外都是 `"0"` | `LuneAudioPlayerAdapter.kt:82` |
| AI 推荐按 `song.id` 记交互 → 所有在线歌被当成同一首 | `LuneAiEngine.kt:76/105/118/130` |

### P2 — 稳定性/资源

| 缺陷 | 位置 |
|---|---|
| QuickJS context 在 IO 线程 `close()`，而脚本线程可能还在 `evaluate` 里 → 释放后使用，**原生段错误**（`unloadScript` 的写法是对的，这里没有） | `LxScriptEngine.kt:125` |
| `loadScript` 无生命周期保护：`CancellationException` 在清理**之前**抛出，取消一次就永久泄漏 HandlerThread + QuickJS 堆；并发 load 会双重初始化 | `LxScriptEngine.kt:71/117/120` |
| `handleInitEvent` 忽略 `status:false`：脚本初始化失败仍报「导入成功」，源列表空且无日志 | `LxScriptEngine.kt:625` |
| OkHttp 请求的构造在 `try` 之外：非法 URL/header 抛 `IllegalArgumentException`，脚本的 requestQueue 永不回包，白等满 30 秒 | `LxScriptEngine.kt:664-729` |
| `_capabilities` 有丢失更新：`refreshScripts` 整体赋值与 toggle 的读-改-写竞争，刚启用的脚本会被抹掉 | `LxRepository.kt:103` |
| 初始化期间 unload 是空操作，之后 `onSuccess` 又把它注册回去 → 删掉的脚本仍常驻 | `LxScriptEngine.kt:132` |
| `removeScript` 在主线程做全量磁盘 IO（读每个 .js 跑 4 个正则） | `LxRepository.kt:129` |
| `requestPic` 形状不匹配，永远返回 null（当前无调用点，潜在） | `LxScriptEngine.kt:218` |
| `stop()` 后再 `start()`：线程池已 `shutdownNow` 无法重启，首个客户端触发未捕获 `RejectedExecutionException`（当前无调用点） | `LxStreamProxy.kt:89` |

---

## 11. 关于 §6 的方案取舍（实测后的新结论）

用户在 lx 音乐里用「👖裤佬SVIP音源」**能搜索、能播放**，因此怀疑「脚本其实支持 search」。
把脚本从手机里拉出来逐字核对后：**脚本里 `search` 出现 0 次**，
每个音源的 `actions` 数组只有一个元素（`musicUrl`），`lyric`/`pic` 也是 0 次。

这恰恰解释了为什么 lx 音乐能搜：**lx 音乐的搜索是客户端自己调各平台公开搜索 API 完成的**（即本文的
方案 B），搜到 songmid 之后再交给脚本的 `musicUrl` 取直链。所以「lx 能搜」证明的是 **lx 有内置搜索**，
不是脚本有 search 能力。

结论：

- 方案 A（只跑脚本）→ 只有「自己实现了 search 的第三方脚本」可用；裤佬这类官方风格脚本用不了。
- **要和 lx 音乐体验对齐，必须做方案 B**（内置各平台搜索 API）。这不再是「可选的优化」，
  而是「有没有搜索」的分水岭。
- 本项目当前的搜索链路已经被修通（§3/§4/§12），**只差一个能产出候选歌曲列表的来源**——
  把方案 B 的搜索结果直接喂进现有的 `LxRepository.search` 流程即可，UI 与播放链路都不用改。

---

## 12. 下一步

构建与真机安装**都已完成**（新包已装到设备上）。剩下唯一没打通的环节是：
**拿到一个当前可用的、支持 search 的真实音源脚本**，然后在在线页搜一首真歌、点播放、听声音。

```bash
# 换新包
adb install -r lunelx/LuneLX-v0.1.2-debug-fixed.apk

# 盯日志（一次在线播放应该看到这几步）
adb logcat -s LuneLX ExoPlayerWrapper MusicService
#   LxStreamProxy: listening on 127.0.0.1:<port>
#   LxRepository: resolved <source>/<songmid> q=<quality>
#   ExoPlayerWrapper: ExoPlayer created, audioSessionId=N   (N > 0)
#   全程不应出现 CLEARTEXT / "has no search-capable script" / "no script for "
```

还需要一台设备才能判定的：是否真的出声、Range 拖动、10 分钟缓存过期后的重连、
在线歌词渲染、混合队列 UI；以及 §10 里 P0/P1 那批「所有在线歌 id 都是 0」的串味问题
（收藏 / 统计 / 封面 / 队列 / MediaSession）——它们都需要改 key 为 uid，属于跨文件改造。
