package com.oneuibook.workspace;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = 34)
public class WorkspaceUiTest {
    private File outDir;

    @Before public void setup() {
        outDir = new File(System.getProperty("workspace.output", "."));
        outDir.mkdirs();
    }

    @Test public void pcWorkspaceCodecRoundTripsAndLocalSave() throws Exception {
        Context ctx = RuntimeEnvironment.getApplication();

        // 1. DOCX
        JSONObject typingDoc = new JSONObject().put("kind", "typing").put("html", "<h1>업무 보고서</h1><p>PC판 워크스페이스 이식</p>");
        ByteArrayOutputStream docxOut = new ByteArrayOutputStream();
        OfficeCodec.exportDocx(typingDoc, docxOut);
        String html = OfficeCodec.importDocx(docxOut.toByteArray());
        assertTrue(html.contains("업무 보고서"));
        assertTrue(html.contains("PC판 워크스페이스 이식"));

        // 2. XLSX (editors.js sheets schema)
        JSONObject cells = new JSONObject()
                .put("A1", new JSONObject().put("v", "예산"))
                .put("B1", new JSONObject().put("v", "100000"))
                .put("B2", new JSONObject().put("v", "=B1*2"));
        JSONObject graphsDoc = new JSONObject().put("kind", "graphs")
                .put("sheets", new JSONArray().put(new JSONObject().put("name", "월간 예산").put("cells", cells)))
                .put("active", 0);
        ByteArrayOutputStream xlsxOut = new ByteArrayOutputStream();
        OfficeCodec.exportXlsx(graphsDoc, xlsxOut);
        JSONObject parsedXlsx = OfficeCodec.importXlsx(xlsxOut.toByteArray());
        JSONObject pCells = parsedXlsx.getJSONArray("sheets").getJSONObject(0).getJSONObject("cells");
        assertEquals("100000", pCells.getJSONObject("B1").getString("v"));
        assertEquals("=B1*2", pCells.getJSONObject("B2").getString("v"));

        // 3. PPTX (editors.js slides schema)
        JSONArray items = new JSONArray()
                .put(new JSONObject().put("type", "text").put("text", "새로운 가능성"))
                .put(new JSONObject().put("type", "text").put("text", "One UI 워크스페이스"));
        JSONObject presDoc = new JSONObject().put("kind", "present")
                .put("slides", new JSONArray().put(new JSONObject().put("id", "s-1").put("items", items)))
                .put("active", 0);
        ByteArrayOutputStream pptxOut = new ByteArrayOutputStream();
        OfficeCodec.exportPptx(presDoc, pptxOut);
        JSONObject parsedPptx = OfficeCodec.importPptx(pptxOut.toByteArray());
        assertEquals("새로운 가능성", parsedPptx.getJSONArray("slides").getJSONObject(0).getJSONArray("items").getJSONObject(0).getString("text"));

        // 4. HWP + rich HWPX (image, colspan table, cell color, template box, styles) + local save/read + AI assist
        String hwpHtml = OfficeCodec.importHwpBinary("한글 문서 본문 테스트입니다.".getBytes(StandardCharsets.UTF_16LE));
        assertTrue(hwpHtml.contains("한글 문서 본문"));
        ByteArrayOutputStream hwpxBaos = new ByteArrayOutputStream();
        try (java.util.zip.ZipOutputStream zos = new java.util.zip.ZipOutputStream(hwpxBaos)) {
            byte[] png1x1 = new byte[] {
                (byte)0x89,0x50,0x4E,0x47,0x0D,0x0A,0x1A,0x0A,0,0,0,13,0x49,0x48,0x44,0x52,
                0,0,0,1,0,0,0,1,8,6,0,0,0,0x1F,0x15,(byte)0xC4,(byte)0x89,0,0,0,13,0x49,0x44,0x41,0x54,
                0x78,(byte)0x9C,0x63,0x60,0x60,0x60,(byte)0xF8,0x0F,0,1,4,1,0,(byte)0x85,(byte)0xE8,(byte)0xC2,(byte)0xA4,
                0,0,0,0,0x49,0x45,0x4E,0x44,(byte)0xAE,0x42,0x60,(byte)0x82
            };
            zos.putNextEntry(new java.util.zip.ZipEntry("Contents/content.hpf"));
            zos.write("<opf:package xmlns:opf=\"http://www.idpf.org/2007/opf\"><opf:manifest><opf:item id=\"img1\" href=\"BinData/photo.png\"/></opf:manifest></opf:package>".getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
            zos.putNextEntry(new java.util.zip.ZipEntry("Contents/header.xml"));
            zos.write(("<hh:head xmlns:hh=\"http://www.hancom.co.kr/hwpml/2011/head\" xmlns:hc=\"http://www.hancom.co.kr/hwpml/2011/core\">"
                + "<hh:refList><hh:borderFills><hh:borderFill id=\"3\"><hh:leftBorder type=\"SOLID\" color=\"#3366CC\"/><hh:rightBorder type=\"SOLID\" color=\"#3366CC\"/><hh:topBorder type=\"SOLID\" color=\"#3366CC\"/><hh:bottomBorder type=\"SOLID\" color=\"#3366CC\"/><hc:fillBrush><hc:winBrush faceColor=\"#E8F0FE\"/></hc:fillBrush></hh:borderFill></hh:borderFills>"
                + "<hh:charProperties><hh:charPr id=\"1\" height=\"1600\" textColor=\"#1A4DB3\"><hh:bold/></hh:charPr></hh:charProperties>"
                + "<hh:paraProperties><hh:paraPr id=\"1\"><hh:align horizontal=\"CENTER\"/></hh:paraPr></hh:paraProperties></hh:refList></hh:head>").getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
            zos.putNextEntry(new java.util.zip.ZipEntry("Contents/section0.xml"));
            zos.write(("<hs:sec xmlns:hs=\"http://www.hancom.co.kr/hwpml/2011/section\" xmlns:hp=\"http://www.hancom.co.kr/hwpml/2011/paragraph\" xmlns:hc=\"http://www.hancom.co.kr/hwpml/2011/core\">"
                + "<hp:p paraPrIDRef=\"1\"><hp:run charPrIDRef=\"1\"><hp:t>보고서 제목</hp:t></hp:run></hp:p>"
                + "<hp:p><hp:run><hp:pic><hc:img binaryItemIDRef=\"img1\"/><hp:curSz width=\"15000\" height=\"7500\"/></hp:pic></hp:run></hp:p>"
                + "<hp:p><hp:run><hp:tbl><hp:sz width=\"40000\" height=\"8000\"/><hp:tr><hp:tc borderFillIDRef=\"3\"><hp:cellSpan colSpan=\"2\" rowSpan=\"1\"/><hp:cellSz width=\"40000\" height=\"4000\"/><hp:subList><hp:p><hp:run charPrIDRef=\"1\"><hp:t>병합 헤더</hp:t></hp:run></hp:p></hp:subList></hp:tc></hp:tr></hp:tbl></hp:run></hp:p>"
                + "<hp:p><hp:run><hp:rect><hp:lineShape color=\"#2563EB\" style=\"SOLID\"/><hp:fillBrush><hc:winBrush faceColor=\"#F0F9FF\"/></hp:fillBrush><hp:drawText><hp:subList><hp:p><hp:run><hp:t>템플릿 박스</hp:t></hp:run></hp:p></hp:subList></hp:drawText></hp:rect></hp:run></hp:p>"
                + "</hs:sec>").getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
            zos.putNextEntry(new java.util.zip.ZipEntry("BinData/photo.png"));
            zos.write(png1x1);
            zos.closeEntry();
        }
        String hwpxHtml = OfficeCodec.importHwpx(hwpxBaos.toByteArray());
        assertTrue(hwpxHtml.contains("data:image/png;base64,"));
        assertTrue(hwpxHtml.contains("colspan=\"2\""));
        assertTrue(hwpxHtml.contains("#E8F0FE"));
        assertTrue(hwpxHtml.contains("hwp-box"));
        assertTrue(hwpxHtml.contains("#F0F9FF"));
        assertTrue(hwpxHtml.contains("text-align:center"));
        assertTrue(hwpxHtml.contains("font-weight:bold"));
        // Verify default/unset black HWP shade/brush colors never emit black backgrounds
        java.lang.reflect.Method c2h = HwpRich.class.getDeclaredMethod("colorRefToHex", int.class, boolean.class, boolean.class);
        c2h.setAccessible(true);
        assertNull(c2h.invoke(null, 0x00000000, true, true));
        assertNull(c2h.invoke(null, 0xFFFFFFFF, true, true));
        assertEquals("#E8F0FE", c2h.invoke(null, 0x00FEF0E8, true, true));
        String saved = OfficeCodec.saveLocal(ctx, "", "테스트보고서", "typing", typingDoc);
        JSONObject reloaded = OfficeCodec.readLocal(saved);
        assertTrue(reloaded.getString("html").contains("업무 보고서"));
        assertTrue(OfficeCodec.localAiAssist("bullets", "첫째 줄\n둘째 줄").contains("• 첫째 줄"));
        assertTrue(OfficeCodec.togglePinRecent(ctx, saved));
        assertTrue(OfficeCodec.getRecent(ctx, "workspace").getJSONObject(0).optBoolean("pinned", false));
        OfficeCodec.removeRecent(ctx, saved);
    }

    @Test public void pcWorkspaceLauncherIconRenders() throws Exception {
        Context ctx = RuntimeEnvironment.getApplication();
        Drawable icon = ctx.getDrawable(R.mipmap.ic_launcher);
        assertNotNull(icon);
        Bitmap bmp = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        icon.setBounds(0, 0, 256, 256);
        icon.draw(c);
        try (FileOutputStream fos = new FileOutputStream(new File(outDir, "icon.png"))) {
            bmp.compress(Bitmap.CompressFormat.PNG, 100, fos);
        }
        bmp.recycle();
    }

    @Test @Config(qualifiers = "ko-rKR-w1100dp-h720dp-xhdpi")
    public void activityBootstrapsPcWorkspaceAssets() throws Exception {
        ActivityController<MainActivity> ctrl = Robolectric.buildActivity(MainActivity.class).setup();
        MainActivity act = ctrl.get();
        assertEquals("워크스페이스", act.getString(R.string.app_name));
        assertEquals("9.5.6", MainActivity.VERSION);
        assertNotNull(act.web);
        // Verify mobile CSS strictly guards hidden elements so #editor/#inspector never leak on Start screen
        try (java.io.InputStream cssIn = act.getAssets().open("app/android-mobile.css")) {
            String css = new String(OfficeCodec.readAll(cssIn), StandardCharsets.UTF_8);
            assertTrue(css.contains("#editor:not([hidden])"));
            assertTrue(css.contains("#inspector:not([hidden])"));
            assertTrue(css.contains("#editor[hidden]"));
        }
        // Back button inside a sub-app returns cleanly to workspace hub instead of killing app
        act.currentKind = "typing";
        act.onBackPressed();
        assertEquals("workspace", act.currentKind);
        assertFalse(act.isFinishing());
        // Verify PC Workspace assets are packaged in APK
        assertNotNull(act.getAssets().open("app/index.html"));
        assertNotNull(act.getAssets().open("app/app.js"));
        assertNotNull(act.getAssets().open("app/editors.js"));
        assertNotNull(act.getAssets().open("app/assets/workspace.png"));
        assertNotNull(act.getAssets().open("app/assets/typing.png"));
        assertNotNull(act.getAssets().open("app/assets/graphs.png"));
        assertNotNull(act.getAssets().open("app/assets/present.png"));
    }
}
