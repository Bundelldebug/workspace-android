package com.oneuibook.workspace;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/** GitHub Releases in-app updater for Workspace Android. */
final class Updater {
    private Updater() {}

    static final String REPO = "Bundelldebug/workspace-android";
    private static final long DAY = 24L * 60 * 60 * 1000;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static long pending = -1;

    static final class Release { String version, notes, apkUrl, pageUrl; }
    interface Cb { void done(Release newer, String error); }

    static void autoCheck(Activity a) {
        if ("robolectric".equals(Build.FINGERPRINT)) return;
        SharedPreferences p = OfficeCodec.prefs(a);
        long now = System.currentTimeMillis();
        if (now - p.getLong("update_last", 0) < DAY) return;
        p.edit().putLong("update_last", now).apply();
        check((r, err) -> { if (r != null && !a.isFinishing() && !a.isDestroyed()) offer(a, r); });
    }

    static void checkInteractive(Activity a) {
        Toast.makeText(a, "업데이트 확인 중...", Toast.LENGTH_SHORT).show();
        check((r, err) -> {
            if (a.isFinishing() || a.isDestroyed()) return;
            if (r != null) offer(a, r);
            else if (err != null) Toast.makeText(a, "업데이트 확인 실패: " + err, Toast.LENGTH_SHORT).show();
            else Toast.makeText(a, "최신 버전입니다 (" + MainActivity.VERSION + ")", Toast.LENGTH_SHORT).show();
        });
    }

    static void check(Cb cb) {
        new Thread(() -> {
            Release r = null;
            String err = null;
            try {
                HttpURLConnection c = (HttpURLConnection) new URL("https://api.github.com/repos/" + REPO + "/releases/latest").openConnection();
                c.setConnectTimeout(8000); c.setReadTimeout(8000);
                c.setRequestProperty("Accept", "application/vnd.github+json");
                c.setRequestProperty("User-Agent", "Workspace-Android-Updater");
                int code = c.getResponseCode();
                if (code == 404) { MAIN.post(() -> cb.done(null, null)); return; }
                if (code != 200) throw new Exception("HTTP " + code);
                JSONObject o = new JSONObject(read(c.getInputStream()));
                String tag = o.optString("tag_name").replaceFirst("^[vV]", "");
                if (newer(tag, MainActivity.VERSION)) {
                    r = new Release();
                    r.version = tag; r.notes = o.optString("body"); r.pageUrl = o.optString("html_url");
                    JSONArray as = o.optJSONArray("assets");
                    if (as != null) for (int i = 0; i < as.length(); i++) {
                        JSONObject x = as.optJSONObject(i);
                        if (x != null && x.optString("name").toLowerCase().endsWith(".apk")) {
                            r.apkUrl = x.optString("browser_download_url"); break;
                        }
                    }
                }
            } catch (Exception e) { err = String.valueOf(e.getMessage()); }
            final Release fr = r; final String fe = err;
            MAIN.post(() -> cb.done(fr, fe));
        }, "workspace-update").start();
    }

    static boolean newer(String a, String b) {
        String[] x = a.split("[.\\-+]"), y = b.split("[.\\-+]");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int p = i < x.length ? num(x[i]) : 0, q = i < y.length ? num(y[i]) : 0;
            if (p != q) return p > q;
        }
        return false;
    }

    private static int num(String s) { try { return Integer.parseInt(s.replaceAll("\\D", "")); } catch (Exception e) { return 0; } }

    private static String read(InputStream in) throws Exception {
        try (InputStream s = in) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[8192]; int n, total = 0;
            while ((n = s.read(buf)) > 0) {
                total += n;
                if (total > 1024 * 1024) throw new IllegalStateException("Response too large");
                b.write(buf, 0, n);
            }
            return b.toString("UTF-8");
        }
    }

    /** Validates that release download URLs strictly belong to this project's GitHub Releases over HTTPS. */
    static boolean isTrustedReleaseUrl(String url) {
        if (url == null || !url.startsWith("https://")) return false;
        try {
            Uri u = Uri.parse(url);
            String host = u.getHost();
            if (host == null) return false;
            String h = host.toLowerCase(java.util.Locale.ROOT);
            if ("github.com".equals(h)) {
                String p = u.getPath();
                return p != null && p.toLowerCase(java.util.Locale.ROOT).startsWith("/" + REPO.toLowerCase(java.util.Locale.ROOT) + "/");
            }
            return "objects.githubusercontent.com".equals(h) || "release-assets.githubusercontent.com".equals(h);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** Verifies downloaded APK package name and signing certificate SHA-256 against the currently installed app. */
    static boolean verifyDownloadedApk(Context app, Uri apkUri, File fallbackFile) {
        try {
            android.content.pm.PackageManager pm = app.getPackageManager();
            String path = null;
            if (fallbackFile != null && fallbackFile.isFile()) {
                path = fallbackFile.getAbsolutePath();
            } else if (apkUri != null && "file".equals(apkUri.getScheme())) {
                path = apkUri.getPath();
            }
            if (path == null) return false;
            int flags = android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES;
            android.content.pm.PackageInfo arch = pm.getPackageArchiveInfo(path, flags);
            if (arch == null || !app.getPackageName().equals(arch.packageName)) return false;
            android.content.pm.PackageInfo cur = pm.getPackageInfo(app.getPackageName(), flags);
            java.util.Set<String> expected = certDigests(cur);
            java.util.Set<String> actual = certDigests(arch);
            if (expected.isEmpty() || actual.isEmpty()) return false;
            for (String d : actual) {
                if (expected.contains(d)) return true;
            }
            return false;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static java.util.Set<String> certDigests(android.content.pm.PackageInfo pi) {
        java.util.Set<String> out = new java.util.HashSet<>();
        if (pi == null || pi.signingInfo == null) return out;
        android.content.pm.Signature[] sigs = pi.signingInfo.hasMultipleSigners()
                ? pi.signingInfo.getApkContentsSigners()
                : pi.signingInfo.getSigningCertificateHistory();
        if (sigs == null) return out;
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            for (android.content.pm.Signature s : sigs) {
                byte[] d = md.digest(s.toByteArray());
                StringBuilder sb = new StringBuilder();
                for (byte b : d) sb.append(String.format(java.util.Locale.US, "%02x", b & 0xff));
                out.add(sb.toString());
            }
        } catch (Throwable ignored) {}
        return out;
    }

    static void offer(Activity a, Release r) {
        new AlertDialog.Builder(a)
                .setTitle("새 버전 " + r.version)
                .setMessage(r.notes == null || r.notes.trim().isEmpty() ? "새 업데이트가 있습니다." : r.notes.trim())
                .setPositiveButton("업데이트", (d, w) -> install(a, r))
                .setNegativeButton("나중에", null)
                .show();
    }

    static void install(Activity a, Release r) {
        if (!isTrustedReleaseUrl(r.apkUrl)) {
            String safePage = isTrustedReleaseUrl(r.pageUrl) ? r.pageUrl : ("https://github.com/" + REPO + "/releases");
            try { a.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(safePage))); } catch (Exception ignored) {}
            return;
        }
        if (!a.getPackageManager().canRequestPackageInstalls()) {
            Toast.makeText(a, "알 수 없는 앱 설치 권한을 허용해 주세요", Toast.LENGTH_LONG).show();
            try { a.startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + a.getPackageName()))); } catch (Exception ignored) {}
            return;
        }
        DownloadManager dm = a.getSystemService(DownloadManager.class);
        if (dm == null) return;
        String safeVer = r.version == null ? "latest" : r.version.replaceAll("[^A-Za-z0-9._-]", "");
        String name = "Workspace-" + safeVer + ".apk";
        final File destFile = new File(a.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), name);
        if (destFile.exists()) destFile.delete();
        DownloadManager.Request q = new DownloadManager.Request(Uri.parse(r.apkUrl))
                .setTitle("워크스페이스 " + safeVer)
                .setMimeType("application/vnd.android.package-archive")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
                .setDestinationInExternalFilesDir(a, Environment.DIRECTORY_DOWNLOADS, name);
        pending = dm.enqueue(q);
        Toast.makeText(a, "업데이트 다운로드 중...", Toast.LENGTH_SHORT).show();
        Context app = a.getApplicationContext();
        BroadcastReceiver done = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent i) {
                if (i.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1) != pending) return;
                try { app.unregisterReceiver(this); } catch (Exception ignored) {}
                Uri apk = dm.getUriForDownloadedFile(pending);
                if (apk == null) return;
                if (!verifyDownloadedApk(app, apk, destFile)) {
                    try { destFile.delete(); } catch (Exception ignored) {}
                    Toast.makeText(app, "업데이트 APK 서명 검증에 실패해 설치를 중단했습니다.", Toast.LENGTH_LONG).show();
                    return;
                }
                Intent in = new Intent(Intent.ACTION_VIEW).setDataAndType(apk, "application/vnd.android.package-archive")
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
                try { app.startActivity(in); } catch (Exception ignored) {}
            }
        };
        if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(done, new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE), Context.RECEIVER_EXPORTED);
        else app.registerReceiver(done, new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE));
    }
}
