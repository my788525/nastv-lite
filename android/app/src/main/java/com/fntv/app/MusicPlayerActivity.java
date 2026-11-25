package com.fntv.app;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
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

import java.io.File;
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
    private boolean listenerAttached = false;
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
        CrashGuard.install(this);
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
        headerTitle.setText("🎵 NASFun 音乐");
        headerTitle.setTextColor(Color.WHITE);
        headerTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        headerTitle.setTypeface(null, Typeface.BOLD);
        headerTitle.setGravity(Gravity.CENTER);
        topBar.addView(headerTitle, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView btnOffline = makePillBtn("⬇ 离线", "#1E293B");
        btnOffline.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showBatchOfflineDialog(); }
        });
        topBar.addView(btnOffline);

        View spTop1 = new View(this);
        topBar.addView(spTop1, new LinearLayout.LayoutParams(dp(6), 1));

        TextView btnLocal = makePillBtn("🎧 本地", "#1E293B");
        btnLocal.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showLocalModeDialog(); }
        });
        topBar.addView(btnLocal);

        View spTop2 = new View(this);
        topBar.addView(spTop2, new LinearLayout.LayoutParams(dp(6), 1));

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
                if (s != null) {
                    s.togglePlayPause();
                } else {
                    MusicPlaybackService.startPendingIfNeeded(MusicPlayerActivity.this);
                    Toast.makeText(MusicPlayerActivity.this, "正在启动音乐播放服务...", Toast.LENGTH_SHORT).show();
                }
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
        btnPlayPause.requestFocus();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        MusicPlaybackService s = MusicPlaybackService.getInstance();
        if (s != null) {
            if (keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE || keyCode == KeyEvent.KEYCODE_MEDIA_PLAY || keyCode == KeyEvent.KEYCODE_MEDIA_PAUSE) {
                s.togglePlayPause();
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_MEDIA_NEXT) {
                s.playNext(false);
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_MEDIA_PREVIOUS) {
                s.playPrevious();
                return true;
            }
        }
        return super.onKeyDown(keyCode, event);
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
        listenerAttached = false;
        MusicPlaybackService s = MusicPlaybackService.getInstance();
        if (s != null) {
            s.addListener(this);
            listenerAttached = true;
            syncAllFromService(s);
        } else {
            String pt = MusicPlaybackService.getPendingTitle();
            if (pt != null && !pt.isEmpty()) {
                tvTitle.setText(pt);
                tvArtist.setText("正在加载音频...");
            }
            MusicPlaybackService.startPendingIfNeeded(this);
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
        listenerAttached = false;
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
        if (!listenerAttached) {
            s.addListener(this);
            listenerAttached = true;
            syncAllFromService(s);
        }
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
                try {
                    Toast.makeText(this, "请授予“显示在其他应用上层”权限以开启悬浮歌词", Toast.LENGTH_LONG).show();
                    Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName()));
                    startActivityForResult(i, REQ_OVERLAY);
                } catch (Exception e) {
                    Toast.makeText(this, "当前投影/电视系统不支持悬浮窗权限设置", Toast.LENGTH_SHORT).show();
                }
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
        btnFloatLyrics.setBackground(makeFocusDrawable(on ? "#0284C7" : "#1E293B", 16));
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

    private void showLocalModeDialog() {
        final OfflineMusicManager mgr = OfflineMusicManager.getInstance(this);
        int cnt = mgr.getTrackCount();
        String sizeTxt = mgr.getTotalSizeText();
        String brLabel = OfflineMusicManager.getBitrateDisplayLabel(OfflineMusicManager.getPreferredBitrate(this));
        String onBrLabel = OfflineMusicManager.getBitrateDisplayLabel(OfflineMusicManager.getOnlineBitrate(this));
        String[] opts = {
                "▶ 播放全部本地离线音乐 (" + cnt + " 首 / " + sizeTxt + ")",
                "🌐 在线听歌最大码率 (" + onBrLabel.split(" ")[0] + " · 默认320k)",
                "⚙ 离线下载最大码率 (" + brLabel.split(" ")[0] + " · 默认192k)",
                "🗑 清空本地离线音乐缓存"
        };
        new AlertDialog.Builder(this)
                .setTitle("🎧 本地模式与音质设置")
                .setItems(opts, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        if (which == 0) {
                            List<PlayListItem> offList = mgr.getOfflinePlayListItems();
                            if (offList.isEmpty()) {
                                Toast.makeText(MusicPlayerActivity.this, "暂无本地离线音乐，请先点击“⬇ 离线”批量下载", Toast.LENGTH_SHORT).show();
                            } else {
                                MusicPlaybackService.startQueue(MusicPlayerActivity.this, offList, 0);
                                Toast.makeText(MusicPlayerActivity.this, "已切换至本地离线模式（" + offList.size() + " 首）", Toast.LENGTH_SHORT).show();
                            }
                        } else if (which == 1) {
                            showOnlineBitratePickerDialog();
                        } else if (which == 2) {
                            showBitratePickerDialog();
                        } else if (which == 3) {
                            mgr.clearAll();
                            Toast.makeText(MusicPlayerActivity.this, "已清空本地离线音乐", Toast.LENGTH_SHORT).show();
                        }
                    }
                })
                .show();
    }

    private void showOnlineBitratePickerDialog() {
        String cur = OfflineMusicManager.getOnlineBitrate(this);
        int checked = 3;
        for (int i = 0; i < OfflineMusicManager.BITRATE_VALUES.length; i++) {
            if (OfflineMusicManager.BITRATE_VALUES[i].equals(cur)) { checked = i; break; }
        }
        new AlertDialog.Builder(this)
                .setTitle("选择在线听歌最大码率（超出则服务端自动转码）")
                .setSingleChoiceItems(OfflineMusicManager.BITRATE_LABELS, checked, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        OfflineMusicManager.setOnlineBitrate(MusicPlayerActivity.this, OfflineMusicManager.BITRATE_VALUES[which]);
                        Toast.makeText(MusicPlayerActivity.this, "在线最大码率已设为：" + OfflineMusicManager.BITRATE_LABELS[which], Toast.LENGTH_SHORT).show();
                        MusicPlaybackService s = MusicPlaybackService.getInstance();
                        if (s != null) s.reloadForOnlineBitrateChange();
                        dialog.dismiss();
                    }
                })
                .show();
    }

    private void showBitratePickerDialog() {
        String cur = OfflineMusicManager.getPreferredBitrate(this);
        int checked = 2;
        for (int i = 0; i < OfflineMusicManager.BITRATE_VALUES.length; i++) {
            if (OfflineMusicManager.BITRATE_VALUES[i].equals(cur)) { checked = i; break; }
        }
        new AlertDialog.Builder(this)
                .setTitle("选择离线下载最大码率")
                .setSingleChoiceItems(OfflineMusicManager.BITRATE_LABELS, checked, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        OfflineMusicManager.setPreferredBitrate(MusicPlayerActivity.this, OfflineMusicManager.BITRATE_VALUES[which]);
                        Toast.makeText(MusicPlayerActivity.this, "已设最大码率：" + OfflineMusicManager.BITRATE_LABELS[which], Toast.LENGTH_SHORT).show();
                        dialog.dismiss();
                    }
                })
                .show();
    }

    private void showBatchOfflineDialog() {
        MusicPlaybackService s = MusicPlaybackService.getInstance();
        if (s == null || s.getQueue().isEmpty()) return;
        final List<PlayListItem> q = s.getQueue();
        final String curBr = OfflineMusicManager.getPreferredBitrate(this);
        String brTxt = OfflineMusicManager.getBitrateDisplayLabel(curBr);
        String[] opts = {
                "⬇ 批量离线当前队列（共 " + q.size() + " 首 · 码率上限 " + brTxt.split(" ")[0] + "）",
                "⚙ 更改下载最大码率（当前：" + brTxt + "）"
        };
        new AlertDialog.Builder(this)
                .setTitle("批量离线缓存到本地")
                .setItems(opts, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        if (which == 0) {
                            OfflineMusicManager mgr = OfflineMusicManager.getInstance(MusicPlayerActivity.this);
                            mgr.addBatchListener(new OfflineMusicManager.BatchListener() {
                                @Override public void onProgress(int done, int total, String currentTitle) {}
                                @Override public void onFinished(int ok, int fail) {
                                    Toast.makeText(MusicPlayerActivity.this,
                                            "离线下载完成：成功 " + ok + " 首" + (fail > 0 ? "，失败 " + fail + " 首" : ""),
                                            Toast.LENGTH_LONG).show();
                                }
                            });
                            mgr.enqueueBatch(q, curBr, true);
                            Toast.makeText(MusicPlayerActivity.this, "已加入后台离线下载队列（跳过已缓存歌曲）", Toast.LENGTH_SHORT).show();
                        } else {
                            showBitratePickerDialog();
                        }
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
                File localCov = OfflineMusicManager.getInstance(MusicPlayerActivity.this).getLocalCoverFile(item.guid);
                if (localCov != null) {
                    coverImg.setImageURI(Uri.fromFile(localCov));
                } else {
                    String base = FnApiManager.getInstance().getBaseUrl();
                    if (base != null && !base.isEmpty()) {
                        SimpleImageLoader.load(base + "/v/api/v1/img/" + item.guid, coverImg, FnApiManager.getInstance().getClient());
                    }
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

    private StateListDrawable makeFocusDrawable(String normalHex, int radiusDp) {
        GradientDrawable normal = new GradientDrawable();
        normal.setColor(Color.parseColor(normalHex));
        normal.setCornerRadius(dp(radiusDp));

        GradientDrawable focused = new GradientDrawable();
        focused.setColor(Color.parseColor("#1E3A5F"));
        focused.setCornerRadius(dp(radiusDp));
        focused.setStroke(dp(2), Color.parseColor("#38BDF8"));

        StateListDrawable sld = new StateListDrawable();
        sld.addState(new int[]{android.R.attr.state_focused}, focused);
        sld.addState(new int[]{android.R.attr.state_selected}, focused);
        sld.addState(new int[]{}, normal);
        return sld;
    }

    private TextView makePillBtn(String text, String colorHex) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(Color.WHITE);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        tv.setPadding(dp(12), dp(6), dp(12), dp(6));
        tv.setFocusable(true);
        tv.setBackground(makeFocusDrawable(colorHex, 16));
        return tv;
    }

    private TextView makeCtrlBtn(String text, int sp) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(Color.WHITE);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(dp(8), dp(10), dp(8), dp(10));
        tv.setFocusable(true);
        tv.setClickable(true);
        tv.setBackground(makeFocusDrawable("#00000000", 12));
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
