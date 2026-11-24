package com.fntv.app;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.fntv.app.api.FnApiManager;
import com.fntv.app.api.model.PlayListItem;
import com.fntv.app.ui.SyncedLyricsView;
import com.fntv.app.util.LyricsParser;
import com.fntv.app.util.SimpleImageLoader;

import java.util.List;
import java.util.Locale;

/**
 * 音乐播放器界面：专辑封面、同步滚动歌词、队列管理、后台播放与悬浮歌词开关。
 */
public class MusicPlayerActivity extends AppCompatActivity implements MusicPlaybackService.Listener {

    private static final int REQ_OVERLAY = 9001;

    private LinearLayout bodyLayout;
    private LinearLayout infoPane;
    private ImageView coverImg;
    private TextView tvTitle;
    private TextView tvArtist;
    private SyncedLyricsView lyricsView;
    private TextView tvPos;
    private TextView tvDur;
    private SeekBar seekBar;
    private TextView btnMode;
    private TextView btnPlayPause;
    private TextView btnFloatLyrics;

    private boolean isDraggingSeek = false;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private final Runnable progressTask = new Runnable() {
        @Override
        public void run() {
            updateProgress();
            uiHandler.postDelayed(this, 300);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        OrientationHelper.adaptOrientation(this);
        buildUi();
        applyOrientationLayout(getResources().getConfiguration().orientation);
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        OrientationHelper.adaptOrientation(this);
        applyOrientationLayout(newConfig.orientation);
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#0B1120"));

        // Top Bar
        LinearLayout topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setPadding(dp(12), dp(10), dp(12), dp(10));
        topBar.setBackgroundColor(Color.parseColor("#111827"));

        TextView btnBack = makePillBtn("⬅ 返回", "#1E293B");
        btnBack.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        });
        topBar.addView(btnBack);

        TextView headerTitle = new TextView(this);
        headerTitle.setText("🎵 NASTV 音乐");
        headerTitle.setTextColor(Color.WHITE);
        headerTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        headerTitle.setTypeface(null, Typeface.BOLD);
        headerTitle.setGravity(Gravity.CENTER);
        topBar.addView(headerTitle, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        btnFloatLyrics = makePillBtn("🪟 悬浮歌词", "#1E293B");
        btnFloatLyrics.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { toggleFloatingLyrics(); }
        });
        topBar.addView(btnFloatLyrics);
        root.addView(topBar);

        // Body (cover/meta + synced lyrics)
        bodyLayout = new LinearLayout(this);
        bodyLayout.setPadding(dp(16), dp(12), dp(16), dp(8));

        infoPane = new LinearLayout(this);
        infoPane.setOrientation(LinearLayout.VERTICAL);
        infoPane.setGravity(Gravity.CENTER);

        coverImg = new ImageView(this);
        coverImg.setScaleType(ImageView.ScaleType.CENTER_CROP);
        GradientDrawable coverBg = new GradientDrawable();
        coverBg.setColor(Color.parseColor("#1E293B"));
        coverBg.setCornerRadius(dp(14));
        coverImg.setBackground(coverBg);
        infoPane.addView(coverImg, new LinearLayout.LayoutParams(dp(180), dp(180)));

        tvTitle = new TextView(this);
        tvTitle.setText("未在播放");
        tvTitle.setTextColor(Color.WHITE);
        tvTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        tvTitle.setTypeface(null, Typeface.BOLD);
        tvTitle.setGravity(Gravity.CENTER);
        tvTitle.setMaxLines(2);
        tvTitle.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        titleLp.topMargin = dp(12);
        infoPane.addView(tvTitle, titleLp);

        tvArtist = new TextView(this);
        tvArtist.setText("NAS 音乐");
        tvArtist.setTextColor(Color.parseColor("#94A3B8"));
        tvArtist.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        tvArtist.setGravity(Gravity.CENTER);
        tvArtist.setSingleLine(true);
        tvArtist.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams artistLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        artistLp.topMargin = dp(4);
        infoPane.addView(tvArtist, artistLp);

        lyricsView = new SyncedLyricsView(this);
        lyricsView.setOnLineClickListener(new SyncedLyricsView.OnLineClickListener() {
            @Override
            public void onSeekTo(long positionMs) {
                MusicPlaybackService s = MusicPlaybackService.getInstance();
                if (s != null) s.seekTo(positionMs);
            }
        });

        bodyLayout.addView(infoPane);
        bodyLayout.addView(lyricsView);
        root.addView(bodyLayout, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        // Bottom Controls
        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setPadding(dp(16), dp(8), dp(16), dp(16));
        bottom.setBackgroundColor(Color.parseColor("#111827"));

        LinearLayout seekRow = new LinearLayout(this);
        seekRow.setOrientation(LinearLayout.HORIZONTAL);
        seekRow.setGravity(Gravity.CENTER_VERTICAL);

        tvPos = new TextView(this);
        tvPos.setText("00:00");
        tvPos.setTextColor(Color.parseColor("#94A3B8"));
        tvPos.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        seekRow.addView(tvPos);

        seekBar = new SeekBar(this);
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                if (fromUser) tvPos.setText(fmtTime(p));
            }
            @Override public void onStartTrackingTouch(SeekBar sb) { isDraggingSeek = true; }
            @Override public void onStopTrackingTouch(SeekBar sb) {
                isDraggingSeek = false;
                MusicPlaybackService s = MusicPlaybackService.getInstance();
                if (s != null) s.seekTo(sb.getProgress());
            }
        });
        seekRow.addView(seekBar, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        tvDur = new TextView(this);
        tvDur.setText("00:00");
        tvDur.setTextColor(Color.parseColor("#94A3B8"));
        tvDur.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        seekRow.addView(tvDur);
        bottom.addView(seekRow);

        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setGravity(Gravity.CENTER);
        btnRow.setPadding(0, dp(10), 0, 0);

        btnMode = makeCtrlBtn("🔁", 18);
        btnMode.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                MusicPlaybackService s = MusicPlaybackService.getInstance();
                if (s != null) {
                    int m = s.cyclePlayMode();
                    updatePlayModeBtn(m);
                }
            }
        });
        btnRow.addView(btnMode, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView btnPrev = makeCtrlBtn("⏮", 24);
        btnPrev.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                MusicPlaybackService s = MusicPlaybackService.getInstance();
                if (s != null) s.playPrevious();
            }
        });
        btnRow.addView(btnPrev, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        btnPlayPause = makeCtrlBtn("▶", 28);
        btnPlayPause.setTextColor(Color.parseColor("#38BDF8"));
        btnPlayPause.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                MusicPlaybackService s = MusicPlaybackService.getInstance();
                if (s != null) s.togglePlayPause();
            }
        });
        btnRow.addView(btnPlayPause, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView btnNext = makeCtrlBtn("⏭", 24);
        btnNext.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                MusicPlaybackService s = MusicPlaybackService.getInstance();
                if (s != null) s.playNext(false);
            }
        });
        btnRow.addView(btnNext, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView btnQueue = makeCtrlBtn("📃", 18);
        btnQueue.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showQueueDialog(); }
        });
        btnRow.addView(btnQueue, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        bottom.addView(btnRow);
        root.addView(bottom);
        setContentView(root);
    }

    private void applyOrientationLayout(int orientation) {
        if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
            bodyLayout.setOrientation(LinearLayout.HORIZONTAL);
            infoPane.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 0.42f));
            lyricsView.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 0.58f));
            coverImg.setLayoutParams(new LinearLayout.LayoutParams(dp(180), dp(180)));
        } else {
            bodyLayout.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            ip.bottomMargin = dp(8);
            infoPane.setLayoutParams(ip);
            lyricsView.setLayoutParams(new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
            coverImg.setLayoutParams(new LinearLayout.LayoutParams(dp(136), dp(136)));
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        MusicPlaybackService s = MusicPlaybackService.getInstance();
        if (s != null) {
            s.addListener(this);
            syncAllFromService(s);
        }
        updateFloatingBtnState();
        uiHandler.post(progressTask);
    }

    @Override
    protected void onPause() {
        super.onPause();
        uiHandler.removeCallbacks(progressTask);
        MusicPlaybackService s = MusicPlaybackService.getInstance();
        if (s != null) s.removeListener(this);
    }

    private void syncAllFromService(MusicPlaybackService s) {
        PlayListItem item = s.getCurrentItem();
        if (item != null) {
            onTrackChanged(item, s.getCurrentIndex(), s.getQueue().size());
        }
        onPlayStateChanged(s.isPlaying());
        onLyricsLoaded(s.getCurrentLyrics(), s.getCurrentArtist(), s.getCurrentAlbum());
        updatePlayModeBtn(s.getPlayMode());
        updateProgress();
    }

    private void updateProgress() {
        MusicPlaybackService s = MusicPlaybackService.getInstance();
        if (s == null) return;
        long pos = s.getCurrentPosition();
        long dur = s.getDuration();
        if (dur > 0) {
            seekBar.setMax((int) dur);
            tvDur.setText(fmtTime(dur));
        }
        if (!isDraggingSeek) {
            seekBar.setProgress((int) pos);
            tvPos.setText(fmtTime(pos));
        }
        lyricsView.updatePosition(pos);
    }

    private void toggleFloatingLyrics() {
        if (FloatingLyricsService.isRunning()) {
            stopService(new Intent(this, FloatingLyricsService.class));
            updateFloatingBtnState();
            return;
        }
        if (!FloatingLyricsService.canDraw(this)) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Toast.makeText(this, "请授予“显示在其他应用上层”权限以开启悬浮歌词", Toast.LENGTH_LONG).show();
                Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName()));
                startActivityForResult(i, REQ_OVERLAY);
                return;
            }
        }
        startService(new Intent(this, FloatingLyricsService.class));
        updateFloatingBtnState();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_OVERLAY && FloatingLyricsService.canDraw(this)) {
            startService(new Intent(this, FloatingLyricsService.class));
            updateFloatingBtnState();
        }
    }

    private void updateFloatingBtnState() {
        boolean on = FloatingLyricsService.isRunning();
        btnFloatLyrics.setText(on ? "🪟 悬浮歌词:开" : "🪟 悬浮歌词:关");
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor(on ? "#0284C7" : "#1E293B"));
        bg.setCornerRadius(dp(16));
        btnFloatLyrics.setBackground(bg);
    }

    private void updatePlayModeBtn(int mode) {
        if (mode == MusicPlaybackService.MODE_SHUFFLE) btnMode.setText("🔀");
        else if (mode == MusicPlaybackService.MODE_LOOP_ONE) btnMode.setText("🔂");
        else btnMode.setText("🔁");
    }

    private void showQueueDialog() {
        final MusicPlaybackService s = MusicPlaybackService.getInstance();
        if (s == null) return;
        List<PlayListItem> q = s.getQueue();
        if (q.isEmpty()) return;
        String[] titles = new String[q.size()];
        int cur = s.getCurrentIndex();
        for (int i = 0; i < q.size(); i++) {
            titles[i] = (i == cur ? "▶ " : "") + q.get(i).title;
        }
        new AlertDialog.Builder(this)
                .setTitle("播放队列 (" + q.size() + " 首)")
                .setItems(titles, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        s.playAt(which);
                    }
                })
                .show();
    }

    @Override
    public void onTrackChanged(final PlayListItem item, int index, int total) {
        uiHandler.post(new Runnable() {
            @Override
            public void run() {
                tvTitle.setText(item.title);
                String base = FnApiManager.getInstance().getBaseUrl();
                if (base != null && !base.isEmpty()) {
                    SimpleImageLoader.load(base + "/v/api/v1/img/" + item.guid, coverImg, FnApiManager.getInstance().getClient());
                }
                lyricsView.setLyrics(null, "正在加载歌词...");
            }
        });
    }

    @Override
    public void onPlayStateChanged(final boolean playing) {
        uiHandler.post(new Runnable() {
            @Override
            public void run() {
                btnPlayPause.setText(playing ? "⏸" : "▶");
            }
        });
    }

    @Override
    public void onLyricsLoaded(final LyricsParser.Result lyrics, final String artist, final String album) {
        uiHandler.post(new Runnable() {
            @Override
            public void run() {
                String sub = "";
                if (artist != null && !artist.isEmpty()) sub = artist;
                if (album != null && !album.isEmpty()) sub = sub.isEmpty() ? album : (sub + " · " + album);
                tvArtist.setText(sub.isEmpty() ? "NAS 音乐" : sub);
                lyricsView.setLyrics(lyrics, "暂无歌词（支持同目录 .lrc 文件或内嵌歌词）");
            }
        });
    }

    @Override
    public void onServiceStopped() {
        uiHandler.post(new Runnable() {
            @Override
            public void run() {
                finish();
            }
        });
    }

    private TextView makePillBtn(String text, String colorHex) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(Color.WHITE);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        tv.setPadding(dp(12), dp(6), dp(12), dp(6));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor(colorHex));
        bg.setCornerRadius(dp(16));
        tv.setBackground(bg);
        return tv;
    }

    private TextView makeCtrlBtn(String text, int sp) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(Color.WHITE);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(dp(8), dp(10), dp(8), dp(10));
        return tv;
    }

    private String fmtTime(long ms) {
        long sec = Math.max(0L, ms / 1000L);
        return String.format(Locale.US, "%02d:%02d", sec / 60, sec % 60);
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
