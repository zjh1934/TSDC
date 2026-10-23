# 听书导出（ListenBook Export）

在安卓手机上，把 **TXT / EPUB 电子书**用系统已经装好的 **TTS 引擎**念出来，并且可以按章节把声音**导出成 WAV 文件**。

- 不自带发音人、不做 OCR、不转码压缩、不做播放器 —— 发音全靠你手机里已有的 TTS 引擎
- 语言目前只支持中文 / 英文
- **纯 Java + 零第三方依赖 + 传统 XML View**，可以用 [AIDE](https://play.google.com/store/apps/details?id=com.aptoidept) 在手机上直接打包安装
- `minSdk 21 / targetSdk 30 / compileSdk 30`（AIDE 的上限就是 API 30）

本项目**完全开源、代码全免费**，欢迎随便看、随便学、随便改。

---

## 一、它能干什么

| 模块 | 入口 | 职责 | 产出 |
|---|---|---|---|
| **A 听书** | 阅读页 + `tts/TtsSpeaker` | 切句 → 朗读 → 逐句高亮 + 自动滚动 | 不产任何文件 |
| **B 导出** | 导出页 + `export/ExportService` | 切章 → 前台服务 → 合成到文件 → 落 WAV | 只产 WAV，不播放 |
| **C 诊断** | 诊断页 + `util/AppLog` | 记录运行流水 + 导出 txt / md 日志 | 日志文件 |

听书和导出**只共享解析层**（`parse/`），各自持有独立的 `TextToSpeech` 实例：导出不播放，听书不写盘。导出的 WAV 交给 VLC / Audiobookshelf / Poweramp 之类的播放器去听。

### 主要特性
- **任务队列**：多本书可以排队，A 跑完自动接上 B，串行不互相踩
- **断点续传**：已完成的章节不会重复合成；进程被杀后重新打开 App 会自动恢复队列
- **合成参数可调**：引擎 / 音色 / 语速（0.5x~3.0x）/ 语调（0.5~2.0）/ 语言
- **自定义输出目录**：通过 SAF 把成品搬到你指定的目录
- **同步到公共音乐目录**：API 29+ 走 MediaStore，自动生成 `.m3u` 播放列表
- **诊断日志**：内存环形缓冲 + 文件追加 + 崩溃捕获，可导出 Markdown 分享

---

## 二、目录结构

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

---

## 三、怎么把它跑起来（AIDE，手机上）

1. 用 [AIDE](https://play.google.com/store/apps/details?id=com.aptoidept) 新建 / 打开项目，选择 **"Android SDK" 类型**（不是 Gradle 类型）
2. 把整个 `ListenBook` 目录放进手机存储，用 AIDE 打开它
   （AIDE 认 `app/src/main/AndroidManifest.xml` 这套标准结构）
3. 若 AIDE 提示找不到 SDK：设置里把 **Android SDK 指到 API 30**
4. 菜单 → Build / Run，等它编出 APK 直接安装
5. **首次运行前**确认系统里至少装了一个 TTS 引擎，并在系统设置「文字转语音输出」里选好默认引擎

> 推荐本地 TTS 引擎：**HayaiTTS**（600+ 音色）或 **Maise**（中文音色最全：晓北/晓妮/晓晓/晓伊/云健/云希/云夏/云扬）。

如果 AIDE 报编译错误，先对照 `使用说明与AIDE构建.md` 第三节的「报错对照表」排查（基本都是版本敏感点）。

### 本地命令行构建（开发机）
需要 JDK 与 Android SDK commandline-tools，跑 `build-local.sh` 即可走通
`aapt2 → javac → d8 → zipalign → apksigner` 完整链路。

---

## 四、怎么用

1. **导入**：把 TXT / EPUB 放进手机，App 里选文件导入，书库出现卡片、章节数合理即可
2. **听书**：打开阅读页 → 点播放 → 有声音、当前句黄底高亮、页面自动滚动
3. **导出**：进导出页勾选章节 → 开始 → 锁屏放着跑（建议开「悬浮进度窗」）
4. **队列**：导出页在有任务时再点「开始导出」会把当前书排到队尾；不带 bookId 进导出页 = 任务中心
5. **找文件**：`Android/data/com.listenbook/files/export/<bookId>/` 下是 WAV；
   想给第三方播放器用，就开「同步到公共音乐目录」或「自定义目录」
6. **诊断**：右上角菜单 → 诊断日志，可导出 Markdown 分享

更完整的「真机验证清单」和「五个坑分别在哪里堵死」见 `使用说明与AIDE构建.md` 第五、六节。

---

## 五、固有限制（不是 bug）

- **导出不能加速**：`synthesizeToFile()` 是实时的，10 小时的书就要跑 10 小时
- **WAV 很大**：16bit / 22.05kHz / 单声道 ≈ 2.6 MB/分钟，10 小时 ≈ 1.6 GB（按需求不做任何转码压缩）
- TXT 章节切分靠正则，网文排版千奇百怪识别不到时退化成按 5000 字分段（标题显示「第 N 段」），EPUB 没这问题

---

## 六、测试

- **离线自检**（纯逻辑层，电脑上跑）：`selftest/SelfTest.java`，覆盖章节规则 / 编码 / 句子切分 / HTML 剥离 / WAV / EPUB / 指纹，共 72 项
- **队列持久化回归**：`回归测试-队列持久化.py`，专门盯落盘恢复最危险的「静默丢字段」失败模式，55 项

---

## 七、许可证

本项目以 **MIT License** 发布，代码全免费，可任意使用、修改、再分发。详见 [LICENSE](LICENSE)。

> 说明：TXT 章节切分与句子切分的**实现思路**参考了 Legado（GPL-3.0）与 Lector（GPL-3.0），但本仓库所有代码均为重写实现，未直接复制其源码；若你后续要照抄它们的代码，成品需遵循各自许可证。

---

## 八、参考与致谢

- [Legado](https://github.com/gedoor/legado) —— TXT 章节规则思路
- [Lector](https://github.com/Lector-Reader/lector) —— 句子切分思路

欢迎提 Issue / PR，一起把它做得更稳更好用。
