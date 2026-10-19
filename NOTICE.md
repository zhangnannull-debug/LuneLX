# NOTICE — 来源、许可与第三方声明

本项目（LuneLX）是自由软件，以 **GNU GPL-3.0-or-later** 发布，完整许可文本见 [LICENSE](LICENSE)。
它是下列作品的衍生 / 参考实现，相应版权与许可声明一并保留。

## 上游与参考实现

| 项目 | 在本项目中的角色 | 许可 | 链接 |
|---|---|---|---|
| **Lune** | 基础播放器（本地媒体库扫描、音效链、Widget、Compose UI） | GPL-3.0 | https://github.com/MrDemonc/Lune |
| **PixelPlayerOLX** | `LxScriptEngine`（QuickJS 音源脚本引擎）的实现参考 | GPL-3.0 | https://github.com/MiNoMinoaa/PixelPlayerOLX |
| **lx-music-mobile** | 音源脚本 API 规范；`app/src/main/assets/lxmusic/user-api-preload.js` 桥接层 | Apache-2.0 | https://github.com/lyswhut/lx-music-mobile |
| **QuickJS Android wrapper** | JavaScript 沙箱 | MIT | https://github.com/HarlonWang/quickjs-android-wrapper |

> `user-api-preload.js` 来自 lx-music-mobile（Apache-2.0）。若你分发本项目的二进制，
> 请同时保留该文件中的版权声明与 Apache-2.0 许可文本（https://www.apache.org/licenses/LICENSE-2.0）。

## 主要第三方库

| 库 | 用途 | 许可 |
|---|---|---|
| AndroidX Media3 (ExoPlayer) | 音频播放、媒体会话 | Apache-2.0 |
| Jetpack Compose / Material 3 | UI | Apache-2.0 |
| androidx.room3 | 本地数据库 | Apache-2.0 |
| Coil | 图片加载 | Apache-2.0 |
| OkHttp | HTTP 客户端 | Apache-2.0 |
| Gson | JSON | Apache-2.0 |
| jaudiotagger | 音频标签读写（下载时写封面/歌词） | LGPL-3.0 |

## 内容与商标

- 本项目**不包含**任何音频、专辑封面或歌词内容；运行时获取的封面/歌词/歌曲信息版权归各平台与版权方所有。
- 各音乐平台的名称、Logo、商标归其各自所有者。本项目与这些平台无从属关系，也未获其背书。
- 音源脚本由第三方作者提供，本项目不内置、不分发任何脚本。

## 免责

在线功能仅供个人学习研究使用。使用者应自行遵守所在地区法律及各平台服务条款，
不得用于侵犯版权或其他合法权益的用途。请支持正版音乐。
