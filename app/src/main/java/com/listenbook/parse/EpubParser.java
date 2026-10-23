package com.listenbook.parse;

import com.listenbook.model.Chapter;
import com.listenbook.util.Fingerprint;
import com.listenbook.util.HtmlStripper;
import com.listenbook.util.Io;
import com.listenbook.util.SafeName;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

/**
 * EPUB 解析。只用 java.util.zip.ZipFile + javax.xml.parsers，全是 JDK 标准库。
 *
 * 解析链：
 *   META-INF/container.xml → OPF 路径 → manifest(id→href) + spine(idref 顺序)
 *   → 逐个取 XHTML → HtmlStripper 去标签 → 纯文本
 *
 * spine 就是天然章节，边界 100% 准确，不用猜。
 */
public final class EpubParser {

    private EpubParser() {
    }

    public static final class Result {
        public String title = "";
        public String author = "";
        public List<Chapter> chapters = new ArrayList<Chapter>();
    }

    public static Result parse(File file, String fallbackTitle) throws IOException {
        Result res = new Result();
        ZipFile zip = null;
        try {
            zip = new ZipFile(file);
            Map<String, ZipEntry> entries = indexEntries(zip);

            String opfPath = findOpfPath(zip, entries);
            if (opfPath == null) {
                throw new IOException("EPUB 缺少 META-INF/container.xml 或 rootfile");
            }
            byte[] opfBytes = readEntry(zip, entries, opfPath);
            if (opfBytes == null) {
                throw new IOException("EPUB 无法读取 OPF: " + opfPath);
            }
            Document opf = newDocument(opfBytes);
            Element root = opf.getDocumentElement();
            String opfDir = parentPath(opfPath);

            res.title = readDc(root, "title");
            res.author = readDc(root, "creator");
            if (res.title.length() == 0) {
                res.title = fallbackTitle;
            }

            // manifest: id -> href
            Map<String, String> manifest = new HashMap<String, String>();
            NodeList items = root.getElementsByTagName("item");
            for (int i = 0; i < items.getLength(); i++) {
                Node n = items.item(i);
                if (!(n instanceof Element)) {
                    continue;
                }
                Element e = (Element) n;
                String id = e.getAttribute("id");
                String href = e.getAttribute("href");
                if (id.length() > 0 && href.length() > 0) {
                    manifest.put(id, resolveHref(opfDir, href));
                }
            }
            // manifest 里可能有 <manifest:manifest> 之类同名标签，用 spine 的 idref 才是权威顺序
            List<String> spineIds = new ArrayList<String>();
            NodeList spineNodes = root.getElementsByTagName("spine");
            if (spineNodes.getLength() > 0) {
                Element spine = (Element) spineNodes.item(0);
                NodeList itemrefs = spine.getElementsByTagName("itemref");
                for (int i = 0; i < itemrefs.getLength(); i++) {
                    Node n = itemrefs.item(i);
                    if (!(n instanceof Element)) {
                        continue;
                    }
                    Element e = (Element) n;
                    String linear = e.getAttribute("linear");
                    if (linear.equalsIgnoreCase("no")) {
                        continue; // 封面/版权页之类
                    }
                    String idref = e.getAttribute("idref");
                    if (idref.length() > 0) {
                        spineIds.add(idref);
                    }
                }
            }
            if (spineIds.isEmpty()) {
                spineIds.addAll(manifest.keySet());
            }

            List<Chapter> chapters = new ArrayList<Chapter>();
            for (int i = 0; i < spineIds.size(); i++) {
                String href = manifest.get(spineIds.get(i));
                if (href == null) {
                    continue;
                }
                byte[] data = readEntry(zip, entries, href);
                if (data == null) {
                    continue;
                }
                String html;
                try {
                    html = new String(data, "UTF-8");
                } catch (Exception e) {
                    continue;
                }
                String text = HtmlStripper.strip(html);
                if (text.replace("\n", "").trim().length() < 2) {
                    continue; // 空 XHTML（只有图片的页）直接跳过
                }
                String title = HtmlStripper.extractTitle(html);
                if (title.length() == 0) {
                    title = firstLineAsTitle(text, i);
                }
                Chapter c = new Chapter();
                c.title = SafeName.title(title, 60);
                c.text = text;
                chapters.add(c);
            }
            if (chapters.isEmpty()) {
                throw new IOException("EPUB spine 里没有找到任何可读文本");
            }
            // 只有一个 spine item 的 EPUB：按 TXT 规则再切一次，否则整本书一个 WAV
            if (chapters.size() == 1) {
                List<Chapter> split = TxtParser.parseText(chapters.get(0).text, res.title, false);
                if (split.size() > 1) {
                    chapters = split;
                }
            }
            for (int i = 0; i < chapters.size(); i++) {
                Chapter c = chapters.get(i);
                c.index = i;
                c.id = Fingerprint.chapterId(res.title, c.title, c.text);
            }
            res.chapters = chapters;
            return res;
        } finally {
            if (zip != null) {
                try {
                    zip.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    // ---------- zip / xml 辅助 ----------

    private static Map<String, ZipEntry> indexEntries(ZipFile zip) {
        Map<String, ZipEntry> map = new HashMap<String, ZipEntry>();
        java.util.Enumeration<? extends ZipEntry> en = zip.entries();
        while (en.hasMoreElements()) {
            ZipEntry e = en.nextElement();
            map.put(e.getName(), e);
            map.put(normalizeKey(e.getName()), e);
        }
        return map;
    }

    private static String normalizeKey(String name) {
        String s = name;
        if (s.startsWith("/")) {
            s = s.substring(1);
        }
        return s.replace("./", "").toLowerCase();
    }

    private static ZipEntry entry(Map<String, ZipEntry> entries, String path) {
        if (path == null || path.length() == 0) {
            return null;
        }
        ZipEntry e = entries.get(path);
        if (e != null) {
            return e;
        }
        e = entries.get(normalizeKey(path));
        if (e != null) {
            return e;
        }
        // 有些 EPUB 的 href 带 %20 之类转义
        try {
            String dec = URLDecoder.decode(path, "UTF-8");
            e = entries.get(dec);
            if (e == null) {
                e = entries.get(normalizeKey(dec));
            }
            return e;
        } catch (Exception ex) {
            return null;
        }
    }

    private static byte[] readEntry(ZipFile zip, Map<String, ZipEntry> entries, String path) {
        ZipEntry e = entry(entries, path);
        if (e == null) {
            return null;
        }
        InputStream in = null;
        try {
            in = zip.getInputStream(e);
            return Io.readAll(in);
        } catch (Exception ex) {
            return null;
        } finally {
            Io.close(in);
        }
    }

    private static String findOpfPath(ZipFile zip, Map<String, ZipEntry> entries) {
        byte[] container = readEntry(zip, entries, "META-INF/container.xml");
        if (container != null) {
            try {
                Document doc = newDocument(container);
                NodeList list = doc.getElementsByTagName("rootfile");
                for (int i = 0; i < list.getLength(); i++) {
                    Node n = list.item(i);
                    if (n instanceof Element) {
                        String fp = ((Element) n).getAttribute("full-path");
                        if (fp != null && fp.length() > 0) {
                            return fp;
                        }
                    }
                }
            } catch (Exception ignored) {
            }
        }
        // 兜底：直接找第一个 .opf
        java.util.Enumeration<? extends ZipEntry> en = zip.entries();
        while (en.hasMoreElements()) {
            String name = en.nextElement().getName();
            if (name.toLowerCase().endsWith(".opf")) {
                return name;
            }
        }
        return null;
    }

    private static Document newDocument(byte[] xml) throws IOException {
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(false);
            // 关掉外部实体，避免恶意 EPUB 触发网络/文件读取
            try {
                f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            } catch (Exception ignored) {
            }
            try {
                f.setFeature("http://xml.org/sax/features/external-general-entities", false);
            } catch (Exception ignored) {
            }
            DocumentBuilder b = f.newDocumentBuilder();
            return b.parse(new java.io.ByteArrayInputStream(xml));
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("XML 解析失败: " + e.getMessage());
        }
    }

    private static String readDc(Element root, String localName) {
        NodeList kids = root.getElementsByTagName("*");
        for (int i = 0; i < kids.getLength(); i++) {
            Node n = kids.item(i);
            String name = n.getNodeName();
            if (name == null) {
                continue;
            }
            String local = name.indexOf(':') >= 0 ? name.substring(name.indexOf(':') + 1) : name;
            if (local.equalsIgnoreCase(localName)) {
                String text = n.getTextContent();
                if (text != null) {
                    text = text.trim();
                    if (text.length() > 0) {
                        return SafeName.title(text, 80);
                    }
                }
            }
        }
        return "";
    }

    /** 把 href 相对 OPF 目录解析成 zip 内路径，处理 ../ 与 URL 转义 */
    public static String resolveHref(String opfDir, String href) {
        String h = href;
        try {
            h = URLDecoder.decode(h, "UTF-8");
        } catch (Exception ignored) {
        }
        h = h.replace('\\', '/');
        while (h.startsWith("./")) {
            h = h.substring(2);
        }
        if (h.startsWith("/")) {
            return h.substring(1);
        }
        String base = opfDir == null ? "" : opfDir;
        String full = base.length() == 0 ? h : base + "/" + h;
        // 折叠 ../ 与 ./
        String[] parts = full.split("/");
        List<String> stack = new ArrayList<String>();
        for (int i = 0; i < parts.length; i++) {
            String p = parts[i];
            if (p.length() == 0 || p.equals(".")) {
                continue;
            }
            if (p.equals("..")) {
                if (!stack.isEmpty()) {
                    stack.remove(stack.size() - 1);
                }
                continue;
            }
            stack.add(p);
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < stack.size(); i++) {
            if (i > 0) {
                sb.append('/');
            }
            sb.append(stack.get(i));
        }
        return sb.toString();
    }

    private static String parentPath(String path) {
        if (path == null) {
            return "";
        }
        int i = path.lastIndexOf('/');
        return i < 0 ? "" : path.substring(0, i);
    }

    private static String firstLineAsTitle(String text, int index) {
        String[] lines = text.split("\n");
        for (int i = 0; i < lines.length; i++) {
            String s = lines[i].trim();
            if (s.length() > 0) {
                return SafeName.title(s, 30);
            }
        }
        return "第 " + (index + 1) + " 节";
    }
}
