package com.fntv.app;

import android.annotation.SuppressLint;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.fntv.app.api.FnApiManager;
import com.fntv.app.api.model.PlayListItem;
import com.fntv.app.util.SimpleImageLoader;

/**
 * 悬浮歌词与迷你播放浮窗服务（参考 NavMusic FloatingPlayerService 多档位与车机防误触设计，兼容 Android 4.4+）。
 */
public class FloatingLyricsService extends Service {

    private static final String PREF_X = "float_lrc_x";
    private static final String PREF_Y = "float_lrc_y";
    private static final String PREF_TIER = "float_lrc_tier";
    private static final String PREF_LOCKED = "float_lrc_locked";
    public static final String PREF_PASSTHROUGH = "float_lrc_passthrough";
    public static final String ACTION_REFRESH_FLAGS = "com.fntv.app.FLOAT_REFRESH_FLAGS";

    public static final int TIER_LYRICS_ONLY = 0;
    public static final int TIER_COMPACT = 1;
    public static final int TIER_FULL = 2;

    private static FloatingLyricsService instance;
    private static boolean running = false;
    public static boolean isRunning() { return running; }

    public static boolean isPassThrough(Context ctx) {
        return ctx != null && ctx.getSharedPreferences("fntv_prefs", MODE_PRIVATE).getBoolean(PREF_PASSTHROUGH, false);
    }

    public static void setPassThrough(Context ctx, boolean enable) {
        if (ctx == null) return;
        ctx.getSharedPreferences("fntv_prefs", MODE_PRIVATE).edit().putBoolean(PREF_PASSTHROUGH, enable).apply();
        if (instance != null) {
            instance.applyPassThrough(enable);
        }
    }

    public static boolean canDraw(Context ctx) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return Settings.canDrawOverlays(ctx);
        }
        return true;
    }

    private WindowManager windowManager;
    private WindowManager.LayoutParams lp;
    private LinearLayout rootLayout;

    private View headerRow;
    private ImageView coverImg;
    private TextView tvTitle;
    private TextView tvLyricMain;
    private TextView tvLyricNext;
    private View controlsRow;
    private TextView btnPlayPause;
    private TextView btnLock;

    private int tier = TIER_LYRICS_ONLY;
    private boolean locked = false;
    private boolean passThrough = false;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private String lastCoverGuid = "";

    private final Runnable tickRunnable = new Runnable() {
        @Override
        public void run() {
            refreshContent();
            handler.postDelayed(this, 250);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        running = true;
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        SharedPreferences sp = getSharedPreferences("fntv_prefs", MODE_PRIVATE);
        tier = sp.getInt(PREF_TIER, TIER_LYRICS_ONLY);
        locked = sp.getBoolean(PREF_LOCKED, false);
        passThrough = sp.getBoolean(PREF_PASSTHROUGH, false);

        buildOverlayView();
        applyTierLayout();
        applyPassThrough(passThrough);
        handler.post(tickRunnable);
    }

    @SuppressLint("ClickableViewAccessibility")
    private void buildOverlayView() {
        rootLayout = new LinearLayout(this);
        rootLayout.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xCC0B1120);
        bg.setCornerRadius(dp(14));
        bg.setStroke(dp(1), 0x4438BDF8);
        rootLayout.setBackground(bg);
        rootLayout.setPadding(dp(12), dp(8), dp(12), dp(8));

        // Header row (cover + title + tier/lock/close)
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        headerRow = header;

        coverImg = new ImageView(this);
        coverImg.setScaleType(ImageView.ScaleType.CENTER_CROP);
        LinearLayout.LayoutParams coverLp = new LinearLayout.LayoutParams(dp(34), dp(34));
        coverLp.rightMargin = dp(8);
        header.addView(coverImg, coverLp);

        tvTitle = new TextView(this);
        tvTitle.setTextColor(Color.WHITE);
        tvTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        tvTitle.setSingleLine(true);
        tvTitle.setEllipsize(TextUtils.TruncateAt.END);
        header.addView(tvTitle, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        header.addView(makeIconBtn("🪟", new View.OnClickListener() {
            @Override public void onClick(View v) { cycleTier(); }
        }));
        header.addView(makeIconBtn("👻", new View.OnClickListener() {
            @Override public void onClick(View v) {
                setPassThrough(FloatingLyricsService.this, true);
                android.widget.Toast.makeText(FloatingLyricsService.this,
                        "已开启穿透模式（可在播放器“悬浮歌词”按钮中关闭）", android.widget.Toast.LENGTH_SHORT).show();
            }
        }));
        btnLock = makeIconBtn(locked ? "🔒" : "🔓", new View.OnClickListener() {
            @Override public void onClick(View v) { toggleLock(); }
        });
        header.addView(btnLock);
        header.addView(makeIconBtn("✕", new View.OnClickListener() {
            @Override public void onClick(View v) { stopSelf(); }
        }));
        rootLayout.addView(header);

        // Main lyric line
        tvLyricMain = new TextView(this);
        tvLyricMain.setTextColor(Color.parseColor("#38BDF8"));
        tvLyricMain.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        tvLyricMain.setGravity(Gravity.CENTER);
        tvLyricMain.setMaxLines(1);
        tvLyricMain.setEllipsize(TextUtils.TruncateAt.END);
        rootLayout.addView(tvLyricMain);

        // Next lyric line
        tvLyricNext = new TextView(this);
        tvLyricNext.setTextColor(Color.parseColor("#CBD5E1"));
        tvLyricNext.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        tvLyricNext.setGravity(Gravity.CENTER);
        tvLyricNext.setMaxLines(1);
        tvLyricNext.setEllipsize(TextUtils.TruncateAt.END);
        rootLayout.addView(tvLyricNext);

        // Controls row
        LinearLayout ctrl = new LinearLayout(this);
        ctrl.setOrientation(LinearLayout.HORIZONTAL);
        ctrl.setGravity(Gravity.CENTER);
        ctrl.setPadding(0, dp(4), 0, 0);
        controlsRow = ctrl;

        ctrl.addView(makeIconBtn("⏮", new View.OnClickListener() {
            @Override public void onClick(View v) {
                MusicPlaybackService s = MusicPlaybackService.getInstance();
                if (s != null) s.playPrevious();
            }
        }));
        btnPlayPause = makeIconBtn("⏯", new View.OnClickListener() {
            @Override public void onClick(View v) {
                MusicPlaybackService s = MusicPlaybackService.getInstance();
                if (s != null) s.togglePlayPause();
            }
        });
        ctrl.addView(btnPlayPause);
        ctrl.addView(makeIconBtn("⏭", new View.OnClickListener() {
            @Override public void onClick(View v) {
                MusicPlaybackService s = MusicPlaybackService.getInstance();
                if (s != null) s.playNext(false);
            }
        }));
        ctrl.addView(makeIconBtn("🎵", new View.OnClickListener() {
            @Override public void onClick(View v) {
                Intent i = new Intent(FloatingLyricsService.this, MusicPlayerActivity.class);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
            }
        }));
        rootLayout.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (tier == TIER_LYRICS_ONLY) cycleTier();
            }
        });
        rootLayout.addView(ctrl);

        int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;

        SharedPreferences sp = getSharedPreferences("fntv_prefs", MODE_PRIVATE);
        lp = new WindowManager.LayoutParams(
                dp(320),
                WindowManager.LayoutParams.WRAP_CONTENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = sp.getInt(PREF_X, dp(24));
        lp.y = sp.getInt(PREF_Y, dp(100));

        rootLayout.setOnTouchListener(new View.OnTouchListener() {
            private int initX, initY;
            private float touchX, touchY;
            private boolean moved;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                if (locked) return false;
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        initX = lp.x;
                        initY = lp.y;
                        touchX = event.getRawX();
                        touchY = event.getRawY();
                        moved = false;
                        return false;
                    case MotionEvent.ACTION_MOVE:
                        int dx = (int) (event.getRawX() - touchX);
                        int dy = (int) (event.getRawY() - touchY);
                        if (Math.abs(dx) > 6 || Math.abs(dy) > 6) {
                            moved = true;
                            lp.x = initX + dx;
                            lp.y = initY + dy;
                            windowManager.updateViewLayout(rootLayout, lp);
                        }
                        return moved;
                    case MotionEvent.ACTION_UP:
                        if (moved) {
                            getSharedPreferences("fntv_prefs", MODE_PRIVATE).edit()
                                    .putInt(PREF_X, lp.x)
                                    .putInt(PREF_Y, lp.y)
                                    .apply();
                            return true;
                        }
                        return false;
                }
                return false;
            }
        });

        windowManager.addView(rootLayout, lp);
    }

    private void cycleTier() {
        tier = (tier + 1) % 3;
        getSharedPreferences("fntv_prefs", MODE_PRIVATE).edit().putInt(PREF_TIER, tier).apply();
        applyTierLayout();
    }

    private void toggleLock() {
        locked = !locked;
        getSharedPreferences("fntv_prefs", MODE_PRIVATE).edit().putBoolean(PREF_LOCKED, locked).apply();
        if (btnLock != null) btnLock.setText(locked ? "🔒" : "🔓");
    }

    private void applyPassThrough(boolean enable) {
        passThrough = enable;
        if (lp == null || rootLayout == null || windowManager == null) return;
        if (enable) {
            lp.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        } else {
            lp.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        }
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(enable ? 0x800B1120 : 0xCC0B1120);
        bg.setCornerRadius(dp(14));
        bg.setStroke(dp(1), enable ? 0x2238BDF8 : 0x4438BDF8);
        rootLayout.setBackground(bg);
        try {
            windowManager.updateViewLayout(rootLayout, lp);
        } catch (Exception ignored) {}
    }

    private void applyTierLayout() {
        if (tier == TIER_LYRICS_ONLY) {
            headerRow.setVisibility(View.GONE);
            controlsRow.setVisibility(View.GONE);
            lp.width = dp(310);
        } else if (tier == TIER_COMPACT) {
            headerRow.setVisibility(View.VISIBLE);
            coverImg.setVisibility(View.GONE);
            controlsRow.setVisibility(View.VISIBLE);
            lp.width = dp(320);
        } else {
            headerRow.setVisibility(View.VISIBLE);
            coverImg.setVisibility(View.VISIBLE);
            controlsRow.setVisibility(View.VISIBLE);
            lp.width = dp(350);
        }
        try {
            windowManager.updateViewLayout(rootLayout, lp);
        } catch (Exception ignored) {}
    }

    private void refreshContent() {
        MusicPlaybackService s = MusicPlaybackService.getInstance();
        if (s == null) {
            tvTitle.setText("NASFun 音乐");
            tvLyricMain.setText("暂无正在播放的歌曲");
            tvLyricNext.setText("轻触切回播放器");
            return;
        }
        PlayListItem item = s.getCurrentItem();
        if (item != null) {
            String artist = s.getCurrentArtist();
            tvTitle.setText(artist.isEmpty() ? item.title : (item.title + " - " + artist));
            if (tier == TIER_FULL && !item.guid.equals(lastCoverGuid)) {
                lastCoverGuid = item.guid;
                java.io.File localCov = OfflineMusicManager.getInstance(this).getLocalCoverFile(item.guid);
                if (localCov != null) {
                    coverImg.setImageURI(android.net.Uri.fromFile(localCov));
                } else {
                    String base = FnApiManager.getInstance().getBaseUrl();
                    if (base != null && !base.isEmpty()) {
                        SimpleImageLoader.load(base + "/v/api/v1/img/" + item.guid, coverImg, FnApiManager.getInstance().getClient());
                    }
                }
            }
        }
        btnPlayPause.setText(s.isPlaying() ? "⏸" : "▶");
        String[] lines = s.getFloatingLyricLines();
        tvLyricMain.setText(lines[0]);
        tvLyricNext.setText(lines[1]);
        tvLyricNext.setVisibility(lines[1].isEmpty() ? View.GONE : View.VISIBLE);
    }

    private TextView makeIconBtn(String text, View.OnClickListener l) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(Color.WHITE);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        tv.setPadding(dp(8), dp(4), dp(8), dp(4));
        tv.setOnClickListener(l);
        return tv;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    @Override
    public void onDestroy() {
        instance = null;
        running = false;
        handler.removeCallbacks(tickRunnable);
        if (windowManager != null && rootLayout != null) {
            try { windowManager.removeView(rootLayout); } catch (Exception ignored) {}
        }
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
