# 听书导出 App

在安卓手机上，把 TXT / EPUB 电子书用**系统已装的 TTS 引擎**念出来，并能按章节导出成 WAV。

- 不自带发音人、不做 OCR、不转码压缩、不做播放器
- 语言只支持中英
- **纯 Java + 零第三方依赖 + 传统 XML View**，可在 AIDE 里直接打包安装
- `minSdk 21 / targetSdk 30 / compileSdk 30`（AIDE 上限就是 API 30）

---

## 一、两个模块严格分离

| 模块 | 入口 | 职责 | 产出 |
|---|---|---|---|
| **A 听书** | `ui/ReaderActivity` + `tts/TtsSpeaker` | 切句 → `speak()` → 逐句高亮 + 自动滚动 | **不产任何文件** |
| **B 导出** | `ui/ExportActivity` + `export/ExportService` | 切章 → 前台 Service → `synthesizeToFile()` → 落 WAV | **只产 WAV，不播放** |
| **C 诊断** | `ui/DiagnosticsActivity` + `util/AppLog` | 记录运行流水 + 导出 txt / md | 日志文件 |

两者只共享 `parse/` 解析层。运行时各自持有**独立的 `TextToSpeech` 实例**，互不干扰：
导出不播放，听书不写盘。播放导出的 WAV 交给 VLC / Audiobookshelf / Poweramp。

---

## 二、目录结构

```
ListenBook/
├── app/src/main/
│   ├── AndroidManifest.xml
│   ├── res/
│   │   ├── layout/     activity_main / activity_reader / activity_export
│   │   │               activity_diagnostics / sheet_list / sheet_confirm
│   │   │               item_book / item_chapter / item_sheet_row /
│   │   │               item_log / diag_advanced
│   │   ├── drawable/   bg_card / bg_btn_primary / bg_btn_danger / bg_btn_ghost
│   │   │               bg_cover / bg_bar / bg_sheet / bg_chip(_selector)
│   │   │               bg_row_press / bg_hint_box / bg_grip / ic_launcher
│   │   ├── anim/       slide_in_bottom / slide_out_bottom（底部弹层动画）
│   │   └── values/     colors / strings / styles
│   └── java/com/listenbook/
│       ├── App.java              进程入口，只负责把日志架起来
│       ├── LogProvider.java      自建的最小 FileProvider（分享日志用）
│       ├── model/     Book / Chapter / BookLibrary
│       ├── parse/     TxtParser / TxtChapterRules / EpubParser / BookParser
│       ├── util/      Io / TextEncoding / HtmlStripper / WavTool
│       │              UriHelper / MediaStorePublisher / Fingerprint / SafeName
│       │              AppLog（诊断日志）/ Settings（全局偏好）/ SafExport（自定义目录）
│       ├── tts/       Sentence / SentenceSplitter / TtsSpeaker / TtsCatalog  ← 模块 A
│       ├── export/    ExportState / ExportStore / ExportJob / ExportService  ← 模块 B
│       │              ExportQueue（任务队列）
│       └── ui/        MainActivity / ReaderActivity / ExportActivity /
│                      DiagnosticsActivity / Sheet（底部弹层）
│                      BookAdapter / ChapterAdapter
├── selftest/          离线自检（不在 AIDE 编译范围内）
├── build-local.sh     本地命令行打包（仅开发机用，AIDE 不需要）
├── app/build.gradle   仅为 Android Studio / 命令行保留，AIDE 不读
└── settings.gradle / build.gradle / gradle.properties
```

37 个 Java 文件，约 9900 行。

---

## 三、AIDE 里怎么打包

1. AIDE → 新建 / 打开项目 → 选择 **"Android SDK" 类型**（不是 Gradle 类型）
2. 把整个 `ListenBook` 目录放进手机存储，用 AIDE 打开该目录
   （AIDE 认 `app/src/main/AndroidManifest.xml` 这个标准结构）
3. 若 AIDE 提示找不到 SDK：设置里把 **Android SDK 指到 API 30**
4. 菜单 → Build / Run，等它编出 APK 直接安装
5. 首次运行前确认系统里至少装了一个 TTS 引擎，并在系统设置的
   「文字转语音输出」里选好默认引擎

**如果 AIDE 报编译错误，先看这几处**（都是版本敏感点）：

| 报错 | 原因 | 处理 |
|---|---|---|
| `foregroundServiceType` 找不到 | 该属性要 API 29+ | 本工程**故意没声明**，若你自己加了请删掉 |
| `NotificationChannel` 找不到 | SDK 低于 26 | 把 AIDE 的 SDK 指到 30 |
| `checkSelfPermission` 找不到 | SDK 低于 23 | 同上；代码里已用 `SDK_INT >= 23` 保护运行时 |
| 找不到 `androidx.*` | 工程零 androidx | 不要往 `build.gradle` 里加依赖，AIDE 拉不到远程 maven |
| 找不到 `App` / `LogProvider` | 新增了 Application 和 Provider | 确认 `AndroidManifest.xml` 是最新版（`android:name=".App"`、`<provider>` 节点） |
| `Locale.forLanguageTag` 找不到 | SDK 低于 21 | minSdk 就是 21，把 SDK 指到 30 |

> App 类（`App.java`）的存在感只在幕后：它保证 Service 先于任何 Activity 启动时
> 日志系统也已经就绪。删掉它不会崩，但"从通知直接进导出页"这条路径会丢头几行日志。

---

## 四、五个坑分别在哪里堵死

| 坑 | 对策 | 代码位置 |
|---|---|---|
| **1** TTS 引擎写不了 `getFilesDir()`（EACCES） | 导出目录用 `getExternalFilesDir(null)/export/<bookId>/` | `ExportStore.baseDir()` |
| **2** 不传 utteranceId 则 `onDone` 永不回调 | 每次合成都塞 `KEY_PARAM_UTTERANCE_ID` 且传第 4 个参数 | `ExportService.synthesizeChunk()`、`TtsSpeaker.pump()` |
| **3** 监听器挂太早被丢弃 | `setOnUtteranceProgressListener` 一律写在 `onInit(SUCCESS)` 内部 | `TtsSpeaker.init()`、`ExportService.initTts()` |
| **4** 单次 4000 字符上限，超了静默丢弃 | 按句切分后再按 3500 字预算分块；单句超 3800 硬切 | `SentenceSplitter.chunk()` / `split()` |
| **5** 回调在 binder 线程 | 听书侧一律 `Handler.post` 回主线程；导出侧只碰 volatile + `CountDownLatch` | `TtsSpeaker.handle()`、`ExportService.settle()` |

文档第 6.1 节的导入 bug 也已堵死：`UriHelper.importTo()` 全程走
`ContentResolver.openInputStream()`，**没有一处对 content Uri 调 `getPath()`**
（只有 `"file"` scheme 分支才用 `getPath()`，那是合法的）。

### 额外补的两处（原文档没提，但不补会出事）

1. **Android 11+ 包可见性**：`targetSdk=30` 时若不在 Manifest 里声明
   `<queries><intent><action android:name="android.intent.action.TTS_SERVICE"/></intent></queries>`，
   可能枚举/绑定不到系统 TTS 引擎。已声明。
2. **`/Android/data/<pkg>/files` 其它 App 读不到**：TTS 引擎只能写这里，
   但 Android 11+ VLC / Audiobookshelf 打不开这个目录。
   所以加了 `MediaStorePublisher`：导出页勾「同步到公共音乐目录」后，
   API 29+ 走 MediaStore 免权限复制一份到 `Music/ListenBook/<书名>/`，
   API ≤28 走公共目录 + `WRITE_EXTERNAL_STORAGE`（Manifest 已限 `maxSdkVersion=28`）。
   同时导出目录里会生成 `<书名>.m3u` 播放列表，可直接喂给播放器按章节连播。

---

## 五、断点续传的实现方式

**文件即状态**，不靠 Service 存活、不靠数据库：

1. 每次启动任务都重新解析书籍（几百毫秒），不依赖任何内存状态
2. `ExportStore.prepare()` 扫目录：过 RIFF 校验的 `.wav` 才算完成，直接跳过
3. 所有 `.tmp` 一律删除重来，绝不信任半截文件
4. 事务边界 = **原子重命名**：分块合成到 `<cid>.pN.tmp` → `onDone` → 合并到
   `<cid>.merge.tmp` → `renameTo(<cid>_<标题>.wav)`。进程被杀最多丢当前这一章
5. 章节 ID 用**内容指纹** `sha256(书名 + 标题 + 正文前 64 字)[:8]`，
   章节增删/重排都不会张冠李戴

UI 侧的扫描一律用 `ExportStore.scan(false)`（只读），
**导出进行中时绝不删 `.tmp`**，否则会把正在合成的分块删掉。

---

## 五点五、v1.1 新增：任务队列

**为什么串行**：TTS 引擎本身串行合成，两个任务一起跑只会互相拖慢，
还会互相删对方的 `.tmp`。所以一个 Service 实例把队列里的任务全部跑完才退场。

- `ExportQueue` 静态持有 `List<ExportJob>`，状态：`PENDING / RUNNING / DONE / FAILED / CANCELED`
- `ExportService.Worker.run()` 是个 `while` 循环 → `takeNext()` → `runJob()`，
  每个任务结束后 rebuild TTS（引擎可能不同）
- UI 侧：导出页顶部会浮出「队列概览」卡；不带 `bookId` 进 `ExportActivity` 就是**任务中心**，
  能看全部任务、取消排队、停止当前
- 「停止」语义：停止正在跑的这一个，队列里剩下的照旧接着跑；
  想全清就点「清空排队」

---

## 五点六、v1.1 新增：合成参数与自定义目录

导出页的「合成设置」卡片里能直接指定：

| 项 | 说明 |
|---|---|
| 引擎 | 从系统已装的 TTS 引擎里选（`PackageManager` 枚举，不用起 TTS 实例） |
| 音色 | 向引擎 `getVoices()` 查询后列出，中文音色排前 |
| 语速 | 0.5x ~ 3.0x，滑杆 + 步进按钮 |
| 语调 | 0.5 ~ 2.0 |
| 语言 | 自动识别 / 固定中文 / 固定英文 |
| 同步到公共音乐目录 | 走 MediaStore，见第 4 节 |
| 自定义目录 | 走 SAF，见下 |

**自定义目录为什么是「先导出、再搬运」**：
`isynthesizeToFile` 的目标是让**另一个 APK（TTS 引擎）去写文件**，
它只被授权写本 App 的外部专属目录，写别处一律 EACCES。
所以流程固定两段 —— 先稳定落在 App 目录（顺带保住断点续传），
每章成功后用 SAF 把 WAV 搬到你挑的目录。
权限在选目录时 `takePersistableUriPermission` 收下，进程被回收也不失效。

> 注意「/Android/data 其它 App 读不到」这个问题在 Android 11+ 依然存在，
> 想给第三方播放器用，就开「同步到公共音乐目录」或「自定义目录」其中之一。

---

## 五点七、v1.1 新增：诊断日志

`util/AppLog` 自实现「内存环形缓冲（3000 条）+ 文件追加 + 崩溃捕获」：

- 写盘丢给后台 `HandlerThread`，调用线程零开销
- 装了 `UncaughtExceptionHandler`，闪退前把栈写进日志
- 单个日志文件 1MB 翻转一次
- 右上角菜单 → **诊断日志**：按级别过滤、关键字搜索、点条目复制、
  导出 **txt / md** 到 `Android/data/com.listenbook/files/Documents/diag/`，并可分享出去
- 同一个页面底部是**高级参数**（排查时才用）：
  单块字数预算 / 超时余量倍数 / 单块重试次数 / TTS 详细日志 / 日志最低级别

分享出去必须是 `content://` Uri（Android 7+ 禁 `file://`），
而 `FileProvider` 属于 androidx，所以这里手写了一个 60 行的 `LogProvider`：
只认文件名、不透传路径，天然挡住目录穿越。

---

## 六、真机验证清单（按顺序做，别跳）

> 沙盒里没有安卓设备，下面这些必须你在真机上跑。

**准备**：装一个本地 TTS 引擎。推荐 **HayaiTTS**（600+ 音色，APK 约 38MB）
或 **Maise**（中文音色最全：晓北/晓妮/晓晓/晓伊/云健/云希/云夏/云扬）。

**第 1 步 · 编译**：AIDE 里 Build，确认 0 error 出 APK。
**第 2 步 · 导入**：先用一个**几千字的短 TXT**，确认书库出现卡片、章数合理。
**第 3 步 · 听书**：打开阅读页 → 点播放 → 确认有声音、当前句黄底高亮、页面自动滚动。
**第 4 步 · 导出 1~2 章**：导出页只勾两章 → 开始 → 锁屏 5 分钟 → 回来看是否完成。
**第 5 步 · 验文件**：用文件管理器进 `Android/data/com.listenbook/files/export/<bookId>/`，
　　　　　　确认 `.wav` 能被播放器打开、时长正确、没有 `.tmp` 残留。
**第 6 步 · 断点续传**：导出到一半强杀 App → 重新进导出页 → 再点开始 →
　　　　　　确认已完成章节被「跳过」计数吃掉，只补剩下的。
**第 7 步 · EPUB**：拿一本真实 EPUB 走一遍 2~6 步（章节边界应 100% 准确）。
**第 8 步 · 长书**：确认无误后再上 50 万字的长篇。
**第 9 步 · 队列**：先给 A 书排 2 章跑起来，期间再去给 B 书排 3 章 →
　　　　　　看通知标题上的「队列还有 N 个」、A 跑完是否自动接上 B。
**第10步 · 诊断日志**：菜单 → 诊断日志 → 导出 Markdown → 分享到微信/邮件，
　　　　　　确认能打开、能看到刚才第 9 步的任务流水。
**第11步 · 自定义目录**（可选）：合成设置 → 自定义目录 → 挑 SD 卡/网盘同步目录 →
　　　　　　跑一章 → 确认子目录 `《书名》/` 里出现同名 WAV。

**要重点盯的四件事**（沙盒测不到的）：

1. 你手机上那个 TTS 引擎**是否真的实现了 `synthesizeToFile(File)`** ——
   有些引擎只支持 `speak()`，会在第一块就报 ERROR
2. 长章节多块合并后的 WAV 是否连续无缝（`WavTool.merge`）
3. 快速切章时旧回调有没有污染新高亮（generation 令牌）
4. 息屏 30 分钟后导出是否还在跑（WakeLock 是否被厂商省电策略杀掉 ——
   国产 ROM 记得给 App 加"自启动 + 后台无限制"白名单）

---

## 七、已验证 / 未验证

**已用真编译器验证**（JDK 20 + Google 官方 `android-30/android.jar`，
走 `aapt2 → javac(--source 8) → d8 → zipalign → apksigner` 完整链路出 APK）：

- 全部 37 个 Java 文件 **0 error**
- 资源编译（aapt2 compile/link）、Manifest、XML 布局引用全部通过
- `aapt2 dump badging` 确认包名 / 版本 / 权限 / Activity-Service-Provider 注册正确
- 无第三方依赖、无 lambda、无 diamond 泛型（AIDE 的 Java 7 模式能吃）

开发机上想复现这条链路，跑 `build-local.sh`（需要 JDK 与 Android SDK commandline-tools，
AIDE 用户可完全忽略这个文件）。

**已用离线自检验证**（`selftest/SelfTest.java`，72 项全通过）：

- TXT 章节规则：标准三章、计数词歧义、全角缩进、纯页码、引号对话、
  「正文。」vs「正文」、英文标题结尾句点、无标题兜底分段
- 编码：GB18030 探测与解码、UTF-8 BOM、行内零宽空格
- 句子切分：`text` 与 `[start,end)` 完全一致、区间不重叠、非空白字符零遗漏、
  Mr./Dr./3.14/p.m./etc./U.S. 的缩写合并、9000 字无标点长句硬切、分块不超 3500
- HTML 剥离：script/style/注释删除、块级转换行、命名与数字实体、列表与 br
- WAV：合法文件 probe、**半截文件判为未完成**、三块合并后 dataLen 与头一致
- EPUB：`dc:title`/`dc:creator`、spine 顺序、`linear="no"` 跳过、缺失项跳过、
  嵌套目录 href、`../` 折叠、`%20` 解码、绝对路径
- 指纹：同内容稳定、改标题/改正文即变、同一文件两次解析逐章一致

**未验证（必须真机）**：AIDE 实际构建、TTS 引擎真实回调、前台服务与通知、
WakeLock 存活、MediaStore 与 SAF 搬运、真实 EPUB/TXT 的兼容性。

---

## 八、固有限制（不是 bug）

- **导出不能加速**：`synthesizeToFile()` 是实时的，10 小时的书就要跑 10 小时
- **WAV 很大**：16bit/22.05kHz/单声道 ≈ 44100 B/s ≈ **2.6 MB/分钟**，
  10 小时 ≈ 1.6 GB。按你的要求不做任何转码压缩
- TXT 章节切分靠正则，网文排版千奇百怪，识别不到时会退化成按 5000 字分段
  （章节标题显示为「第 N 段」），EPUB 没这个问题

---

## 九、跑离线自检

自检只覆盖纯逻辑层（不依赖 Android 运行时），在电脑上：

```bash
# 需要一个 JDK，以及 org.json 的 jar（Android 自带，桌面 JDK 没有）
javac -encoding UTF-8 -cp json.jar -d out \
  app/src/main/java/com/listenbook/util/{Io,TextEncoding,Fingerprint,SafeName,HtmlStripper,WavTool}.java \
  app/src/main/java/com/listenbook/model/{Book,Chapter}.java \
  app/src/main/java/com/listenbook/parse/*.java \
  app/src/main/java/com/listenbook/tts/{Sentence,SentenceSplitter}.java \
  selftest/SelfTest.java
java -Dfile.encoding=UTF-8 -cp out:json.jar SelfTest
```

期望输出：`通过 72 项，失败 0 项`。

---

## 十、许可证

- `HtmlStripper` / `WavTool` / `UriHelper` / `MediaStorePublisher`：自研，无外部来源
- TXT 章节规则**思想**参考 Legado（GPL-3.0），正则为重写实现
- 句子切分**思想**参考 Lector（GPL-3.0）的 Segmenter，代码为重写实现
- 未直接复制上述项目的任何源码文件；若你后续要照抄其代码，成品需遵循 GPL-3.0
