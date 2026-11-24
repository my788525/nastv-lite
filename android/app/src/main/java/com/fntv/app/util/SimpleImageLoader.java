package com.fntv.app.util;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.util.Log;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * 高性能图片加载器 - 专为车机低负载、高流畅度优化
 * 支持动态内存缓存、异步后台线程池、内存安全采样解码与视图复用校验
 */
public class SimpleImageLoader {

    private static final String TAG = "ImageLoader";

    // 动态计算缓存大小：堆内存的 1/8，介于 16MB 到 64MB 之间
    private static final int CACHE_SIZE;
    static {
        long maxMem = Runtime.getRuntime().maxMemory();
        int calc = (int) (maxMem / 8);
        if (calc < 16 * 1024 * 1024) calc = 16 * 1024 * 1024;
        if (calc > 64 * 1024 * 1024) calc = 64 * 1024 * 1024;
        CACHE_SIZE = calc;
    }

    private static final LruCache<String, Bitmap> cache = new LruCache<String, Bitmap>(CACHE_SIZE) {
        @Override
        protected int sizeOf(String key, Bitmap bitmap) {
            return bitmap.getByteCount();
        }
    };

    // 专用的后台线程池，限制并发数以保护车机 CPU 和网络带宽，避免主线程卡顿
    private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(3, new ThreadFactory() {
        private int count = 0;
        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "ImageLoader-" + (++count));
            t.setPriority(Thread.NORM_PRIORITY - 1);
            return t;
        }
    });

    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());

    /**
     * 加载图片（使用 OkHttpClient，自动携带认证头）
     */
    public static void load(final String url, final ImageView view, final OkHttpClient client) {
        if (view == null) return;

        if (url == null || url.trim().isEmpty()) {
            view.setImageBitmap(null);
            view.setBackgroundColor(0xFF2A2A2A);
            return;
        }

        final String cleanUrl = url.trim();
        view.setTag(cleanUrl);

        Bitmap cached = cache.get(cleanUrl);
        if (cached != null && !cached.isRecycled()) {
            view.setImageBitmap(cached);
            return;
        }

        // 占位显示
        view.setImageBitmap(null);

        EXECUTOR.execute(new Runnable() {
            @Override
            public void run() {
                Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
                final Bitmap bitmap = downloadAndDecode(cleanUrl, client);

                if (bitmap != null) {
                    cache.put(cleanUrl, bitmap);
                }

                MAIN_HANDLER.post(new Runnable() {
                    @Override
                    public void run() {
                        // 校验 view 绑定的 tag 是否与当前 url 一致，防止滑动复用错位
                        Object tag = view.getTag();
                        if (cleanUrl.equals(tag)) {
                            if (bitmap != null && !bitmap.isRecycled()) {
                                view.setImageBitmap(bitmap);
                            } else {
                                view.setBackgroundColor(0xFF333333);
                            }
                        }
                    }
                });
            }
        });
    }

    public static void clearCache() {
        cache.evictAll();
    }

    private static Bitmap downloadAndDecode(String urlStr, OkHttpClient client) {
        try {
            Request request = new Request.Builder()
                    .url(urlStr)
                    .build();

            Response response = client.newCall(request).execute();
            if (response.code() != 200 || response.body() == null) {
                return null;
            }

            String contentType = response.header("Content-Type");
            if (contentType != null && !contentType.contains("image")) {
                return null;
            }

            InputStream input = response.body().byteStream();
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = input.read(buf)) != -1) {
                baos.write(buf, 0, n);
            }
            input.close();
            byte[] imageData = baos.toByteArray();

            return decodeSampledBitmap(imageData, 400, 600);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 采样率解码，节省车机内存并加快解码速度
     */
    private static Bitmap decodeSampledBitmap(byte[] data, int reqWidth, int reqHeight) {
        if (data == null || data.length == 0) return null;
        try {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, options);

            options.inSampleSize = calculateInSampleSize(options, reqWidth, reqHeight);
            options.inJustDecodeBounds = false;
            options.inPreferredConfig = Bitmap.Config.RGB_565; // RGB_565 节省 50% 内存
            options.inPurgeable = true;
            options.inInputShareable = true;

            Bitmap bmp = BitmapFactory.decodeByteArray(data, 0, data.length, options);
            if (bmp != null) return bmp;

            // 备用 ARGB_8888 解码
            options.inPreferredConfig = Bitmap.Config.ARGB_8888;
            return BitmapFactory.decodeByteArray(data, 0, data.length, options);
        } catch (Throwable t) {
            return null;
        }
    }

    private static int calculateInSampleSize(BitmapFactory.Options options, int reqWidth, int reqHeight) {
        final int height = options.outHeight;
        final int width = options.outWidth;
        int inSampleSize = 1;

        if (height > reqHeight || width > reqWidth) {
            final int halfHeight = height / 2;
            final int halfWidth = width / 2;
            while ((halfHeight / inSampleSize) >= reqHeight && (halfWidth / inSampleSize) >= reqWidth) {
                inSampleSize *= 2;
            }
        }
        return inSampleSize;
    }
}
