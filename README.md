# 听书导出（ListenBook Export）

> 在安卓手机上，把 **TXT / EPUB 电子书**用系统已经装好的 **TTS 引擎**念出来，并一键按章节**导出成 WAV 有声书**。

[![下载 APK](https://img.shields.io/badge/下载-APK%20v4.3-brightgreen?logo=android)](https://raw.githubusercontent.com/zjh1934/TSDC/main/ListenBook-v4.3.apk)
[![下载源码](https://img.shields.io/badge/下载-源码%20zip-blue)](https://github.com/zjh1934/TSDC/archive/refs/heads/main.zip)
![Platform](https://img.shields.io/badge/platform-Android-3DDC84)
![License](https://img.shields.io/badge/license-MIT-blue)
![Language](https://img.shields.io/badge/language-Java-orange)
![TTS](https://img.shields.io/badge/TTS-系统引擎-brightgreen)

- 不自带发音人、不做 OCR、不转码压缩、不做播放器 —— 发音全靠你手机里已有的 TTS 引擎
- 纯 Java + 零第三方依赖 + 传统 XML View，可以用 **AIDE** 在手机上直接打包安装
- `minSdk 21 / targetSdk 30 / compileSdk 30`
- **完全开源、代码全免费**（MIT），随便看、随便学、随便改、拿去二次发布都行

---

## 📥 先下载、装上（看这一节就够了）

### 方法一：直接下载安装包（最简单）

👉 **[点这里下载 ListenBook-v4.3.apk](https://raw.githubusercontent.com/zjh1934/TSDC/main/ListenBook-v4.3.apk)**

下载之后：

1. 点开下载好的文件（一般在「下载」或「文件管理」里）
2. 手机问"是否允许安装" → 点 **允许 / 继续**
3. 安装完成 → 点 **打开**

> **如果手机提示"未知来源 / 不安全"被拦住**：
> 这是安卓的正常保护，不是软件有问题。点提示里的 **设置** → 打开 **允许来自此来源的应用** → 回来继续装。

### 方法二：想要源代码

👉 **[点这里下载全部源代码（zip）](https://github.com/zjh1934/TSDC/archive/refs/heads/main.zip)**

或者用命令：`git clone https://github.com/zjh1934/TSDC.git`

---

## 🎧 怎么用（三步）

| 步骤 | 干什么 | 怎么做 |
|---|---|---|
| **1. 导入** | 把书读进来 | 把 TXT / EPUB 放进手机，App 里选文件导入，书库出现卡片、章节数合理即可 |
| **2. 听书** | 边看边听 | 打开阅读页 → 点播放 → 有声音、当前句黄底高亮、页面自动滚动 |
| **3. 导出** | 变成有声书 | 进导出页勾选章节 → 开始 → 锁屏放着跑（建议开「悬浮进度窗」），成品是 WAV |

**找导出文件**：`Android/data/com.listenbook/files/export/<bookId>/` 下是 WAV；
想给 VLC / Audiobookshelf / Poweramp 之类的播放器用，就开「同步到公共音乐目录」或「自定义目录」。

> **首次使用前**：系统里至少装一个 TTS 引擎，并在系统设置「文字转语音输出」里选好默认引擎。
> 推荐本地 TTS 引擎：**HayaiTTS**（600+ 音色）或 **Maise**（中文音色最全）。

---

## 这是给谁做的

- 通勤、做家务、眼睛累了，想"听书"的人
- 手里有 TXT / EPUB，想把它变成 WAV 有声书、在播放器里反复听的人
- 视力不便、想用"听"代替"看"的人
- 想学习安卓 **TTS 离线合成 / AIDE 手机打包** 的开发者

不需要任何服务器、不联网、不上传你的任何内容。

---

## ❓ 常见问题

**Q：点了播放没声音？**
先确认手机装了 TTS 引擎，并在系统「设置 → 文字转语音输出」里选好默认引擎。刚装好第一次用，等一两秒再点。

**Q：导出的 WAV 特别大 / 特别慢？**
`synthesizeToFile()` 是**实时**合成的，10 小时的书就要跑 10 小时；16bit/22.05kHz/单声道 ≈ 2.6 MB/分钟，10 小时 ≈ 1.6 GB。按需求本项目**不做任何转码压缩**，要更小就自己转 MP3。

**Q：章节切分不对 / 显示"第 N 段"？**
TXT 章节切分靠正则，碰到网文千奇百怪的排版识别不到时会退化成按 5000 字分段；EPUB 通常没问题。

**Q：会上传我的隐私吗？**
不会。这个软件**不联网、不上传任何内容**，你读的书不会离开你的手机。

---

## 💻 给开发者

### 它能干什么

| 模块 | 入口 | 职责 | 产出 |
|---|---|---|---|
| **A 听书** | 阅读页 + `tts/TtsSpeaker` | 切句 → 朗读 → 逐句高亮 + 自动滚动 | 不产任何文件 |
| **B 导出** | 导出页 + `export/ExportService` | 切章 → 前台服务 → 合成到文件 → 落 WAV | 只产 WAV，不播放 |
| **C 诊断** | 诊断页 + `util/AppLog` | 记录运行流水 + 导出 txt / md 日志 | 日志文件 |

听书和导出**只共享解析层**（`parse/`），各自持有独立的 `TextToSpeech` 实例：导出不播放，听书不写盘。

### 主要特性

- **任务队列**：多本书可以排队，A 跑完自动接上 B，串行不互相踩
- **断点续传**：已完成的章节不会重复合成；进程被杀后重新打开 App 会自动恢复队列
- **合成参数可调**：引擎 / 音色 / 语速（0.5x~3.0x）/ 语调（0.5~2.0）/ 语言
- **自定义输出目录**：通过 SAF 把成品搬到你指定的目录
- **同步到公共音乐目录**：API 29+ 走 MediaStore，自动生成 `.m3u` 播放列表
- **诊断日志**：内存环形缓冲 + 文件追加 + 崩溃捕获，可导出 Markdown 分享

### 目录结构

```
ListenBook/
├── app/src/main/
│   ├── AndroidManifest.xml
│   ├── res/                      # layout / drawable / anim / values
│   └── java/com/listenbook/
│       ├── App.java              # 进程入口，负责把日志架起来
│       ├── LogProvider.java      # 手写的最小 FileProvider（分享日志用）
│       ├── model/     Book / Chapter / BookLibrary
│       ├── parse/     TxtParser / TxtChapterRules / EpubParser / BookParser
│       ├── util/      Io / TextEncoding / HtmlStripper / WavTool
│       │              UriHelper / MediaStorePublisher / Fingerprint / SafeName
│       │              AppLog（诊断日志）/ Settings（全局偏好）/ SafExport（自定义目录）
│       ├── tts/       Sentence / SentenceSplitter / TtsSpeaker / TtsCatalog   ← 模块 A
│       ├── export/    ExportState / ExportStore / ExportJob / ExportService    ← 模块 B
│       │              ExportQueue（任务队列）
│       └── ui/        MainActivity / 阅读 / ExportActivity / DiagnosticsActivity /
│                      KeepAliveActivity / FloatingWindow / Sheet / 各种 Adapter
├── selftest/SelfTest.java        # 离线自检（不进 AIDE 编译范围）
├── 回归测试-队列持久化.py          # 队列落盘回归测试
├── 检查布局契约.py                # 布局契约检查
├── build-local.sh                # 本地命令行打包（仅开发机用）
├── build.gradle / settings.gradle / gradle.properties
├── app/build.gradle              # 仅供 Android Studio / 命令行，AIDE 不读
├── app/app_config.json / app/repository.json / settings.json
├── 使用说明与AIDE构建.md          # ★ 最详细的说明文档（必看）
├── v1.1-更新说明.md
└── v4.3-更新说明.md
```

共 ~42 个 Java 文件，约 9900 行。

### 怎么把它跑起来（AIDE，手机上）

1. 用 [AIDE](https://play.google.com/store/apps/details?id=com.aptoidept) 新建 / 打开项目，选择 **"Android SDK" 类型**（不是 Gradle 类型）
2. 把整个 `ListenBook` 目录放进手机存储，用 AIDE 打开它
   （AIDE 认 `app/src/main/AndroidManifest.xml` 这套标准结构）
3. 若 AIDE 提示找不到 SDK：设置里把 **Android SDK 指到 API 30**
4. 菜单 → Build / Run，等它编出 APK 直接安装
5. **首次运行前**确认系统里至少装了一个 TTS 引擎，并在系统设置「文字转语音输出」里选好默认引擎

如果 AIDE 报编译错误，先对照 `使用说明与AIDE构建.md` 第三节的「报错对照表」排查（基本都是版本敏感点）。

### 本地命令行构建（开发机）

需要 JDK 与 Android SDK commandline-tools，跑 `build-local.sh` 即可走通
`aapt2 → javac → d8 → zipalign → apksigner` 完整链路。

### 测试

- **离线自检**（纯逻辑层，电脑上跑）：`selftest/SelfTest.java`，覆盖章节规则 / 编码 / 句子切分 / HTML 剥离 / WAV / EPUB / 指纹，共 72 项
- **队列持久化回归**：`回归测试-队列持久化.py`，专门盯落盘恢复最危险的「静默丢字段」失败模式，55 项

---

## 许可证

**MIT License** —— 随便用、随便改、拿去二次发布都行，只需要保留一行版权声明。不用问我，也不用通知我。详见 [LICENSE](LICENSE)。

> 说明：TXT 章节切分与句子切分的**实现思路**参考了 Legado（GPL-3.0）与 Lector（GPL-3.0），但本仓库所有代码均为重写实现，未直接复制其源码；若你后续要照抄它们的代码，成品需遵循各自许可证。

---

## 参考与致谢

- [Legado](https://github.com/gedoor/legado) —— TXT 章节规则思路
- [Lector](https://github.com/Lector-Reader/lector) —— 句子切分思路

欢迎提 Issue / PR，一起把它做得更稳更好用。

---

## 关键词（方便有需要的人搜到这里）

**中文**：听书 · 电子书朗读 · TXT朗读 · EPUB朗读 · 导出WAV · 有声书制作 · 文字转语音 · 文本转语音 · TTS · 安卓TTS · 离线朗读 · 章节朗读 · 电子书转音频 · 听小说 · 听书软件 · 朗读器 · 本地TTS · 不联网朗读 · AIDE打包 · 开源听书 · 无障碍听书

**English**: android text to speech · TTS · txt to speech · epub to speech · text to audio · export to wav · audiobook maker · ebook reader tts · offline tts · android tts app · listen to books · read aloud · chinese tts · open source tts · AIDE build · java android
