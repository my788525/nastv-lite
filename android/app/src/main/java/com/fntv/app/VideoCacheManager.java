package com.fntv.app;

import android.content.Context;
import android.net.Uri;
import android.util.Log;

import com.google.android.exoplayer2.database.StandaloneDatabaseProvider;
import com.google.android.exoplayer2.upstream.DataSource;
import com.google.android.exoplayer2.upstream.DataSpec;
import com.google.android.exoplayer2.upstream.cache.CacheDataSource;
import com.google.android.exoplayer2.upstream.cache.CacheWriter;
import com.google.android.exoplayer2.upstream.cache.LeastRecentlyUsedCacheEvictor;
import com.google.android.exoplayer2.upstream.cache.SimpleCache;

import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.OkHttpClient;

/**
 * 视频本地环形缓存（150MB LRU SimpleCache）+ HLS 下一分片并发预取器
 * 对标飞牛影视弱网流畅体验：
 * 1. .m3u8 播放列表直连不缓存，保证实时刷新新切片
 * 2. .ts / .m4s / init.mp4 与直链媒体块走 150MB 环形缓存，闪断/回退秒读本地
 * 3. 请求 seg_N 时后台 2 线程并发预取 seg_{N+1} 和 seg_{N+2} 写入 SimpleCache
 */
public class VideoCacheManager {

    private static final String TAG = "VideoCache";
    private static final long MAX_CACHE_BYTES = 150L * 1024L * 1024L;
    private static final Pattern SEG_PATTERN = Pattern.compile("^(.*seg_)(\\d+)(\\.(?:ts|m4s).*)$");

    private static SimpleCache videoCache;
    private static final ExecutorService prefetchPool = Executors.newFixedThreadPool(2);
    private static final Set<String> inFlight = Collections.synchronizedSet(new HashSet<>());

    public static synchronized SimpleCache getCache(Context ctx) {
        if (videoCache == null && ctx != null) {
            try {
                File dir = new File(ctx.getCacheDir(), "video_ring_cache");
                if (!dir.exists()) dir.mkdirs();
                videoCache = new SimpleCache(
                        dir,
                        new LeastRecentlyUsedCacheEvictor(MAX_CACHE_BYTES),
                        new StandaloneDatabaseProvider(ctx.getApplicationContext())
                );
            } catch (Throwable t) {
                Log.w(TAG, "init SimpleCache failed: " + t.getMessage());
            }
        }
        return videoCache;
    }

    public static DataSource.Factory createDataSourceFactory(
            final Context ctx,
            final OkHttpClient client,
            final boolean useRingCache,
            final boolean usePrefetch) {

        final DataSource.Factory upstreamFactory = () -> new OkHttpExoDataSource(client);
        final SimpleCache cache = useRingCache ? getCache(ctx) : null;

        if (cache == null) {
            return upstreamFactory;
        }

        final CacheDataSource.Factory cacheFactory = new CacheDataSource.Factory()
                .setCache(cache)
                .setUpstreamDataSourceFactory(upstreamFactory)
                .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR);

        return () -> new DataSource() {
            private DataSource delegate;

            @Override
            public void addTransferListener(com.google.android.exoplayer2.upstream.TransferListener transferListener) {
                if (delegate != null) delegate.addTransferListener(transferListener);
            }

            @Override
            public long open(DataSpec dataSpec) throws IOException {
                String uriStr = dataSpec.uri != null ? dataSpec.uri.toString() : "";
                boolean isPlaylist = uriStr.contains(".m3u8");
                if (isPlaylist) {
                    delegate = upstreamFactory.createDataSource();
                } else {
                    delegate = cacheFactory.createDataSource();
                    if (usePrefetch) {
                        scheduleNextSegmentsPrefetch(uriStr, cache, upstreamFactory);
                    }
                }
                return delegate.open(dataSpec);
            }

            @Override
            public int read(byte[] buffer, int offset, int length) throws IOException {
                return delegate != null ? delegate.read(buffer, offset, length) : -1;
            }

            @Override
            public Uri getUri() {
                return delegate != null ? delegate.getUri() : null;
            }

            @Override
            public void close() throws IOException {
                if (delegate != null) {
                    delegate.close();
                    delegate = null;
                }
            }
        };
    }

    private static void scheduleNextSegmentsPrefetch(
            String currentUrl,
            final SimpleCache cache,
            final DataSource.Factory upstreamFactory) {
        Matcher m = SEG_PATTERN.matcher(currentUrl);
        if (!m.matches()) return;
        String prefix = m.group(1);
        String numStr = m.group(2);
        String suffix = m.group(3);
        int curNum;
        try {
            curNum = Integer.parseInt(numStr);
        } catch (Exception e) {
            return;
        }
        int width = numStr.length();
        for (int delta = 1; delta <= 2; delta++) {
            final String nextUrl = prefix + String.format(Locale.US, "%0" + width + "d", curNum + delta) + suffix;
            if (!inFlight.add(nextUrl)) continue;
            prefetchPool.execute(() -> {
                try {
                    if (cache.isCached(nextUrl, 0, 1)) return;
                    DataSpec spec = new DataSpec(Uri.parse(nextUrl));
                    CacheDataSource cds = new CacheDataSource(
                            cache,
                            upstreamFactory.createDataSource(),
                            CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR);
                    CacheWriter writer = new CacheWriter(cds, spec, null, null);
                    writer.cache();
                } catch (Throwable ignored) {
                } finally {
                    inFlight.remove(nextUrl);
                }
            });
        }
    }

    public static void clearCache(Context ctx) {
        try {
            File dir = new File(ctx.getCacheDir(), "video_ring_cache");
            deleteRec(dir);
        } catch (Throwable ignored) {}
    }

    private static void deleteRec(File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) {
                for (File k : kids) deleteRec(k);
            }
        }
        f.delete();
    }
}
