package com.oneuibook.workspace;

import android.util.Base64;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.Inflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

/**
 * Self-contained rich HWPX (OWPML / KS X 6101) and HWP 5.0 (OLE2 CFB) to HTML importer.
 * Preserves BinData images, merged tables (colspan/rowspan, cell background fills, borders,
 * proportional widths), text boxes/shapes/template frames, headers/footers, character styles
 * (bold/italic/underline/strike/size/color/highlight), paragraph alignment/spacing, and page breaks.
 */
final class HwpRich {
    private HwpRich() {}

    private static final class CharStyle {
        float pt = 10f;
        boolean bold, italic, underline, strike;
        String color, bgColor;
    }

    private static final class ParaStyle {
        String align;
        float marginTopPt, marginBottomPt, marginLeftPt, lineHeight;
    }

    private static final class BorderFillStyle {
        String bgColor, borderColor;
        boolean hasAnyBorder = true;
    }

    private static final class HwpxStyles {
        final Map<String, byte[]> entries = new LinkedHashMap<>();
        final Map<String, String> entriesLower = new HashMap<>();
        final Map<String, CharStyle> chars = new HashMap<>();
        final Map<String, ParaStyle> paras = new HashMap<>();
        final Map<String, BorderFillStyle> borders = new HashMap<>();
        final Map<String, String> manifest = new HashMap<>();
        final Map<String, String> binByStem = new HashMap<>();
        long imgBudget = 32L * 1024 * 1024;
    }

    // ==================== HWPX ====================

    static String renderHwpxBytes(byte[] zipBytes) throws Exception {
        HwpxStyles st = new HwpxStyles();
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry ze;
            byte[] buf = new byte[16384];
            int entries = 0;
            long totalBytes = 0;
            while ((ze = zis.getNextEntry()) != null) {
                if (++entries > 1000) break;
                if (ze.isDirectory()) continue;
                String name = ze.getName().replace('\\', '/');
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                int n;
                while ((n = zis.read(buf)) > 0 && bos.size() < 16 * 1024 * 1024 && totalBytes < 32L * 1024 * 1024) {
                    bos.write(buf, 0, n);
                    totalBytes += n;
                }
                if (totalBytes >= 32L * 1024 * 1024) break;
                st.entries.put(name, bos.toByteArray());
                String nl = name.toLowerCase(Locale.ROOT);
                st.entriesLower.put(nl, name);
                if (nl.startsWith("bindata/") || nl.contains("/bindata/")) {
                    String base = baseName(name);
                    int dot = base.lastIndexOf('.');
                    String stem = (dot > 0 ? base.substring(0, dot) : base).toLowerCase(Locale.ROOT);
                    st.binByStem.put(stem, name);
                    st.binByStem.put(base.toLowerCase(Locale.ROOT), name);
                }
            }
        }

        parseHwpxManifest(st);
        parseHwpxHeader(st);

        List<String> secs = new ArrayList<>();
        for (String name : st.entries.keySet()) {
            if (name.matches("(?i)^Contents/section\\d+\\.xml$")) secs.add(name);
        }
        Collections.sort(secs, new Comparator<String>() {
            @Override public int compare(String a, String b) {
                if (a.length() != b.length()) return a.length() - b.length();
                return a.compareToIgnoreCase(b);
            }
        });
        if (secs.isEmpty()) return "<p>(HWPX 본문을 찾지 못했습니다)</p>";

        StringBuilder sb = new StringBuilder("<div class=\"hwp-doc\">");
        for (int si = 0; si < secs.size(); si++) {
            if (si > 0) sb.append("<hr class=\"pb\">");
            Document d = parseXml(st.entries.get(secs.get(si)));
            if (d != null && d.getDocumentElement() != null) {
                for (Element p : kids(d.getDocumentElement())) hwpxBlock(p, st, sb);
            }
        }
        sb.append("</div>");
        return sb.toString();
    }

    private static void parseHwpxManifest(HwpxStyles st) {
        byte[] raw = getEntry(st, "Contents/content.hpf");
        if (raw == null) return;
        Document d = parseXml(raw);
        if (d == null) return;
        for (Element it : descendants(d.getDocumentElement(), "item")) {
            String id = attr(it, "id");
            String href = attr(it, "href");
            if (id != null && href != null) {
                String clean = href.replace('\\', '/').trim();
                while (clean.startsWith("./")) clean = clean.substring(2);
                while (clean.startsWith("../")) clean = clean.substring(3);
                while (clean.startsWith("/")) clean = clean.substring(1);
                String res = st.entriesLower.get(clean.toLowerCase(Locale.ROOT));
                if (res == null) res = st.entriesLower.get(("contents/" + clean).toLowerCase(Locale.ROOT));
                if (res == null) res = st.binByStem.get(baseName(clean).toLowerCase(Locale.ROOT));
                if (res != null) {
                    st.manifest.put(id, res);
                    st.manifest.put(id.toLowerCase(Locale.ROOT), res);
                }
            }
        }
    }

    private static void parseHwpxHeader(HwpxStyles st) {
        byte[] raw = getEntry(st, "Contents/header.xml");
        if (raw == null) return;
        Document d = parseXml(raw);
        if (d == null || d.getDocumentElement() == null) return;

        for (Element cp : descendants(d.getDocumentElement(), "charPr")) {
            String id = attr(cp, "id");
            if (id == null) continue;
            CharStyle cs = new CharStyle();
            cs.pt = Math.max(6f, Math.min(72f, parseInt(attr(cp, "height"), 1000) / 100f));
            cs.color = normHexColor(attr(cp, "textColor"), false);
            String shade = attr(cp, "shadeColor");
            if (shade != null && !"none".equalsIgnoreCase(shade)) cs.bgColor = normHexColor(shade, true, true);
            cs.bold = kid(cp, "bold") != null;
            cs.italic = kid(cp, "italic") != null;
            Element ul = kid(cp, "underline");
            if (ul != null) cs.underline = !"NONE".equalsIgnoreCase(attr(ul, "type"));
            Element so = kid(cp, "strikeout");
            if (so != null) cs.strike = !"NONE".equalsIgnoreCase(attr(so, "shape"));
            st.chars.put(id, cs);
        }

        for (Element pp : descendants(d.getDocumentElement(), "paraPr")) {
            String id = attr(pp, "id");
            if (id == null) continue;
            ParaStyle ps = new ParaStyle();
            Element al = kid(pp, "align");
            if (al != null) {
                String horz = attr(al, "horizontal");
                if ("CENTER".equalsIgnoreCase(horz)) ps.align = "center";
                else if ("RIGHT".equalsIgnoreCase(horz)) ps.align = "right";
                else if ("JUSTIFY".equalsIgnoreCase(horz) || "DISTRIBUTE".equalsIgnoreCase(horz)) ps.align = "justify";
                else ps.align = "left";
            }
            Element mg = find(pp, "margin");
            if (mg != null) {
                Element prev = kid(mg, "prev"), next = kid(mg, "next"), left = kid(mg, "left");
                if (prev != null) ps.marginTopPt = parseInt(attr(prev, "value"), 0) / 100f;
                if (next != null) ps.marginBottomPt = parseInt(attr(next, "value"), 0) / 100f;
                if (left != null) ps.marginLeftPt = parseInt(attr(left, "value"), 0) / 100f;
            }
            Element ls = find(pp, "lineSpacing");
            if (ls != null) {
                int v = parseInt(attr(ls, "value"), 160);
                if (v >= 80 && v <= 400) ps.lineHeight = v / 100f;
            }
            st.paras.put(id, ps);
        }

        for (Element bf : descendants(d.getDocumentElement(), "borderFill")) {
            String id = attr(bf, "id");
            if (id == null) continue;
            BorderFillStyle bs = new BorderFillStyle();
            Element fill = find(bf, "fillBrush");
            if (fill != null) {
                Element wb = find(fill, "winBrush");
                if (wb != null) bs.bgColor = normHexColor(attr(wb, "faceColor"), true, true);
                if (bs.bgColor == null) {
                    Element grad = find(fill, "gradation");
                    if (grad != null) {
                        Element col = find(grad, "color");
                        if (col != null) bs.bgColor = normHexColor(attr(col, "value"), true, true);
                    }
                }
            }
            int vis = 0;
            String bc = null;
            for (String side : new String[]{"leftBorder", "rightBorder", "topBorder", "bottomBorder"}) {
                Element b = kid(bf, side);
                if (b != null && !"NONE".equalsIgnoreCase(attr(b, "type"))) {
                    vis++;
                    if (bc == null) bc = normHexColor(attr(b, "color"), false);
                }
            }
            bs.hasAnyBorder = vis > 0;
            bs.borderColor = bc != null ? bc : "#9AA5B4";
            st.borders.put(id, bs);
        }
    }

    private static void hwpxBlock(Element e, HwpxStyles st, StringBuilder sb) {
        if ("p".equals(ln(e))) {
            if ("1".equals(attr(e, "pageBreak"))) sb.append("<hr class=\"pb\">");
            ParaStyle ps = st.paras.get(attr(e, "paraPrIDRef"));
            String pStyle = buildParaStyleAttr(ps);
            StringBuilder line = new StringBuilder(), blocks = new StringBuilder();
            hwpxWalk(e, st, null, line, blocks);
            if (line.length() > 0) sb.append("<p").append(pStyle).append(">").append(line).append("</p>");
            else if (blocks.length() == 0) sb.append("<p").append(pStyle).append("><br></p>");
            sb.append(blocks);
        } else {
            for (Element c : kids(e)) hwpxBlock(c, st, sb);
        }
    }

    private static void hwpxWalk(Element e, HwpxStyles st, CharStyle curChar, StringBuilder line, StringBuilder blocks) {
        NodeList nl = e.getChildNodes();
        for (int i = 0; i < nl.getLength(); i++) {
            Node n = nl.item(i);
            if (n.getNodeType() != Node.ELEMENT_NODE) continue;
            Element x = (Element) n;
            String nm = ln(x);
            if ("run".equals(nm)) {
                CharStyle cs = st.chars.get(attr(x, "charPrIDRef"));
                hwpxWalk(x, st, cs != null ? cs : curChar, line, blocks);
            } else if ("t".equals(nm)) {
                StringBuilder txt = new StringBuilder();
                NodeList tnl = x.getChildNodes();
                for (int k = 0; k < tnl.getLength(); k++) {
                    Node tn = tnl.item(k);
                    if (tn.getNodeType() == Node.TEXT_NODE) txt.append(esc(tn.getNodeValue()));
                    else if (tn.getNodeType() == Node.ELEMENT_NODE) {
                        String tnm = ln(tn);
                        if ("tab".equals(tnm)) txt.append("&emsp;");
                        else if ("lineBreak".equals(tnm)) txt.append("<br>");
                        else if ("nbSpace".equals(tnm) || "fwSpace".equals(tnm)) txt.append("&nbsp;");
                    }
                }
                if (txt.length() > 0) appendStyledRun(line, txt.toString(), curChar);
            } else if ("tbl".equals(nm)) {
                renderHwpxTable(x, st, blocks);
            } else if ("pic".equals(nm) || "img".equals(nm)) {
                String im = renderHwpxPic(x, st);
                if (!im.isEmpty()) blocks.append(im);
            } else if ("rect".equals(nm) || "ellipse".equals(nm) || "polygon".equals(nm) || "container".equals(nm)) {
                renderHwpxShapeBox(x, st, blocks);
            } else if ("switch".equals(nm)) {
                Element branch = kid(x, "case");
                if (branch == null) branch = kid(x, "default");
                if (branch != null) hwpxWalk(branch, st, curChar, line, blocks);
            } else if ("header".equals(nm) || "footer".equals(nm)) {
                StringBuilder hf = new StringBuilder();
                for (Element p : directSubListParagraphs(x)) hwpxBlock(p, st, hf);
                if (hf.length() > 0) blocks.append("<div class=\"hwp-hf\">").append(hf).append("</div>");
            } else if (!"secPr".equals(nm) && !"colPr".equals(nm)) {
                hwpxWalk(x, st, curChar, line, blocks);
            }
        }
    }

    private static void renderHwpxTable(Element tbl, HwpxStyles st, StringBuilder out) {
        Element tblSz = kid(tbl, "sz");
        long tblWidth = tblSz != null ? parseLong(attr(tblSz, "width"), 0) : 0;
        BorderFillStyle tbf = st.borders.get(attr(tbl, "borderFillIDRef"));
        String tblStyle = "width:100%;border-collapse:collapse;margin:8px 0;" + (tbf != null && !tbf.hasAnyBorder ? "border:none;" : "");
        out.append("<table class=\"hwp-tbl\" style=\"").append(tblStyle).append("\">");
        for (Element tr : kids(tbl, "tr")) {
            out.append("<tr>");
            for (Element tc : kids(tr, "tc")) {
                int cs = 1, rs = 1;
                Element span = kid(tc, "cellSpan");
                if (span != null) {
                    cs = Math.max(1, parseInt(attr(span, "colSpan"), 1));
                    rs = Math.max(1, parseInt(attr(span, "rowSpan"), 1));
                }
                BorderFillStyle cbf = st.borders.get(attr(tc, "borderFillIDRef"));
                StringBuilder cst = new StringBuilder("padding:6px 8px;vertical-align:top;");
                if (cbf != null) {
                    if (cbf.bgColor != null) cst.append("background-color:").append(cbf.bgColor).append(";");
                    if (!cbf.hasAnyBorder) cst.append("border:none;");
                    else cst.append("border:1px solid ").append(cbf.borderColor).append(";");
                } else {
                    cst.append("border:1px solid #cbd5e1;");
                }
                Element csz = kid(tc, "cellSz");
                if (csz != null && tblWidth > 0) {
                    long cw = parseLong(attr(csz, "width"), 0);
                    if (cw > 0) cst.append("width:").append(Math.max(3, Math.min(100, (int) Math.round(cw * 100.0 / tblWidth)))).append("%;");
                }
                out.append("<td");
                if (cs > 1) out.append(" colspan=\"").append(cs).append("\"");
                if (rs > 1) out.append(" rowspan=\"").append(rs).append("\"");
                out.append(" style=\"").append(cst).append("\">");
                for (Element p : directSubListParagraphs(tc)) hwpxBlock(p, st, out);
                out.append("</td>");
            }
            out.append("</tr>");
        }
        out.append("</table>");
    }

    private static void renderHwpxShapeBox(Element box, HwpxStyles st, StringBuilder out) {
        if ("container".equals(ln(box))) {
            for (Element ch : kids(box)) {
                String cnm = ln(ch);
                if ("pic".equals(cnm) || "img".equals(cnm)) out.append(renderHwpxPic(ch, st));
                else if ("tbl".equals(cnm)) renderHwpxTable(ch, st, out);
                else if ("rect".equals(cnm) || "ellipse".equals(cnm) || "polygon".equals(cnm) || "container".equals(cnm)) renderHwpxShapeBox(ch, st, out);
            }
            return;
        }
        Element dt = kid(box, "drawText");
        List<Element> ps = dt != null ? directSubListParagraphs(dt) : Collections.<Element>emptyList();
        Element pic = find(box, "pic");
        if (ps.isEmpty() && pic == null) return;
        String bg = null, bd = null;
        Element fill = kid(box, "fillBrush");
        if (fill != null) {
            Element wb = find(fill, "winBrush");
            if (wb != null) bg = normHexColor(attr(wb, "faceColor"), true, true);
        }
        Element ls = kid(box, "lineShape");
        if (ls != null && !"NONE".equalsIgnoreCase(attr(ls, "style"))) bd = normHexColor(attr(ls, "color"), false);
        StringBuilder style = new StringBuilder("padding:10px 12px;margin:8px 0;border-radius:6px;");
        if (bg != null) style.append("background-color:").append(bg).append(";");
        if (bd != null) style.append("border:1px solid ").append(bd).append(";");
        else if (bg == null) style.append("border:1px solid #cbd5e1;");
        out.append("<div class=\"hwp-box\" style=\"").append(style).append("\">");
        if (pic != null) out.append(renderHwpxPic(pic, st));
        for (Element p : ps) hwpxBlock(p, st, out);
        out.append("</div>");
    }

    private static String renderHwpxPic(Element node, HwpxStyles st) {
        Element im = "img".equals(ln(node)) ? node : find(node, "img");
        if (im == null) return "";
        String id = attr(im, "binaryItemIDRef");
        if (id == null || id.isEmpty()) return "";
        String entry = st.manifest.get(id);
        if (entry == null) entry = st.manifest.get(id.toLowerCase(Locale.ROOT));
        if (entry == null) entry = st.binByStem.get(id.toLowerCase(Locale.ROOT));
        if (entry == null) return "";
        byte[] b = st.entries.get(entry);
        if (b == null || b.length == 0 || b.length > st.imgBudget) return "";
        String mime = detectImageMime(b, entry);
        if (mime == null) return "";
        st.imgBudget -= b.length;
        String style = "max-width:100%;height:auto;display:block;margin:8px auto;";
        Element curSz = find(node, "curSz");
        if (curSz != null) {
            long wHwp = parseLong(attr(curSz, "width"), 0);
            int wPx = (int) Math.round(wHwp / 75.0);
            if (wPx >= 24 && wPx <= 1200) style = "width:" + wPx + "px;max-width:100%;height:auto;display:block;margin:8px auto;";
        }
        return "<img src=\"data:" + mime + ";base64," + Base64.encodeToString(b, Base64.NO_WRAP) + "\" style=\"" + style + "\">";
    }

    // ==================== HWP 5.0 BINARY (OLE2 CFB) ====================

    private static final class Cfb {
        private final byte[] d;
        private final int ss, mss, cutoff;
        private final List<Integer> fat = new ArrayList<>();
        private final List<Integer> miniFat = new ArrayList<>();
        private final byte[] mini;
        final Map<String, int[]> entries = new LinkedHashMap<>();

        private int sec(int s) { return (s + 1) * ss; }
        private static int i32(byte[] b, int o) {
            return (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8) | ((b[o + 2] & 0xFF) << 16) | ((b[o + 3] & 0xFF) << 24);
        }
        private static int u16(byte[] b, int o) { return (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8); }

        Cfb(byte[] data) throws Exception {
            d = data;
            if (d.length < 512 || (d[0] & 0xFF) != 0xD0 || (d[1] & 0xFF) != 0xCF) throw new Exception("Not OLE2");
            ss = 1 << u16(d, 30);
            mss = 1 << u16(d, 32);
            int nFat = i32(d, 44), dirStart = i32(d, 48);
            cutoff = i32(d, 56);
            int miniStart = i32(d, 60), nMini = i32(d, 64), difStart = i32(d, 68), nDif = i32(d, 72);
            List<Integer> fatSecs = new ArrayList<>();
            for (int i = 0; i < 109 && fatSecs.size() < nFat; i++) {
                int v = i32(d, 76 + i * 4);
                if (v >= 0) fatSecs.add(v);
            }
            int ds = difStart;
            for (int i = 0; i < nDif && ds >= 0 && fatSecs.size() < nFat; i++) {
                int o = sec(ds);
                for (int j = 0; j < ss / 4 - 1 && fatSecs.size() < nFat; j++) {
                    int v = i32(d, o + j * 4);
                    if (v >= 0) fatSecs.add(v);
                }
                ds = i32(d, o + ss - 4);
            }
            for (int fs : fatSecs) {
                int o = sec(fs);
                for (int j = 0; j < ss / 4 && o + j * 4 + 4 <= d.length; j++) fat.add(i32(d, o + j * 4));
            }
            byte[] dir = chain(dirStart, -1);
            mini = chain(i32(dir, 116), i32(dir, 120));
            if (nMini > 0 && miniStart >= 0) {
                byte[] mf = chain(miniStart, -1);
                for (int i = 0; i + 4 <= mf.length; i += 4) miniFat.add(i32(mf, i));
            }
            walk(dir, i32(dir, 76), "", 0);
        }

        private void walk(byte[] dir, int id, String prefix, int guard) {
            if (id < 0 || guard > 5000 || id * 128 + 128 > dir.length) return;
            int o = id * 128;
            int nl = u16(dir, o + 64);
            String name = new String(dir, o, Math.max(0, nl - 2), StandardCharsets.UTF_16LE);
            int type = dir[o + 66] & 0xFF;
            walk(dir, i32(dir, o + 68), prefix, guard + 1);
            walk(dir, i32(dir, o + 72), prefix, guard + 1);
            String full = (prefix + name).toLowerCase(Locale.ROOT);
            if (type == 2) entries.put(full, new int[]{i32(dir, o + 116), i32(dir, o + 120)});
            else if (type == 1) walk(dir, i32(dir, o + 76), full + "/", guard + 1);
        }

        private byte[] chain(int start, int size) {
            ByteArrayOutputStream ms = new ByteArrayOutputStream();
            int s = start, n = 0;
            while (s >= 0 && s < fat.size() && n++ < 400000) {
                int o = sec(s);
                if (o + ss > d.length) break;
                ms.write(d, o, ss);
                s = fat.get(s);
            }
            byte[] r = ms.toByteArray();
            return (size >= 0 && size < r.length) ? Arrays.copyOf(r, size) : r;
        }

        byte[] read(String name) {
            int[] e = entries.get(name.toLowerCase(Locale.ROOT));
            if (e == null) return null;
            if (e[1] >= cutoff) return chain(e[0], e[1]);
            ByteArrayOutputStream ms = new ByteArrayOutputStream();
            int s = e[0], n = 0;
            while (s >= 0 && s < miniFat.size() && n++ < 100000) {
                int o = s * mss;
                if (o + mss > mini.length) break;
                ms.write(mini, o, mss);
                s = miniFat.get(s);
            }
            byte[] r = ms.toByteArray();
            return (e[1] >= 0 && e[1] < r.length) ? Arrays.copyOf(r, e[1]) : r;
        }
    }

    private static final class HwpRec {
        final int tag, level;
        final byte[] data;
        HwpRec(int t, int l, byte[] d) { tag = t; level = l; data = d; }
    }

    private static final class HwpDocInfo {
        final List<String> binNames = new ArrayList<>();
        final Map<Integer, byte[]> binBytes = new LinkedHashMap<>();
        final Map<Integer, String> binMime = new HashMap<>();
        final Set<Integer> usedBins = new HashSet<>();
        final List<BorderFillStyle> borderFills = new ArrayList<>();
        final List<CharStyle> charShapes = new ArrayList<>();
        final List<ParaStyle> paraShapes = new ArrayList<>();
        long imgBudget = 32L * 1024 * 1024;
    }

    private static final class HwpCell {
        int col, row, colSpan = 1, rowSpan = 1, borderFillId;
        long width;
        String html = "";
    }

    static String renderHwpBytes(byte[] fileBytes) {
        if (fileBytes == null || fileBytes.length < 512 || (fileBytes[0] & 0xFF) != 0xD0 || (fileBytes[1] & 0xFF) != 0xCF) {
            return fallbackRawUtf16(fileBytes);
        }
        try {
            Cfb cf = new Cfb(fileBytes);
            byte[] fh = cf.read("FileHeader");
            if (fh == null || fh.length < 40) return "<p>(HWP 헤더를 읽을 수 없습니다)</p>";
            int flags = le32(fh, 36);
            boolean compressed = (flags & 1) != 0;
            if ((flags & 2) != 0) return "<p>(암호가 설정된 HWP 문서는 열 수 없습니다)</p>";
            boolean distribution = (flags & 4) != 0;

            HwpDocInfo info = new HwpDocInfo();
            byte[] docInfoRaw = cf.read("DocInfo");
            if (docInfoRaw != null && docInfoRaw.length > 0) {
                parseHwpDocInfo(parseHwpRecords(compressed ? inflateSafe(docInfoRaw) : docInfoRaw), info);
            }
            loadHwpBinData(cf, compressed, info);

            StringBuilder sb = new StringBuilder("<div class=\"hwp-doc\">");
            int sec = 0;
            boolean hasContent = false;
            String secPrefix = distribution ? "ViewText/Section" : "BodyText/Section";
            while (true) {
                byte[] raw = cf.read(secPrefix + sec);
                if (raw == null) break;
                if (sec > 0) sb.append("<hr class=\"pb\">");
                sec++;
                byte[] secBytes = distribution ? decryptHwpViewText(raw, compressed) : (compressed ? inflateSafe(raw) : raw);
                if (secBytes != null && secBytes.length > 0) {
                    List<HwpRec> recs = parseHwpRecords(secBytes);
                    int before = sb.length();
                    renderHwpRange(recs, 0, recs.size(), 0, info, sb);
                    if (sb.length() > before) hasContent = true;
                }
            }
            if (!hasContent) {
                byte[] prvImg = cf.read("PrvImage");
                if (prvImg != null && prvImg.length > 16) {
                    String mime = detectImageMime(prvImg, "prv.png");
                    if (mime != null) {
                        sb.append("<img src=\"data:").append(mime).append(";base64,")
                                .append(Base64.encodeToString(prvImg, Base64.NO_WRAP))
                                .append("\" style=\"max-width:100%;height:auto;display:block;margin:8px auto;\">");
                        hasContent = true;
                    }
                }
                byte[] prvTxt = cf.read("PrvText");
                if (prvTxt != null && prvTxt.length >= 2) {
                    String txt = new String(prvTxt, StandardCharsets.UTF_16LE).trim();
                    if (!txt.isEmpty()) {
                        for (String line : txt.split("\\r?\\n")) sb.append("<p>").append(esc(line)).append("</p>");
                        hasContent = true;
                    }
                }
            }
            for (Map.Entry<Integer, byte[]> e : info.binBytes.entrySet()) {
                if (!info.usedBins.contains(e.getKey())) {
                    String tag = hwpImageTag(e.getKey(), info);
                    if (!tag.isEmpty()) { sb.append(tag); hasContent = true; }
                }
            }
            if (!hasContent) sb.append("<p>(문서 내용이 없습니다)</p>");
            return sb.append("</div>").toString();
        } catch (Throwable t) {
            String fb = fallbackRawUtf16(fileBytes);
            if (!fb.isEmpty()) return fb;
            return "<p>(HWP 문서 열기 오류: " + esc(t.getMessage()) + ")</p>";
        }
    }

    private static String fallbackRawUtf16(byte[] raw) {
        if (raw == null || raw.length < 2) return "<p>(문서 내용이 없습니다)</p>";
        StringBuilder out = new StringBuilder(), run = new StringBuilder();
        for (int i = 0; i + 1 < raw.length; i += 2) {
            char ch = (char) ((raw[i] & 0xFF) | ((raw[i + 1] & 0xFF) << 8));
            boolean ok = (ch >= 0xAC00 && ch <= 0xD7A3) || (ch >= 0x20 && ch <= 0x7E) || ch == '\n' || ch == '\r' || ch == '\t';
            if (ok) run.append(ch);
            else { if (run.length() >= 8) out.append(run).append('\n'); run.setLength(0); }
        }
        if (run.length() >= 8) out.append(run);
        if (out.length() == 0) return "";
        StringBuilder html = new StringBuilder();
        for (String line : out.toString().split("\r?\n")) {
            String t = line.trim();
            if (!t.isEmpty()) html.append("<p>").append(esc(t)).append("</p>");
        }
        return html.toString();
    }

    private static List<HwpRec> parseHwpRecords(byte[] raw) {
        List<HwpRec> out = new ArrayList<>();
        if (raw == null) return out;
        int pos = 0;
        while (pos + 4 <= raw.length) {
            long h = le32(raw, pos) & 0xFFFFFFFFL;
            pos += 4;
            int tag = (int) (h & 0x3FF);
            int level = (int) ((h >>> 10) & 0x3FF);
            int size = (int) (h >>> 20);
            if (size == 0xFFF) {
                if (pos + 4 > raw.length) break;
                size = le32(raw, pos);
                pos += 4;
            }
            if (size < 0 || pos + size > raw.length) break;
            out.add(new HwpRec(tag, level, Arrays.copyOfRange(raw, pos, pos + size)));
            pos += size;
        }
        return out;
    }

    private static void parseHwpDocInfo(List<HwpRec> recs, HwpDocInfo info) {
        for (HwpRec r : recs) {
            byte[] d = r.data;
            if (r.tag == 0x12 && d.length >= 6) {
                int type = le16(d, 0) & 0x0F;
                if (type == 1 || type == 2) {
                    int binId = le16(d, 2);
                    String ext = "";
                    if (d.length >= 6) {
                        int elen = le16(d, 4);
                        if (elen > 0 && 6 + elen * 2 <= d.length) ext = readUtf16(d, 6, elen).toLowerCase(Locale.ROOT);
                    }
                    info.binNames.add(String.format(Locale.ROOT, "BinData/BIN%04X%s", binId, ext.isEmpty() ? "" : "." + ext));
                } else info.binNames.add("");
            } else if (r.tag == 0x14 && d.length >= 26) {
                BorderFillStyle bs = new BorderFillStyle();
                int vis = 0; String bc = null;
                for (int i = 0; i < 4; i++) {
                    if ((d[2 + i] & 0xFF) != 0) {
                        vis++;
                        if (bc == null && 10 + i * 4 + 4 <= d.length) bc = colorRefToHex(le32(d, 10 + i * 4), false);
                    }
                }
                bs.hasAnyBorder = vis > 0;
                bs.borderColor = bc != null ? bc : "#9AA5B4";
                if (d.length >= 44 && (le32(d, 32) & 1) != 0) bs.bgColor = colorRefToHex(le32(d, 36), true, true);
                info.borderFills.add(bs);
            } else if (r.tag == 0x15 && d.length >= 56) {
                CharStyle cs = new CharStyle();
                cs.pt = Math.max(6f, Math.min(72f, le32(d, 42) / 100f));
                int attr = le32(d, 46);
                cs.italic = (attr & 1) != 0;
                cs.bold = (attr & 2) != 0;
                cs.underline = ((attr >>> 2) & 3) != 0;
                cs.strike = ((attr >>> 18) & 7) != 0;
                cs.color = colorRefToHex(le32(d, 52), false, false);
                // Offset 56 is underlineColor (defaults to 0x00000000 black!); offset 60 is shadeColor (0xFFFFFFFF = none)
                if (d.length >= 64) {
                    int shade = le32(d, 60);
                    if (shade != -1 && shade != 0 && (shade & 0xFFFFFF) != 0xFFFFFF && (shade & 0xFFFFFF) != 0) {
                        cs.bgColor = colorRefToHex(shade, true, true);
                    }
                }
                info.charShapes.add(cs);
            } else if (r.tag == 0x19 && d.length >= 4) {
                ParaStyle ps = new ParaStyle();
                int al = (le32(d, 0) >>> 2) & 7;
                ps.align = al == 1 ? "left" : (al == 2 ? "right" : (al == 3 ? "center" : "justify"));
                if (d.length >= 24) {
                    ps.marginLeftPt = Math.max(0, le32(d, 4) / 200f);
                    ps.marginTopPt = Math.max(0, le32(d, 16) / 200f);
                    ps.marginBottomPt = Math.max(0, le32(d, 20) / 200f);
                }
                info.paraShapes.add(ps);
            }
        }
    }

    private static void loadHwpBinData(Cfb cf, boolean compressed, HwpDocInfo info) {
        for (int i = 0; i < info.binNames.size(); i++) {
            String nm = info.binNames.get(i);
            if (nm == null || nm.isEmpty()) continue;
            byte[] raw = cf.read(nm);
            if (raw != null && raw.length > 0) storeBinImage(i + 1, nm, raw, compressed, info);
        }
        for (String name : cf.entries.keySet()) {
            if (!name.startsWith("bindata/")) continue;
            int id = parseBinHexId(name);
            if (id <= 0 || info.binBytes.containsKey(id)) continue;
            byte[] raw = cf.read(name);
            if (raw != null && raw.length > 0) storeBinImage(id, name, raw, compressed, info);
        }
    }

    private static void storeBinImage(int id, String name, byte[] raw, boolean compressed, HwpDocInfo info) {
        byte[] data = raw;
        if (detectImageMime(data, "") == null && compressed) {
            byte[] inf = inflateSafe(raw);
            if (inf != null && inf.length > 0) data = inf;
        }
        String mime = detectImageMime(data, name);
        if (mime != null && data.length > 0) { info.binBytes.put(id, data); info.binMime.put(id, mime); }
    }

    private static int renderHwpRange(List<HwpRec> recs, int start, int end, int baseLevel, HwpDocInfo info, StringBuilder sb) {
        int i = start;
        while (i < end) {
            HwpRec r = recs.get(i);
            if (r.level < baseLevel) break;
            if (r.tag == 0x42 && r.level == baseLevel) {
                int paraShapeId = r.data.length >= 10 ? le16(r.data, 8) : -1;
                if (r.data.length >= 13 && (r.data[12] & 4) != 0) sb.append("<hr class=\"pb\">");
                int nextPara = i + 1;
                while (nextPara < end && recs.get(nextPara).level > baseLevel) nextPara++;
                byte[] textData = null, csData = null;
                StringBuilder childBlocks = new StringBuilder();
                int j = i + 1;
                while (j < nextPara) {
                    HwpRec c = recs.get(j);
                    if (c.level == baseLevel + 1) {
                        if (c.tag == 0x43) { textData = c.data; j++; }
                        else if (c.tag == 0x44) { csData = c.data; j++; }
                        else if (c.tag == 0x47 && c.data.length >= 4) {
                            int ctrlEnd = j + 1;
                            while (ctrlEnd < nextPara && recs.get(ctrlEnd).level > c.level) ctrlEnd++;
                            String cid = ctrlIdStr(c.data);
                            if ("tbl ".equals(cid)) renderHwpTable(recs, j, ctrlEnd, c.level, info, childBlocks);
                            else if ("gso ".equals(cid)) renderHwpGso(recs, j, ctrlEnd, info, childBlocks);
                            else if ("head".equals(cid) || "foot".equals(cid)) {
                                StringBuilder hf = new StringBuilder();
                                for (int k = j + 1; k < ctrlEnd; k++) {
                                    if (recs.get(k).tag == 0x42) k = renderHwpRange(recs, k, ctrlEnd, recs.get(k).level, info, hf) - 1;
                                }
                                if (hf.length() > 0) childBlocks.append("<div class=\"hwp-hf\">").append(hf).append("</div>");
                            }
                            j = ctrlEnd;
                        } else j++;
                    } else j++;
                }
                String pHtml = renderHwpParaText(textData, csData, info);
                ParaStyle ps = (paraShapeId >= 0 && paraShapeId < info.paraShapes.size()) ? info.paraShapes.get(paraShapeId) : null;
                String pStyle = buildParaStyleAttr(ps);
                if (!pHtml.isEmpty()) sb.append("<p").append(pStyle).append(">").append(pHtml).append("</p>");
                else if (childBlocks.length() == 0) sb.append("<p").append(pStyle).append("><br></p>");
                sb.append(childBlocks);
                i = nextPara;
            } else i++;
        }
        return i;
    }

    private static String renderHwpParaText(byte[] textData, byte[] csData, HwpDocInfo info) {
        if (textData == null || textData.length < 2) return "";
        List<int[]> runs = new ArrayList<>();
        if (csData != null) for (int p = 0; p + 8 <= csData.length; p += 8) runs.add(new int[]{le32(csData, p), le32(csData, p + 4)});
        StringBuilder out = new StringBuilder(), curBuf = new StringBuilder();
        CharStyle curStyle = null;
        int runIdx = 0, charPos = 0;
        for (int p = 0; p + 1 < textData.length; ) {
            while (runIdx + 1 < runs.size() && runs.get(runIdx + 1)[0] <= charPos) runIdx++;
            CharStyle want = (!runs.isEmpty() && runs.get(runIdx)[1] >= 0 && runs.get(runIdx)[1] < info.charShapes.size())
                    ? info.charShapes.get(runs.get(runIdx)[1]) : null;
            if (want != curStyle && curBuf.length() > 0) { appendStyledRun(out, curBuf.toString(), curStyle); curBuf.setLength(0); }
            curStyle = want;
            int ch = le16(textData, p);
            if (ch >= 32) { curBuf.append(esc(String.valueOf((char) ch))); p += 2; charPos++; }
            else if (ch == 9) { curBuf.append("&emsp;"); p += 16; charPos += 8; }
            else if (ch == 10) { curBuf.append("<br>"); p += 2; charPos++; }
            else if (ch == 13 || ch == 0) { p += 2; charPos++; }
            else if (ch == 24) { curBuf.append("-"); p += 2; charPos++; }
            else if (ch == 30 || ch == 31) { curBuf.append(" "); p += 2; charPos++; }
            else if (ch < 24) { p += 16; charPos += 8; }
            else { p += 2; charPos++; }
        }
        if (curBuf.length() > 0) appendStyledRun(out, curBuf.toString(), curStyle);
        return out.toString();
    }

    private static void renderHwpTable(List<HwpRec> recs, int start, int end, int ctrlLevel, HwpDocInfo info, StringBuilder out) {
        int rowCount = 0, colCount = 0;
        List<HwpCell> cells = new ArrayList<>();
        int cellLevel = ctrlLevel + 1;
        int i = start + 1;
        while (i < end) {
            HwpRec r = recs.get(i);
            if (r.tag == 0x4D && r.level == cellLevel && r.data.length >= 8) {
                rowCount = le16(r.data, 4);
                colCount = le16(r.data, 6);
                i++;
            } else if (r.tag == 0x48 && r.level == cellLevel) {
                HwpCell cell = new HwpCell();
                int off = detectCellPropsOffset(r.data, rowCount, colCount);
                if (off > 0) {
                    cell.col = le16(r.data, off);
                    cell.row = le16(r.data, off + 2);
                    cell.colSpan = Math.max(1, le16(r.data, off + 4));
                    cell.rowSpan = Math.max(1, le16(r.data, off + 6));
                    cell.width = le32(r.data, off + 8) & 0xFFFFFFFFL;
                    if (off + 26 <= r.data.length) cell.borderFillId = le16(r.data, off + 24);
                }
                int cellEnd = i + 1;
                while (cellEnd < end && recs.get(cellEnd).level > cellLevel) cellEnd++;
                StringBuilder cHtml = new StringBuilder();
                renderHwpRange(recs, i + 1, cellEnd, cellLevel + 1, info, cHtml);
                cell.html = cHtml.toString();
                cells.add(cell);
                i = cellEnd;
            } else i++;
        }
        if (cells.isEmpty()) return;
        Map<Integer, List<HwpCell>> byRow = new LinkedHashMap<>();
        int maxRow = Math.max(rowCount - 1, 0);
        for (HwpCell c : cells) {
            if (c.row > maxRow) maxRow = c.row;
            List<HwpCell> l = byRow.get(c.row);
            if (l == null) { l = new ArrayList<>(); byRow.put(c.row, l); }
            l.add(c);
        }
        out.append("<table class=\"hwp-tbl\" style=\"width:100%;border-collapse:collapse;margin:8px 0;\">");
        for (int rr = 0; rr <= maxRow; rr++) {
            List<HwpCell> rowCells = byRow.get(rr);
            if (rowCells == null || rowCells.isEmpty()) continue;
            Collections.sort(rowCells, new Comparator<HwpCell>() {
                @Override public int compare(HwpCell a, HwpCell b) { return Integer.compare(a.col, b.col); }
            });
            long rowW = 0;
            for (HwpCell c : rowCells) rowW += Math.max(0, c.width);
            out.append("<tr>");
            for (HwpCell c : rowCells) {
                StringBuilder st = new StringBuilder("padding:6px 8px;vertical-align:top;");
                BorderFillStyle bf = (c.borderFillId >= 1 && c.borderFillId <= info.borderFills.size())
                        ? info.borderFills.get(c.borderFillId - 1) : null;
                if (bf != null) {
                    if (bf.bgColor != null) st.append("background-color:").append(bf.bgColor).append(";");
                    if (!bf.hasAnyBorder) st.append("border:none;");
                    else st.append("border:1px solid ").append(bf.borderColor).append(";");
                } else st.append("border:1px solid #cbd5e1;");
                if (rowW > 0 && c.width > 0) st.append("width:").append(Math.max(4, Math.min(100, (int) Math.round(c.width * 100.0 / rowW)))).append("%;");
                out.append("<td");
                if (c.colSpan > 1) out.append(" colspan=\"").append(c.colSpan).append("\"");
                if (c.rowSpan > 1) out.append(" rowspan=\"").append(c.rowSpan).append("\"");
                out.append(" style=\"").append(st).append("\">").append(c.html.isEmpty() ? "&nbsp;" : c.html).append("</td>");
            }
            out.append("</tr>");
        }
        out.append("</table>");
    }

    private static int detectCellPropsOffset(byte[] data, int rowCount, int colCount) {
        if (data == null || data.length < 32) return -1;
        int maxR = rowCount > 0 ? rowCount : 500;
        int maxC = colCount > 0 ? colCount : 200;
        for (int cand : new int[]{6, 8}) {
            if (cand + 26 > data.length) continue;
            int col = le16(data, cand);
            int row = le16(data, cand + 2);
            int cs = le16(data, cand + 4);
            int rs = le16(data, cand + 6);
            long w = le32(data, cand + 8) & 0xFFFFFFFFL;
            if (col < maxC && row < maxR && cs >= 1 && cs <= maxC && rs >= 1 && rs <= maxR && w > 0 && w < 200000L) {
                return cand;
            }
        }
        return data.length >= 34 ? 8 : 6;
    }

    private static void renderHwpGso(List<HwpRec> recs, int start, int end, HwpDocInfo info, StringBuilder out) {
        int i = start + 1;
        while (i < end) {
            HwpRec r = recs.get(i);
            if (r.tag == 0x55) {
                int binId = extractHwpPictureBinId(r.data, info);
                if (binId > 0) out.append(hwpImageTag(binId, info));
                i++;
            } else if (r.tag == 0x48) {
                int boxEnd = i + 1;
                while (boxEnd < end && recs.get(boxEnd).level > r.level) boxEnd++;
                StringBuilder inner = new StringBuilder();
                renderHwpRange(recs, i + 1, boxEnd, r.level + 1, info, inner);
                if (inner.length() > 0) out.append("<div class=\"hwp-box\" style=\"border:1px solid #cbd5e1;border-radius:6px;padding:10px 12px;margin:8px 0;\">").append(inner).append("</div>");
                i = boxEnd;
            } else i++;
        }
    }

    private static int extractHwpPictureBinId(byte[] d, HwpDocInfo info) {
        if (d == null || d.length < 73) return -1;
        for (int off : new int[]{71, 67, 73, 69}) {
            if (off + 2 <= d.length) {
                int id = le16(d, off);
                if (id >= 1 && info.binBytes.containsKey(id)) return id;
            }
        }
        for (int off = 64; off + 2 <= Math.min(d.length, 84); off++) {
            int id = le16(d, off);
            if (id >= 1 && info.binBytes.containsKey(id)) return id;
        }
        return -1;
    }

    private static String hwpImageTag(int binId, HwpDocInfo info) {
        byte[] b = info.binBytes.get(binId);
        String mime = info.binMime.get(binId);
        if (b == null || mime == null || b.length > info.imgBudget) return "";
        info.usedBins.add(binId);
        info.imgBudget -= b.length;
        return "<img src=\"data:" + mime + ";base64," + Base64.encodeToString(b, Base64.NO_WRAP)
                + "\" style=\"max-width:100%;height:auto;display:block;margin:8px auto;\">";
    }

    // ==================== Shared Helpers ====================

    private static void appendStyledRun(StringBuilder sb, String html, CharStyle cs) {
        if (html == null || html.isEmpty()) return;
        if (cs == null) { sb.append(html); return; }
        StringBuilder st = new StringBuilder();
        if (Math.abs(cs.pt - 10f) >= 0.8f) st.append("font-size:").append(String.format(Locale.US, "%.1fpt;", cs.pt));
        if (cs.color != null && !"#000000".equalsIgnoreCase(cs.color)) st.append("color:").append(cs.color).append(";");
        if (cs.bgColor != null) st.append("background-color:").append(cs.bgColor).append(";");
        if (cs.bold) st.append("font-weight:bold;");
        if (cs.italic) st.append("font-style:italic;");
        if (cs.underline && cs.strike) st.append("text-decoration:underline line-through;");
        else if (cs.underline) st.append("text-decoration:underline;");
        else if (cs.strike) st.append("text-decoration:line-through;");
        if (st.length() == 0) sb.append(html);
        else sb.append("<span style=\"").append(st).append("\">").append(html).append("</span>");
    }

    private static String buildParaStyleAttr(ParaStyle ps) {
        if (ps == null) return "";
        StringBuilder st = new StringBuilder();
        if (ps.align != null && !"left".equals(ps.align)) st.append("text-align:").append(ps.align).append(";");
        if (ps.marginTopPt > 0.5f && ps.marginTopPt < 80f) st.append("margin-top:").append(String.format(Locale.US, "%.1fpt;", ps.marginTopPt));
        if (ps.marginBottomPt > 0.5f && ps.marginBottomPt < 80f) st.append("margin-bottom:").append(String.format(Locale.US, "%.1fpt;", ps.marginBottomPt));
        if (ps.marginLeftPt > 1f && ps.marginLeftPt < 200f) st.append("margin-left:").append(String.format(Locale.US, "%.1fpt;", ps.marginLeftPt));
        if (ps.lineHeight >= 1.0f && ps.lineHeight <= 3.0f) st.append("line-height:").append(String.format(Locale.US, "%.2f;", ps.lineHeight));
        return st.length() == 0 ? "" : " style=\"" + st + "\"";
    }

    private static byte[] getEntry(HwpxStyles st, String name) {
        byte[] b = st.entries.get(name);
        if (b != null) return b;
        String real = st.entriesLower.get(name.toLowerCase(Locale.ROOT));
        return real != null ? st.entries.get(real) : null;
    }

    private static Document parseXml(byte[] b) {
        if (b == null || b.length == 0) return null;
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(true);
            f.setExpandEntityReferences(false);
            try { f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true); } catch (Throwable ignored) {}
            try { f.setFeature("http://xml.org/sax/features/external-general-entities", false); } catch (Throwable ignored) {}
            try { f.setFeature("http://xml.org/sax/features/external-parameter-entities", false); } catch (Throwable ignored) {}
            try { f.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false); } catch (Throwable ignored) {}
            try { f.setXIncludeAware(false); } catch (Throwable ignored) {}
            DocumentBuilder db = f.newDocumentBuilder();
            return db.parse(new ByteArrayInputStream(b));
        } catch (Throwable t) {
            return null;
        }
    }

    private static String ln(Node e) {
        if (e == null) return "";
        String l = e.getLocalName();
        if (l != null) return l;
        String n = e.getNodeName();
        int c = n.indexOf(':');
        return c >= 0 ? n.substring(c + 1) : n;
    }

    private static String attr(Element e, String local) {
        if (e == null) return null;
        NamedNodeMap attrs = e.getAttributes();
        for (int i = 0; i < attrs.getLength(); i++) {
            Node a = attrs.item(i);
            if (local.equals(ln(a))) return a.getNodeValue();
        }
        return null;
    }

    private static List<Element> kids(Node e) {
        List<Element> out = new ArrayList<>();
        if (e == null) return out;
        NodeList nl = e.getChildNodes();
        for (int i = 0; i < nl.getLength(); i++) if (nl.item(i).getNodeType() == Node.ELEMENT_NODE) out.add((Element) nl.item(i));
        return out;
    }

    private static List<Element> kids(Node e, String local) {
        List<Element> out = new ArrayList<>();
        if (e == null) return out;
        NodeList nl = e.getChildNodes();
        for (int i = 0; i < nl.getLength(); i++) {
            Node n = nl.item(i);
            if (n.getNodeType() == Node.ELEMENT_NODE && local.equals(ln(n))) out.add((Element) n);
        }
        return out;
    }

    private static Element kid(Node e, String local) {
        List<Element> l = kids(e, local);
        return l.isEmpty() ? null : l.get(0);
    }

    private static List<Element> descendants(Node e, String local) {
        List<Element> out = new ArrayList<>();
        walkDesc(e, local, out);
        return out;
    }

    private static void walkDesc(Node e, String local, List<Element> out) {
        if (e == null) return;
        NodeList nl = e.getChildNodes();
        for (int i = 0; i < nl.getLength(); i++) {
            Node n = nl.item(i);
            if (n.getNodeType() == Node.ELEMENT_NODE) {
                Element el = (Element) n;
                if (local.equals(ln(el))) out.add(el);
                walkDesc(el, local, out);
            }
        }
    }

    private static Element find(Node e, String local) {
        List<Element> l = descendants(e, local);
        return l.isEmpty() ? null : l.get(0);
    }

    private static List<Element> directSubListParagraphs(Element container) {
        List<Element> out = new ArrayList<>();
        Element sl = kid(container, "subList");
        for (Element ch : kids(sl != null ? sl : container)) if ("p".equals(ln(ch))) out.add(ch);
        return out;
    }

    private static byte[] decryptHwpViewText(byte[] raw, boolean compressed) {
        if (raw == null || raw.length < 260) return null;
        try {
            long h = le32(raw, 0) & 0xFFFFFFFFL;
            int tag = (int) (h & 0x3FF);
            int size = (int) (h >>> 20);
            int off = 4;
            if (size == 0xFFF) { size = le32(raw, 4); off = 8; }
            if (tag != 0x1C || size < 256 || off + size > raw.length) return null;
            byte[] block = Arrays.copyOfRange(raw, off, off + 256);
            int[] seed = new int[]{le32(block, 0)};
            int idx = (seed[0] & 0x0F) + 4;
            int c = 0, val = 0;
            for (int i = 0; i < 256; i++) {
                if (c == 0) {
                    val = hwpRand(seed) & 0xFF;
                    c = (hwpRand(seed) & 0x0F) + 1;
                }
                if (i >= 4) block[i] = (byte) ((block[i] & 0xFF) ^ val);
                c--;
            }
            byte[] key = Arrays.copyOfRange(block, idx, idx + 16);
            int encStart = off + size;
            int encLen = ((raw.length - encStart) / 16) * 16;
            if (encLen <= 0) return null;
            javax.crypto.Cipher cipher = javax.crypto.Cipher.getInstance("AES/ECB/NoPadding");
            cipher.init(javax.crypto.Cipher.DECRYPT_MODE, new javax.crypto.spec.SecretKeySpec(key, "AES"));
            byte[] dec = cipher.doFinal(raw, encStart, encLen);
            return compressed ? inflateSafe(dec) : dec;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static int hwpRand(int[] seed) {
        seed[0] = (seed[0] * 214013 + 2531011);
        return (seed[0] >>> 16) & 0x7FFF;
    }

    private static String normHexColor(String s, boolean skipWhite) {
        return normHexColor(s, skipWhite, false);
    }

    private static String normHexColor(String s, boolean skipWhite, boolean skipDefaultBlack) {
        if (s == null) return null;
        s = s.trim();
        if (s.isEmpty() || "none".equalsIgnoreCase(s)) return null;
        if (s.startsWith("#")) s = s.substring(1);
        if (s.length() == 8) {
            if (s.startsWith("00")) return null;
            s = s.substring(2);
        }
        if (s.length() != 6) return null;
        if (skipWhite && "FFFFFF".equalsIgnoreCase(s)) return null;
        if (skipDefaultBlack && "000000".equalsIgnoreCase(s)) return null;
        for (int i = 0; i < 6; i++) {
            char c = s.charAt(i);
            if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F'))) return null;
        }
        return "#" + s.toUpperCase(Locale.ROOT);
    }

    private static String colorRefToHex(int c, boolean skipWhite) {
        return colorRefToHex(c, skipWhite, false);
    }

    private static String colorRefToHex(int c, boolean skipWhite, boolean skipDefaultBlack) {
        if (c == -1) return null;
        if (skipDefaultBlack && (c == 0 || (c & 0xFFFFFF) == 0)) return null;
        int r = c & 0xFF, g = (c >>> 8) & 0xFF, b = (c >>> 16) & 0xFF;
        if (skipWhite && r >= 252 && g >= 252 && b >= 252) return null;
        if (skipDefaultBlack && r <= 8 && g <= 8 && b <= 8) return null;
        return String.format(Locale.ROOT, "#%02X%02X%02X", r, g, b);
    }

    private static String detectImageMime(byte[] b, String name) {
        if (b != null && b.length >= 12) {
            if ((b[0] & 0xFF) == 0x89 && b[1] == 0x50 && b[2] == 0x4E && b[3] == 0x47) return "image/png";
            if ((b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8) return "image/jpeg";
            if (b[0] == 'G' && b[1] == 'I' && b[2] == 'F') return "image/gif";
            if (b[0] == 'B' && b[1] == 'M') return "image/bmp";
            if (b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F' && b[8] == 'W' && b[9] == 'E') return "image/webp";
        }
        String n = name == null ? "" : name.toLowerCase(Locale.ROOT);
        if (n.endsWith(".png")) return "image/png";
        if (n.endsWith(".jpg") || n.endsWith(".jpeg")) return "image/jpeg";
        if (n.endsWith(".gif")) return "image/gif";
        if (n.endsWith(".bmp")) return "image/bmp";
        if (n.endsWith(".webp")) return "image/webp";
        return null;
    }

    private static int parseBinHexId(String name) {
        String base = baseName(name).toUpperCase(Locale.ROOT);
        if (base.startsWith("BIN")) {
            int dot = base.indexOf('.');
            try { return Integer.parseInt(dot > 3 ? base.substring(3, dot) : base.substring(3), 16); } catch (Exception ignored) {}
        }
        return -1;
    }

    private static String baseName(String p) {
        int i = Math.max(p.lastIndexOf('/'), p.lastIndexOf('\\'));
        return i >= 0 ? p.substring(i + 1) : p;
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private static int parseInt(String s, int def) {
        try { return s != null ? Integer.parseInt(s.trim()) : def; } catch (Exception e) { return def; }
    }

    private static long parseLong(String s, long def) {
        try { return s != null ? Long.parseLong(s.trim()) : def; } catch (Exception e) { return def; }
    }

    private static int le16(byte[] b, int o) { return (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8); }
    private static int le32(byte[] b, int o) { return ByteBuffer.wrap(b, o, 4).order(ByteOrder.LITTLE_ENDIAN).getInt(); }

    private static String readUtf16(byte[] b, int o, int chars) {
        StringBuilder sb = new StringBuilder(chars);
        for (int i = 0; i < chars && o + i * 2 + 1 < b.length; i++) sb.append((char) le16(b, o + i * 2));
        return sb.toString();
    }

    private static String ctrlIdStr(byte[] d) {
        return new String(new char[]{(char) (d[3] & 0xFF), (char) (d[2] & 0xFF), (char) (d[1] & 0xFF), (char) (d[0] & 0xFF)});
    }

    private static byte[] inflateSafe(byte[] data) {
        Inflater inf = new Inflater(true);
        try {
            inf.setInput(data);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[65536];
            while (!inf.finished() && bos.size() < 60 * 1024 * 1024) {
                int n = inf.inflate(buf);
                if (n <= 0) break;
                bos.write(buf, 0, n);
            }
            return bos.toByteArray();
        } catch (Throwable t) {
            return data;
        } finally {
            inf.end();
        }
    }
}
