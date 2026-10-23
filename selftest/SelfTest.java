import com.listenbook.model.Chapter;
import com.listenbook.parse.BookParser;
import com.listenbook.parse.EpubParser;
import com.listenbook.parse.TxtChapterRules;
import com.listenbook.parse.TxtParser;
import com.listenbook.tts.Sentence;
import com.listenbook.tts.SentenceSplitter;
import com.listenbook.util.Fingerprint;
import com.listenbook.util.HtmlStripper;
import com.listenbook.util.Io;
import com.listenbook.util.SafeName;
import com.listenbook.util.TextEncoding;
import com.listenbook.util.WavTool;

import java.io.File;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 离线自检：只覆盖不依赖 Android 运行时的纯逻辑层。
 * TTS 回调、前台服务、通知、MediaStore 必须真机验，这里测不了。
 *
 * 运行方式见 selftest/README.md
 */
public class SelfTest {

    static int pass = 0;
    static int fail = 0;
    static File tmp;

    static void check(String name, boolean ok, String detail) {
        if (ok) {
            pass++;
            System.out.println("  PASS  " + name);
        } else {
            fail++;
            System.out.println("  FAIL  " + name + "  -> " + detail);
        }
    }

    public static void main(String[] args) throws Exception {
        tmp = new File(System.getProperty("java.io.tmpdir"), "listenbook-selftest");
        Io.deleteQuietly(tmp);
        tmp.mkdirs();

        System.out.println("[1] TXT 章节规则与编码");
        testTxt();
        System.out.println("[2] 句子切分与偏移量");
        testSentences();
        System.out.println("[3] HTML 剥离");
        testHtml();
        System.out.println("[4] WAV 校验与合并");
        testWav();
        System.out.println("[5] EPUB spine 解析");
        testEpub();
        System.out.println("[6] 指纹稳定性与文件名净化");
        testFingerprint();

        System.out.println("");
        System.out.println("通过 " + pass + " 项，失败 " + fail + " 项");
        if (fail > 0) {
            System.exit(1);
        }
    }

    // ---------------------------------------------------------------- 1

    static void testTxt() throws Exception {
        String text = "第一章 少年出山\n"
                + "\u3000\u3000他推开木门，山风灌进来。\n"
                + "\u3000\u3000“走吧。”\n"
                + "第二章 夜行\n"
                + "\u3000\u3000路上没人。\n"
                + "第三章 归途\n"
                + "\u3000\u3000天亮了。\n";
        List<Chapter> cs = TxtParser.parse(write("cn.txt", text.getBytes("UTF-8")), "测试书", false);
        check("标准三章切分", cs.size() == 3, "实际 " + cs.size());
        check("章标题正确", cs.size() == 3 && cs.get(0).title.equals("第一章 少年出山")
                && cs.get(2).title.equals("第三章 归途"), cs.size() == 3 ? cs.get(0).title : "-");
        check("正文保留段落全角缩进（阅读体验）", cs.size() == 3 && cs.get(0).text.indexOf('\u3000') == 0,
                cs.size() == 3 ? cs.get(0).text.replace("\n", "|") : "-");
        check("句子起点跳过全角缩进", cs.size() == 3 && SentenceSplitter.split(cs.get(0).text).get(0).start == 2,
                cs.size() == 3 ? SentenceSplitter.split(cs.get(0).text).get(0).start + "" : "-");
        check("单标题的书不被兜底打散",
                TxtParser.parse(write("one.txt", "第一章 唯一\n正文。\n".getBytes("UTF-8")), "o", false).get(0)
                        .title.equals("第一章 唯一"), "");
        check("章节 id 为 8 位且互不相同", cs.size() == 3 && cs.get(0).id.length() == 8
                && !cs.get(0).id.equals(cs.get(1).id) && !cs.get(1).id.equals(cs.get(2).id), "");

        // 误报诱饵
        String decoy = "第三部分的内容很重要。\n"
                + "\u3000\u3000第三章讲过这件事，谁都知道。\n"
                + "他说了一句话，然后离开了这个地方，走了很远很远的路才停下来休息了一会儿才继续往前走。\n"
                + "第四章 转折\n"
                + "\u3000\u3000正文。\n"
                + "12\n"
                + "“第五章？”她问。\n";
        List<Chapter> ds = TxtParser.parse(write("decoy.txt", decoy.getBytes("UTF-8")), "诱饵", false);
        check("计数词歧义不误判", containsTitle(ds, "第四章") && !containsTitle(ds, "第三部分"), "章数 " + ds.size());
        check("全角缩进行不误判", !containsTitle(ds, "第三章讲过"), "");
        check("纯页码行不误判", !containsTitle(ds, "12"), "");
        check("引号对话不误判", !containsTitle(ds, "第五章"), "");

        // 兜底分段
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 600; i++) {
            sb.append("这是第").append(i).append("段没有任何章节标题的普通正文内容，用来触发兜底分段规则。\n");
        }
        List<Chapter> fs = TxtParser.parse(write("plain.txt", sb.toString().getBytes("UTF-8")), "无标题", false);
        int max = 0;
        for (int i = 0; i < fs.size(); i++) {
            max = Math.max(max, fs.get(i).length());
        }
        check("无标题时兜底分段", fs.size() >= 2, "章数 " + fs.size());
        check("兜底每块不超 6000 字", max <= 6000, "最长 " + max);

        // 编码
        String cn = "第一章 编码\n\u3000\u3000中文内容测试。\n";
        File gbk = write("gbk.txt", cn.getBytes("GB18030"));
        String det = TextEncoding.detect(Io.readAll(gbk));
        check("GB18030 探测", det.toUpperCase().contains("GB"), det);
        check("GBK 解码不乱码", TextEncoding.decode(Io.readAll(gbk)).contains("中文内容测试"), "");
        check("GBK 文件能切章", TxtParser.parse(gbk, "gbk", false).size() == 1, "");

        byte[] u8 = cn.getBytes("UTF-8");
        byte[] bom = new byte[u8.length + 3];
        bom[0] = (byte) 0xEF;
        bom[1] = (byte) 0xBB;
        bom[2] = (byte) 0xBF;
        System.arraycopy(u8, 0, bom, 3, u8.length);
        File bf = write("bom.txt", bom);
        check("UTF-8 BOM 探测", TextEncoding.detect(Io.readAll(bf)).equals("UTF-8"), "");
        check("BOM 不影响首章标题",
                TxtParser.parse(bf, "bom", false).get(0).title.equals("第一章 编码"), "");
        check("行内零宽空格剥离后仍匹配", TxtChapterRules.isTitle("第\u200B二章 零宽", false), "");
        check("「正文。」不被当成章节标题", !TxtChapterRules.isTitle("正文。", false), "");
        check("「正文」单独成行仍是标题", TxtChapterRules.isTitle("正文", false), "");
        check("英文标题允许结尾句点", TxtChapterRules.isTitle("Chapter 1.", false), "");
        check("中文标题不允许结尾叹号", !TxtChapterRules.isTitle("第一章 起风！", false), "");
    }

    static boolean containsTitle(List<Chapter> cs, String frag) {
        for (int i = 0; i < cs.size(); i++) {
            if (cs.get(i).title.contains(frag)) {
                return true;
            }
        }
        return false;
    }

    // ---------------------------------------------------------------- 2

    static void testSentences() throws Exception {
        String text = "第一句话在这里。第二句来了！第三句呢？“第四句是引用。”\n"
                + "第五句跨行了。\n\n第六句在空行之后…结束了。";
        List<Sentence> ss = SentenceSplitter.split(text);
        boolean okSub = true;
        boolean okOrder = true;
        int prevEnd = -1;
        boolean[] cover = new boolean[text.length()];
        for (int i = 0; i < ss.size(); i++) {
            Sentence s = ss.get(i);
            if (!text.substring(s.start, s.end).equals(s.text)) {
                okSub = false;
            }
            if (s.start < prevEnd) {
                okOrder = false;
            }
            prevEnd = s.end;
            for (int p = s.start; p < s.end; p++) {
                cover[p] = true;
            }
        }
        int missed = 0;
        for (int p = 0; p < text.length(); p++) {
            char c = text.charAt(p);
            if (!cover[p] && c != '\n' && c != ' ' && c != '\t') {
                missed++;
            }
        }
        check("句子 text 与 [start,end) 完全一致", okSub, "");
        check("句子区间单调不重叠", okOrder, "");
        check("非空白字符零遗漏（高亮不会漏字）", missed == 0, "漏 " + missed);
        check("中文按句末标点切分（… 也算终止符）", ss.size() == 7, "实际 " + ss.size() + ": " + ss);

        String en = "Mr. Smith went to Washington. He met Dr. Jones at 3.14 p.m. "
                + "They discussed U.S. policy etc. and left. Wait! Really? Yes.";
        List<Sentence> es = SentenceSplitter.split(en);
        check("Mr./Dr. 不切断", es.get(0).text.equals("Mr. Smith went to Washington."), es.get(0).text);
        check("小数 3.14 不切断", es.size() > 1 && es.get(1).text.contains("3.14"),
                es.size() > 1 ? es.get(1).text : "-");
        check("p.m./etc. 条件合并", es.size() == 6 && es.get(2).text.endsWith("left.")
                && es.get(2).text.contains("etc. and"), es.size() + " 句: " + es);
        check("感叹号问号独立成句", es.size() == 6 && es.get(3).text.equals("Wait!")
                && es.get(4).text.equals("Really?") && es.get(5).text.equals("Yes."), "");

        // 分块上限
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 3000; i++) {
            sb.append("这是第").append(i).append("句，用来把章节撑到很长很长。");
        }
        String longText = sb.toString();
        List<String> chunks = SentenceSplitter.chunk(longText, SentenceSplitter.CHUNK_BUDGET);
        int over = 0;
        int joined = 0;
        for (int i = 0; i < chunks.size(); i++) {
            if (chunks.get(i).length() > SentenceSplitter.CHUNK_BUDGET) {
                over++;
            }
            joined += chunks.get(i).length();
        }
        check("长章分成多块", chunks.size() > 1, chunks.size() + "");
        check("无块超过 3500 字（避开 4000 静默丢弃）", over == 0, over + " 块超限");
        check("分块不丢字", joined >= longText.length() - chunks.size(), joined + " vs " + longText.length());

        StringBuilder one = new StringBuilder();
        for (int i = 0; i < 9000; i++) {
            one.append('字');
        }
        List<Sentence> hard = SentenceSplitter.split(one.toString());
        boolean anyOver = false;
        for (int i = 0; i < hard.size(); i++) {
            if (hard.get(i).length() > SentenceSplitter.HARD_LIMIT) {
                anyOver = true;
            }
        }
        check("9000 字无标点长句被硬切", hard.size() > 1 && !anyOver, hard.size() + " 句");
    }

    // ---------------------------------------------------------------- 3

    static void testHtml() throws Exception {
        String html = "<html><head><title>第一章 &amp; 序</title>"
                + "<style>p{color:red}</style>"
                + "<script>var a = '<p>not a paragraph</p>';</script></head>"
                + "<body><!-- 注释 -->"
                + "<h1>第一章 起风</h1>"
                + "<p>段落一&nbsp;有空格。</p>"
                + "<p>段落二<br/>换行后继续</p>"
                + "<ul><li>项目一</li><li>项目二</li></ul>"
                + "<p>&#x4E2D;&#25991;&ldquo;引号&rdquo;</p>"
                + "</body></html>";
        String out = HtmlStripper.strip(html);
        check("script/style 内容被删", !out.contains("color:red") && !out.contains("not a paragraph"), out);
        check("注释被删", !out.contains("注释"), out);
        check("块级标签转换行", out.split("\n").length >= 5, out.replace("\n", "|"));
        check("命名实体解码", out.contains("段落一 有空格") && !out.contains("&nbsp;"), out);
        check("数字/十六进制实体解码", out.contains("中文“引号”"), out);
        check("列表项独立成行", out.contains("项目一\n项目二"), out.replace("\n", "|"));
        check("br 产生换行", out.contains("段落二\n换行后继续"), out.replace("\n", "|"));
        check("标题提取", HtmlStripper.extractTitle(html).contains("第一章 起风"), HtmlStripper.extractTitle(html));
        check("无连续空行", !out.contains("\n\n"), out.replace("\n", "|"));
    }

    // ---------------------------------------------------------------- 4

    static File makeWav(String name, int pcmBytes, int sampleRate) throws Exception {
        WavTool.Info fmt = new WavTool.Info();
        fmt.channels = 1;
        fmt.sampleRate = sampleRate;
        fmt.bitsPerSample = 16;
        File f = new File(tmp, name);
        FileOutputStream out = new FileOutputStream(f);
        out.write(WavTool.buildHeader(fmt, pcmBytes));
        byte[] pcm = new byte[pcmBytes];
        for (int i = 0; i < pcm.length; i++) {
            pcm[i] = (byte) (i % 127);
        }
        out.write(pcm);
        out.close();
        return f;
    }

    static void testWav() throws Exception {
        File a = makeWav("a.wav", 44100, 22050);
        File b = makeWav("b.wav", 88200, 22050);
        File c = makeWav("c.wav", 22050, 22050);
        WavTool.Info ia = WavTool.probe(a);
        check("合法 WAV 通过 probe", ia.valid && ia.dataLen == 44100L, ia.describe());
        check("时长估算约 1 秒", Math.abs(ia.millis() - 1000L) < 50L, ia.millis() + "ms");

        File trunc = new File(tmp, "trunc.wav");
        Io.copyFile(a, trunc);
        RandomAccessFile raf = new RandomAccessFile(trunc, "rw");
        raf.seek(40);
        raf.writeInt(Integer.reverseBytes(999999));
        raf.close();
        WavTool.Info it = WavTool.probe(trunc);
        check("半截 WAV 判为未完成（不能只看文件存在）", !it.valid && it.truncated, it.describe());
        check("非法文件判为无效", !WavTool.probe(write("junk.wav", "这不是音频".getBytes("UTF-8"))).valid, "");

        File merged = new File(tmp, "merged.wav");
        boolean ok = WavTool.merge(new File[]{a, b, c}, merged);
        WavTool.Info im = WavTool.probe(merged);
        check("三块合并成功", ok && im.valid, im.describe());
        check("合并后 dataLen = 三块之和", im.dataLen == 154350L, im.dataLen + "");
        check("合并后采样率不变", im.sampleRate == 22050, im.sampleRate + "");
        check("合并后文件 = 44 字节头 + data", merged.length() == 44L + im.dataLen, merged.length() + "");
        check("单块合并走重命名", WavTool.merge(new File[]{a}, new File(tmp, "single.wav"))
                && WavTool.isValid(new File(tmp, "single.wav")), "");
        check("体积量级正确（约 2.6MB/分钟）",
                WavTool.humanSize(44100L * 60L).endsWith("MB"), WavTool.humanSize(44100L * 60L));
    }

    // ---------------------------------------------------------------- 5

    static void put(ZipOutputStream z, String name, String content) throws Exception {
        z.putNextEntry(new ZipEntry(name));
        z.write(content.getBytes("UTF-8"));
        z.closeEntry();
    }

    static void testEpub() throws Exception {
        File epub = new File(tmp, "test.epub");
        ZipOutputStream z = new ZipOutputStream(new FileOutputStream(epub));
        put(z, "mimetype", "application/epub+zip");
        put(z, "META-INF/container.xml",
                "<?xml version=\"1.0\"?><container version=\"1.0\" "
                        + "xmlns=\"urn:oasis:names:tc:opendocument:xmlns:container\">"
                        + "<rootfiles><rootfile full-path=\"OEBPS/content.opf\" "
                        + "media-type=\"application/oebps-package+xml\"/></rootfiles></container>");
        put(z, "OEBPS/content.opf",
                "<?xml version=\"1.0\"?><package xmlns=\"http://www.idpf.org/2007/opf\" "
                        + "xmlns:dc=\"http://purl.org/dc/elements/1.1/\" version=\"2.0\" unique-identifier=\"id\">"
                        + "<metadata><dc:title>测试电子书</dc:title><dc:creator>某作者</dc:creator></metadata>"
                        + "<manifest>"
                        + "<item id=\"c1\" href=\"chap1.xhtml\" media-type=\"application/xhtml+xml\"/>"
                        + "<item id=\"c2\" href=\"text/chap2.xhtml\" media-type=\"application/xhtml+xml\"/>"
                        + "<item id=\"c3\" href=\"../extra/chap3.xhtml\" media-type=\"application/xhtml+xml\"/>"
                        + "<item id=\"cover\" href=\"cover.xhtml\" media-type=\"application/xhtml+xml\"/>"
                        + "<item id=\"missing\" href=\"nope.xhtml\" media-type=\"application/xhtml+xml\"/>"
                        + "</manifest>"
                        + "<spine><itemref idref=\"cover\" linear=\"no\"/><itemref idref=\"c1\"/>"
                        + "<itemref idref=\"c2\"/><itemref idref=\"missing\"/><itemref idref=\"c3\"/></spine>"
                        + "</package>");
        put(z, "OEBPS/cover.xhtml", "<html><body><p>封面</p></body></html>");
        put(z, "OEBPS/chap1.xhtml", "<html><head><title>Chapter One</title><style>x{}</style></head>"
                + "<body><h1>第一章 &amp; 开篇</h1><p>正文一。</p><p>正文二。</p></body></html>");
        put(z, "OEBPS/text/chap2.xhtml", "<html><body><h2>第二章</h2><p>嵌套目录里的正文。</p></body></html>");
        put(z, "extra/chap3.xhtml", "<html><body><p>用 ../ 引用的第三章正文。</p></body></html>");
        z.close();

        EpubParser.Result r = EpubParser.parse(epub, "fallback");
        check("书名取自 dc:title", r.title.equals("测试电子书"), r.title);
        check("作者取自 dc:creator", r.author.equals("某作者"), r.author);
        check("spine 顺序 = 3 章（linear=no 与缺失项被跳过）", r.chapters.size() == 3, r.chapters.size() + "");
        check("标题来自 h1 且实体已解码",
                r.chapters.size() > 0 && r.chapters.get(0).title.contains("第一章 & 开篇"),
                r.chapters.size() > 0 ? r.chapters.get(0).title : "-");
        check("嵌套目录 href 解析正确",
                r.chapters.size() > 1 && r.chapters.get(1).text.contains("嵌套目录里的正文"), "");
        check("../ 相对路径折叠正确",
                r.chapters.size() > 2 && r.chapters.get(2).text.contains("引用的第三章正文"), "");
        check("style 内容没有混进正文", r.chapters.size() > 0 && !r.chapters.get(0).text.contains("x{}"), "");
        check("每章都有指纹 id", r.chapters.size() == 3 && r.chapters.get(0).id.length() == 8
                && !r.chapters.get(0).id.equals(r.chapters.get(2).id), "");
        check("resolveHref 处理 ./", EpubParser.resolveHref("OEBPS", "./a.xhtml").equals("OEBPS/a.xhtml"),
                EpubParser.resolveHref("OEBPS", "./a.xhtml"));
        check("resolveHref 处理 ../", EpubParser.resolveHref("OEBPS", "../x/a.xhtml").equals("x/a.xhtml"),
                EpubParser.resolveHref("OEBPS", "../x/a.xhtml"));
        check("resolveHref 处理 %20",
                EpubParser.resolveHref("OEBPS", "my%20c.xhtml").equals("OEBPS/my c.xhtml"),
                EpubParser.resolveHref("OEBPS", "my%20c.xhtml"));
        check("resolveHref 处理绝对路径", EpubParser.resolveHref("OEBPS", "/a.xhtml").equals("a.xhtml"),
                EpubParser.resolveHref("OEBPS", "/a.xhtml"));
    }

    // ---------------------------------------------------------------- 6

    static void testFingerprint() throws Exception {
        String body = "正文内容开头的一小段文字，用来做指纹输入，超过六十四个字符也没关系，会被截断处理掉。";
        String id1 = Fingerprint.chapterId("书", "第一章", body);
        check("同内容指纹稳定", id1.equals(Fingerprint.chapterId("书", "第一章", body)), "");
        check("改标题指纹变化", !id1.equals(Fingerprint.chapterId("书", "第二章", body)), "");
        check("改正文指纹变化", !id1.equals(Fingerprint.chapterId("书", "第一章", "完全不同的正文")), "");

        File f = write("stable.txt", "第一章 甲\n内容甲。\n第二章 乙\n内容乙。\n第三章 丙\n内容丙。\n".getBytes("UTF-8"));
        BookParser.invalidate();
        List<Chapter> r1 = BookParser.parse(f, "TXT", "稳定书", "k1").chapters;
        BookParser.invalidate();
        List<Chapter> r2 = BookParser.parse(f, "TXT", "稳定书", "k2").chapters;
        boolean same = r1.size() == r2.size() && r1.size() == 3;
        for (int i = 0; same && i < r1.size(); i++) {
            same = r1.get(i).id.equals(r2.get(i).id) && r1.get(i).title.equals(r2.get(i).title);
        }
        check("两次解析逐章一致（断点续传的根基）", same, r1.size() + " vs " + r2.size());

        String safe = SafeName.file("第三/章: 起风*了?\"<>/|");
        check("SafeName 清掉全部非法字符", safe.indexOf('/') < 0 && safe.indexOf(':') < 0
                && safe.indexOf('*') < 0 && safe.indexOf('"') < 0 && safe.indexOf('|') < 0, safe);
        StringBuilder longSb = new StringBuilder();
        for (int i = 0; i < 200; i++) {
            longSb.append('字');
        }
        check("SafeName 截断长标题", SafeName.file(longSb.toString(), 40).length() <= 40, "");
        check("SafeName 不留末尾句点", !SafeName.file("标题...").endsWith("."), SafeName.file("标题..."));
    }

    static File write(String name, byte[] data) throws Exception {
        File f = new File(tmp, name);
        FileOutputStream out = new FileOutputStream(f);
        out.write(data);
        out.close();
        return f;
    }
}
