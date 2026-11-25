package com.fntv.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import com.fntv.app.api.FnApiManager;
import com.fntv.app.api.model.ApiResponse;
import com.fntv.app.api.model.AudioLyricsResponse;
import com.fntv.app.api.model.PlayListItem;
import com.fntv.app.util.LyricsParser;
import com.google.android.exoplayer2.DefaultLoadControl;
import com.google.android.exoplayer2.ExoPlayer;
import com.google.android.exoplayer2.MediaItem;
import com.google.android.exoplayer2.PlaybackException;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.database.StandaloneDatabaseProvider;
import com.google.android.exoplayer2.extractor.DefaultExtractorsFactory;
import com.google.android.exoplayer2.source.DefaultMediaSourceFactory;
import com.google.android.exoplayer2.source.ProgressiveMediaSource;
import com.google.android.exoplayer2.upstream.DataSource;
import com.google.android.exoplayer2.upstream.DataSpec;
import com.google.android.exoplayer2.upstream.cache.CacheDataSource;
import com.google.android.exoplayer2.upstream.cache.CacheWriter;
import com.google.android.exoplayer2.upstream.cache.LeastRecentlyUsedCacheEvictor;
import com.google.android.exoplayer2.upstream.cache.SimpleCache;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

/**
 * 后台音乐播放服务（支持前台通知栏、AudioFocus 车机/通话避让、MediaSession 方向盘/蓝牙按键、LRC 歌词同步）。
 */
public class MusicPlaybackService extends Service {

    private static final String TAG = "MusicService";
    private static final String CHANNEL_ID = "nastv_music_channel";
    private static final int NOTIF_ID = 56781;

    public static final String ACTION_PLAY_QUEUE = "com.fntv.app.MUSIC_PLAY_QUEUE";
    public static final String ACTION_TOGGLE = "com.fntv.app.MUSIC_TOGGLE";
    public static final String ACTION_NEXT = "com.fntv.app.MUSIC_NEXT";
    public static final String ACTION_PREV = "com.fntv.app.MUSIC_PREV";
    public static final String ACTION_STOP = "com.fntv.app.MUSIC_STOP";

    public static final int MODE_LOOP_ALL = 0;
    public static final int MODE_SHUFFLE = 1;
    public static final int MODE_LOOP_ONE = 2;

    public interface Listener {
        void onTrackChanged(PlayListItem item, int index, int total);
        void onPlayStateChanged(boolean playing);
        void onLyricsLoaded(LyricsParser.Result lyrics, String artist, String album);
        void onServiceStopped();
    }

    private static MusicPlaybackService instance;

    public static MusicPlaybackService getInstance() {
        return instance;
    }

    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private final List<PlayListItem> queue = new ArrayList<>();
    private int currentIndex = -1;
    private int playMode = MODE_LOOP_ALL;

    private ExoPlayer player;
    private MediaSessionCompat mediaSession;
    private AudioManager audioManager;
    private PowerManager.WakeLock wakeLock;
    private boolean pausedByFocusLoss = false;

    private LyricsParser.Result currentLyrics = new LyricsParser.Result(
            Collections.<LyricsParser.TimedLyric>emptyList(), Collections.<String>emptyList());
    private String currentArtist = "";
    private String currentAlbum = "";
    private final Random random = new Random();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private static final long MAX_MUSIC_CACHE_BYTES = 512L * 1024L * 1024L;
    private SimpleCache musicCache;
    private CacheDataSource.Factory cacheDataSourceFactory;
    private final ExecutorService prefetchExecutor = Executors.newSingleThreadExecutor();
    private final AtomicInteger prefetchGeneration = new AtomicInteger(0);
    private volatile CacheWriter activeCacheWriter;
    private volatile int nextShuffleIndex = -1;
    private volatile boolean fallbackDirectTried = false;
    private DataSource.Factory activeDataSourceFactory;
    private DataSource.Factory directDataSourceFactory;
    private final ConcurrentHashMap<String, AudioLyricsResponse> lyricsCache = new ConcurrentHashMap<>();

    private static List<PlayListItem> pendingQueue;
    private static int pendingIndex = 0;

    public static void startQueue(Context ctx, List<PlayListItem> items, int startIndex) {
        if (items == null || items.isEmpty()) return;
        pendingQueue = new ArrayList<>(items);
        pendingIndex = Math.max(0, Math.min(startIndex, items.size() - 1));
        Intent intent = new Intent(ctx, MusicPlaybackService.class);
        intent.setAction(ACTION_PLAY_QUEUE);
        ContextCompat.startForegroundService(ctx, intent);
    }

    private final AudioManager.OnAudioFocusChangeListener focusListener = new AudioManager.OnAudioFocusChangeListener() {
        @Override
        public void onAudioFocusChange(int focusChange) {
            if (player == null) return;
            switch (focusChange) {
                case AudioManager.AUDIOFOCUS_LOSS:
                    pausedByFocusLoss = false;
                    pause();
                    break;
                case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
                    if (isPlaying()) {
                        pausedByFocusLoss = true;
                        pause();
                    }
                    break;
                case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
                    player.setVolume(0.25f);
                    break;
                case AudioManager.AUDIOFOCUS_GAIN:
                    player.setVolume(1.0f);
                    if (pausedByFocusLoss) {
                        pausedByFocusLoss = false;
                        resume();
                    }
                    break;
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        createNotificationChannel();
        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);

        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm != null) {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "nastv:music_wakelock");
            wakeLock.setReferenceCounted(false);
        }

        DataSource.Factory upstreamFactory = new DataSource.Factory() {
            @Override
            public DataSource createDataSource() {
                return new OkHttpExoDataSource(FnApiManager.getInstance().getStreamClient());
            }
        };
        directDataSourceFactory = upstreamFactory;

        DataSource.Factory dsFactory = upstreamFactory;
        try {
            File cacheDir = new File(getCacheDir(), "music_cache");
            musicCache = new SimpleCache(
                    cacheDir,
                    new LeastRecentlyUsedCacheEvictor(MAX_MUSIC_CACHE_BYTES),
                    new StandaloneDatabaseProvider(this));
            cacheDataSourceFactory = new CacheDataSource.Factory()
                    .setCache(musicCache)
                    .setUpstreamDataSourceFactory(upstreamFactory)
                    .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR);
            dsFactory = cacheDataSourceFactory;
        } catch (Exception e) {
            Log.w(TAG, "Init music disk cache failed, fallback to direct stream: " + e.getMessage());
        }
        activeDataSourceFactory = dsFactory;

        DefaultLoadControl loadControl = new DefaultLoadControl.Builder()
                .setBufferDurationsMs(15_000, 90_000, 1_500, 3_000)
                .build();

        player = new ExoPlayer.Builder(this)
                .setMediaSourceFactory(new DefaultMediaSourceFactory(dsFactory))
                .setLoadControl(loadControl)
                .build();
        player.addListener(new Player.Listener() {
            @Override
            public void onIsPlayingChanged(boolean isPlaying) {
                if (isPlaying && wakeLock != null && !wakeLock.isHeld()) {
                    try { wakeLock.acquire(4 * 60 * 60 * 1000L); } catch (Exception ignored) {}
                } else if (!isPlaying && wakeLock != null && wakeLock.isHeld()) {
                    try { wakeLock.release(); } catch (Exception ignored) {}
                }
                updateMediaSessionState(isPlaying);
                updateNotification();
                for (Listener l : listeners) l.onPlayStateChanged(isPlaying);
            }

            @Override
            public void onPlaybackStateChanged(int playbackState) {
                if (playbackState == Player.STATE_ENDED) {
                    playNext(true);
                }
            }

            @Override
            public void onPlayerError(PlaybackException error) {
                Log.e(TAG, "Music player error: " + error.getMessage(), error);
                PlayListItem cur = getCurrentItem();
                if (!fallbackDirectTried && cur != null && directDataSourceFactory != null) {
                    fallbackDirectTried = true;
                    String directUrl = resolveBaseUrl() + "/v/api/v1/media/range/" + cur.guid;
                    Log.w(TAG, "Fallback to direct audio stream: " + directUrl);
                    player.setMediaSource(new ProgressiveMediaSource.Factory(
                            directDataSourceFactory, new DefaultExtractorsFactory())
                            .createMediaSource(MediaItem.fromUri(Uri.parse(directUrl))));
                    player.prepare();
                    player.play();
                    return;
                }
                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        android.widget.Toast.makeText(MusicPlaybackService.this,
                                "音频加载失败，请检查网络或切换音质", android.widget.Toast.LENGTH_SHORT).show();
                    }
                });
            }
        });

        mediaSession = new MediaSessionCompat(this, "NASTVMusicSession");
        mediaSession.setCallback(new MediaSessionCompat.Callback() {
            @Override public void onPlay() { resume(); }
            @Override public void onPause() { pause(); }
            @Override public void onSkipToNext() { playNext(false); }
            @Override public void onSkipToPrevious() { playPrevious(); }
            @Override public void onSeekTo(long pos) { seekTo(pos); }
        });
        mediaSession.setActive(true);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(NOTIF_ID, buildNotification());
        if (intent != null && intent.getAction() != null) {
            String action = intent.getAction();
            if (ACTION_PLAY_QUEUE.equals(action)) {
                if (pendingQueue != null && !pendingQueue.isEmpty()) {
                    synchronized (queue) {
                        queue.clear();
                        queue.addAll(pendingQueue);
                    }
                    playAt(pendingIndex);
                }
            } else if (ACTION_TOGGLE.equals(action)) {
                togglePlayPause();
            } else if (ACTION_NEXT.equals(action)) {
                playNext(false);
            } else if (ACTION_PREV.equals(action)) {
                playPrevious();
            } else if (ACTION_STOP.equals(action)) {
                stopPlaybackAndSelf();
                return START_NOT_STICKY;
            }
        }
        startForeground(NOTIF_ID, buildNotification());
        return START_STICKY;
    }

    public void playAt(int index) {
        PlayListItem item;
        int total;
        synchronized (queue) {
            if (queue.isEmpty() || index < 0 || index >= queue.size()) return;
            currentIndex = index;
            item = queue.get(index);
            total = queue.size();
        }

        if (audioManager != null) {
            audioManager.requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);
        }

        File localFile = OfflineMusicManager.getInstance(this).getLocalAudioFile(item.guid);
        String baseUrl = resolveBaseUrl();
        String playUri = (localFile != null)
                ? Uri.fromFile(localFile).toString()
                : buildOnlineAudioUrl(baseUrl, item.guid);
        Log.d(TAG, "Playing audio (" + (localFile != null ? "LOCAL" : "STREAM") + "): " + item.title + " -> " + playUri);

        currentArtist = item.artist != null ? item.artist : "";
        currentAlbum = item.album != null ? item.album : "";
        currentLyrics = new LyricsParser.Result(Collections.<LyricsParser.TimedLyric>emptyList(), Collections.<String>emptyList());
        fallbackDirectTried = false;

        DataSource.Factory useFactory = (localFile != null && directDataSourceFactory != null)
                ? directDataSourceFactory
                : (activeDataSourceFactory != null ? activeDataSourceFactory : directDataSourceFactory);
        player.setMediaSource(new ProgressiveMediaSource.Factory(
                useFactory, new DefaultExtractorsFactory())
                .createMediaSource(MediaItem.fromUri(Uri.parse(playUri))));
        player.prepare();
        player.play();

        updateMediaSessionMetadata(item);
        startForeground(NOTIF_ID, buildNotification());
        for (Listener l : listeners) {
            l.onTrackChanged(item, currentIndex, total);
        }

        fetchLyrics(item);
        schedulePrefetchNextTracks(index, baseUrl);
    }

    private String buildOnlineAudioUrl(String baseUrl, String guid) {
        String br = OfflineMusicManager.getOnlineBitrate(this);
        if ("original".equalsIgnoreCase(br)) {
            return baseUrl + "/v/api/v1/media/range/" + guid;
        }
        return baseUrl + "/v/api/v1/audio/offline/" + guid + "?bitrate=" + br;
    }

    public void reloadForOnlineBitrateChange() {
        if (currentIndex >= 0) {
            long pos = getCurrentPosition();
            playAt(currentIndex);
            if (pos > 1000) seekTo(pos);
        }
    }

    private String resolveBaseUrl() {
        String baseUrl = FnApiManager.getInstance().getBaseUrl();
        if (baseUrl == null || baseUrl.isEmpty()) {
            String saved = getSharedPreferences("fntv_prefs", MODE_PRIVATE).getString("host", "");
            if (saved.isEmpty()) saved = getSharedPreferences("fntv_prefs", MODE_PRIVATE).getString("server_url", "");
            baseUrl = FnApiManager.normalizeHostUrl(saved);
        }
        return baseUrl;
    }

    private void schedulePrefetchNextTracks(final int curIndex, final String baseUrl) {
        if (cacheDataSourceFactory == null || baseUrl == null || baseUrl.isEmpty()) return;
        final int gen = prefetchGeneration.incrementAndGet();
        CacheWriter prevWriter = activeCacheWriter;
        if (prevWriter != null) {
            try { prevWriter.cancel(); } catch (Exception ignored) {}
        }

        final List<PlayListItem> targets = new ArrayList<>();
        synchronized (queue) {
            int size = queue.size();
            if (size <= 1) return;
            if (playMode == MODE_SHUFFLE) {
                int next = random.nextInt(size);
                if (next == curIndex) next = (next + 1) % size;
                nextShuffleIndex = next;
                targets.add(queue.get(next));
                if (size > 2) {
                    int next2 = (next + 1) % size;
                    if (next2 != curIndex) targets.add(queue.get(next2));
                }
            } else if (playMode != MODE_LOOP_ONE) {
                int next1 = (curIndex + 1) % size;
                targets.add(queue.get(next1));
                if (size > 2) {
                    int next2 = (curIndex + 2) % size;
                    if (next2 != curIndex) targets.add(queue.get(next2));
                }
            }
        }

        for (PlayListItem t : targets) {
            prefetchLyricsSilently(t);
        }

        prefetchExecutor.execute(new Runnable() {
            @Override
            public void run() {
                for (PlayListItem target : targets) {
                    if (gen != prefetchGeneration.get()) return;
                    if (OfflineMusicManager.getInstance(MusicPlaybackService.this).isDownloaded(target.guid)) continue;
                    String url = buildOnlineAudioUrl(baseUrl, target.guid);
                    try {
                        CacheDataSource ds = cacheDataSourceFactory.createDataSourceForDownloading();
                        DataSpec spec = new DataSpec(Uri.parse(url));
                        CacheWriter writer = new CacheWriter(ds, spec, null, null);
                        activeCacheWriter = writer;
                        writer.cache();
                        Log.d(TAG, "Pre-cached track: " + target.title);
                    } catch (Exception e) {
                        if (gen != prefetchGeneration.get()) return;
                        Log.w(TAG, "Pre-cache skipped for " + target.title + ": " + e.getMessage());
                    } finally {
                        activeCacheWriter = null;
                    }
                }
            }
        });
    }

    private void prefetchLyricsSilently(final PlayListItem item) {
        if (item == null || lyricsCache.containsKey(item.guid) || FnApiManager.getInstance().getApi() == null) return;
        FnApiManager.getInstance().getApi().getAudioLyrics(item.guid).enqueue(new Callback<ApiResponse<AudioLyricsResponse>>() {
            @Override
            public void onResponse(Call<ApiResponse<AudioLyricsResponse>> call, Response<ApiResponse<AudioLyricsResponse>> resp) {
                if (resp.isSuccessful() && resp.body() != null && resp.body().data != null) {
                    lyricsCache.put(item.guid, resp.body().data);
                }
            }
            @Override
            public void onFailure(Call<ApiResponse<AudioLyricsResponse>> call, Throwable t) {}
        });
    }

    private void fetchLyrics(final PlayListItem item) {
        if (item == null) return;
        OfflineMusicManager.OfflineTrack off = OfflineMusicManager.getInstance(this).getOfflineTrack(item.guid);
        if (off != null) {
            AudioLyricsResponse offResp = new AudioLyricsResponse();
            offResp.guid = off.guid;
            offResp.title = off.title;
            offResp.artist = off.artist;
            offResp.album = off.album;
            offResp.duration = off.duration;
            offResp.lrc = off.lrc;
            applyLyricsResponse(item, offResp);
            if (off.lrc != null && !off.lrc.isEmpty()) return;
        }
        AudioLyricsResponse cached = lyricsCache.get(item.guid);
        if (cached != null) {
            applyLyricsResponse(item, cached);
            return;
        }
        if (FnApiManager.getInstance().getApi() == null) return;
        FnApiManager.getInstance().getApi().getAudioLyrics(item.guid).enqueue(new Callback<ApiResponse<AudioLyricsResponse>>() {
            @Override
            public void onResponse(Call<ApiResponse<AudioLyricsResponse>> call, Response<ApiResponse<AudioLyricsResponse>> resp) {
                if (resp.isSuccessful() && resp.body() != null && resp.body().data != null) {
                    lyricsCache.put(item.guid, resp.body().data);
                    applyLyricsResponse(item, resp.body().data);
                }
            }

            @Override
            public void onFailure(Call<ApiResponse<AudioLyricsResponse>> call, Throwable t) {
                Log.w(TAG, "Lyrics fetch failed: " + t.getMessage());
            }
        });
    }

    private void applyLyricsResponse(PlayListItem item, AudioLyricsResponse d) {
        PlayListItem cur = getCurrentItem();
        if (cur == null || !item.guid.equals(cur.guid)) return;
        if (d.artist != null && !d.artist.isEmpty()) currentArtist = d.artist;
        if (d.album != null && !d.album.isEmpty()) currentAlbum = d.album;
        currentLyrics = LyricsParser.parse(d.lrc);
        updateMediaSessionMetadata(cur);
        updateNotification();
        for (Listener l : listeners) {
            l.onLyricsLoaded(currentLyrics, currentArtist, currentAlbum);
        }
    }

    public void togglePlayPause() {
        if (player == null) return;
        if (player.isPlaying()) pause();
        else resume();
    }

    public void pause() {
        if (player != null) player.pause();
    }

    public void resume() {
        if (player == null) return;
        if (audioManager != null) {
            audioManager.requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);
        }
        player.play();
    }

    public void playNext(boolean fromEnded) {
        int size;
        synchronized (queue) {
            size = queue.size();
        }
        if (size == 0) return;

        if (fromEnded && playMode == MODE_LOOP_ONE) {
            playAt(currentIndex);
            return;
        }
        if (playMode == MODE_SHUFFLE && size > 1) {
            int next = nextShuffleIndex;
            if (next < 0 || next >= size || next == currentIndex) {
                next = random.nextInt(size);
                if (next == currentIndex) next = (next + 1) % size;
            }
            nextShuffleIndex = -1;
            playAt(next);
            return;
        }
        int next = (currentIndex + 1) % size;
        playAt(next);
    }

    public void playPrevious() {
        int size;
        synchronized (queue) {
            size = queue.size();
        }
        if (size == 0) return;
        if (player != null && player.getCurrentPosition() > 5000) {
            player.seekTo(0);
            return;
        }
        int prev = (currentIndex - 1 + size) % size;
        playAt(prev);
    }

    public void seekTo(long posMs) {
        if (player != null) player.seekTo(Math.max(0L, posMs));
    }

    public int cyclePlayMode() {
        playMode = (playMode + 1) % 3;
        return playMode;
    }

    public int getPlayMode() { return playMode; }

    public boolean isPlaying() {
        return player != null && player.isPlaying();
    }

    public long getCurrentPosition() {
        return player != null ? Math.max(0L, player.getCurrentPosition()) : 0L;
    }

    public long getDuration() {
        return player != null && player.getDuration() > 0 ? player.getDuration() : 0L;
    }

    public PlayListItem getCurrentItem() {
        synchronized (queue) {
            if (currentIndex >= 0 && currentIndex < queue.size()) {
                return queue.get(currentIndex);
            }
            return null;
        }
    }

    public List<PlayListItem> getQueue() {
        synchronized (queue) {
            return new ArrayList<>(queue);
        }
    }

    public int getCurrentIndex() { return currentIndex; }
    public LyricsParser.Result getCurrentLyrics() { return currentLyrics; }
    public String getCurrentArtist() { return currentArtist; }
    public String getCurrentAlbum() { return currentAlbum; }

    /** 返回悬浮窗所需的当前行与下一行歌词 */
    public String[] getFloatingLyricLines() {
        if (currentLyrics == null || currentLyrics.isEmpty()) {
            PlayListItem item = getCurrentItem();
            return new String[]{item != null ? item.title : "NASFun 音乐", currentArtist.isEmpty() ? "正在播放" : currentArtist};
        }
        if (currentLyrics.hasTimed()) {
            int idx = LyricsParser.lineIndexAt(currentLyrics.timed, getCurrentPosition());
            if (idx < 0) {
                return new String[]{currentLyrics.timed.get(0).text, currentLyrics.timed.size() > 1 ? currentLyrics.timed.get(1).text : ""};
            }
            String cur = currentLyrics.timed.get(idx).text;
            String nxt = (idx + 1 < currentLyrics.timed.size()) ? currentLyrics.timed.get(idx + 1).text : "";
            return new String[]{cur, nxt};
        }
        String first = currentLyrics.plainLines.get(0);
        String second = currentLyrics.plainLines.size() > 1 ? currentLyrics.plainLines.get(1) : "";
        return new String[]{first, second};
    }

    public void addListener(Listener l) {
        if (l != null) listeners.addIfAbsent(l);
    }

    public void removeListener(Listener l) {
        listeners.remove(l);
    }

    public void stopPlaybackAndSelf() {
        stopService(new Intent(this, FloatingLyricsService.class));
        if (player != null) player.stop();
        for (Listener l : listeners) l.onServiceStopped();
        stopForeground(true);
        stopSelf();
    }

    private void updateMediaSessionMetadata(PlayListItem item) {
        if (mediaSession == null || item == null) return;
        MediaMetadataCompat.Builder mb = new MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, item.title)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, currentArtist.isEmpty() ? "NASFun 音乐" : currentArtist)
                .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, currentAlbum);
        if (getDuration() > 0) {
            mb.putLong(MediaMetadataCompat.METADATA_KEY_DURATION, getDuration());
        }
        mediaSession.setMetadata(mb.build());
    }

    private void updateMediaSessionState(boolean playing) {
        if (mediaSession == null) return;
        int state = playing ? PlaybackStateCompat.STATE_PLAYING : PlaybackStateCompat.STATE_PAUSED;
        long actions = PlaybackStateCompat.ACTION_PLAY | PlaybackStateCompat.ACTION_PAUSE
                | PlaybackStateCompat.ACTION_PLAY_PAUSE | PlaybackStateCompat.ACTION_SKIP_TO_NEXT
                | PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS | PlaybackStateCompat.ACTION_SEEK_TO;
        mediaSession.setPlaybackState(new PlaybackStateCompat.Builder()
                .setActions(actions)
                .setState(state, getCurrentPosition(), 1.0f)
                .build());
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "NASFun 音乐播放", NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) nm.createNotificationChannel(ch);
        }
    }

    private void updateNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(NOTIF_ID, buildNotification());
    }

    private Notification buildNotification() {
        int piFlags = Build.VERSION.SDK_INT >= 23
                ? PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
                : PendingIntent.FLAG_UPDATE_CURRENT;

        Intent openIntent = new Intent(this, MusicPlayerActivity.class);
        PendingIntent contentPi = PendingIntent.getActivity(this, 0, openIntent, piFlags);

        PendingIntent prevPi = PendingIntent.getService(this, 1, new Intent(this, MusicPlaybackService.class).setAction(ACTION_PREV), piFlags);
        PendingIntent togglePi = PendingIntent.getService(this, 2, new Intent(this, MusicPlaybackService.class).setAction(ACTION_TOGGLE), piFlags);
        PendingIntent nextPi = PendingIntent.getService(this, 3, new Intent(this, MusicPlaybackService.class).setAction(ACTION_NEXT), piFlags);
        PendingIntent stopPi = PendingIntent.getService(this, 4, new Intent(this, MusicPlaybackService.class).setAction(ACTION_STOP), piFlags);

        PlayListItem item = getCurrentItem();
        String title = item != null ? item.title : "NASFun 音乐";
        String sub = currentArtist.isEmpty() ? "正在播放 NAS 音乐" : currentArtist;

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle(title)
                .setContentText(sub)
                .setContentIntent(contentPi)
                .setOngoing(isPlaying())
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .addAction(android.R.drawable.ic_media_previous, "上一首", prevPi)
                .addAction(isPlaying() ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play,
                        isPlaying() ? "暂停" : "播放", togglePi)
                .addAction(android.R.drawable.ic_media_next, "下一首", nextPi)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "停止", stopPi)
                .build();
    }

    @Override
    public void onDestroy() {
        instance = null;
        if (audioManager != null) audioManager.abandonAudioFocus(focusListener);
        if (wakeLock != null && wakeLock.isHeld()) {
            try { wakeLock.release(); } catch (Exception ignored) {}
        }
        if (mediaSession != null) mediaSession.release();
        if (player != null) player.release();
        prefetchGeneration.incrementAndGet();
        CacheWriter w = activeCacheWriter;
        if (w != null) {
            try { w.cancel(); } catch (Exception ignored) {}
        }
        prefetchExecutor.shutdownNow();
        if (musicCache != null) {
            try { musicCache.release(); } catch (Exception ignored) {}
        }
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
