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

        // 4. HWP + local save/read + AI assist
        String hwpHtml = OfficeCodec.importHwpBinary("한글 문서 본문 테스트입니다.".getBytes(StandardCharsets.UTF_16LE));
        assertTrue(hwpHtml.contains("한글 문서 본문"));
        String saved = OfficeCodec.saveLocal(ctx, "", "테스트보고서", "typing", typingDoc);
        JSONObject reloaded = OfficeCodec.readLocal(saved);
        assertTrue(reloaded.getString("html").contains("업무 보고서"));
        assertTrue(OfficeCodec.localAiAssist("bullets", "첫째 줄\n둘째 줄").contains("• 첫째 줄"));
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
        assertEquals("9.5.1", MainActivity.VERSION);
        assertNotNull(act.web);
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
