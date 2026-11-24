package com.fntv.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.fntv.app.api.FnApiManager;
import com.fntv.app.api.model.ApiResponse;
import com.fntv.app.api.model.AudioLyricsResponse;
import com.fntv.app.api.model.PlayListItem;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * 离线音乐管理与批量转码下载器（支持最大码率限制、歌词与封面本地化、无网络“本地模式”播放）。
 */
public class OfflineMusicManager {

    private static final String TAG = "OfflineMusic";
    private static final String PREF_MAX_BITRATE = "offline_max_bitrate";
    private static final String PREF_ONLINE_BITRATE = "online_max_bitrate";
    public static final String DEFAULT_BITRATE = "192";
    public static final String DEFAULT_ONLINE_BITRATE = "320";

    public static final String[] BITRATE_VALUES = {"96", "128", "192", "320", "original"};
    public static final String[] BITRATE_LABELS = {
            "96 kbps (极省空间 · 约 0.7 MB/分钟)",
            "128 kbps (标准省流 · 约 1.0 MB/分钟)",
            "192 kbps (推荐均衡 · 约 1.4 MB/分钟)",
            "320 kbps (极高音质 · 约 2.4 MB/分钟)",
            "原声无损 (保留原文件 · 体积较大)"
    };

    public static class OfflineTrack {
        public String guid;
        public String title;
        public String artist;
        public String album;
        public long duration;
        public String bitrate;
        public long fileSize;
        public String lrc;
        public String audioFileName;
        public String coverFileName;
        public long downloadedAt;
    }

    public interface BatchListener {
        void onProgress(int done, int total, String currentTitle);
        void onFinished(int successCount, int failCount);
    }

    private static OfflineMusicManager instance;

    public static synchronized OfflineMusicManager getInstance(Context ctx) {
        if (instance == null) {
            instance = new OfflineMusicManager(ctx.getApplicationContext());
        }
        return instance;
    }

    private final Context appCtx;
    private final File rootDir;
    private final File indexFile;
    private final Gson gson = new Gson();
    private final Map<String, OfflineTrack> trackMap = new LinkedHashMap<>();
    private final ExecutorService dlExecutor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final CopyOnWriteArrayList<BatchListener> batchListeners = new CopyOnWriteArrayList<>();

    private volatile boolean downloading = false;
    private volatile int batchDone = 0;
    private volatile int batchTotal = 0;
    private volatile String batchCurrentTitle = "";

    private OfflineMusicManager(Context ctx) {
        this.appCtx = ctx;
        this.rootDir = new File(ctx.getFilesDir(), "offline_music");
        if (!this.rootDir.exists()) this.rootDir.mkdirs();
        this.indexFile = new File(this.rootDir, "index.json");
        loadIndex();
    }

    public static String getPreferredBitrate(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences("fntv_prefs", Context.MODE_PRIVATE);
        return sp.getString(PREF_MAX_BITRATE, DEFAULT_BITRATE);
    }

    public static void setPreferredBitrate(Context ctx, String bitrate) {
        ctx.getSharedPreferences("fntv_prefs", Context.MODE_PRIVATE)
                .edit().putString(PREF_MAX_BITRATE, bitrate).apply();
    }

    public static String getOnlineBitrate(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences("fntv_prefs", Context.MODE_PRIVATE);
        return sp.getString(PREF_ONLINE_BITRATE, DEFAULT_ONLINE_BITRATE);
    }

    public static void setOnlineBitrate(Context ctx, String bitrate) {
        ctx.getSharedPreferences("fntv_prefs", Context.MODE_PRIVATE)
                .edit().putString(PREF_ONLINE_BITRATE, bitrate).apply();
    }

    public static String getBitrateDisplayLabel(String bitrate) {
        for (int i = 0; i < BITRATE_VALUES.length; i++) {
            if (BITRATE_VALUES[i].equals(bitrate)) return BITRATE_LABELS[i];
        }
        return BITRATE_LABELS[2];
    }

    private synchronized void loadIndex() {
        trackMap.clear();
        if (!indexFile.exists()) return;
        try (FileInputStream fis = new FileInputStream(indexFile)) {
            byte[] buf = new byte[(int) indexFile.length()];
            int read = fis.read(buf);
            if (read > 0) {
                Type type = new TypeToken<List<OfflineTrack>>() {}.getType();
                List<OfflineTrack> list = gson.fromJson(new String(buf, 0, read, "UTF-8"), type);
                if (list != null) {
                    for (OfflineTrack t : list) {
                        if (t != null && t.guid != null && t.audioFileName != null) {
                            File f = new File(rootDir, t.audioFileName);
                            if (f.exists() && f.length() > 0) {
                                trackMap.put(t.guid, t);
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Load offline index failed: " + e.getMessage());
        }
    }

    private synchronized void saveIndex() {
        try {
            List<OfflineTrack> list = new ArrayList<>(trackMap.values());
            String json = gson.toJson(list);
            File tmp = new File(rootDir, "index.json.tmp");
            try (FileOutputStream fos = new FileOutputStream(tmp)) {
                fos.write(json.getBytes("UTF-8"));
            }
            if (indexFile.exists()) indexFile.delete();
            tmp.renameTo(indexFile);
        } catch (Exception e) {
            Log.w(TAG, "Save offline index failed: " + e.getMessage());
        }
    }

    public synchronized boolean isDownloaded(String guid) {
        return getLocalAudioFile(guid) != null;
    }

    public synchronized OfflineTrack getOfflineTrack(String guid) {
        if (guid == null) return null;
        return trackMap.get(guid);
    }

    public synchronized File getLocalAudioFile(String guid) {
        if (guid == null) return null;
        OfflineTrack t = trackMap.get(guid);
        if (t == null || t.audioFileName == null) return null;
        File f = new File(rootDir, t.audioFileName);
        return (f.exists() && f.length() > 0) ? f : null;
    }

    public synchronized File getLocalCoverFile(String guid) {
        if (guid == null) return null;
        OfflineTrack t = trackMap.get(guid);
        if (t == null || t.coverFileName == null) return null;
        File f = new File(rootDir, t.coverFileName);
        return (f.exists() && f.length() > 0) ? f : null;
    }

    public synchronized int getTrackCount() {
        return trackMap.size();
    }

    public synchronized List<PlayListItem> getOfflinePlayListItems() {
        List<OfflineTrack> tracks = new ArrayList<>(trackMap.values());
        Collections.sort(tracks, new Comparator<OfflineTrack>() {
            @Override
            public int compare(OfflineTrack a, OfflineTrack b) {
                return Long.compare(b.downloadedAt, a.downloadedAt);
            }
        });
        List<PlayListItem> out = new ArrayList<>();
        for (OfflineTrack t : tracks) {
            PlayListItem item = new PlayListItem();
            item.guid = t.guid;
            item.title = t.title;
            item.artist = t.artist;
            item.album = t.album;
            item.duration = t.duration;
            item.fileSize = t.fileSize;
            item.type = "Audio";
            out.add(item);
        }
        return out;
    }

    public synchronized String getTotalSizeText() {
        long total = 0;
        for (OfflineTrack t : trackMap.values()) {
            total += t.fileSize;
        }
        if (total < 1024) return total + " B";
        if (total < 1024 * 1024) return String.format(Locale.US, "%.1f KB", total / 1024.0);
        if (total < 1024L * 1024L * 1024L) return String.format(Locale.US, "%.1f MB", total / (1024.0 * 1024.0));
        return String.format(Locale.US, "%.2f GB", total / (1024.0 * 1024.0 * 1024.0));
    }

    public synchronized void removeTrack(String guid) {
        OfflineTrack t = trackMap.remove(guid);
        if (t != null) {
            if (t.audioFileName != null) new File(rootDir, t.audioFileName).delete();
            if (t.coverFileName != null) new File(rootDir, t.coverFileName).delete();
            saveIndex();
        }
    }

    public synchronized void clearAll() {
        for (OfflineTrack t : trackMap.values()) {
            if (t.audioFileName != null) new File(rootDir, t.audioFileName).delete();
            if (t.coverFileName != null) new File(rootDir, t.coverFileName).delete();
        }
        trackMap.clear();
        saveIndex();
    }

    public boolean isDownloading() { return downloading; }
    public int getBatchDone() { return batchDone; }
    public int getBatchTotal() { return batchTotal; }
    public String getBatchCurrentTitle() { return batchCurrentTitle; }

    public void addBatchListener(BatchListener l) {
        if (l != null) batchListeners.addIfAbsent(l);
    }

    public void removeBatchListener(BatchListener l) {
        batchListeners.remove(l);
    }

    public void enqueueBatch(final List<PlayListItem> items, final String maxBitrate, final boolean skipDownloaded) {
        if (items == null || items.isEmpty()) return;
        final List<PlayListItem> todo = new ArrayList<>();
        for (PlayListItem it : items) {
            if (it == null || !"Audio".equalsIgnoreCase(it.type)) continue;
            if (skipDownloaded && isDownloaded(it.guid)) continue;
            todo.add(it);
        }
        if (todo.isEmpty()) return;

        downloading = true;
        batchDone = 0;
        batchTotal = todo.size();

        dlExecutor.execute(new Runnable() {
            @Override
            public void run() {
                int okCount = 0;
                int failCount = 0;
                String baseUrl = FnApiManager.getInstance().getBaseUrl();
                if (baseUrl == null || baseUrl.isEmpty()) {
                    String saved = appCtx.getSharedPreferences("fntv_prefs", Context.MODE_PRIVATE).getString("server_url", "");
                    baseUrl = FnApiManager.normalizeHostUrl(saved);
                }
                OkHttpClient client = FnApiManager.getInstance().getStreamClient();

                for (int i = 0; i < todo.size(); i++) {
                    final PlayListItem item = todo.get(i);
                    batchCurrentTitle = item.title != null ? item.title : "";
                    final int doneNow = i;
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            for (BatchListener l : batchListeners) l.onProgress(doneNow, batchTotal, batchCurrentTitle);
                        }
                    });

                    boolean ok = downloadSingle(item, baseUrl, maxBitrate, client);
                    if (ok) okCount++;
                    else failCount++;
                    batchDone = i + 1;
                }

                downloading = false;
                final int finalOk = okCount;
                final int finalFail = failCount;
                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        for (BatchListener l : batchListeners) l.onFinished(finalOk, finalFail);
                    }
                });
            }
        });
    }

    private boolean downloadSingle(PlayListItem item, String baseUrl, String bitrate, OkHttpClient client) {
        if (client == null || baseUrl == null || baseUrl.isEmpty()) return false;
        String audioName = "aud_" + item.guid + ".dat";
        File tmpAudio = new File(rootDir, audioName + ".tmp");
        File outAudio = new File(rootDir, audioName);

        try {
            String url = baseUrl + "/v/api/v1/audio/offline/" + item.guid + "?bitrate=" + bitrate;
            Request req = new Request.Builder().url(url).build();
            try (Response resp = client.newCall(req).execute()) {
                if (!resp.isSuccessful() || resp.body() == null) return false;
                try (InputStream is = resp.body().byteStream();
                     FileOutputStream fos = new FileOutputStream(tmpAudio)) {
                    byte[] buf = new byte[16384];
                    int n;
                    while ((n = is.read(buf)) != -1) {
                        fos.write(buf, 0, n);
                    }
                }
            }
            if (tmpAudio.length() <= 0) {
                tmpAudio.delete();
                return false;
            }
            if (outAudio.exists()) outAudio.delete();
            tmpAudio.renameTo(outAudio);

            OfflineTrack track = new OfflineTrack();
            track.guid = item.guid;
            track.title = item.title;
            track.artist = item.artist != null ? item.artist : "";
            track.album = item.album != null ? item.album : "";
            track.duration = item.duration;
            track.bitrate = bitrate;
            track.fileSize = outAudio.length();
            track.audioFileName = audioName;
            track.downloadedAt = System.currentTimeMillis();

            // Fetch & store lyrics locally
            try {
                if (FnApiManager.getInstance().getApi() != null) {
                    retrofit2.Response<ApiResponse<AudioLyricsResponse>> lr =
                            FnApiManager.getInstance().getApi().getAudioLyrics(item.guid).execute();
                    if (lr.isSuccessful() && lr.body() != null && lr.body().data != null) {
                        AudioLyricsResponse ld = lr.body().data;
                        if (ld.artist != null && !ld.artist.isEmpty()) track.artist = ld.artist;
                        if (ld.album != null && !ld.album.isEmpty()) track.album = ld.album;
                        if (ld.duration > 0) track.duration = ld.duration;
                        if (ld.lrc != null) track.lrc = ld.lrc;
                    }
                }
            } catch (Exception ignored) {}

            // Fetch & store album cover locally
            try {
                String coverName = "cov_" + item.guid + ".jpg";
                File outCover = new File(rootDir, coverName);
                Request cReq = new Request.Builder().url(baseUrl + "/v/api/v1/img/" + item.guid).build();
                try (Response cResp = client.newCall(cReq).execute()) {
                    if (cResp.isSuccessful() && cResp.body() != null) {
                        try (InputStream cis = cResp.body().byteStream();
                             FileOutputStream cfos = new FileOutputStream(outCover)) {
                            byte[] cbuf = new byte[8192];
                            int cn;
                            while ((cn = cis.read(cbuf)) != -1) {
                                cfos.write(cbuf, 0, cn);
                            }
                        }
                        if (outCover.length() > 0) track.coverFileName = coverName;
                        else outCover.delete();
                    }
                }
            } catch (Exception ignored) {}

            synchronized (this) {
                trackMap.put(track.guid, track);
                saveIndex();
            }
            return true;
        } catch (Exception e) {
            Log.w(TAG, "Offline download failed for " + item.title + ": " + e.getMessage());
            tmpAudio.delete();
            return false;
        }
    }
}
