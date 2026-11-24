package com.fntv.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.MimeTypeMap;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import com.fntv.app.api.FnApiManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLEncoder;
import java.util.Locale;

import okhttp3.Request;
import okhttp3.Response;

/**
 * 远程 NAS 全文件浏览与拉取下载器
 * 支持任意文件浏览（APK、文档、音视频、压缩包等），下载到本地后一键安装或打开
 */
public class RemoteFileBrowser {

    private final Activity activity;
    private final String baseUrl;
    private final LinearLayout container;
    private final TextView tvPath;
    private final Button btnUp;
    private final Button btnRefresh;

    private String currentPath = "";
    private String parentPath = "";
    private boolean loadedOnce = false;

    public RemoteFileBrowser(Activity activity, String baseUrl,
                             LinearLayout container, TextView tvPath,
                             Button btnUp, Button btnRefresh) {
        this.activity = activity;
        this.baseUrl = baseUrl != null ? baseUrl.replaceAll("/+$", "") : "";
        this.container = container;
        this.tvPath = tvPath;
        this.btnUp = btnUp;
        this.btnRefresh = btnRefresh;

        if (btnUp != null) {
            btnUp.setOnClickListener(v -> goUp());
        }
        if (btnRefresh != null) {
            btnRefresh.setOnClickListener(v -> loadDirectory(currentPath));
        }
    }

    public void ensureLoaded() {
        if (!loadedOnce) {
            loadedOnce = true;
            loadDirectory("");
        }
    }

    public boolean handleBack() {
        if (currentPath != null && !currentPath.isEmpty()) {
            goUp();
            return true;
        }
        return false;
    }

    public void goUp() {
        if (currentPath == null || currentPath.isEmpty()) return;
        loadDirectory(parentPath != null ? parentPath : "");
    }

    public void loadDirectory(final String path) {
        if (container == null) return;
        container.removeAllViews();
        addStatusText("正在加载远程目录...");

        new Thread(() -> {
            try {
                String encoded = URLEncoder.encode(path != null ? path : "", "UTF-8");
                String url = baseUrl + "/v/api/v1/files/list?path=" + encoded;
                Request req = new Request.Builder().url(url).get().build();
                try (Response resp = FnApiManager.getInstance().getClient().newCall(req).execute()) {
                    String body = resp.body() != null ? resp.body().string() : "";
                    JSONObject root = new JSONObject(body);
                    if (root.optInt("code", -1) != 0) {
                        String msg = root.optString("msg", "加载失败");
                        activity.runOnUiThread(() -> {
                            container.removeAllViews();
                            addStatusText("加载失败：" + msg);
                        });
                        return;
                    }
                    JSONObject data = root.optJSONObject("data");
                    final String cur = data != null ? data.optString("current", "") : "";
                    final String par = data != null ? data.optString("parent", "") : "";
                    final JSONArray entries = data != null ? data.optJSONArray("entries") : new JSONArray();

                    activity.runOnUiThread(() -> {
                        currentPath = cur;
                        parentPath = par;
                        if (tvPath != null) {
                            tvPath.setText(cur.isEmpty() ? "远程目录：授权根目录" : ("远程目录：" + cur));
                        }
                        if (btnUp != null) {
                            btnUp.setEnabled(!cur.isEmpty());
                            btnUp.setAlpha(cur.isEmpty() ? 0.5f : 1.0f);
                        }
                        renderEntries(entries);
                    });
                }
            } catch (Exception e) {
                activity.runOnUiThread(() -> {
                    container.removeAllViews();
                    addStatusText("网络或服务端不支持文件浏览接口：" + e.getMessage());
                });
            }
        }).start();
    }

    private void addStatusText(String txt) {
        TextView tv = new TextView(activity);
        tv.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(100)));
        tv.setGravity(Gravity.CENTER);
        tv.setText(txt);
        tv.setTextColor(0xFF94A3B8);
        tv.setTextSize(14);
        container.addView(tv);
    }

    private void renderEntries(JSONArray entries) {
        container.removeAllViews();
        if (entries == null || entries.length() == 0) {
            addStatusText("空目录");
            return;
        }
        for (int i = 0; i < entries.length(); i++) {
            JSONObject obj = entries.optJSONObject(i);
            if (obj == null) continue;
            final String name = obj.optString("name", "");
            final String path = obj.optString("path", "");
            final boolean isDir = obj.optBoolean("is_dir", false);
            final long size = obj.optLong("size", 0);
            final String ext = obj.optString("ext", "");

            LinearLayout row = new LinearLayout(activity);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = dp(6);
            row.setLayoutParams(lp);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(14), dp(12), dp(14), dp(12));
            row.setBackgroundResource(R.drawable.bg_input);
            row.setFocusable(true);
            row.setClickable(true);

            TextView tvIcon = new TextView(activity);
            tvIcon.setText(getIcon(isDir, ext));
            tvIcon.setTextSize(18);
            tvIcon.setPadding(0, 0, dp(12), 0);
            row.addView(tvIcon);

            TextView tvName = new TextView(activity);
            LinearLayout.LayoutParams nameLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            tvName.setLayoutParams(nameLp);
            tvName.setText(name);
            tvName.setTextColor(0xFFF8FAFC);
            tvName.setTextSize(14);
            tvName.setMaxLines(2);
            row.addView(tvName);

            TextView tvMeta = new TextView(activity);
            tvMeta.setText(isDir ? "文件夹 →" : formatSize(size));
            tvMeta.setTextColor(0xFF94A3B8);
            tvMeta.setTextSize(12);
            tvMeta.setPadding(dp(10), 0, 0, 0);
            row.addView(tvMeta);

            row.setOnClickListener(v -> {
                if (isDir) {
                    loadDirectory(path);
                } else {
                    showFileActionDialog(name, path, size, ext);
                }
            });

            container.addView(row);
        }
    }

    private void showFileActionDialog(String name, String path, long size, String ext) {
        String actionLabel = "apk".equals(ext) ? "⬇ 下载并安装 APK" : "⬇ 下载到本机";
        new AlertDialog.Builder(activity)
                .setTitle(name)
                .setMessage("大小：" + formatSize(size) + "\n路径：" + path)
                .setPositiveButton(actionLabel, (d, w) -> downloadFile(name, path, ext))
                .setNegativeButton("取消", null)
                .show();
    }

    private void downloadFile(final String name, final String remotePath, final String ext) {
        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(16), dp(20), dp(16));

        final TextView tvProg = new TextView(activity);
        tvProg.setText("正在连接 NAS 下载...");
        tvProg.setTextColor(0xFF0F172A);
        tvProg.setTextSize(14);
        box.addView(tvProg);

        final ProgressBar bar = new ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.topMargin = dp(12);
        bar.setLayoutParams(blp);
        bar.setMax(100);
        box.addView(bar);

        final boolean[] cancelled = {false};
        AlertDialog dlg = new AlertDialog.Builder(activity)
                .setTitle("拉取文件：" + name)
                .setView(box)
                .setCancelable(false)
                .setNegativeButton("取消", (d, w) -> cancelled[0] = true)
                .create();
        dlg.show();

        new Thread(() -> {
            try {
                File dir = new File(activity.getExternalFilesDir(null), "download");
                if (!dir.exists()) dir.mkdirs();
                File outFile = new File(dir, name);

                String encoded = URLEncoder.encode(remotePath, "UTF-8");
                String url = baseUrl + "/v/api/v1/files/download?path=" + encoded;
                Request req = new Request.Builder().url(url).get().build();

                try (Response resp = FnApiManager.getInstance().getStreamClient().newCall(req).execute()) {
                    if (!resp.isSuccessful() || resp.body() == null) {
                        throw new Exception("HTTP " + resp.code());
                    }
                    long total = resp.body().contentLength();
                    InputStream is = resp.body().byteStream();
                    OutputStream os = new FileOutputStream(outFile);
                    byte[] buf = new byte[32768];
                    long downloaded = 0;
                    int len;
                    long lastUi = 0;
                    while ((len = is.read(buf)) != -1) {
                        if (cancelled[0]) {
                            os.close();
                            is.close();
                            outFile.delete();
                            return;
                        }
                        os.write(buf, 0, len);
                        downloaded += len;
                        long now = System.currentTimeMillis();
                        if (now - lastUi > 150) {
                            lastUi = now;
                            final long dCopy = downloaded;
                            final int pct = total > 0 ? (int) (dCopy * 100 / total) : 0;
                            activity.runOnUiThread(() -> {
                                bar.setIndeterminate(total <= 0);
                                if (total > 0) bar.setProgress(pct);
                                tvProg.setText(total > 0
                                        ? String.format(Locale.CHINA, "已下载 %d%% (%s / %s)", pct, formatSize(dCopy), formatSize(total))
                                        : ("已下载 " + formatSize(dCopy)));
                            });
                        }
                    }
                    os.flush();
                    os.close();
                    is.close();
                }

                activity.runOnUiThread(() -> {
                    if (dlg.isShowing()) dlg.dismiss();
                    onDownloadComplete(outFile, ext);
                });
            } catch (Exception e) {
                activity.runOnUiThread(() -> {
                    if (dlg.isShowing()) dlg.dismiss();
                    Toast.makeText(activity, "下载失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        }).start();
    }

    private void onDownloadComplete(File file, String ext) {
        if ("apk".equals(ext)) {
            openOrInstallFile(file, "application/vnd.android.package-archive");
            return;
        }
        new AlertDialog.Builder(activity)
                .setTitle("下载完成")
                .setMessage("已保存至：\n" + file.getAbsolutePath())
                .setPositiveButton("打开文件", (d, w) -> openOrInstallFile(file, guessMime(ext)))
                .setNegativeButton("关闭", null)
                .show();
    }

    private void openOrInstallFile(File file, String mime) {
        try {
            Uri uri;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                uri = FileProvider.getUriForFile(activity, activity.getPackageName() + ".fileprovider", file);
            } else {
                uri = Uri.fromFile(file);
            }
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(uri, mime);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            activity.startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(activity, "未找到可打开此文件的应用，已保存至：" + file.getAbsolutePath(), Toast.LENGTH_LONG).show();
        }
    }

    private String guessMime(String ext) {
        if (ext == null || ext.isEmpty()) return "*/*";
        String m = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
        return m != null ? m : "*/*";
    }

    private String getIcon(boolean isDir, String ext) {
        if (isDir) return "📁";
        if ("apk".equals(ext)) return "📦";
        if ("mp4".equals(ext) || "mkv".equals(ext) || "avi".equals(ext) || "mov".equals(ext) || "ts".equals(ext)) return "🎬";
        if ("mp3".equals(ext) || "flac".equals(ext) || "wav".equals(ext) || "aac".equals(ext) || "m4a".equals(ext)) return "🎵";
        if ("jpg".equals(ext) || "jpeg".equals(ext) || "png".equals(ext) || "webp".equals(ext)) return "🖼️";
        if ("zip".equals(ext) || "rar".equals(ext) || "7z".equals(ext) || "tar".equals(ext) || "gz".equals(ext)) return "🗜️";
        return "📄";
    }

    private String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        double kb = bytes / 1024.0;
        if (kb < 1024) return String.format(Locale.CHINA, "%.1f KB", kb);
        double mb = kb / 1024.0;
        if (mb < 1024) return String.format(Locale.CHINA, "%.1f MB", mb);
        return String.format(Locale.CHINA, "%.2f GB", mb / 1024.0);
    }

    private int dp(int v) {
        return (int) (v * activity.getResources().getDisplayMetrics().density);
    }
}
