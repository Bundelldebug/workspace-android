package com.oneuibook.workspace;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.print.PrintAttributes;
import android.print.PrintManager;
import android.provider.OpenableColumns;
import android.util.Base64;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import org.json.JSONObject;

import java.io.InputStream;
import java.io.OutputStream;

/**
 * One UI Book 워크스페이스 (안드로이드) — PC판 워크스페이스(OneUI_Workspace_Native/app)의
 * 아이콘·UI·리본·서식 패널·편집기(타이핑/그래프즈/프레젠트)를 WebView + AndroidHost 브리지로 그대로 이식.
 */
public final class MainActivity extends Activity {
    static final String VERSION = "9.5.7";
    private static final int REQ_OPEN = 5001, REQ_SAVE_AS = 5002, REQ_IMAGE = 5003;

    WebView web;
    String currentKind = "workspace";
    private String pendingOpenPath = null;
    private int pendingRpcId = -1;
    private JSONObject pendingSaveDoc;
    private String pendingSaveKind = "typing";

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (savedInstanceState != null) currentKind = savedInstanceState.getString("kind", "workspace");

        Window w = getWindow();
        w.setDecorFitsSystemWindows(false);
        w.setStatusBarColor(Color.TRANSPARENT);
        w.setNavigationBarColor(Color.TRANSPARENT);
        w.setNavigationBarContrastEnforced(false);

        web = new WebView(this);
        WebSettings ws = web.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setAllowFileAccess(true);
        ws.setAllowFileAccessFromFileURLs(true);
        ws.setAllowUniversalAccessFromFileURLs(true);
        ws.setBuiltInZoomControls(false);
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            ws.setAlgorithmicDarkeningAllowed(false);
        }
        web.setBackgroundColor(0xFFF4F6FA);
        web.addJavascriptInterface(new HostBridge(), "AndroidHost");
        web.setWebViewClient(new WebViewClient() {
            @Override public void onPageFinished(WebView view, String url) {
                if (url != null) {
                    Uri u = Uri.parse(url);
                    String k = u.getQueryParameter("kind");
                    if (k != null && !k.isEmpty()) currentKind = k;
                }
                pushInsets();
                if (pendingOpenPath != null) {
                    String p = pendingOpenPath;
                    pendingOpenPath = null;
                    try { emitEvent("openArg", new JSONObject().put("path", p)); } catch (Exception ignored) {}
                }
            }
        });

        FrameLayout root = new FrameLayout(this);
        root.addView(web, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            android.graphics.Insets sys = insets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            topInsetPx = sys.top;
            botInsetPx = Math.max(sys.bottom, insets.getInsets(WindowInsets.Type.ime()).bottom);
            pushInsets();
            return WindowInsets.CONSUMED;
        });
        setContentView(root);
        applySystemBarAppearance(OfficeCodec.prefs(this).getString("ui_theme", "system"));
        handleIncomingIntent(getIntent(), false);
        web.loadUrl("file:///android_asset/app/index.html?kind=" + currentKind);
        Updater.autoCheck(this);
    }

    private void applySystemBarAppearance(String theme) {
        boolean isSysDark = (getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        boolean dark = "dark".equals(theme) || ("system".equals(theme) && isSysDark);
        if (web != null) web.setBackgroundColor(dark ? 0xFF000000 : 0xFFF2F2F7);
        WindowInsetsController ctrl = getWindow().getInsetsController();
        if (ctrl != null) {
            int mask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
            ctrl.setSystemBarsAppearance(dark ? 0 : mask, mask);
        }
    }

    @Override public void onConfigurationChanged(android.content.res.Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        applySystemBarAppearance(OfficeCodec.prefs(this).getString("ui_theme", "system"));
        pushInsets();
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (handleIncomingIntent(intent, true)) {
            web.loadUrl("file:///android_asset/app/index.html?kind=" + currentKind);
        }
    }

    private boolean handleIncomingIntent(Intent intent, boolean reload) {
        if (intent == null || !Intent.ACTION_VIEW.equals(intent.getAction()) || intent.getData() == null) return false;
        try {
            Uri uri = intent.getData();
            String name = queryDisplayName(uri);
            try (InputStream is = getContentResolver().openInputStream(uri)) {
                JSONObject doc = OfficeCodec.importStream(name, is);
                String kind = doc.optString("kind", "typing");
                String savedPath = OfficeCodec.saveLocal(this, "", name.replaceAll("\\.[^.]+$", ""), kind, doc);
                currentKind = kind;
                pendingOpenPath = savedPath;
                return true;
            }
        } catch (Exception ignored) { return false; }
    }

    private int topInsetPx, botInsetPx;

    private void pushInsets() {
        float d = Math.max(1f, getResources().getDisplayMetrics().density);
        int topDp = Math.round(topInsetPx / d);
        int botDp = Math.round(botInsetPx / d);
        if (web != null) web.evaluateJavascript("window.__setAndroidInsets&&window.__setAndroidInsets(" + topDp + "," + botDp + ")", null);
    }

    private void reply(int id, boolean ok, Object data, String err) {
        try {
            JSONObject msg = new JSONObject().put("id", id).put("ok", ok);
            if (ok) msg.put("data", data != null ? data : JSONObject.NULL);
            else msg.put("error", err != null ? err : "오류가 발생했습니다.");
            String js = "window.__onAndroidMessage(" + JSONObject.quote(msg.toString()) + ")";
            runOnUiThread(() -> web.evaluateJavascript(js, null));
        } catch (Exception ignored) {}
    }

    private void emitEvent(String event, JSONObject data) {
        try {
            JSONObject msg = new JSONObject().put("event", event).put("data", data);
            String js = "window.__onAndroidMessage(" + JSONObject.quote(msg.toString()) + ")";
            runOnUiThread(() -> web.evaluateJavascript(js, null));
        } catch (Exception ignored) {}
    }

    private String nameForKind(String kind) {
        if ("typing".equals(kind)) return "타이핑";
        if ("graphs".equals(kind)) return "그래프즈";
        if ("present".equals(kind)) return "프레젠트";
        return "워크스페이스";
    }

    private String queryDisplayName(Uri uri) {
        try (Cursor c = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) return c.getString(0);
        } catch (Exception ignored) {}
        String p = uri.getLastPathSegment();
        return p != null ? p : "문서.docx";
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        int id = pendingRpcId;
        pendingRpcId = -1;
        if (res != RESULT_OK || data == null || data.getData() == null) {
            if (id >= 0) reply(id, true, null, null);
            return;
        }
        Uri uri = data.getData();
        try {
            if (req == REQ_OPEN) {
                String name = queryDisplayName(uri);
                try (InputStream is = getContentResolver().openInputStream(uri)) {
                    JSONObject doc = OfficeCodec.importStream(name, is);
                    String kind = doc.optString("kind", "typing");
                    if ("workspace".equals(currentKind)) currentKind = kind;
                    reply(id, true, doc, null);
                }
            } else if (req == REQ_SAVE_AS) {
                String name = queryDisplayName(uri);
                try (OutputStream os = getContentResolver().openOutputStream(uri, "wt")) {
                    if ("graphs".equals(pendingSaveKind)) OfficeCodec.exportXlsx(pendingSaveDoc, os);
                    else if ("present".equals(pendingSaveKind)) OfficeCodec.exportPptx(pendingSaveDoc, os);
                    else OfficeCodec.exportDocx(pendingSaveDoc, os);
                }
                reply(id, true, new JSONObject().put("path", uri.toString()).put("title", name), null);
            } else if (req == REQ_IMAGE) {
                try (InputStream is = getContentResolver().openInputStream(uri)) {
                    byte[] raw = OfficeCodec.readAll(is);
                    String mime = getContentResolver().getType(uri);
                    if (mime == null) mime = "image/png";
                    String b64 = Base64.encodeToString(raw, Base64.NO_WRAP);
                    reply(id, true, new JSONObject().put("dataUrl", "data:" + mime + ";base64," + b64), null);
                }
            }
        } catch (Exception e) {
            if (id >= 0) reply(id, false, null, e.getMessage());
        }
    }

    @Override public void onBackPressed() {
        if ("robolectric".equalsIgnoreCase(android.os.Build.FINGERPRINT) || web == null) {
            if (!"workspace".equals(currentKind)) {
                currentKind = "workspace";
                if (web != null) web.loadUrl("file:///android_asset/app/index.html?kind=workspace");
                return;
            }
            super.onBackPressed();
            return;
        }
        web.evaluateJavascript("window.__handleAndroidBack ? window.__handleAndroidBack() : 'exit'", value -> {
            String res = value == null ? "" : value.replace("\"", "").trim();
            if ("handled".equals(res)) return;
            if (!"workspace".equals(currentKind)) {
                currentKind = "workspace";
                web.loadUrl("file:///android_asset/app/index.html?kind=workspace");
                return;
            }
            finish();
        });
    }

    @Override protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putString("kind", currentKind);
    }

    final class HostBridge {
        @JavascriptInterface public void postMessage(String raw) {
            try {
                JSONObject req = new JSONObject(raw);
                int id = req.optInt("id", -1);
                String method = req.optString("method", "");
                JSONObject args = req.optJSONObject("args");
                if (args == null) args = new JSONObject();

                switch (method) {
                    case "bootstrap": {
                        boolean hasRec = OfficeCodec.prefs(MainActivity.this).contains("recovery_" + currentKind);
                        JSONObject res = new JSONObject()
                                .put("kind", currentKind)
                                .put("name", nameForKind(currentKind))
                                .put("recent", OfficeCodec.getRecent(MainActivity.this, currentKind))
                                .put("recovery", hasRec)
                                .put("npu", "온디바이스");
                        reply(id, true, res, null);
                        break;
                    }
                    case "launch": {
                        String nextKind = args.optString("kind", "typing");
                        currentKind = nextKind;
                        reply(id, true, new JSONObject().put("launched", true), null);
                        emitEvent("switchKind", new JSONObject().put("kind", nextKind));
                        break;
                    }
                    case "readFile": {
                        String path = args.optString("path", "");
                        JSONObject doc = OfficeCodec.readLocal(path);
                        reply(id, true, doc, null);
                        break;
                    }
                    case "openDialog": {
                        pendingRpcId = id;
                        runOnUiThread(() -> {
                            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                            i.addCategory(Intent.CATEGORY_OPENABLE);
                            i.setType("*/*");
                            startActivityForResult(i, REQ_OPEN);
                        });
                        break;
                    }
                    case "saveFile": {
                        JSONObject doc = args.optJSONObject("doc");
                        if (doc == null) doc = new JSONObject();
                        String kind = doc.optString("kind", "workspace".equals(currentKind) ? "typing" : currentKind);
                        boolean asNew = args.optBoolean("as", false);
                        String suggested = args.optString("suggested", "제목 없음");
                        if (asNew) {
                            pendingRpcId = id;
                            pendingSaveDoc = doc;
                            pendingSaveKind = kind;
                            String ext = "graphs".equals(kind) ? ".xlsx" : ("present".equals(kind) ? ".pptx" : ".docx");
                            String mime = "graphs".equals(kind)
                                    ? "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                                    : ("present".equals(kind)
                                        ? "application/vnd.openxmlformats-officedocument.presentationml.presentation"
                                        : "application/vnd.openxmlformats-officedocument.wordprocessingml.document");
                            runOnUiThread(() -> {
                                Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                                i.addCategory(Intent.CATEGORY_OPENABLE);
                                i.setType(mime);
                                i.putExtra(Intent.EXTRA_TITLE, suggested + ext);
                                startActivityForResult(i, REQ_SAVE_AS);
                            });
                        } else {
                            String savedPath = OfficeCodec.saveLocal(MainActivity.this, args.optString("path", ""), suggested, kind, doc);
                            String title = new java.io.File(savedPath).getName();
                            reply(id, true, new JSONObject().put("path", savedPath).put("title", title), null);
                        }
                        break;
                    }
                    case "pdf": {
                        String title = args.optString("title", "문서");
                        runOnUiThread(() -> {
                            PrintManager pm = (PrintManager) getSystemService(Context.PRINT_SERVICE);
                            if (pm != null) pm.print(title, web.createPrintDocumentAdapter(title), new PrintAttributes.Builder().build());
                        });
                        reply(id, true, true, null);
                        break;
                    }
                    case "pickImage": {
                        pendingRpcId = id;
                        runOnUiThread(() -> {
                            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                            i.addCategory(Intent.CATEGORY_OPENABLE);
                            i.setType("image/*");
                            startActivityForResult(i, REQ_IMAGE);
                        });
                        break;
                    }
                    case "confirmDiscard": {
                        reply(id, true, true, null);
                        break;
                    }
                    case "saveRecovery": {
                        OfficeCodec.prefs(MainActivity.this).edit()
                                .putString("recovery_" + currentKind, args.toString()).apply();
                        reply(id, true, true, null);
                        break;
                    }
                    case "loadRecovery": {
                        String rawRec = OfficeCodec.prefs(MainActivity.this).getString("recovery_" + currentKind, null);
                        reply(id, true, rawRec != null ? new JSONObject(rawRec) : null, null);
                        break;
                    }
                    case "clearRecovery": {
                        OfficeCodec.prefs(MainActivity.this).edit().remove("recovery_" + currentKind).apply();
                        reply(id, true, true, null);
                        break;
                    }
                    case "aiAssist": {
                        String out = OfficeCodec.localAiAssist(args.optString("task", ""), args.optString("text", ""));
                        reply(id, true, new JSONObject().put("engine", "온디바이스").put("result", out), null);
                        break;
                    }
                    case "checkUpdate": {
                        runOnUiThread(() -> Updater.checkInteractive(MainActivity.this));
                        reply(id, true, true, null);
                        break;
                    }
                    case "pinRecent": {
                        String path = args.optString("path", "");
                        boolean pinned = OfficeCodec.togglePinRecent(MainActivity.this, path);
                        reply(id, true, new JSONObject().put("pinned", pinned).put("recent", OfficeCodec.getRecent(MainActivity.this, currentKind)), null);
                        break;
                    }
                    case "deleteRecent": {
                        String path = args.optString("path", "");
                        OfficeCodec.removeRecent(MainActivity.this, path);
                        reply(id, true, new JSONObject().put("recent", OfficeCodec.getRecent(MainActivity.this, currentKind)), null);
                        break;
                    }
                    case "shareText": {
                        String title = args.optString("title", "문서");
                        String text = args.optString("text", "");
                        runOnUiThread(() -> {
                            Intent s = new Intent(Intent.ACTION_SEND);
                            s.setType("text/plain");
                            s.putExtra(Intent.EXTRA_SUBJECT, title);
                            s.putExtra(Intent.EXTRA_TEXT, text);
                            startActivity(Intent.createChooser(s, "문서 공유"));
                        });
                        reply(id, true, true, null);
                        break;
                    }
                    case "getPrefs": {
                        android.content.SharedPreferences sp = OfficeCodec.prefs(MainActivity.this);
                        JSONObject pref = new JSONObject()
                                .put("theme", sp.getString("ui_theme", "system"))
                                .put("glassT", sp.getFloat("ui_glass_t", 0.55f))
                                .put("glassStyle", sp.getString("ui_glass_style", "prism"))
                                .put("autoSave", sp.getBoolean("ui_auto_save", true));
                        reply(id, true, pref, null);
                        break;
                    }
                    case "setPref": {
                        android.content.SharedPreferences.Editor ed = OfficeCodec.prefs(MainActivity.this).edit();
                        String newTheme = null;
                        if (args.has("theme")) { newTheme = args.optString("theme", "system"); ed.putString("ui_theme", newTheme); }
                        if (args.has("glassT")) ed.putFloat("ui_glass_t", (float) args.optDouble("glassT", 0.55));
                        if (args.has("glassStyle")) ed.putString("ui_glass_style", args.optString("glassStyle", "prism"));
                        if (args.has("autoSave")) ed.putBoolean("ui_auto_save", args.optBoolean("autoSave", true));
                        ed.apply();
                        if (newTheme != null) {
                            final String ft = newTheme;
                            runOnUiThread(() -> applySystemBarAppearance(ft));
                        }
                        reply(id, true, true, null);
                        break;
                    }
                    default: {
                        // setTitle, drag, minimize, maximize, closeWindow
                        reply(id, true, true, null);
                        break;
                    }
                }
            } catch (Exception e) {
                // ignore malformed
            }
        }
    }
}
