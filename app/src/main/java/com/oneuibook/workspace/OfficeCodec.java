package com.oneuibook.workspace;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import javax.xml.parsers.DocumentBuilderFactory;

/**
 * Pure Java OOXML / HWPX / HWP / CSV codec & local document storage aligned 1:1
 * with OneUI_Workspace_Native/app/editors.js data schemas:
 * - typing:  { kind:'typing', html, text }
 * - graphs:  { kind:'graphs', sheets:[{ name, cells:{ A1:{ v, b, bg, fmt, d } } }], active }
 * - present: { kind:'present', slides:[{ id, bg, transition, duration, notes, items:[...] }], active }
 */
final class OfficeCodec {
    private OfficeCodec() {}

    static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences("workspace_android", Context.MODE_PRIVATE);
    }

    static File docsDir(Context c) {
        File d = new File(c.getFilesDir(), "workspace_docs");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    /** Strictly confines local file operations to workspace_docs via canonical path check. */
    static boolean isInsideDocsDir(Context c, File f) {
        if (c == null || f == null) return false;
        try {
            String base = docsDir(c).getCanonicalPath();
            String target = f.getCanonicalPath();
            return target.startsWith(base + File.separator);
        } catch (Exception e) {
            return false;
        }
    }

    /** Sanitizes imported document HTML to neutralize script execution inside the WebView editor. */
    static String sanitizeHtml(String html) {
        if (html == null || html.isEmpty()) return "";
        String s = html;
        s = s.replaceAll("(?is)<(script|iframe|object|embed|applet|base|meta|link|form)\\b[^>]*>.*?</\\1>", "");
        s = s.replaceAll("(?is)<(script|iframe|object|embed|applet|base|meta|link|form)\\b[^>]*/?>", "");
        s = s.replaceAll("(?i)\\son[a-z]+\\s*=\\s*(\"[^\"]*\"|'[^']*'|[^\\s>]+)", "");
        s = s.replaceAll("(?i)(href|src|action|formaction)\\s*=\\s*\"\\s*(javascript|vbscript|data\\s*:\\s*text/html)[^\"]*\"", "$1=\"#\"");
        s = s.replaceAll("(?i)(href|src|action|formaction)\\s*=\\s*'\\s*(javascript|vbscript|data\\s*:\\s*text/html)[^']*'", "$1='#'");
        return s;
    }

    static JSONArray getRecent(Context c, String filterKind) {
        JSONArray out = new JSONArray();
        try {
            JSONArray all = new JSONArray(prefs(c).getString("recent_docs", "[]"));
            List<JSONObject> pinned = new ArrayList<>();
            List<JSONObject> normal = new ArrayList<>();
            for (int i = 0; i < all.length(); i++) {
                JSONObject it = all.getJSONObject(i);
                if (filterKind == null || "workspace".equals(filterKind) || filterKind.equals(it.optString("kind"))) {
                    if (it.optBoolean("pinned", false)) pinned.add(it); else normal.add(it);
                }
            }
            for (JSONObject it : pinned) out.put(it);
            for (JSONObject it : normal) out.put(it);
        } catch (Exception ignored) {}
        return out;
    }

    static boolean togglePinRecent(Context c, String path) {
        boolean nextPin = false;
        try {
            JSONArray all = new JSONArray(prefs(c).getString("recent_docs", "[]"));
            for (int i = 0; i < all.length(); i++) {
                JSONObject it = all.getJSONObject(i);
                if (path.equals(it.optString("path"))) {
                    nextPin = !it.optBoolean("pinned", false);
                    it.put("pinned", nextPin);
                    break;
                }
            }
            prefs(c).edit().putString("recent_docs", all.toString()).apply();
        } catch (Exception ignored) {}
        return nextPin;
    }

    static void removeRecent(Context c, String path) {
        try {
            JSONArray all = new JSONArray(prefs(c).getString("recent_docs", "[]"));
            JSONArray next = new JSONArray();
            for (int i = 0; i < all.length(); i++) {
                JSONObject it = all.getJSONObject(i);
                if (!path.equals(it.optString("path"))) next.put(it);
            }
            prefs(c).edit().putString("recent_docs", next.toString()).apply();
            if (path != null && !path.isEmpty()) {
                File f = new File(path);
                if (isInsideDocsDir(c, f)) {
                    f.delete();
                    new File(f.getCanonicalPath() + ".owjson").delete();
                }
            }
        } catch (Exception ignored) {}
    }

    static void addRecent(Context c, String path, String name, String kind) {
        try {
            JSONArray all = new JSONArray(prefs(c).getString("recent_docs", "[]"));
            boolean wasPinned = false;
            for (int i = 0; i < all.length(); i++) {
                JSONObject it = all.getJSONObject(i);
                if (path.equals(it.optString("path"))) wasPinned = it.optBoolean("pinned", false);
            }
            JSONArray next = new JSONArray();
            String now = new SimpleDateFormat("M월 d일 HH:mm", Locale.KOREA).format(new Date());
            next.put(new JSONObject().put("path", path).put("name", name).put("kind", kind).put("time", now).put("pinned", wasPinned));
            for (int i = 0; i < all.length() && next.length() < 30; i++) {
                JSONObject it = all.getJSONObject(i);
                if (!path.equals(it.optString("path"))) next.put(it);
            }
            prefs(c).edit().putString("recent_docs", next.toString()).apply();
        } catch (Exception ignored) {}
    }

    static String saveLocal(Context c, String existingPath, String suggested, String kind, JSONObject doc) throws Exception {
        String ext = "graphs".equals(kind) ? ".xlsx" : ("present".equals(kind) ? ".pptx" : ".docx");
        File f;
        if (existingPath != null && !existingPath.isEmpty() && isInsideDocsDir(c, new File(existingPath))) {
            f = new File(existingPath).getCanonicalFile();
        } else {
            String safe = (suggested == null || suggested.trim().isEmpty() ? "제목 없음" : suggested.trim())
                    .replaceAll("[\\\\/:*?\"<>|]", "_");
            f = new File(docsDir(c), safe + ext);
            int n = 1;
            while (f.exists()) f = new File(docsDir(c), safe + " (" + (++n) + ")" + ext);
        }
        // Store lossless JSON sidecar + real OOXML file
        try (FileOutputStream fos = new FileOutputStream(f)) {
            if ("graphs".equals(kind)) exportXlsx(doc, fos);
            else if ("present".equals(kind)) exportPptx(doc, fos);
            else exportDocx(doc, fos);
        }
        File sidecar = new File(f.getAbsolutePath() + ".owjson");
        try (FileOutputStream fos = new FileOutputStream(sidecar)) {
            fos.write(doc.toString().getBytes(StandardCharsets.UTF_8));
        }
        addRecent(c, f.getAbsolutePath(), f.getName(), kind);
        return f.getAbsolutePath();
    }

    static JSONObject readLocal(Context c, String path) throws Exception {
        if (path == null || path.isEmpty()) throw new SecurityException("Invalid path");
        File f = new File(path);
        if (!isInsideDocsDir(c, f)) throw new SecurityException("Access denied outside workspace_docs");
        File canon = f.getCanonicalFile();
        File sidecar = new File(canon.getAbsolutePath() + ".owjson");
        if (sidecar.exists() && isInsideDocsDir(c, sidecar)) {
            try (FileInputStream fis = new FileInputStream(sidecar)) {
                JSONObject doc = new JSONObject(new String(readAll(fis), StandardCharsets.UTF_8));
                if (doc.has("html")) doc.put("html", sanitizeHtml(doc.optString("html", "")));
                doc.put("path", canon.getAbsolutePath());
                doc.put("title", canon.getName());
                doc.put("imported", false);
                return doc;
            }
        }
        try (FileInputStream fis = new FileInputStream(canon)) {
            JSONObject doc = importStream(canon.getName(), fis);
            doc.put("path", canon.getAbsolutePath());
            return doc;
        }
    }

    static JSONObject importStream(String fileName, InputStream is) throws Exception {
        byte[] raw = readAll(is);
        String name = fileName == null ? "문서" : fileName;
        String lower = name.toLowerCase(Locale.ROOT);
        JSONObject out;
        if (lower.endsWith(".docx")) {
            out = new JSONObject().put("kind", "typing").put("html", sanitizeHtml(importDocx(raw)));
        } else if (lower.endsWith(".hwpx")) {
            out = new JSONObject().put("kind", "typing").put("html", sanitizeHtml(importHwpx(raw)));
        } else if (lower.endsWith(".hwp")) {
            out = new JSONObject().put("kind", "typing").put("html", sanitizeHtml(importHwpBinary(raw)));
        } else if (lower.endsWith(".xlsx")) {
            out = importXlsx(raw);
        } else if (lower.endsWith(".csv")) {
            out = importCsv(new String(raw, StandardCharsets.UTF_8));
        } else if (lower.endsWith(".pptx")) {
            out = importPptx(raw);
        } else {
            StringBuilder sb = new StringBuilder();
            for (String line : new String(raw, StandardCharsets.UTF_8).split("\r?\n"))
                sb.append("<p>").append(escHtml(line)).append("</p>");
            out = new JSONObject().put("kind", "typing").put("html", sb.toString());
        }
        out.put("title", name);
        out.put("imported", true);
        return out;
    }

    // ---- DOCX ----
    static String importDocx(byte[] zipBytes) throws Exception {
        byte[] xml = readZipEntry(zipBytes, "word/document.xml");
        if (xml == null) return "<p>(문서 본문을 찾지 못했습니다)</p>";
        Document doc = parseXml(xml);
        NodeList ps = doc.getElementsByTagNameNS("*", "p");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < ps.getLength(); i++) {
            Element p = (Element) ps.item(i);
            StringBuilder line = new StringBuilder();
            NodeList ts = p.getElementsByTagNameNS("*", "t");
            for (int j = 0; j < ts.getLength(); j++) line.append(ts.item(j).getTextContent());
            if (line.length() > 0) sb.append("<p>").append(escHtml(line.toString())).append("</p>");
        }
        return sb.length() > 0 ? sb.toString() : "<p><br></p>";
    }

    static void exportDocx(JSONObject doc, OutputStream os) throws Exception {
        String html = doc.optString("html", "");
        String plain = html.replaceAll("(?i)</(p|div|h1|h2|h3|li|tr)>", "\n")
                .replaceAll("(?i)<br\\s*/?>", "\n").replaceAll("<[^>]+>", "")
                .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&nbsp;", " ");
        StringBuilder body = new StringBuilder();
        for (String line : plain.split("\n")) {
            body.append("<w:p><w:r><w:t xml:space=\"preserve\">").append(escXml(line)).append("</w:t></w:r></w:p>");
        }
        try (ZipOutputStream zos = new ZipOutputStream(os)) {
            putZip(zos, "[Content_Types].xml",
                    "<?xml version=\"1.0\" encoding=\"UTF-8\"?><Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
                    + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
                    + "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
                    + "<Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/></Types>");
            putZip(zos, "_rels/.rels",
                    "<?xml version=\"1.0\" encoding=\"UTF-8\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                    + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"word/document.xml\"/></Relationships>");
            putZip(zos, "word/document.xml",
                    "<?xml version=\"1.0\" encoding=\"UTF-8\"?><w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body>"
                    + body + "</w:body></w:document>");
        }
    }

    // ---- HWPX / HWP (Rich HwpRich engine: images, merged tables, fills, boxes, styles) ----
    static String importHwpx(byte[] zipBytes) throws Exception {
        return HwpRich.renderHwpxBytes(zipBytes);
    }

    static String importHwpBinary(byte[] raw) {
        return HwpRich.renderHwpBytes(raw);
    }

    // ---- XLSX / CSV (editors.js GraphsEditor schema: sheets:[{name, cells:{A1:{v}}}] ) ----
    static JSONObject importXlsx(byte[] zipBytes) throws Exception {
        List<String> shared = new ArrayList<>();
        byte[] ss = readZipEntry(zipBytes, "xl/sharedStrings.xml");
        if (ss != null) {
            Document d = parseXml(ss);
            NodeList sis = d.getElementsByTagNameNS("*", "si");
            for (int i = 0; i < sis.getLength(); i++) shared.add(sis.item(i).getTextContent());
        }
        JSONObject cells = new JSONObject();
        byte[] sh = readZipEntry(zipBytes, "xl/worksheets/sheet1.xml");
        if (sh != null) {
            Document d = parseXml(sh);
            NodeList cs = d.getElementsByTagNameNS("*", "c");
            for (int i = 0; i < cs.getLength(); i++) {
                Element c = (Element) cs.item(i);
                String ref = c.getAttribute("r");
                String ty = c.getAttribute("t");
                NodeList fs = c.getElementsByTagNameNS("*", "f");
                if (fs.getLength() > 0) {
                    cells.put(ref, new JSONObject().put("v", "=" + fs.item(0).getTextContent()));
                    continue;
                }
                NodeList vs = c.getElementsByTagNameNS("*", "v");
                if (vs.getLength() > 0) {
                    String val = vs.item(0).getTextContent();
                    if ("s".equals(ty)) {
                        int idx = Integer.parseInt(val);
                        if (idx >= 0 && idx < shared.size()) cells.put(ref, new JSONObject().put("v", shared.get(idx)));
                    } else {
                        cells.put(ref, new JSONObject().put("v", val));
                    }
                }
            }
        }
        JSONArray sheets = new JSONArray().put(new JSONObject().put("name", "시트 1").put("cells", cells));
        return new JSONObject().put("kind", "graphs").put("sheets", sheets).put("active", 0);
    }

    static void exportXlsx(JSONObject doc, OutputStream os) throws Exception {
        JSONArray sheets = doc.optJSONArray("sheets");
        JSONObject cells = (sheets != null && sheets.length() > 0)
                ? sheets.getJSONObject(0).optJSONObject("cells") : new JSONObject();
        if (cells == null) cells = new JSONObject();
        StringBuilder sheetData = new StringBuilder("<sheetData>");
        for (int r = 1; r <= 50; r++) {
            StringBuilder rowXml = new StringBuilder();
            for (int col = 0; col < 18; col++) {
                String ref = "" + (char) ('A' + col) + r;
                if (!cells.has(ref)) continue;
                Object obj = cells.opt(ref);
                String v = obj instanceof JSONObject ? ((JSONObject) obj).optString("v", "") : String.valueOf(obj);
                if (v.isEmpty()) continue;
                if (v.startsWith("=")) rowXml.append("<c r=\"").append(ref).append("\"><f>").append(escXml(v.substring(1))).append("</f></c>");
                else if (isNumeric(v)) rowXml.append("<c r=\"").append(ref).append("\"><v>").append(escXml(v)).append("</v></c>");
                else rowXml.append("<c r=\"").append(ref).append("\" t=\"inlineStr\"><is><t>").append(escXml(v)).append("</t></is></c>");
            }
            if (rowXml.length() > 0) sheetData.append("<row r=\"").append(r).append("\">").append(rowXml).append("</row>");
        }
        sheetData.append("</sheetData>");
        try (ZipOutputStream zos = new ZipOutputStream(os)) {
            putZip(zos, "[Content_Types].xml",
                    "<?xml version=\"1.0\" encoding=\"UTF-8\"?><Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
                    + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
                    + "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
                    + "<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>"
                    + "<Override PartName=\"/xl/worksheets/sheet1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/></Types>");
            putZip(zos, "_rels/.rels",
                    "<?xml version=\"1.0\" encoding=\"UTF-8\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                    + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/></Relationships>");
            putZip(zos, "xl/workbook.xml",
                    "<?xml version=\"1.0\" encoding=\"UTF-8\"?><workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">"
                    + "<sheets><sheet name=\"Sheet1\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>");
            putZip(zos, "xl/_rels/workbook.xml.rels",
                    "<?xml version=\"1.0\" encoding=\"UTF-8\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                    + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet1.xml\"/></Relationships>");
            putZip(zos, "xl/worksheets/sheet1.xml",
                    "<?xml version=\"1.0\" encoding=\"UTF-8\"?><worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">"
                    + sheetData + "</worksheet>");
        }
    }

    static JSONObject importCsv(String csv) throws Exception {
        JSONObject cells = new JSONObject();
        String[] rows = csv.split("\r?\n");
        for (int r = 0; r < Math.min(rows.length, 50); r++) {
            String[] cols = rows[r].split(",");
            for (int c = 0; c < Math.min(cols.length, 18); c++) {
                String v = cols[c].trim();
                if (!v.isEmpty()) cells.put("" + (char) ('A' + c) + (r + 1), new JSONObject().put("v", v));
            }
        }
        return new JSONObject().put("kind", "graphs")
                .put("sheets", new JSONArray().put(new JSONObject().put("name", "시트 1").put("cells", cells)))
                .put("active", 0);
    }

    // ---- PPTX (editors.js PresentEditor schema: slides:[{id,bg,transition,duration,notes,items:[...]}]) ----
    static JSONObject importPptx(byte[] zipBytes) throws Exception {
        JSONArray slides = new JSONArray();
        for (int n = 1; n <= 50; n++) {
            byte[] xml = readZipEntry(zipBytes, "ppt/slides/slide" + n + ".xml");
            if (xml == null) break;
            Document d = parseXml(xml);
            NodeList ts = d.getElementsByTagNameNS("*", "t");
            String title = "";
            StringBuilder body = new StringBuilder();
            for (int i = 0; i < ts.getLength(); i++) {
                String txt = ts.item(i).getTextContent().trim();
                if (txt.isEmpty()) continue;
                if (title.isEmpty()) title = txt; else body.append(txt).append('\n');
            }
            JSONArray items = new JSONArray();
            items.put(new JSONObject().put("id", "e-" + n + "-1").put("type", "text").put("x", 8).put("y", 14)
                    .put("w", 84).put("h", 16).put("text", title.isEmpty() ? "슬라이드 " + n : title)
                    .put("size", 36).put("bold", true).put("color", "#1d2430").put("align", "left"));
            if (body.length() > 0) {
                items.put(new JSONObject().put("id", "e-" + n + "-2").put("type", "text").put("x", 8).put("y", 34)
                        .put("w", 84).put("h", 50).put("text", body.toString().trim())
                        .put("size", 22).put("bold", false).put("color", "#333d4b").put("align", "left"));
            }
            slides.put(new JSONObject().put("id", "s-" + n).put("bg", "#ffffff")
                    .put("transition", "fade").put("duration", 0.5).put("notes", "").put("items", items));
        }
        return new JSONObject().put("kind", "present").put("slides", slides).put("active", 0);
    }

    static void exportPptx(JSONObject doc, OutputStream os) throws Exception {
        JSONArray slides = doc.optJSONArray("slides");
        if (slides == null || slides.length() == 0) {
            slides = new JSONArray().put(new JSONObject().put("items", new JSONArray()
                    .put(new JSONObject().put("text", "프레젠테이션"))));
        }
        try (ZipOutputStream zos = new ZipOutputStream(os)) {
            StringBuilder overrides = new StringBuilder(), sldIds = new StringBuilder(), presRels = new StringBuilder();
            for (int i = 0; i < slides.length(); i++) {
                int n = i + 1;
                overrides.append("<Override PartName=\"/ppt/slides/slide").append(n)
                        .append(".xml\" ContentType=\"application/vnd.openxmlformats-officedocument.presentationml.slide+xml\"/>");
                sldIds.append("<p:sldId id=\"").append(256 + i).append("\" r:id=\"rId").append(n).append("\"/>");
                presRels.append("<Relationship Id=\"rId").append(n)
                        .append("\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/slide\" Target=\"slides/slide")
                        .append(n).append(".xml\"/>");
                JSONArray items = slides.getJSONObject(i).optJSONArray("items");
                String t = "", b = "";
                if (items != null) {
                    if (items.length() > 0) t = items.getJSONObject(0).optString("text", "");
                    StringBuilder bb = new StringBuilder();
                    for (int k = 1; k < items.length(); k++) {
                        String tx = items.getJSONObject(k).optString("text", "");
                        if (!tx.isEmpty()) bb.append(tx).append('\n');
                    }
                    b = bb.toString().trim();
                }
                putZip(zos, "ppt/slides/slide" + n + ".xml",
                        "<?xml version=\"1.0\" encoding=\"UTF-8\"?><p:sld xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" xmlns:p=\"http://schemas.openxmlformats.org/presentationml/2006/main\">"
                        + "<p:cSld><p:spTree><p:nvGrpSpPr><p:cNvPr id=\"1\" name=\"\"/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr><p:grpSpPr/>"
                        + "<p:sp><p:nvSpPr><p:cNvPr id=\"2\" name=\"Title\"/><p:cNvSpPr/><p:nvPr/></p:nvSpPr><p:spPr/><p:txBody><a:bodyPr/><a:p><a:r><a:t>" + escXml(t) + "</a:t></a:r></a:p></p:txBody></p:sp>"
                        + "<p:sp><p:nvSpPr><p:cNvPr id=\"3\" name=\"Body\"/><p:cNvSpPr/><p:nvPr/></p:nvSpPr><p:spPr/><p:txBody><a:bodyPr/><a:p><a:r><a:t>" + escXml(b) + "</a:t></a:r></a:p></p:txBody></p:sp>"
                        + "</p:spTree></p:cSld></p:sld>");
            }
            putZip(zos, "[Content_Types].xml",
                    "<?xml version=\"1.0\" encoding=\"UTF-8\"?><Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
                    + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
                    + "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
                    + "<Override PartName=\"/ppt/presentation.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.presentationml.presentation.main+xml\"/>"
                    + overrides + "</Types>");
            putZip(zos, "_rels/.rels",
                    "<?xml version=\"1.0\" encoding=\"UTF-8\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                    + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"ppt/presentation.xml\"/></Relationships>");
            putZip(zos, "ppt/presentation.xml",
                    "<?xml version=\"1.0\" encoding=\"UTF-8\"?><p:presentation xmlns:p=\"http://schemas.openxmlformats.org/presentationml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">"
                    + "<p:sldIdLst>" + sldIds + "</p:sldIdLst></p:presentation>");
            putZip(zos, "ppt/_rels/presentation.xml.rels",
                    "<?xml version=\"1.0\" encoding=\"UTF-8\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                    + presRels + "</Relationships>");
        }
    }

    /** Offline on-device text helper for the PC Workspace AI panel. */
    static String localAiAssist(String task, String text) {
        String clean = text == null ? "" : text.trim();
        if (clean.isEmpty()) return "분석할 문서 내용이 비어 있습니다.";
        String[] lines = clean.split("\r?\n");
        if ("bullets".equals(task)) {
            StringBuilder sb = new StringBuilder();
            for (String l : lines) {
                String t = l.trim();
                if (!t.isEmpty()) sb.append("• ").append(t.replaceFirst("^[•\\-*\\d.\\s]+", "")).append('\n');
            }
            return sb.toString().trim();
        }
        if ("polish".equals(task)) {
            return clean.replaceAll("[ \\t]+", " ").replaceAll("\n{3,}", "\n\n");
        }
        if ("summarize".equals(task)) {
            StringBuilder sb = new StringBuilder("핵심 요약:\n");
            int count = 0;
            for (String l : lines) {
                String t = l.trim();
                if (t.length() >= 4) { sb.append("- ").append(t).append('\n'); if (++count >= 3) break; }
            }
            return sb.toString().trim();
        }
        return "[온디바이스 안내] 번역 모델이 연결되어 있지 않아 원문을 그대로 반환합니다.\n\n" + clean;
    }

    private static final int MAX_IO_BYTES = 25 * 1024 * 1024;
    private static final int MAX_ZIP_ENTRIES = 1000;

    private static byte[] readZipEntry(byte[] zipBytes, String name) throws Exception {
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry e;
            int entries = 0;
            while ((e = zis.getNextEntry()) != null) {
                if (++entries > MAX_ZIP_ENTRIES) throw new IllegalStateException("ZIP 항목 수 제한 초과");
                if (name.equalsIgnoreCase(e.getName())) return readAll(zis);
            }
        }
        return null;
    }

    private static void putZip(ZipOutputStream zos, String name, String content) throws Exception {
        zos.putNextEntry(new ZipEntry(name));
        zos.write(content.getBytes(StandardCharsets.UTF_8));
        zos.closeEntry();
    }

    private static Document parseXml(byte[] xml) throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(true);
        f.setExpandEntityReferences(false);
        try { f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true); } catch (Throwable ignored) {}
        try { f.setFeature("http://xml.org/sax/features/external-general-entities", false); } catch (Throwable ignored) {}
        try { f.setFeature("http://xml.org/sax/features/external-parameter-entities", false); } catch (Throwable ignored) {}
        try { f.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false); } catch (Throwable ignored) {}
        try { f.setXIncludeAware(false); } catch (Throwable ignored) {}
        return f.newDocumentBuilder().parse(new ByteArrayInputStream(xml));
    }

    static byte[] readAll(InputStream is) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n, total = 0;
        while ((n = is.read(buf)) > 0) {
            total += n;
            if (total > MAX_IO_BYTES) throw new IllegalStateException("파일 크기 제한(25MB) 초과");
            bos.write(buf, 0, n);
        }
        return bos.toByteArray();
    }

    private static boolean isNumeric(String s) {
        try { Double.parseDouble(s); return true; } catch (Exception e) { return false; }
    }

    private static String escXml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private static String escHtml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
