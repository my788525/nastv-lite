package com.fntv.app;

import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import com.fntv.app.api.FnApiManager;
import com.fntv.app.api.model.*;
import com.fntv.app.util.MediaSortHelper;
import com.fntv.app.util.PinyinUtils;
import com.fntv.app.util.SimpleImageLoader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Stack;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

public class HomeActivity extends AppCompatActivity implements MusicPlaybackService.Listener {

    private Button tabMovies, tabMusic, tabLibrary, tabFiles, tabSettings;
    private View panelMovies, panelMusic, panelLibrary, panelFiles, panelSettings;
    private LinearLayout musicHubContainer;
    private Button btnTopBack, btnTopNowPlaying;
    private View miniPlayerBar, miniPlayerInfo;
    private ProgressBar miniProgressBar;
    private ImageView ivMiniCover;
    private TextView tvMiniTitle, tvMiniSub;
    private Button btnMiniPrev, btnMiniPlayPause, btnMiniNext, btnMiniStop;
    private final Handler miniHandler = new Handler(Looper.getMainLooper());
    private final Runnable miniProgressTask = new Runnable() {
        @Override
        public void run() {
            MusicPlaybackService ms = MusicPlaybackService.getInstance();
            if (ms != null && miniProgressBar != null && ms.getCurrentItem() != null) {
                long dur = ms.getDuration();
                long pos = ms.getCurrentPosition();
                if (dur > 0) {
                    miniProgressBar.setProgress((int) Math.min(1000L, Math.max(0L, pos * 1000L / dur)));
                }
                miniHandler.postDelayed(this, 500);
            }
        }
    };
    private boolean musicHubBuilt = false;
    private String selectedMusicLibGuid = null;
    private boolean pendingNavRestore = false;
    private RemoteFileBrowser remoteFileBrowser;
    private LinearLayout moviesContainer, libraryContainer;
    /** 详情页渲染目标容器：影视标签用 moviesContainer；媒体库标签内（如文件夹兜底）用 libraryContainer，
     *  避免 buildDetailPage 把详情渲染进被 GONE 的 moviesContainer 导致黑屏。 */
    private LinearLayout detailContainer;
    private boolean detailInLibrary = false;
    /** 媒体库/文件夹浏览请求的全部子项类型。
     *  注意：isFolder() 认 "Directory"/"Folder"/"folder"，但旧浏览请求 tags.type 只含 "Directory"，
     *  若 FnOS 把子文件夹返回为 type "Folder" 会被过滤→空列表→误走 getPlayInfo 兜底只显示首个视频。
     *  此处补全 Folder/folder 及 Season/Collection，避免漏类型导致返回空。 */
    private static final List<String> BROWSE_TYPES = Arrays.asList(
            "Movie", "TV", "Directory", "Video", "Episode", "Season", "Collection", "Folder", "folder");
    private TextView tvMoviesLoading, tvLibraryLoading, tvLibraryEmpty;
    private EditText etSearch;
    private TextView tvSection;
    private boolean isSearching = false;
    private TextView tvSettingUsername, tvSettingServer, tvDecoderValue;
    private Button btnLogout;
    private UpdateManager updateManager;
    private RelativeLayout rlDecoderSetting, rlSeekStep;
    private TextView tvSeekStepValue;

    // 影视菜单与列表视图模式设置
    public static final String PREF_VIEW_MODE = "pref_view_mode"; // "list" 或 "grid"，默认 "list"
    public static final String PREF_SORT_FIELD = "pref_sort_field"; // 0=名称, 1=修改时间, 2=创建时间
    public static final String PREF_SORT_ORDER = "pref_sort_order"; // 0=正序(ASC), 1=反序(DESC)
    public static final String PREF_FOLDERS_FIRST = "pref_folders_first"; // 默认 true
    public static final String PREF_FOLDER_HIERARCHY = "pref_folder_hierarchy"; // 真实文件夹路径层级浏览，默认 true

    // 播放列表播放模式
    public static final int PLAY_MODE_SEQUENTIAL = 0; // 顺序播放 (从头开始)
    public static final int PLAY_MODE_RESUME = 1;     // 继续播放 (上次播放列表与进度)
    public static final int PLAY_MODE_SHUFFLE = 2;    // 随机播放 (随机打乱顺序)

    private RelativeLayout rlViewModeSetting, rlSortFieldSetting, rlSortOrderSetting, rlFoldersFirstSetting, rlFolderHierarchySetting;
    private TextView tvViewModeValue, tvSortFieldValue, tvSortOrderValue, tvFoldersFirstValue, tvFolderHierarchyValue;

    private RelativeLayout rlPlayModeSetting, rlScreenOffSetting, rlAutoResumeSetting, rlPinyinSearchSetting,
            rlMenuPanelSetting, rlDpadAccelSetting, rlSleepTimerSetting, rlVocalBoostSetting,
            rlDefaultQualitySetting, rlBufferProfileSetting, rlWeakNetRetrySetting,
            rlVideoCodecSetting, rlHlsContainerSetting, rlHlsSegSetting, rlHttpGzipSetting,
            rlVideoCacheSetting, rlHlsPrefetchSetting, rlServerHwDecodeSetting,
            rlMediaStyleNotifSetting, rlMusicCacheSetting, rlLowBwImgSetting, rlNetAutoResumeSetting;
    private TextView tvPlayModeValue, tvScreenOffValue, tvAutoResumeValue, tvPinyinSearchValue,
            tvMenuPanelValue, tvDpadAccelValue, tvSleepTimerValue, tvVocalBoostValue,
            tvDefaultQualityValue, tvBufferProfileValue, tvWeakNetRetryValue,
            tvVideoCodecValue, tvHlsContainerValue, tvHlsSegValue, tvHttpGzipValue,
            tvVideoCacheValue, tvHlsPrefetchValue, tvServerHwDecodeValue,
            tvMediaStyleNotifValue, tvMusicCacheValue, tvLowBwImgValue, tvNetAutoResumeValue;
    private boolean hasAutoResumedOnStartup = false;
    private final Map<String, String> libModeOverrides = new HashMap<>();

    public static class FolderNavState {
        public String guid;
        public String title;
        public String singleChildGuid;
        public boolean parentIsFolder;
        public List<PlayListItem> cachedList;
        public FolderNavState(String guid, String title, String singleChildGuid, boolean parentIsFolder, List<PlayListItem> list) {
            this.guid = guid;
            this.title = title;
            this.singleChildGuid = singleChildGuid;
            this.parentIsFolder = parentIsFolder;
            this.cachedList = list;
        }
    }
    private final Stack<FolderNavState> folderNavStack = new Stack<>();

    public boolean isListViewMode() {
        return "list".equals(prefs != null ? prefs.getString(PREF_VIEW_MODE, "list") : "list");
    }

    public int getSortField() {
        return prefs != null ? prefs.getInt(PREF_SORT_FIELD, MediaSortHelper.SORT_MODIFY_TIME) : MediaSortHelper.SORT_MODIFY_TIME;
    }

    public int getSortOrder() {
        return prefs != null ? prefs.getInt(PREF_SORT_ORDER, MediaSortHelper.ORDER_DESC) : MediaSortHelper.ORDER_DESC;
    }

    public boolean isFoldersFirst() {
        return prefs != null ? prefs.getBoolean(PREF_FOLDERS_FIRST, true) : true;
    }

    public boolean isFolderHierarchyMode() {
        return prefs != null ? prefs.getBoolean(PREF_FOLDER_HIERARCHY, true) : true;
    }

    private int currentTab = 0;
    private final List<MediaDbItem> mediaLibraries = new ArrayList<>();
    private boolean showingOverview = true;
    private boolean showingEpisodes = false;
    private boolean showingDetail = false;
    private boolean loadingPreviews = false;

    // 媒体库浏览排序状态
    private String currentBrowseGuid;
    private String currentBrowseTitle;
    private LinearLayout currentBrowseContainer;
    private boolean browseListViewMode = true;             // 默认采用列表视图
    private boolean currentBrowseParentIsFolder = false;   // 当前浏览父级是否为文件夹型
    private TextView currentBrowseLoading;
    private String currentBrowseSingleChildGuid;
    private int libSortColumnIndex = 0; // 0=添加日期, 1=发行日期
    private int libSortOrderIndex = 1;  // 0=升序, 1=降序

    private FnApiManager apiManager;
    private String baseUrl = "";
    private SharedPreferences prefs;
    private static final String PREF_DECODER = "decoder_mode";

    private long t0;
    private boolean overviewBuilt = false;
    private long backPressedTime = 0;

    // 横竖屏切换时保存的页面状态
    private String savedBrowseGuid, savedBrowseTitle;
    private List<PlayListItem> savedBrowseList;
    private PlayListItem savedDetailItem;
    private PlayInfoResponse savedDetailInfo;
    private boolean browseFromLibrary;
    private String savedLiveChannelTitle;

    @Override

    public void onConfigurationChanged(android.content.res.Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        // 车机在 主屏/副驾屏/分屏 之间移动时，重新适配目标屏方向
        OrientationHelper.adaptOrientation(this);
        if (savedDetailItem != null) {
            buildDetailPage(savedDetailItem, savedDetailInfo);
        } else if (savedBrowseList != null) {
            if (browseFromLibrary) { renderBrowseGrid(savedBrowseList, savedBrowseTitle); } else { renderGridInContainer(savedBrowseList, savedBrowseTitle, moviesContainer); }
        } else if (currentTab == 1 && savedBrowseGuid != null) {
            browseItemsInContainer(savedBrowseGuid, savedBrowseTitle,
                    libraryContainer, tvLibraryLoading, currentBrowseSingleChildGuid,
                    currentBrowseParentIsFolder);
        } else if (currentTab == 0) {
            loadingPreviews = false;
            overviewBuilt = false;
            showOverview();
            loadAllPreviews();
            loadLiveChannels();
        }
    }

    @Override

    protected void onCreate(Bundle savedInstanceState) {
        t0 = System.currentTimeMillis();
        super.onCreate(savedInstanceState);
        CrashGuard.install(this);
        setContentView(R.layout.activity_home);

        // 比亚迪车机：启动即适配当前屏横竖屏状态，不触发系统旋转
        OrientationHelper.adaptOrientation(this);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            getWindow().setStatusBarColor(Color.TRANSPARENT);
            getWindow().setNavigationBarColor(0xFF1A1A1A);
        }

        prefs = getSharedPreferences("fntv_prefs", MODE_PRIVATE);
        apiManager = FnApiManager.getInstance();
        baseUrl = prefs.getString("host", "").replaceAll("/+$", "");
        if (apiManager.getApi() == null && !baseUrl.isEmpty()) {
            apiManager.updateBaseUrl(baseUrl);
        }
        if (apiManager.getToken() == null || apiManager.getToken().isEmpty()) {
            String savedTok = prefs.getString("auth_token", "");
            if (!savedTok.isEmpty()) apiManager.setToken(savedTok);
        }
        // 切换服务器时清空观看记录
        String lastHost = prefs.getString("last_host", "");
        if (!lastHost.equals(baseUrl) && !lastHost.isEmpty()) {
            prefs.edit().remove("watch_history").putString("last_host", baseUrl).apply();
        } else if (lastHost.isEmpty() && !baseUrl.isEmpty()) {
            prefs.edit().putString("last_host", baseUrl).apply();
        }
        initViews();
        setupTabs();
        setupSettings();
        setupLogout();
        updateManager.setup();
        setupSearch();

        int restoredTab = prefs.getInt("nav_last_tab", 0);
        if (restoredTab < 0 || restoredTab > 4) restoredTab = 0;
        String savedNavGuid = prefs.getString("nav_lib_guid", "");
        pendingNavRestore = !savedNavGuid.isEmpty() && (restoredTab == 0 || restoredTab == 1);
        switchTab(restoredTab);
        tvMoviesLoading.setVisibility(View.VISIBLE);
        tvMoviesLoading.setText("正在加载媒体库...");
        loadOverview();
    }


    private void initViews() {
        tabMovies = findViewById(R.id.tabMovies);
        tabMusic = findViewById(R.id.tabMusic);
        tabLibrary = findViewById(R.id.tabLibrary);
        tabFiles = findViewById(R.id.tabFiles);
        tabSettings = findViewById(R.id.tabSettings);
        panelMovies = findViewById(R.id.panelMovies);
        panelMusic = findViewById(R.id.panelMusic);
        panelLibrary = findViewById(R.id.panelLibrary);
        panelFiles = findViewById(R.id.panelFiles);
        panelSettings = findViewById(R.id.panelSettings);
        musicHubContainer = findViewById(R.id.musicHubContainer);
        btnTopBack = findViewById(R.id.btnTopBack);
        btnTopNowPlaying = findViewById(R.id.btnTopNowPlaying);
        miniPlayerBar = findViewById(R.id.miniPlayerBar);
        miniPlayerInfo = findViewById(R.id.miniPlayerInfo);
        miniProgressBar = findViewById(R.id.miniProgressBar);
        ivMiniCover = findViewById(R.id.ivMiniCover);
        tvMiniTitle = findViewById(R.id.tvMiniTitle);
        tvMiniSub = findViewById(R.id.tvMiniSub);
        btnMiniPrev = findViewById(R.id.btnMiniPrev);
        btnMiniPlayPause = findViewById(R.id.btnMiniPlayPause);
        btnMiniNext = findViewById(R.id.btnMiniNext);
        btnMiniStop = findViewById(R.id.btnMiniStop);
        setupTopAndMiniPlayer();
        remoteFileBrowser = new RemoteFileBrowser(
                this,
                baseUrl,
                findViewById(R.id.filesListContainer),
                findViewById(R.id.tvFilesPath),
                findViewById(R.id.btnFilesUp),
                findViewById(R.id.btnFilesRefresh)
        );
        remoteFileBrowser.setOnPathChangedListener(this::updateTopBarState);
        moviesContainer = findViewById(R.id.moviesGridContainer);
        libraryContainer = findViewById(R.id.libraryGridContainer);
        detailContainer = moviesContainer;
        etSearch = findViewById(R.id.etSearch);
        tvMoviesLoading = findViewById(R.id.tvMoviesLoading);
        tvLibraryLoading = findViewById(R.id.tvLibraryLoading);
        tvLibraryEmpty = findViewById(R.id.tvLibraryEmpty);
        tvSection = findViewById(R.id.tvSection);
        tvSettingUsername = findViewById(R.id.tvSettingUsername);
        tvSettingServer = findViewById(R.id.tvSettingServer);
        tvDecoderValue = findViewById(R.id.tvDecoderValue);
        btnLogout = findViewById(R.id.btnLogout);
        Button btnCheckUpdate = findViewById(R.id.btnCheckUpdate);
        updateManager = new UpdateManager(this, btnCheckUpdate, BuildConfig.VERSION_CODE);
        rlDecoderSetting = findViewById(R.id.rlDecoderSetting);
        rlSeekStep = findViewById(R.id.rlSeekStep);
        tvSeekStepValue = findViewById(R.id.tvSeekStepValue);
        tvSettingServer.setText(prefs.getString("host", ""));

        rlViewModeSetting = findViewById(R.id.rlViewModeSetting);
        tvViewModeValue = findViewById(R.id.tvViewModeValue);
        rlSortFieldSetting = findViewById(R.id.rlSortFieldSetting);
        tvSortFieldValue = findViewById(R.id.tvSortFieldValue);
        rlSortOrderSetting = findViewById(R.id.rlSortOrderSetting);
        tvSortOrderValue = findViewById(R.id.tvSortOrderValue);
        rlFoldersFirstSetting = findViewById(R.id.rlFoldersFirstSetting);
        tvFoldersFirstValue = findViewById(R.id.tvFoldersFirstValue);
        rlFolderHierarchySetting = findViewById(R.id.rlFolderHierarchySetting);
        tvFolderHierarchyValue = findViewById(R.id.tvFolderHierarchyValue);

        rlPlayModeSetting = findViewById(R.id.rlPlayModeSetting);
        tvPlayModeValue = findViewById(R.id.tvPlayModeValue);
        rlScreenOffSetting = findViewById(R.id.rlScreenOffSetting);
        tvScreenOffValue = findViewById(R.id.tvScreenOffValue);
        rlAutoResumeSetting = findViewById(R.id.rlAutoResumeSetting);
        tvAutoResumeValue = findViewById(R.id.tvAutoResumeValue);
        rlPinyinSearchSetting = findViewById(R.id.rlPinyinSearchSetting);
        tvPinyinSearchValue = findViewById(R.id.tvPinyinSearchValue);
        rlMenuPanelSetting = findViewById(R.id.rlMenuPanelSetting);
        tvMenuPanelValue = findViewById(R.id.tvMenuPanelValue);
        rlDpadAccelSetting = findViewById(R.id.rlDpadAccelSetting);
        tvDpadAccelValue = findViewById(R.id.tvDpadAccelValue);
        rlSleepTimerSetting = findViewById(R.id.rlSleepTimerSetting);
        tvSleepTimerValue = findViewById(R.id.tvSleepTimerValue);
        rlVocalBoostSetting = findViewById(R.id.rlVocalBoostSetting);
        tvVocalBoostValue = findViewById(R.id.tvVocalBoostValue);
        rlDefaultQualitySetting = findViewById(R.id.rlDefaultQualitySetting);
        tvDefaultQualityValue = findViewById(R.id.tvDefaultQualityValue);
        rlBufferProfileSetting = findViewById(R.id.rlBufferProfileSetting);
        tvBufferProfileValue = findViewById(R.id.tvBufferProfileValue);
        rlWeakNetRetrySetting = findViewById(R.id.rlWeakNetRetrySetting);
        tvWeakNetRetryValue = findViewById(R.id.tvWeakNetRetryValue);
        rlVideoCodecSetting = findViewById(R.id.rlVideoCodecSetting);
        tvVideoCodecValue = findViewById(R.id.tvVideoCodecValue);
        rlHlsContainerSetting = findViewById(R.id.rlHlsContainerSetting);
        tvHlsContainerValue = findViewById(R.id.tvHlsContainerValue);
        rlHlsSegSetting = findViewById(R.id.rlHlsSegSetting);
        tvHlsSegValue = findViewById(R.id.tvHlsSegValue);
        rlHttpGzipSetting = findViewById(R.id.rlHttpGzipSetting);
        tvHttpGzipValue = findViewById(R.id.tvHttpGzipValue);
        rlVideoCacheSetting = findViewById(R.id.rlVideoCacheSetting);
        tvVideoCacheValue = findViewById(R.id.tvVideoCacheValue);
        rlHlsPrefetchSetting = findViewById(R.id.rlHlsPrefetchSetting);
        tvHlsPrefetchValue = findViewById(R.id.tvHlsPrefetchValue);
        rlServerHwDecodeSetting = findViewById(R.id.rlServerHwDecodeSetting);
        tvServerHwDecodeValue = findViewById(R.id.tvServerHwDecodeValue);
        rlMediaStyleNotifSetting = findViewById(R.id.rlMediaStyleNotifSetting);
        tvMediaStyleNotifValue = findViewById(R.id.tvMediaStyleNotifValue);
        rlMusicCacheSetting = findViewById(R.id.rlMusicCacheSetting);
        tvMusicCacheValue = findViewById(R.id.tvMusicCacheValue);
        rlLowBwImgSetting = findViewById(R.id.rlLowBwImgSetting);
        tvLowBwImgValue = findViewById(R.id.tvLowBwImgValue);
        rlNetAutoResumeSetting = findViewById(R.id.rlNetAutoResumeSetting);
        tvNetAutoResumeValue = findViewById(R.id.tvNetAutoResumeValue);
        SimpleImageLoader.lowBandwidthMode = prefs.getBoolean("pref_low_bw_img", true);
        com.fntv.app.api.AuthInterceptor.enableGzip = prefs.getBoolean("pref_http_gzip", true);

        TextView tvVersion = findViewById(R.id.tvVersionName);
        try {
            tvVersion.setText("NASFun v" + getPackageManager().getPackageInfo(getPackageName(), 0).versionName);
        } catch (Exception ignored) {}
    }


    // ======================== Tab ========================

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        updateMiniPlayer();
    }

    @Override
    protected void onResume() {
        super.onResume();
        CrashGuard.showIfCrashed(this);
        MusicPlaybackService ms = MusicPlaybackService.getInstance();
        if (ms != null) ms.addListener(this);
        updateMiniPlayer();
        updateTopBarState();
        miniHandler.removeCallbacks(miniProgressTask);
        if (ms != null && ms.getCurrentItem() != null) {
            miniHandler.post(miniProgressTask);
        }
        // 仅在容器为空时才重建，避免从视频/歌曲退出时重置滚动条与当前浏览位置
        if (savedDetailItem != null) {
            if (detailContainer == null || detailContainer.getChildCount() == 0) {
                buildDetailPage(savedDetailItem, savedDetailInfo);
            }
        } else if (currentTab == 0 && !mediaLibraries.isEmpty() && overviewBuilt && showingOverview) {
            loadContinueWatching();
        } else if (currentTab == 0 && !overviewBuilt && showingOverview && mediaLibraries.isEmpty()) {
            loadOverview();
        } else if (currentTab == 4) {
            if (!musicHubBuilt && !mediaLibraries.isEmpty()) {
                buildMusicHub();
            } else {
                refreshMusicHubHighlight();
            }
        } else if (mediaLibraries.isEmpty()) {
            if (currentTab == 1) loadMediaLibraries();
            else loadOverview();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        miniHandler.removeCallbacks(miniProgressTask);
        MusicPlaybackService ms = MusicPlaybackService.getInstance();
        if (ms != null) ms.removeListener(this);
        saveNavState();
    }


    private void setupTabs() {
        tabMovies.setOnClickListener(v -> {
            if (currentTab == 0 && !showingOverview) {
                showOverview();
                loadAllPreviews();
                loadContinueWatching();
                loadLiveChannels();
            } else {
                switchTab(0);
            }
        });
        if (tabMusic != null) tabMusic.setOnClickListener(v -> switchTab(4));
        tabLibrary.setOnClickListener(v -> {
            boolean alreadyHere = (currentTab == 1);
            switchTab(1);
            if (alreadyHere || (savedBrowseGuid == null && libraryContainer.getChildCount() <= 2)) {
                savedBrowseGuid = null;
                savedBrowseList = null;
                folderNavStack.clear();
                loadMediaLibraries();
            }
        });
        if (tabFiles != null) tabFiles.setOnClickListener(v -> switchTab(2));
        tabSettings.setOnClickListener(v -> switchTab(3));
    }


    private void switchTab(int index) {
        int prevTab = currentTab;
        currentTab = index;
        // 从媒体库切换到其他标签时仅清除搜索框焦点，不丢失浏览路径
        if (prevTab == 1 && isSearching) clearSearch();
        if (prefs != null) prefs.edit().putInt("nav_last_tab", index).apply();
        panelMovies.setVisibility(index == 0 ? View.VISIBLE : View.GONE);
        if (panelMusic != null) panelMusic.setVisibility(index == 4 ? View.VISIBLE : View.GONE);
        panelLibrary.setVisibility(index == 1 ? View.VISIBLE : View.GONE);
        if (panelFiles != null) panelFiles.setVisibility(index == 2 ? View.VISIBLE : View.GONE);
        panelSettings.setVisibility(index == 3 ? View.VISIBLE : View.GONE);
        tabMovies.setSelected(index == 0);
        if (tabMusic != null) tabMusic.setSelected(index == 4);
        tabLibrary.setSelected(index == 1);
        if (tabFiles != null) tabFiles.setSelected(index == 2);
        tabSettings.setSelected(index == 3);
        updateTopBarState();
        if (index == 0) tabMovies.requestFocus();
        else if (index == 4) {
            if (tabMusic != null) tabMusic.requestFocus();
            if (!musicHubBuilt) buildMusicHub();
            else refreshMusicHubHighlight();
        } else if (index == 1) {
            tabLibrary.requestFocus();
            if (libraryContainer.getChildCount() <= 2 && savedBrowseGuid == null) {
                loadMediaLibraries();
            }
        } else if (index == 2) {
            if (tabFiles != null) tabFiles.requestFocus();
            if (remoteFileBrowser != null) remoteFileBrowser.ensureLoaded();
        } else tabSettings.requestFocus();
    }


    // ==================== 影视概览 ====================


    private void loadOverview() {
        Log.d("Overview", "loadOverview start  t=" + (System.currentTimeMillis() - t0) + "ms");
        showingOverview = true;
        showingDetail = false;
        showingEpisodes = false;
        overviewBuilt = false;
        loadingPreviews = false;
        tvMoviesLoading.setVisibility(View.VISIBLE);

        final int[] retryCount = {1};
        apiManager.getApi().getMediaDbList().enqueue(new Callback<ApiResponse<List<MediaDbItem>>>() {
            @Override
            public void onResponse(Call<ApiResponse<List<MediaDbItem>>> call,
                                   Response<ApiResponse<List<MediaDbItem>>> response) {
                String bodyStr = response.body() != null ? "code=" + response.body().code + " msg=" + response.body().msg + " data=" + (response.body().data != null ? response.body().data.size() + "条" : "null") : "nullBody";
                Log.d("Overview", "getMediaDbList resp code=" + response.code() + " " + bodyStr + " t=" + (System.currentTimeMillis() - t0) + "ms");
                if (response.body() != null && response.body().code != 0) {
                    try { Log.w("Overview", "错误响应: " + new com.google.gson.Gson().toJson(response.body())); } catch (Exception ignored) {}
                }
                // Auth Failed 时重试一次
                if (response.body() != null && response.body().code == -2 && retryCount[0] > 0) {
                    retryCount[0]--;
                    Log.d("Overview", "Auth Failed，重试中...");
                    call.clone().enqueue(this);
                    return;
                }
                if (response.isSuccessful() && response.body() != null && response.body().code == 0
                        && response.body().data != null && !response.body().data.isEmpty()) {
                    mediaLibraries.clear();
                    for (MediaDbItem lib : response.body().data) {
                        if (!lib.refreshDisabled) mediaLibraries.add(lib);
                    }
                    Log.d("Overview", "loaded " + mediaLibraries.size() + " libraries");
                    showOverview();
                    loadAllPreviews();
                    loadContinueWatching();
                    loadLiveChannels();
                    if (currentTab == 4 && !musicHubBuilt) {
                        buildMusicHub();
                    }
                    if (pendingNavRestore) {
                        pendingNavRestore = false;
                        restoreNavState();
                    }
                } else {
                    tvMoviesLoading.setVisibility(View.GONE);
                }
            }
            @Override
            public void onFailure(Call<ApiResponse<List<MediaDbItem>>> call, Throwable t) {
                tvMoviesLoading.setVisibility(View.GONE);
                Log.e("Overview", "getMediaDbList onFailure: " + t.getMessage() + " t=" + (System.currentTimeMillis() - t0) + "ms");
            }
        });
    }

    /** 构建概览 */
    private void showOverview() {
        tvMoviesLoading.setVisibility(View.GONE);
        savedDetailItem = null; savedDetailInfo = null; savedBrowseList = null; savedBrowseGuid = null;
        savedLiveChannelTitle = null;
        folderNavStack.clear();
        showingEpisodes = false;
        moviesContainer.removeAllViews();
        showingOverview = true;
        showingDetail = false;
        showingEpisodes = false;
        overviewBuilt = true;
        updateTopBarState();

        if (isListViewMode()) {
            renderOverviewList();
            return;
        }

        // 继续观看（最顶部，占位，稍后由 loadContinueWatching 填充）
        LinearLayout continueWatchingBox = new LinearLayout(this);
        continueWatchingBox.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        continueWatchingBox.setOrientation(LinearLayout.VERTICAL);
        continueWatchingBox.setTag("continue_watching");
        moviesContainer.addView(continueWatchingBox);

        // 各媒体库：标题 + 预览容器
        java.util.ArrayList<Integer> viewAllIds = new java.util.ArrayList<>();
        for (MediaDbItem lib : mediaLibraries) {
            LinearLayout headerRow = makeLibHeader(lib.guid, lib.title, 0);
            moviesContainer.addView(headerRow);
            for (int ci = 0; ci < headerRow.getChildCount(); ci++) {
                View child = headerRow.getChildAt(ci);
                if (child instanceof Button && child.isFocusable()) {
                    viewAllIds.add(child.getId());
                }
            }
            // 预览卡片容器（暂空，loadAllPreviews 后填充）
            LinearLayout previewBox = new LinearLayout(this);
            previewBox.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            previewBox.setOrientation(LinearLayout.VERTICAL);
            previewBox.setPadding(6, 0, 6, 0);
            previewBox.setTag("preview_" + lib.guid);
            moviesContainer.addView(previewBox);
            moviesContainer.addView(makeSpacer(16));
        }

        if (moviesContainer.getChildCount() == 0) {
            TextView e = new TextView(this);
            e.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 120));
            e.setGravity(Gravity.CENTER);
            e.setText("暂无影视内容");
            e.setTextColor(0xFF808080);
            e.setTextSize(14);
            moviesContainer.addView(e);
        }
    }

    /** 列表模式下的概览：以文件夹列表展示所有媒体库分类 */
    private void renderOverviewList() {
        moviesContainer.removeAllViews();
        showingOverview = true;
        showingDetail = false;
        showingEpisodes = false;
        overviewBuilt = true;
        updateTopBarState();

        // 顶部控制栏
        LinearLayout topBar = new LinearLayout(this);
        topBar.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setBackgroundResource(R.drawable.bg_input);
        topBar.setPadding(16, 12, 16, 12);

        TextView titleTv = new TextView(this);
        titleTv.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        titleTv.setText("📁 影视分类 (" + mediaLibraries.size() + " 个媒体库)");
        titleTv.setTextColor(0xFFEEEEEE);
        titleTv.setTextSize(15);
        titleTv.setTypeface(Typeface.DEFAULT_BOLD);
        topBar.addView(titleTv);

        // 排序方式快捷按钮
        Button sortFieldBtn = new Button(this);
        sortFieldBtn.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, (int)(38 * getResources().getDisplayMetrics().density)));
        sortFieldBtn.setBackgroundResource(R.drawable.bg_btn_primary);
        sortFieldBtn.setText(MediaSortHelper.SORT_FIELD_LABELS[getSortField()] + " ▾");
        sortFieldBtn.setTextColor(0xFFEEEEEE);
        sortFieldBtn.setTextSize(12);
        sortFieldBtn.setFocusable(true);
        sortFieldBtn.setPadding(16, 0, 16, 0);
        sortFieldBtn.setOnClickListener(v -> showSortFieldDialog());
        topBar.addView(sortFieldBtn);

        View sp1 = new View(this);
        sp1.setLayoutParams(new LinearLayout.LayoutParams(12, 1));
        topBar.addView(sp1);

        // 升降序快捷按钮
        Button sortOrderBtn = new Button(this);
        sortOrderBtn.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, (int)(38 * getResources().getDisplayMetrics().density)));
        sortOrderBtn.setBackgroundResource(R.drawable.bg_btn_primary);
        sortOrderBtn.setText(getSortOrder() == MediaSortHelper.ORDER_ASC ? "正序 ▴" : "反序 ▾");
        sortOrderBtn.setTextColor(0xFFEEEEEE);
        sortOrderBtn.setTextSize(12);
        sortOrderBtn.setFocusable(true);
        sortOrderBtn.setPadding(16, 0, 16, 0);
        sortOrderBtn.setOnClickListener(v -> toggleSortOrder());
        topBar.addView(sortOrderBtn);

        View sp2 = new View(this);
        sp2.setLayoutParams(new LinearLayout.LayoutParams(12, 1));
        topBar.addView(sp2);

        // 视图切换按钮
        Button viewToggleBtn = new Button(this);
        viewToggleBtn.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, (int)(38 * getResources().getDisplayMetrics().density)));
        viewToggleBtn.setBackgroundResource(R.drawable.bg_btn_primary);
        viewToggleBtn.setText(isListViewMode() ? "📁 列表视图" : "▦ 海报视图");
        viewToggleBtn.setTextColor(0xFFEEEEEE);
        viewToggleBtn.setTextSize(12);
        viewToggleBtn.setFocusable(true);
        viewToggleBtn.setPadding(16, 0, 16, 0);
        viewToggleBtn.setOnClickListener(v -> toggleViewMode());
        topBar.addView(viewToggleBtn);

        moviesContainer.addView(topBar);
        moviesContainer.addView(makeSpacer(8));

        // 继续观看占位
        LinearLayout continueBox = new LinearLayout(this);
        continueBox.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        continueBox.setOrientation(LinearLayout.VERTICAL);
        continueBox.setTag("continue_watching");
        moviesContainer.addView(continueBox);

        // 媒体库文件夹列表（影视模式自动过滤纯音乐库，保持影院界面纯粹）
        for (MediaDbItem lib : mediaLibraries) {
            if (isMusicLibrary(lib)) continue;
            moviesContainer.addView(makeLibFolderRow(lib));
            moviesContainer.addView(makeSpacer(6));
        }

        if (mediaLibraries.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 120));
            empty.setGravity(Gravity.CENTER);
            empty.setText("暂无媒体库分类");
            empty.setTextColor(0xFF808080);
            empty.setTextSize(14);
            moviesContainer.addView(empty);
        }
    }

    private View makeLibFolderRow(final MediaDbItem lib) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setBackgroundResource(R.drawable.bg_media_card);
        row.setFocusable(true);
        row.setClickable(true);
        row.setMinimumHeight((int) (58 * getResources().getDisplayMetrics().density));
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(16, 12, 16, 12);

        TextView icon = new TextView(this);
        icon.setText("📁");
        icon.setTextSize(20);
        icon.setPadding(0, 0, 16, 0);
        row.addView(icon);

        LinearLayout textCol = new LinearLayout(this);
        textCol.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        textCol.setOrientation(LinearLayout.VERTICAL);

        TextView title = new TextView(this);
        title.setText(lib.title != null ? lib.title : "未命名分类");
        title.setTextSize(16);
        title.setTextColor(0xFFEEEEEE);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        textCol.addView(title);

        TextView sub = new TextView(this);
        sub.setText("媒体库文件夹 · 类型: " + (lib.category != null ? lib.category : "综合"));
        sub.setTextSize(12);
        sub.setTextColor(0xFF808080);
        textCol.addView(sub);

        row.addView(textCol);

        TextView arrow = new TextView(this);
        arrow.setText("进入 ›");
        arrow.setTextSize(13);
        arrow.setTextColor(0xFF81C784);
        arrow.setPadding(12, 0, 4, 0);
        row.addView(arrow);

        row.setOnFocusChangeListener((v, hasFocus) -> {
            row.setScaleX(hasFocus ? 1.015f : 1.0f);
            row.setScaleY(hasFocus ? 1.015f : 1.0f);
            row.setBackgroundResource(hasFocus ? R.drawable.bg_input : R.drawable.bg_media_card);
        });

        row.setOnClickListener(v -> browseItems(lib.guid, lib.title));
        final MediaDbItem libItem = lib;
        row.setOnLongClickListener(v -> {
            showLibActionDialog(libItem);
            return true;
        });
        return row;
    }

    /** 加载各媒体库预览 */
    private void loadAllPreviews() {
        if (isListViewMode()) return; // 列表模式无需耗费资源拉取海报预览
        if (loadingPreviews) return;
        loadingPreviews = true;
        for (final MediaDbItem lib : mediaLibraries) {
            final String guid = lib.guid;
            apiManager.getApi().getItemList(ItemListRequest.browseLibrary(guid))
                .enqueue(new Callback<ApiResponse<ItemListResponse>>() {
                @Override
                public void onResponse(Call<ApiResponse<ItemListResponse>> call,
                                       Response<ApiResponse<ItemListResponse>> response) {
                    if (!response.isSuccessful() || response.body() == null || response.body().code != 0
                            || response.body().data == null || response.body().data.list == null
                            || response.body().data.list.isEmpty()) return;
                    // 取前6个填到预览容器
                    List<PlayListItem> items = response.body().data.list;
                    if (items.size() > 20) items = items.subList(0, 20);
                    fillPreview(guid, items);
                }
                @Override public void onFailure(Call<ApiResponse<ItemListResponse>> call, Throwable t) {}
            });
        }
    }

    /** 填充预览卡片（先清空再填充，防止重复） */

    private void fillPreview(String libGuid, List<PlayListItem> items) {
        for (int i = 0; i < moviesContainer.getChildCount(); i++) {
            View v = moviesContainer.getChildAt(i);
            if (v instanceof LinearLayout && ("preview_" + libGuid).equals(v.getTag())) {
                LinearLayout box = (LinearLayout) v;
                box.removeAllViews();
                populateGrid(box, items);
                break;
            }
        }
    }

    /** 构建媒体库标题行（整行可聚焦，点击 = 查看全部） */

    private LinearLayout makeLibHeader(String libGuid, String libTitle, int count) {
        LinearLayout headerRow = new LinearLayout(this);
        headerRow.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        headerRow.setOrientation(LinearLayout.HORIZONTAL);
        headerRow.setPadding(6, 24, 6, 18);
        headerRow.setGravity(Gravity.CENTER_VERTICAL);
        headerRow.setMinimumHeight(68);
        headerRow.setId(View.generateViewId());

        TextView header = new TextView(this);
        header.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        header.setText(libTitle);
        header.setTextColor(0xFFEEEEEE);
        header.setTextSize(18);
        header.setTypeface(Typeface.DEFAULT_BOLD);
        headerRow.addView(header);

        Button viewAll = new Button(this);
        viewAll.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, 68));
        viewAll.setBackgroundResource(R.drawable.bg_input);
        viewAll.setText("查看全部 ›");
        viewAll.setTextColor(0xFFB0B0B0);
        viewAll.setTextSize(14);
        viewAll.setFocusable(true);
        viewAll.setId(View.generateViewId());
        viewAll.setPadding(24, 0, 24, 0);
        viewAll.setOnClickListener(v -> browseItems(libGuid, libTitle));
        viewAll.setOnFocusChangeListener((v, hasFocus) -> {
            viewAll.setTextColor(hasFocus ? 0xFF81C784 : 0xFFB0B0B0);
            viewAll.setBackgroundColor(hasFocus ? 0x44FFFFFF : 0x00000000);
        });
        headerRow.setOnClickListener(v -> browseItems(libGuid, libTitle));
        headerRow.setFocusable(true);
        headerRow.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) viewAll.requestFocus();
        });
        headerRow.addView(viewAll);
        return headerRow;
    }


    private String makePosterUrl(String path) {
        if (path == null || path.isEmpty()) {
            Log.d("PosterUrl", "path is null/empty");
            return null;
        }
        String p = path.startsWith("/") ? path : "/" + path;
        String fullUrl = baseUrl + "/v/api/v1/sys/img" + p + "?w=400";
        Log.d("PosterUrl", "poster=" + path + " -> " + fullUrl);
        return fullUrl;
    }


    // ==================== 继续观看 ====================


    private void addContinueWatchingApi(LinearLayout cont, List<PlayListItem> items, int viewAllId) {
        if (items == null || items.isEmpty()) {
            cont.setVisibility(View.GONE);
            return;
        }
        cont.removeAllViews();
        cont.setVisibility(View.VISIBLE);

        TextView h = new TextView(this);
        h.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        h.setPadding(6, 8, 6, 4);
        h.setText("▶ 继续观看 (" + items.size() + ")");
        h.setTextColor(0xFF81C784);
        h.setTextSize(15);
        h.setTypeface(Typeface.DEFAULT_BOLD);
        cont.addView(h);

        if (isListViewMode()) {
            for (PlayListItem item : items) {
                View row = makeListItemRow(item);
                cont.addView(row);
                cont.addView(makeSpacer(6));
            }
            cont.addView(makeSpacer(6));
            return;
        }

        HorizontalScrollView hsv = new HorizontalScrollView(this);
        hsv.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 410));
        hsv.setScrollBarStyle(View.SCROLLBARS_INSIDE_OVERLAY);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(10, 8, 10, 8);

        for (PlayListItem item : items) {
            View card = makeContinueCard(item);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    220, 380);
            lp.setMargins(10, 0, 10, 0);
            card.setLayoutParams(lp);
            if (viewAllId > 0) card.setNextFocusDownId(viewAllId);
            row.addView(card);
        }

        hsv.addView(row);
        cont.addView(hsv);

        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                loadImagesLazily(hsv, 0);
            }
        });

        cont.addView(makeSpacer(6));
    }

    private String formatContinueTitle(PlayListItem item) {
        if (item.tvTitle != null && !item.tvTitle.isEmpty()) {
            StringBuilder sb = new StringBuilder(item.tvTitle);
            if (item.seasonNumber > 0) sb.append(" 第").append(item.seasonNumber).append("季");
            if (item.episodeNumber > 0) sb.append(item.episodeNumber).append("集");
            return sb.toString();
        }
        return item.title != null ? item.title : "";
    }

    private View makeContinueCard(PlayListItem item) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.bg_media_card);
        card.setPadding(6, 6, 6, 6);
        card.setFocusable(true);

        RoundedImageView poster = new RoundedImageView(this);
        poster.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 280));
        poster.setScaleType(ImageView.ScaleType.CENTER_CROP);
        poster.setCornerRadius(10);
        poster.setBackgroundColor(0xFF333333);
        String imgUrl = makePosterUrl(item.poster);
        if (imgUrl != null) { poster.setTag(imgUrl); }
        card.addView(poster);

        // 进度条
        LinearLayout pBar = new LinearLayout(this);
        pBar.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 5));
        pBar.setOrientation(LinearLayout.HORIZONTAL);
        pBar.setWeightSum(100);
        int pct = item.duration > 0 ? Math.max(0, Math.min(100, (int)(item.ts * 100 / item.duration))) : 0;
        Log.d("Overview", "进度条: ts=" + item.ts + " dur=" + item.duration + " pct=" + pct + " " + formatContinueTitle(item));
        if (pct > 0) {
            View f = new View(HomeActivity.this);
            f.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, pct));
            f.setBackgroundColor(0xFF81C784);
            pBar.addView(f);
        }
        if (pct < 100) {
            View r = new View(HomeActivity.this);
            r.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 100 - pct));
            r.setBackgroundColor(0xFF555555);
            pBar.addView(r);
        }
        card.addView(pBar);

        TextView title = new TextView(this);
        title.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 85));
        title.setSingleLine(true);
        title.setHorizontallyScrolling(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.MARQUEE);
        title.setMarqueeRepeatLimit(-1);
        title.setTextSize(13);
        title.setTextColor(0xFFEEEEEE);
        title.setGravity(Gravity.CENTER_VERTICAL);
        title.setPadding(4, 0, 4, 0);
        title.setText(formatContinueTitle(item));
        title.setFocusable(false);
        card.addView(title);

        // 跑马灯：强制选中，始终滚动
        title.post(() -> title.setSelected(true));

        // 点击直接播放
        final PlayListItem fi = item;
        card.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                launchPlayer(fi.guid, fi.title, fi.tvTitle != null ? fi.tvTitle : "",
                        fi.episodeNumber, fi.poster, fi.getCategoryLabel(),
                        fi.ts, fi.duration, fi.parentGuid);
            }
        });

        return card;
    }

    private void loadContinueWatching() {
        apiManager.getApi().getPlayList().enqueue(new Callback<ApiResponse<List<PlayListItem>>>() {
            @Override
            public void onResponse(Call<ApiResponse<List<PlayListItem>>> call,
                                   Response<ApiResponse<List<PlayListItem>>> response) {
                if (!response.isSuccessful() || response.body() == null || response.body().code != 0
                        || response.body().data == null || response.body().data.isEmpty()) return;

                // 过滤掉时长在10分钟（600秒）以内的短视频（如MV/短片），不展示在最近播放/继续观看中
                List<PlayListItem> rawList = response.body().data;
                List<PlayListItem> filteredList = new ArrayList<>();
                for (PlayListItem item : rawList) {
                    if (item == null) continue;
                    if (item.duration > 0 && item.duration < 600) {
                        continue;
                    }
                    filteredList.add(item);
                }
                if (filteredList.isEmpty()) return;

                // 启动应用自动继续播放（车机收音机式开机即播未完项）
                if (!hasAutoResumedOnStartup && prefs.getBoolean("pref_auto_resume", true)) {
                    hasAutoResumedOnStartup = true;
                    PlayListItem firstItem = filteredList.get(0);
                    if (firstItem != null && firstItem.guid != null) {
                        Toast.makeText(HomeActivity.this, "🚗 开机自动续播：" + firstItem.getDisplayTitle(), Toast.LENGTH_SHORT).show();
                        launchPlayer(firstItem.guid, firstItem.getDisplayTitle(), firstItem.tvTitle != null ? firstItem.tvTitle : "",
                                firstItem.episodeNumber, firstItem.poster, firstItem.getCategoryLabel(),
                                firstItem.ts, firstItem.duration, firstItem.parentGuid);
                    }
                }

                // 找到继续观看占位容器
                for (int i = 0; i < moviesContainer.getChildCount(); i++) {
                    View v = moviesContainer.getChildAt(i);
                    if (v instanceof LinearLayout && "continue_watching".equals(v.getTag())) {
                        final int firstViewAllId;
                        // 查找第一个查看全部按钮
                        int fva = -1;
                        for (int j = 0; j < moviesContainer.getChildCount(); j++) {
                            View cv = moviesContainer.getChildAt(j);
                            if (cv instanceof ViewGroup) {
                                for (int ci = 0; ci < ((ViewGroup) cv).getChildCount(); ci++) {
                                    View child = ((ViewGroup) cv).getChildAt(ci);
                                    if (child instanceof Button && child.isFocusable()) {
                                        fva = child.getId();
                                        break;
                                    }
                                }
                                if (fva > 0) break;
                            }
                        }
                        addContinueWatchingApi((LinearLayout) v, filteredList, fva);
                        break;
                    }
                }
            }
            @Override
            public void onFailure(Call<ApiResponse<List<PlayListItem>>> call, Throwable t) {}
        });
    }


    private void setFocusDownInContainer(ViewGroup group, int targetId) {
        for (int i = 0; i < group.getChildCount(); i++) {
            View v = group.getChildAt(i);
            if (v.isFocusable()) {
                v.setNextFocusDownId(targetId);
            }
            if (v instanceof ViewGroup) {
                setFocusDownInContainer((ViewGroup) v, targetId);
            }
        }
    }


    // ==================== 横向滚动卡片 ====================


    private void populateGrid(LinearLayout cont, List<PlayListItem> items) {
        HorizontalScrollView hsv = new HorizontalScrollView(this);
        hsv.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 410));
        hsv.setScrollBarStyle(View.SCROLLBARS_INSIDE_OVERLAY);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(12, 8, 12, 8);

        // 找到 cont 前后的 headerRow，取 viewAll 按钮
        View focusUpTarget = null;
        View focusDownTarget = null;
        if (cont.getParent() instanceof ViewGroup) {
            ViewGroup parent = (ViewGroup) cont.getParent();
            int idx = parent.indexOfChild(cont);
            // 前面的 headerRow（当前分类的查看全部）
            for (int si = idx - 1; si >= 0; si--) {
                View v = parent.getChildAt(si);
                if (v instanceof ViewGroup) {
                    for (int ci = 0; ci < ((ViewGroup) v).getChildCount(); ci++) {
                        View child = ((ViewGroup) v).getChildAt(ci);
                        if (child instanceof Button && child.isFocusable()) {
                            focusUpTarget = child;
                            break;
                        }
                    }
                    if (focusUpTarget != null) break;
                }
            }
            // 后面的 headerRow（下一个分类的查看全部）
            for (int si = idx + 1; si < parent.getChildCount(); si++) {
                View v = parent.getChildAt(si);
                if (v instanceof ViewGroup) {
                    for (int ci = 0; ci < ((ViewGroup) v).getChildCount(); ci++) {
                        View child = ((ViewGroup) v).getChildAt(ci);
                        if (child instanceof Button && child.isFocusable()) {
                            focusDownTarget = child;
                            break;
                        }
                    }
                    if (focusDownTarget != null) break;
                }
            }
        }

        for (int i = 0; i < items.size(); i++) {
            View card = makeItemCard(items.get(i));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(220, 380);
            lp.setMargins(10, 0, 10, 0);
            card.setLayoutParams(lp);
            // 卡片：↑到当前查看全部，↓到下一个查看全部
            if (focusUpTarget != null) card.setNextFocusUpId(focusUpTarget.getId());
            if (focusDownTarget != null) card.setNextFocusDownId(focusDownTarget.getId());
            row.addView(card);
        }

        hsv.addView(row);
        cont.addView(hsv);

        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                loadImagesLazily(hsv, 0);
            }
        });
    }

    /** 竖版卡片（2:3 比例适配海报图，图片为主，文字一条） */

    private View makeItemCard(PlayListItem item) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.bg_media_card);
        card.setPadding(6, 6, 6, 6);
        card.setFocusable(true);

        // 海报 — 固定卡片高度(列表整齐)，图片宽度撑满卡片并按原始比例 FIT_CENTER 居中显示(不变形)
        RoundedImageView iv = new RoundedImageView(this);
        int posterH = (int) (220 * getResources().getDisplayMetrics().density);
        iv.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, posterH));
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        iv.setBackgroundResource(R.drawable.bg_poster_ph);
        iv.setCornerRadius(10);
        String imgUrl = makePosterUrl(item.poster);
        if (imgUrl != null) { iv.setTag(imgUrl); }
        card.addView(iv);

        // 底部文字条：类型 + 标题
        LinearLayout textBar = new LinearLayout(this);
        textBar.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 88));
        textBar.setOrientation(LinearLayout.VERTICAL);
        textBar.setGravity(Gravity.CENTER_VERTICAL);
        textBar.setPadding(0, 4, 0, 4);

        TextView tag = new TextView(this);
        tag.setTextSize(9);
        tag.setTextColor(0xFF78909C);
        String t = item.type;
        if ("TV".equals(t)) t = "剧集";
        else if ("Movie".equals(t)) t = "电影";
        else if ("Directory".equals(t)) t = "文件夹";
        else if ("Video".equals(t)) t = "视频";
        tag.setText(t);
        textBar.addView(tag);

        final TextView title = new TextView(this);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.MARQUEE);
        title.setMarqueeRepeatLimit(-1);
        title.setTextSize(11);
        title.setTextColor(0xFFEEEEEE);
        title.setText(item.title != null ? item.title : "未知");
        textBar.addView(title);

        card.addView(textBar);

        card.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                title.setSelected(hasFocus);
                card.setScaleX(hasFocus ? 1.05f : 1.0f);
                card.setScaleY(hasFocus ? 1.05f : 1.0f);
                card.setBackgroundResource(hasFocus ? R.drawable.bg_input : R.drawable.bg_media_card);
            }
        });

        card.setTag(item);
        card.setOnClickListener(v -> onItemClick((PlayListItem) card.getTag()));
        final PlayListItem cardItem = item;
        card.setOnLongClickListener(v -> {
            if (cardItem != null && cardItem.isFolder()) {
                showFolderActionDialog(cardItem);
            } else if (cardItem != null) {
                showFileActionDialog(cardItem);
            }
            return true;
        });
        return card;
    }


    private void onItemClick(PlayListItem item) {
        if (item != null && item.isFolder()) {
            // 文件夹/分类：推入导航栈，进入下级目录浏览
            if (currentBrowseGuid != null) {
                folderNavStack.push(new FolderNavState(currentBrowseGuid, currentBrowseTitle,
                        currentBrowseSingleChildGuid, currentBrowseParentIsFolder, savedBrowseList));
            }
            browseItemsInContainer(item.guid, item.getDisplayTitle(),
                    currentBrowseContainer, currentBrowseLoading,
                    (item.singleChildGuid != null && !item.singleChildGuid.isEmpty()) ? item.singleChildGuid : null,
                    true);
        } else if (item != null && "Audio".equalsIgnoreCase(item.type)) {
            launchPlayer(item.guid, item.getDisplayTitle(), "", 0, item.poster, "Audio", 0, item.duration, item.parentGuid);
        } else {
            showDetail(item);  // 可播放项走 getPlayInfo
        }
    }


    // ==================== 查看全部 ====================


    private void browseItems(String ancestorGuid, String title) {
        LinearLayout container = (currentTab == 1) ? libraryContainer : moviesContainer;
        TextView loading = (currentTab == 1) ? tvLibraryLoading : tvMoviesLoading;
        browseItemsInContainer(ancestorGuid, title, container, loading, null, isFolderHierarchyMode());
    }

    private void browseItemsInContainer(String ancestorGuid, String title,
                                        LinearLayout container, TextView loadingView,
                                        String singleChildGuid, boolean parentIsFolder) {
        Log.d("Overview", "browseItems: guid=" + ancestorGuid + " title=" + title);
        savedDetailItem = null; savedDetailInfo = null;
        showingEpisodes = false;
        savedBrowseGuid = ancestorGuid; savedBrowseTitle = title;
        browseFromLibrary = (container == libraryContainer);
        if (browseFromLibrary) etSearch.setVisibility(View.GONE);
        if (container == moviesContainer) showingOverview = false;
        saveNavState();
        updateTopBarState();

        // 存储当前浏览上下文，排序变化时用于重新加载
        currentBrowseGuid = ancestorGuid;
        currentBrowseTitle = title;
        currentBrowseContainer = container;
        currentBrowseLoading = loadingView;
        currentBrowseSingleChildGuid = singleChildGuid;
        currentBrowseParentIsFolder = parentIsFolder;

        container.removeAllViews();
        loadingView.setVisibility(View.VISIBLE);

        String sortColumn = MediaSortHelper.getApiSortColumn(getSortField());
        String sortType = MediaSortHelper.getApiSortType(getSortOrder());
        final boolean[] browseRetried = {false};
        // FnOS 嵌套文件夹 guid 以 fv_ 开头，必须用 parent_guid 列出子项；
        // 顶层媒体库源 guid：若开启文件夹层级模式，必须传递 exclude_grouped_video=0 才能获取真实目录结构与 Directory
        final boolean useParent = ancestorGuid != null && ancestorGuid.startsWith("fv_");
        ItemListRequest request = useParent
                ? ItemListRequest.browseFolderByParent(ancestorGuid)
                : new ItemListRequest(ancestorGuid, BROWSE_TYPES, !isFolderHierarchyMode(), sortColumn, sortType, 100);
        if (!useParent && ancestorGuid != null) {
            request.viewMode = getEffectiveLibMode(ancestorGuid);
        }
        Log.d("Overview", "browseItems: guid=" + ancestorGuid + " useParent=" + useParent);
        Callback<ApiResponse<ItemListResponse>> browseCb = new Callback<ApiResponse<ItemListResponse>>() {
            @Override
            public void onResponse(Call<ApiResponse<ItemListResponse>> call,
                                   Response<ApiResponse<ItemListResponse>> response) {
                loadingView.setVisibility(View.GONE);
                Log.d("Overview", "browseItems response code=" + response.code());

                if (response.isSuccessful() && response.body() != null && response.body().code == 0
                        && response.body().data != null && response.body().data.list != null
                        && !response.body().data.list.isEmpty()) {
                    List<PlayListItem> list = response.body().data.list;
                    int total = response.body().data.total;
                    StringBuilder typeSummary = new StringBuilder();
                    for (PlayListItem pli : list) {
                        typeSummary.append(pli.type).append("(").append(pli.title).append(") ");
                    }
                    Log.d("Overview", "browseItems: got " + list.size() + " items, total=" + total
                            + " | types: " + typeSummary.toString());

                    // 真实文件夹层级过滤：
                    // 如果开启文件夹层级模式，并且结果中包含子文件夹，
                    // 过滤掉那些 parentGuid 属于更深层子文件夹的项目，只保留直属于当前层级的项（直属子文件夹与直属文件）
                    if (isFolderHierarchyMode()) {
                        java.util.Set<String> subFolderGuids = new java.util.HashSet<>();
                        for (PlayListItem pli : list) {
                            if (pli.isFolder() && pli.guid != null && !pli.guid.isEmpty()) {
                                subFolderGuids.add(pli.guid);
                            }
                        }
                        if (!subFolderGuids.isEmpty()) {
                            List<PlayListItem> directChildren = new ArrayList<>();
                            for (PlayListItem pli : list) {
                                if (pli.parentGuid != null && subFolderGuids.contains(pli.parentGuid)) {
                                    continue;
                                }
                                directChildren.add(pli);
                            }
                            list = directChildren;
                        }
                    }

                    // 仅在非文件夹层级模式且非文件夹页面时，过滤掉子文件夹
                    if (!parentIsFolder && !isFolderHierarchyMode()) {
                        List<PlayListItem> filtered = new ArrayList<>();
                        for (PlayListItem pli : list) {
                            if (!pli.isFolder()) filtered.add(pli);
                        }
                        list = filtered;
                    }

                    MediaSortHelper.sortItems(list, getSortField(), getSortOrder(), isFoldersFirst());
                    savedBrowseList = list;
                    savedBrowseTitle = title;

                    renderBrowseAll(container, list, title);
                } else {
                    // 浏览为空：回退尝试另一种 guid 形态（fv_ 文件夹试 ancestor_guid，媒体库源试 parent_guid），
                    // 再兜底 getPlayInfo（单视频文件夹/合集本身可播放）。
                    if (!browseRetried[0] && response.body() != null && response.body().code == 0) {
                        browseRetried[0] = true;
                        ItemListRequest altReq = useParent
                                ? new ItemListRequest(ancestorGuid, BROWSE_TYPES, !isFolderHierarchyMode(), sortColumn, sortType, 50)
                                : ItemListRequest.browseFolderByParent(ancestorGuid);
                        Log.d("Overview", "browseItems: 空列表，回退另一种 guid 形态 guid=" + ancestorGuid
                                + " useParent=" + useParent);
                        loadingView.setVisibility(View.VISIBLE);
                        apiManager.getApi().getItemList(altReq).enqueue(this);
                        return;
                    }
                    Log.d("Overview", "browseItems: 全部浏览皆空，兜底 getPlayInfo guid=" + ancestorGuid);
                    tryPlayFolderAsDetail(ancestorGuid, title, container, loadingView);
                }
            }
            @Override
            public void onFailure(Call<ApiResponse<ItemListResponse>> call, Throwable t) {
                loadingView.setVisibility(View.GONE);
            }
        };
        apiManager.getApi().getItemList(request).enqueue(browseCb);
    }

    /** 文件夹浏览两次均为空时的兜底：某些文件夹（单视频文件夹/合集）本身可播放，
     *  web 对其直接 getPlayInfo 播放。尝试 getPlayInfo，有可播放数据则渲染详情页，否则显示「暂无内容」。 */
    private void tryPlayFolderAsDetail(String guid, String title, LinearLayout container, View loadingView) {
        loadingView.setVisibility(View.VISIBLE);
        Map<String, String> body = new HashMap<>();
        body.put("item_guid", guid);
        apiManager.getApi().getPlayInfo(body).enqueue(new Callback<ApiResponse<PlayInfoResponse>>() {
            @Override
            public void onResponse(Call<ApiResponse<PlayInfoResponse>> call,
                                   Response<ApiResponse<PlayInfoResponse>> response) {
                loadingView.setVisibility(View.GONE);
                if (response.isSuccessful() && response.body() != null && response.body().code == 0
                        && response.body().data != null && response.body().data.item != null) {
                    PlayInfoResponse info = response.body().data;
                    PlayInfoResponse.ItemInfo it = info.item;
                    Log.d("Overview", "tryPlayFolderAsDetail: getPlayInfo ok guid=" + guid
                            + " | info.type=" + info.type + " item.guid=" + it.guid
                            + " numberOfSeasons=" + it.numberOfSeasons + " numberOfEpisodes=" + it.numberOfEpisodes
                            + " singleChildGuid=" + (currentBrowseSingleChildGuid != null ? currentBrowseSingleChildGuid : "null")
                            + " parentGuid=" + info.parentGuid + " info.type=" + info.type);
                    PlayListItem pi = new PlayListItem();
                    // 叶子文件夹（单视频）后端常经 single_child_guid 指向实际视频；优先用其作为可播放 guid
                    pi.guid = (currentBrowseSingleChildGuid != null && !currentBrowseSingleChildGuid.isEmpty())
                            ? currentBrowseSingleChildGuid : (it.guid != null ? it.guid : guid);
                    // 媒体库标签内进入的文件夹兜底：详情渲染进可见的 libraryContainer，而非被 GONE 的 moviesContainer
                    detailInLibrary = browseFromLibrary;
                    detailContainer = browseFromLibrary ? libraryContainer : moviesContainer;
                    pi.title = it.title;
                    pi.poster = it.poster;
                    pi.tvTitle = it.tvTitle;
                    pi.parentTitle = it.parentTitle;
                    pi.overview = it.overview;
                    pi.episodeNumber = it.episodeNumber;
                    pi.voteAverage = it.voteAverage;
                    pi.duration = it.duration > 0 ? (int) (it.duration / 60) : it.runtime;
                    pi.ts = info.ts;
                    pi.parentGuid = info.parentGuid;
                    pi.type = info.type;
                    buildDetailPage(pi, info);
                    return;
                }
                showBrowseEmpty(container, loadingView);
            }
            @Override
            public void onFailure(Call<ApiResponse<PlayInfoResponse>> call, Throwable t) {
                loadingView.setVisibility(View.GONE);
                showBrowseEmpty(container, loadingView);
            }
        });
    }

    /** 浏览分区最终空列表提示 */    private void showBrowseEmpty(LinearLayout container, View loadingView) {
        loadingView.setVisibility(View.GONE);
        TextView e = new TextView(HomeActivity.this);
        e.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 120));
        e.setGravity(Gravity.CENTER);
        e.setText("暂无内容");
        e.setTextColor(0xFF808080);
        e.setTextSize(14);
        container.addView(e);
    }

    /** 从缓存数据重绘浏览（横竖屏切换时调用，统一走 renderBrowseAll 以复用网格/列表与固定列数） */

    private void renderBrowseGrid(List<PlayListItem> list, String title) {
        renderBrowseAll(libraryContainer, list, title);
    }

    /** 在指定容器中绘制缓存网格（旋转时复用） */
    private void renderGridInContainer(List<PlayListItem> list, String title, LinearLayout container) {
        renderBrowseAll(container, list, title);
    }

    // ==================== 媒体库排序筛选 ====================

    /** 使用当前排序重新加载媒体库内容 */
    private void reFetchLibraryItems() {
        if (currentBrowseGuid == null || currentBrowseContainer == null) return;
        browseItemsInContainer(currentBrowseGuid, currentBrowseTitle,
                currentBrowseContainer, currentBrowseLoading, currentBrowseSingleChildGuid,
                currentBrowseParentIsFolder);
    }

    /** 构建排序筛选栏 */
    private LinearLayout makeLibSortFilterBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundResource(R.drawable.bg_input);
        bar.setPadding(16, 12, 16, 12);

        // 排序方式标签
        TextView sortLabel = new TextView(this);
        sortLabel.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        sortLabel.setGravity(Gravity.CENTER_VERTICAL);
        sortLabel.setText("排序: ");
        sortLabel.setTextColor(0xFFB0B0B0);
        sortLabel.setTextSize(13);
        bar.addView(sortLabel);

        // 排序列选择按钮
        Button sortColumnBtn = new Button(this);
        sortColumnBtn.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, (int)(38 * getResources().getDisplayMetrics().density)));
        sortColumnBtn.setBackgroundResource(R.drawable.bg_btn_primary);
        sortColumnBtn.setText(MediaSortHelper.SORT_FIELD_LABELS[getSortField()] + " ▾");
        sortColumnBtn.setTextColor(0xFFEEEEEE);
        sortColumnBtn.setTextSize(12);
        sortColumnBtn.setFocusable(true);
        sortColumnBtn.setPadding(16, 0, 16, 0);
        sortColumnBtn.setOnFocusChangeListener((v, hasFocus) -> {
            v.setScaleX(hasFocus ? 1.05f : 1.0f);
            v.setScaleY(hasFocus ? 1.05f : 1.0f);
        });
        sortColumnBtn.setOnClickListener(v -> showSortFieldDialog());
        bar.addView(sortColumnBtn);

        // 弹性间隔
        View spacer = new View(this);
        spacer.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1));
        bar.addView(spacer);

        // 顺序标签
        TextView orderLabel = new TextView(this);
        orderLabel.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        orderLabel.setGravity(Gravity.CENTER_VERTICAL);
        orderLabel.setText("顺序: ");
        orderLabel.setTextColor(0xFFB0B0B0);
        orderLabel.setTextSize(13);
        bar.addView(orderLabel);

        // 排序顺序选择按钮
        Button sortOrderBtn = new Button(this);
        sortOrderBtn.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, (int)(38 * getResources().getDisplayMetrics().density)));
        sortOrderBtn.setBackgroundResource(R.drawable.bg_btn_primary);
        sortOrderBtn.setText(getSortOrder() == MediaSortHelper.ORDER_ASC ? "正序 ▴" : "反序 ▾");
        sortOrderBtn.setTextColor(0xFFEEEEEE);
        sortOrderBtn.setTextSize(12);
        sortOrderBtn.setFocusable(true);
        sortOrderBtn.setPadding(16, 0, 16, 0);
        sortOrderBtn.setOnFocusChangeListener((v, hasFocus) -> {
            v.setScaleX(hasFocus ? 1.05f : 1.0f);
            v.setScaleY(hasFocus ? 1.05f : 1.0f);
        });
        sortOrderBtn.setOnClickListener(v -> toggleSortOrder());
        bar.addView(sortOrderBtn);

        View sp3 = new View(this);
        sp3.setLayoutParams(new LinearLayout.LayoutParams(12, 1));
        bar.addView(sp3);

        // 网格/列表 视图切换
        Button viewToggle = new Button(this);
        viewToggle.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, (int)(38 * getResources().getDisplayMetrics().density)));
        viewToggle.setBackgroundResource(R.drawable.bg_btn_primary);
        viewToggle.setText(isListViewMode() ? "📁 列表" : "▦ 海报");
        viewToggle.setTextColor(0xFFEEEEEE);
        viewToggle.setTextSize(12);
        viewToggle.setFocusable(true);
        viewToggle.setPadding(16, 0, 16, 0);
        viewToggle.setOnFocusChangeListener((v, hasFocus) -> {
            v.setScaleX(hasFocus ? 1.05f : 1.0f);
            v.setScaleY(hasFocus ? 1.05f : 1.0f);
        });
        viewToggle.setOnClickListener(v -> toggleViewMode());
        bar.addView(viewToggle);

        View sp4 = new View(this);
        sp4.setLayoutParams(new LinearLayout.LayoutParams(12, 1));
        bar.addView(sp4);

        // 全部播放（连续播放当前文件夹内所有视频）
        Button playAllBtn = new Button(this);
        playAllBtn.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, (int)(38 * getResources().getDisplayMetrics().density)));
        playAllBtn.setBackgroundResource(R.drawable.bg_btn_primary);
        playAllBtn.setText("▶ 全部播放");
        playAllBtn.setTextColor(0xFF81C784);
        playAllBtn.setTextSize(12);
        playAllBtn.setFocusable(true);
        playAllBtn.setPadding(16, 0, 16, 0);
        playAllBtn.setOnFocusChangeListener((v, hasFocus) -> {
            v.setScaleX(hasFocus ? 1.05f : 1.0f);
            v.setScaleY(hasFocus ? 1.05f : 1.0f);
        });
        playAllBtn.setOnClickListener(v -> playAllCurrentFolder());
        bar.addView(playAllBtn);

        // 服务端双模一键切换：文件夹模式 <-> 海报墙模式
        String rootLibId = getCurrentRootLibId();
        if (rootLibId != null) {
            View sp5 = new View(this);
            sp5.setLayoutParams(new LinearLayout.LayoutParams(12, 1));
            bar.addView(sp5);

            String curMode = getEffectiveLibMode(rootLibId);
            String serverMode = "movie_tv";
            for (MediaDbItem l : mediaLibraries) {
                if (l.guid != null && l.guid.equals(rootLibId) && l.mode != null) {
                    serverMode = l.mode;
                    break;
                }
            }
            Button libModeBtn = new Button(this);
            libModeBtn.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, (int)(38 * getResources().getDisplayMetrics().density)));
            libModeBtn.setBackgroundResource(R.drawable.bg_btn_primary);
            libModeBtn.setText("folder".equals(curMode)
                    ? ("music".equalsIgnoreCase(serverMode) ? "🎵 音乐模式" : "🎬 海报墙模式")
                    : "📁 文件夹模式");
            libModeBtn.setTextColor(0xFF38BDF8);
            libModeBtn.setTextSize(12);
            libModeBtn.setFocusable(true);
            libModeBtn.setPadding(16, 0, 16, 0);
            libModeBtn.setOnClickListener(v -> toggleCurrentLibMode(rootLibId));
            bar.addView(libModeBtn);
        }

        boolean hasAudioInBrowse = false;
        if (savedBrowseList != null) {
            for (PlayListItem pli : savedBrowseList) {
                if (pli != null && "Audio".equalsIgnoreCase(pli.type)) {
                    hasAudioInBrowse = true;
                    break;
                }
            }
        }
        if (hasAudioInBrowse) {
            View spAud = new View(this);
            spAud.setLayoutParams(new LinearLayout.LayoutParams(12, 1));
            bar.addView(spAud);

            Button batchOfflineBtn = new Button(this);
            batchOfflineBtn.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, (int)(38 * getResources().getDisplayMetrics().density)));
            batchOfflineBtn.setBackgroundResource(R.drawable.bg_btn_primary);
            batchOfflineBtn.setText("⬇ 批量离线");
            batchOfflineBtn.setTextColor(0xFF38BDF8);
            batchOfflineBtn.setTextSize(12);
            batchOfflineBtn.setFocusable(true);
            batchOfflineBtn.setPadding(16, 0, 16, 0);
            batchOfflineBtn.setOnClickListener(v -> showBatchOfflineBrowseDialog());
            bar.addView(batchOfflineBtn);
        }

        if (OfflineMusicManager.getInstance(this).getTrackCount() > 0) {
            View spLoc = new View(this);
            spLoc.setLayoutParams(new LinearLayout.LayoutParams(12, 1));
            bar.addView(spLoc);

            Button localModeBtn = new Button(this);
            localModeBtn.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, (int)(38 * getResources().getDisplayMetrics().density)));
            localModeBtn.setBackgroundResource(R.drawable.bg_btn_primary);
            localModeBtn.setText("🎧 本地模式 (" + OfflineMusicManager.getInstance(this).getTrackCount() + ")");
            localModeBtn.setTextColor(0xFF34D399);
            localModeBtn.setTextSize(12);
            localModeBtn.setFocusable(true);
            localModeBtn.setPadding(16, 0, 16, 0);
            localModeBtn.setOnClickListener(v -> showLocalOfflineBrowseDialog());
            bar.addView(localModeBtn);
        }

        if (MusicPlaybackService.getInstance() != null && MusicPlaybackService.getInstance().getCurrentItem() != null) {
            View spM = new View(this);
            spM.setLayoutParams(new LinearLayout.LayoutParams(12, 1));
            bar.addView(spM);

            Button nowMusicBtn = new Button(this);
            nowMusicBtn.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, (int)(38 * getResources().getDisplayMetrics().density)));
            nowMusicBtn.setBackgroundResource(R.drawable.bg_btn_primary);
            nowMusicBtn.setText("🎵 正在播放");
            nowMusicBtn.setTextColor(0xFF34D399);
            nowMusicBtn.setTextSize(12);
            nowMusicBtn.setFocusable(true);
            nowMusicBtn.setPadding(16, 0, 16, 0);
            nowMusicBtn.setOnClickListener(v -> startActivity(new Intent(this, MusicPlayerActivity.class)));
            bar.addView(nowMusicBtn);
        }

        // 遥控器水平焦点链：保证左右导航在各按钮间丝滑连贯切换
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            sortColumnBtn.setId(View.generateViewId());
            sortOrderBtn.setId(View.generateViewId());
            viewToggle.setId(View.generateViewId());
            playAllBtn.setId(View.generateViewId());

            sortColumnBtn.setNextFocusRightId(sortOrderBtn.getId());
            sortOrderBtn.setNextFocusLeftId(sortColumnBtn.getId());
            sortOrderBtn.setNextFocusRightId(viewToggle.getId());
            viewToggle.setNextFocusLeftId(sortOrderBtn.getId());
            viewToggle.setNextFocusRightId(playAllBtn.getId());
            playAllBtn.setNextFocusLeftId(viewToggle.getId());
        }

        // 手机竖屏自适应：外层包一层 HorizontalScrollView 保证窄屏下所有按钮不被裁切
        LinearLayout wrapper = new LinearLayout(this);
        wrapper.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        wrapper.setOrientation(LinearLayout.VERTICAL);
        HorizontalScrollView hsv = new HorizontalScrollView(this);
        hsv.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        hsv.setHorizontalScrollBarEnabled(false);
        hsv.setFillViewport(true);
        hsv.addView(bar);
        wrapper.addView(hsv);
        return wrapper;
    }

    private void toggleViewMode() {
        String newMode = isListViewMode() ? "grid" : "list";
        prefs.edit().putString(PREF_VIEW_MODE, newMode).apply();
        if (tvViewModeValue != null) {
            tvViewModeValue.setText("list".equals(newMode) ? "文件夹/文件列表" : "海报墙模式");
        }
        if (currentTab == 0 && showingOverview) {
            showOverview();
            if (!isListViewMode()) {
                loadAllPreviews();
                loadContinueWatching();
                loadLiveChannels();
            } else {
                loadContinueWatching();
            }
        } else if (currentTab == 1 && savedBrowseGuid == null) {
            loadMediaLibraries();
        } else if (savedBrowseList != null && currentBrowseContainer != null) {
            renderBrowseAll(currentBrowseContainer, savedBrowseList, savedBrowseTitle);
        }
    }

    private void showSortFieldDialog() {
        new android.app.AlertDialog.Builder(this)
                .setTitle("排序方式")
                .setSingleChoiceItems(MediaSortHelper.SORT_FIELD_LABELS, getSortField(), (dialog, which) -> {
                    prefs.edit().putInt(PREF_SORT_FIELD, which).apply();
                    if (tvSortFieldValue != null) {
                        tvSortFieldValue.setText(MediaSortHelper.SORT_FIELD_LABELS[which]);
                    }
                    dialog.dismiss();
                    onSortChanged();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void toggleSortOrder() {
        int newOrder = getSortOrder() == MediaSortHelper.ORDER_ASC ? MediaSortHelper.ORDER_DESC : MediaSortHelper.ORDER_ASC;
        prefs.edit().putInt(PREF_SORT_ORDER, newOrder).apply();
        if (tvSortOrderValue != null) {
            tvSortOrderValue.setText(MediaSortHelper.SORT_ORDER_LABELS[newOrder]);
        }
        onSortChanged();
    }

    private void onSortChanged() {
        if (savedBrowseList != null && !savedBrowseList.isEmpty()) {
            MediaSortHelper.sortItems(savedBrowseList, getSortField(), getSortOrder(), isFoldersFirst());
            if (currentBrowseContainer != null) {
                renderBrowseAll(currentBrowseContainer, savedBrowseList, savedBrowseTitle);
            }
        } else if (currentBrowseGuid != null) {
            reFetchLibraryItems();
        } else if (currentTab == 0 && showingOverview && isListViewMode()) {
            showOverview();
        }
    }

    private void goBackFolder() {
        if (!folderNavStack.isEmpty()) {
            FolderNavState prev = folderNavStack.pop();
            currentBrowseGuid = prev.guid;
            currentBrowseTitle = prev.title;
            currentBrowseSingleChildGuid = prev.singleChildGuid;
            currentBrowseParentIsFolder = prev.parentIsFolder;
            savedBrowseGuid = prev.guid;
            savedBrowseTitle = prev.title;
            if (prev.cachedList != null && !prev.cachedList.isEmpty()) {
                savedBrowseList = prev.cachedList;
                renderBrowseAll(currentBrowseContainer, savedBrowseList, savedBrowseTitle);
            } else {
                browseItemsInContainer(prev.guid, prev.title, currentBrowseContainer, currentBrowseLoading,
                        prev.singleChildGuid, prev.parentIsFolder);
            }
        } else {
            if (browseFromLibrary) {
                savedBrowseGuid = null;
                savedBrowseList = null;
                saveNavState();
                loadMediaLibraries();
            } else {
                saveNavState();
                loadOverview();
            }
        }
    }

    private View makeParentFolderRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setBackgroundResource(R.drawable.bg_media_card);
        row.setFocusable(true);
        row.setClickable(true);
        row.setMinimumHeight((int) (52 * getResources().getDisplayMetrics().density));
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(16, 10, 16, 10);

        TextView icon = new TextView(this);
        icon.setText("📁");
        icon.setTextSize(18);
        icon.setPadding(0, 0, 14, 0);
        row.addView(icon);

        TextView title = new TextView(this);
        title.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        if (!folderNavStack.isEmpty()) {
            FolderNavState parentState = folderNavStack.peek();
            title.setText(".. [返回上一级: " + (parentState.title != null ? parentState.title : "目录") + "]");
        } else {
            title.setText(".. [返回上一级列表]");
        }
        title.setTextSize(15);
        title.setTextColor(0xFF81C784);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        row.addView(title);

        row.setOnFocusChangeListener((v, hasFocus) -> {
            row.setScaleX(hasFocus ? 1.015f : 1.0f);
            row.setScaleY(hasFocus ? 1.015f : 1.0f);
            row.setBackgroundResource(hasFocus ? R.drawable.bg_input : R.drawable.bg_media_card);
        });

        row.setOnClickListener(v -> goBackFolder());
        return row;
    }

    // ==================== 网格/列表 双视图渲染 ====================

    /** 渲染当前浏览：标题 + 排序栏 + 列表/网格正文（按 isListViewMode() 切换） */
    private void renderBrowseAll(ViewGroup container, List<PlayListItem> list, String title) {
        container.removeAllViews();
        StringBuilder breadcrumb = new StringBuilder();
        if (folderNavStack != null && !folderNavStack.isEmpty()) {
            for (FolderNavState st : folderNavStack) {
                if (st.title != null && !st.title.isEmpty()) {
                    breadcrumb.append(st.title).append(" / ");
                }
            }
        }
        breadcrumb.append(title != null ? title : "");

        TextView h = new TextView(HomeActivity.this);
        h.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        h.setPadding(6, 8, 6, 4);
        h.setText("📁 " + breadcrumb.toString() + " (" + list.size() + "项)");
        h.setTextColor(0xFFEEEEEE);
        h.setTextSize(15);
        h.setTypeface(Typeface.DEFAULT_BOLD);
        container.addView(h);

        LinearLayout sortFilterBar = makeLibSortFilterBar();
        sortFilterBar.setTag("lib_sort_bar");
        container.addView(sortFilterBar);
        container.addView(makeSpacer(8));

        renderBrowseBody(container, list);
        new Handler(Looper.getMainLooper()).post(() -> loadImagesLazily(container, 0));
        // 遥控器焦点兜底：新页面渲染后确保首个可聚焦控件获得高亮焦点
        container.post(() -> {
            View f = container.findFocus();
            if (f == null) {
                for (int i = 0; i < container.getChildCount(); i++) {
                    View cv = container.getChildAt(i);
                    if (cv != null && cv.isFocusable()) {
                        cv.requestFocus();
                        break;
                    }
                }
            }
        });
    }

    private void renderBrowseBody(ViewGroup container, List<PlayListItem> list) {
        if (list == null || list.isEmpty()) {
            if (!folderNavStack.isEmpty() || (currentBrowseGuid != null && !showingOverview)) {
                container.addView(makeParentFolderRow());
                container.addView(makeSpacer(6));
            }
            TextView empty = new TextView(HomeActivity.this);
            empty.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            empty.setPadding(6, 24, 6, 24);
            empty.setText("文件夹暂无内容");
            empty.setTextColor(0xFF808080);
            empty.setTextSize(14);
            container.addView(empty);
            return;
        }
        if (isListViewMode()) renderList(container, list);
        else renderGrid(container, list);
    }

    /** 网格视图：根据屏幕宽度自适应列数，海报固定高度 */
    private void renderGrid(ViewGroup container, List<PlayListItem> list) {
        if (!folderNavStack.isEmpty() || (currentBrowseGuid != null && !showingOverview)) {
            container.addView(makeParentFolderRow());
            container.addView(makeSpacer(6));
        }
        int cols = 8;
        if (getResources().getConfiguration().orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT) {
            cols = 4;
        }
        for (int idx = 0; idx < list.size(); idx += cols) {
            LinearLayout row = new LinearLayout(HomeActivity.this);
            row.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            row.setOrientation(LinearLayout.HORIZONTAL);
            int inRow = Math.min(cols, list.size() - idx);
            for (int c = 0; c < cols && idx + c < list.size(); c++) {
                PlayListItem pli = list.get(idx + c);
                View card = makeItemCard(pli);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
                lp.rightMargin = 6;
                lp.leftMargin = 6;
                card.setLayoutParams(lp);
                row.addView(card);
            }
            for (int e = inRow; e < cols; e++) {
                View spacer = new View(HomeActivity.this);
                spacer.setLayoutParams(new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.MATCH_PARENT, 1));
                row.addView(spacer);
            }
            container.addView(row);
            container.addView(makeSpacer(12));
        }
    }

    /** 列表视图：每行一项，支持文件夹/视频/剧集多样化图标、时长、修改/创建时间与进度显示 */
    private void renderList(ViewGroup container, List<PlayListItem> list) {
        if (!folderNavStack.isEmpty() || (currentBrowseGuid != null && !showingOverview)) {
            container.addView(makeParentFolderRow());
            container.addView(makeSpacer(6));
        }
        for (PlayListItem pli : list) {
            container.addView(makeListItemRow(pli));
            container.addView(makeSpacer(6));
        }
    }

    private View makeListItemRow(final PlayListItem item) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setBackgroundResource(R.drawable.bg_media_card);
        row.setFocusable(true);
        row.setClickable(true);
        row.setMinimumHeight((int) (58 * getResources().getDisplayMetrics().density));
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(14, 10, 14, 10);

        // 左侧类型图标
        TextView icon = new TextView(this);
        icon.setTextSize(18);
        icon.setPadding(0, 0, 12, 0);
        if (item.isFolder()) {
            icon.setText("📁");
        } else if ("TV".equals(item.type)) {
            icon.setText("📺");
        } else if ("Movie".equals(item.type)) {
            icon.setText("🎬");
        } else if ("Episode".equals(item.type)) {
            icon.setText("▶️");
        } else {
            icon.setText("🎞️");
        }
        row.addView(icon);

        // 中间文字区域
        LinearLayout textCol = new LinearLayout(this);
        textCol.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        textCol.setOrientation(LinearLayout.VERTICAL);

        final TextView tvTitle = new TextView(this);
        tvTitle.setText(item.getDisplayTitle());
        tvTitle.setTextSize(15);
        tvTitle.setTextColor(0xFFEEEEEE);
        tvTitle.setTypeface(Typeface.DEFAULT_BOLD);
        tvTitle.setSingleLine(true);
        tvTitle.setEllipsize(TextUtils.TruncateAt.MARQUEE);
        tvTitle.setMarqueeRepeatLimit(-1);
        textCol.addView(tvTitle);

        // 副标题：类型 · 时长 · 修改时间/创建时间
        StringBuilder subText = new StringBuilder();
        if (item.isFolder()) {
            subText.append("[文件夹]");
        } else if ("TV".equals(item.type)) {
            subText.append("[剧集]");
        } else if ("Movie".equals(item.type)) {
            subText.append("[电影]");
        } else if ("Episode".equals(item.type)) {
            subText.append("[单集]");
        } else {
            subText.append("[视频]");
        }

        String dur = item.getFormattedDuration();
        if (!dur.isEmpty()) {
            subText.append(" · 时长 ").append(dur);
        }
        String date = item.getDisplayDate();
        if (!date.isEmpty()) {
            subText.append(" · ").append(date);
        }
        String fPath = item.getDisplayFilePath();
        if (fPath != null && !fPath.isEmpty()) {
            int lastSep = Math.max(fPath.lastIndexOf('/'), fPath.lastIndexOf('\\'));
            String fName = (lastSep >= 0 && lastSep < fPath.length() - 1) ? fPath.substring(lastSep + 1) : fPath;
            if (!fName.equalsIgnoreCase(item.getDisplayTitle())) {
                subText.append(" · ").append(fName);
            }
        }
        TextView tvSub = new TextView(this);
        tvSub.setText(subText.toString());
        tvSub.setTextSize(12);
        tvSub.setTextColor(0xFF808080);
        tvSub.setSingleLine(true);
        tvSub.setEllipsize(TextUtils.TruncateAt.END);
        textCol.addView(tvSub);

        row.addView(textCol);

        // 右侧状态与箭头
        if (item.isFolder()) {
            TextView arrow = new TextView(this);
            arrow.setText("›");
            arrow.setTextSize(20);
            arrow.setTextColor(0xFF808080);
            arrow.setPadding(8, 0, 4, 0);
            row.addView(arrow);
        } else if (item.watched == 1) {
            TextView watched = new TextView(this);
            watched.setText("✓ 已看");
            watched.setTextSize(11);
            watched.setTextColor(0xFF81C784);
            watched.setPadding(8, 0, 4, 0);
            row.addView(watched);
        } else if (item.ts > 0 && item.duration >= 600) {
            int pct = Math.min(100, (int) (item.ts * 100 / item.duration));
            TextView prog = new TextView(this);
            prog.setText("进度 " + pct + "%");
            prog.setTextSize(11);
            prog.setTextColor(0xFF64B5F6);
            prog.setPadding(8, 0, 4, 0);
            row.addView(prog);
        }

        row.setOnFocusChangeListener((v, hasFocus) -> {
            row.setScaleX(hasFocus ? 1.015f : 1.0f);
            row.setScaleY(hasFocus ? 1.015f : 1.0f);
            row.setBackgroundResource(hasFocus ? R.drawable.bg_input : R.drawable.bg_media_card);
            tvTitle.setSelected(hasFocus);
        });

        row.setTag(item);
        row.setOnClickListener(v -> onItemClick((PlayListItem) row.getTag()));
        final PlayListItem rowItem = item;
        row.setOnLongClickListener(v -> {
            if (rowItem != null && rowItem.isFolder()) {
                showFolderActionDialog(rowItem);
            } else if (rowItem != null) {
                showFileActionDialog(rowItem);
            }
            return true;
        });
        return row;
    }

    /** 切换网格/列表后仅重渲染正文，不重新联网 */
    private void rerenderBrowseBody() {
        if (savedBrowseList == null || currentBrowseContainer == null) return;
        renderBrowseAll(currentBrowseContainer, savedBrowseList, savedBrowseTitle);
    }

    /** 判断顶层媒体库是否为“文件夹型”（文件夹型展示内部子文件夹，内容型隐藏） */
    private String getCurrentRootLibId() {
        if (folderNavStack != null && !folderNavStack.isEmpty()) {
            String g = folderNavStack.get(0).guid;
            for (MediaDbItem l : mediaLibraries) {
                if (l.guid != null && l.guid.equals(g)) return g;
            }
        }
        if (currentBrowseGuid != null) {
            for (MediaDbItem l : mediaLibraries) {
                if (l.guid != null && l.guid.equals(currentBrowseGuid)) return currentBrowseGuid;
            }
        }
        return null;
    }

    private String getEffectiveLibMode(String libGuid) {
        if (libGuid == null) return "movie_tv";
        if (libModeOverrides.containsKey(libGuid)) {
            return libModeOverrides.get(libGuid);
        }
        if (prefs != null) {
            String saved = prefs.getString("lib_mode_" + libGuid, "");
            if (!saved.isEmpty()) {
                libModeOverrides.put(libGuid, saved);
                return saved;
            }
        }
        for (MediaDbItem l : mediaLibraries) {
            if (l.guid != null && l.guid.equals(libGuid)) {
                if ("folder".equalsIgnoreCase(l.mode)) return "folder";
                if ("music".equalsIgnoreCase(l.mode)) return "music";
                return "movie_tv";
            }
        }
        return "movie_tv";
    }

    private void toggleCurrentLibMode(String libGuid) {
        String cur = getEffectiveLibMode(libGuid);
        String serverMode = "movie_tv";
        for (MediaDbItem l : mediaLibraries) {
            if (l.guid != null && l.guid.equals(libGuid) && l.mode != null) {
                serverMode = l.mode;
                break;
            }
        }
        String nonFolder = "music".equalsIgnoreCase(serverMode) ? "music" : "movie_tv";
        String next = "folder".equals(cur) ? nonFolder : "folder";
        libModeOverrides.put(libGuid, next);
        prefs.edit()
                .putString("lib_mode_" + libGuid, next)
                .putString(PREF_VIEW_MODE, "folder".equals(next) ? "list" : "grid")
                .apply();
        folderNavStack.clear();
        String title = currentBrowseTitle;
        for (MediaDbItem l : mediaLibraries) {
            if (l.guid != null && l.guid.equals(libGuid)) {
                title = l.title;
                break;
            }
        }
        browseItemsInContainer(libGuid, title,
                currentBrowseContainer != null ? currentBrowseContainer : libraryContainer,
                currentBrowseLoading != null ? currentBrowseLoading : tvLibraryLoading,
                null, "folder".equals(next));
    }

    private boolean isLibFolderForm(MediaDbItem lib) {
        if (lib == null) return false;
        String eff = getEffectiveLibMode(lib.guid);
        if ("folder".equalsIgnoreCase(eff)) return true;
        if ("movie_tv".equalsIgnoreCase(eff)) return false;
        if (isFolderHierarchyMode()) return true;
        String c = lib.category;
        if (c == null) return false;
        c = c.trim();
        // 内容型媒体库（电影/剧集/音乐/视频）不展示子文件夹
        if ("Movie".equals(c) || "TV".equals(c) || "Music".equals(c)
                || "Video".equals(c) || "Audio".equals(c) || "Show".equals(c)) {
            return false;
        }
        return true; // Mix / Custom / Photo / Other 等视为文件夹型
    }

    /** 启动播放器 */
    private void launchPlayer(String guid, String title, String tvTitle, int epNum,
                              String poster, String cat, long ts, long dur) {
        launchPlayer(guid, title, tvTitle, epNum, poster, cat, ts, dur, null, false);
    }

    private void launchPlayer(String guid, String title, String tvTitle, int epNum,
                              String poster, String cat, long ts, long dur, String parentGuid) {
        launchPlayer(guid, title, tvTitle, epNum, poster, cat, ts, dur, parentGuid, false);
    }

    private void launchPlayer(String guid, String title, String tvTitle, int epNum,
                              String poster, String cat, long ts, long dur, String parentGuid, boolean forceSingle) {
        boolean isAudioItem = "Audio".equalsIgnoreCase(cat) || "Music".equalsIgnoreCase(cat);
        if (!isAudioItem && savedBrowseList != null) {
            for (PlayListItem pli : savedBrowseList) {
                if (pli != null && guid != null && guid.equals(pli.guid) && "Audio".equalsIgnoreCase(pli.type)) {
                    isAudioItem = true;
                    break;
                }
            }
        }
        if (isAudioItem) {
            List<PlayListItem> audioQueue = new ArrayList<>();
            int audioStartIdx = 0;
            if (!forceSingle && savedBrowseList != null) {
                for (PlayListItem pli : savedBrowseList) {
                    if (pli != null && "Audio".equalsIgnoreCase(pli.type)) {
                        if (guid != null && guid.equals(pli.guid)) audioStartIdx = audioQueue.size();
                        audioQueue.add(pli);
                    }
                }
            }
            if (audioQueue.isEmpty()) {
                PlayListItem single = new PlayListItem();
                single.guid = guid;
                single.title = title;
                single.type = "Audio";
                single.poster = poster;
                single.duration = dur;
                single.parentGuid = parentGuid;
                audioQueue.add(single);
                audioStartIdx = 0;
            }
            MusicPlaybackService.startQueue(this, audioQueue, audioStartIdx);
            startActivity(new Intent(this, MusicPlayerActivity.class));
            return;
        }

        // 自动续播对10分钟(<600s)以下视频不生效，避免MV类视频从末尾几秒开始播
        if (dur > 0 && dur < 600) {
            ts = 0;
        }

        // 自动加入同文件夹播放队列：如果未强制单项播放，且当前浏览列表中存在该视频及同文件夹兄弟视频
        if (!forceSingle && savedBrowseList != null && !savedBrowseList.isEmpty()) {
            boolean currentFound = false;
            List<PlayListItem> playableSiblings = new ArrayList<>();
            for (PlayListItem pli : savedBrowseList) {
                if (pli != null && !pli.isFolder() && !"TV".equals(pli.type)) {
                    playableSiblings.add(pli);
                    if (guid != null && guid.equals(pli.guid)) {
                        currentFound = true;
                    }
                }
            }
            if (currentFound && playableSiblings.size() > 1) {
                String plTitle = (savedBrowseTitle != null && !savedBrowseTitle.isEmpty()) ? savedBrowseTitle : "文件夹播放";
                startPlaylistPlayback(plTitle, savedBrowseList, currentBrowseGuid, PLAY_MODE_SEQUENTIAL, guid, ts);
                return;
            }
        }

        Intent intent = new Intent(this, PlayerActivity.class);
        intent.putExtra("guid", guid);
        intent.putExtra("title", title);
        intent.putExtra("tv_title", tvTitle);
        intent.putExtra("episode_number", epNum);
        intent.putExtra("poster", poster);
        intent.putExtra("category", cat);
        intent.putExtra("ts", ts);
        intent.putExtra("duration", dur);
        if (parentGuid != null) intent.putExtra("parent_guid", parentGuid);
        if (forceSingle) intent.putExtra("force_single", true);
        startActivity(intent);
    }

    private void showBatchOfflineBrowseDialog() {
        if (savedBrowseList == null || savedBrowseList.isEmpty()) return;
        final List<PlayListItem> audioItems = new ArrayList<>();
        for (PlayListItem pli : savedBrowseList) {
            if (pli != null && "Audio".equalsIgnoreCase(pli.type)) audioItems.add(pli);
        }
        if (audioItems.isEmpty()) return;
        final String curBr = OfflineMusicManager.getPreferredBitrate(this);
        String brTxt = OfflineMusicManager.getBitrateDisplayLabel(curBr);
        String[] opts = {
                "⬇ 下载当前列表全部音频（共 " + audioItems.size() + " 首 · 最大码率 " + brTxt.split(" ")[0] + "）",
                "⚙ 选择下载最大码率（当前：" + brTxt + "）"
        };
        new AlertDialog.Builder(this)
                .setTitle("批量离线音乐到本地")
                .setItems(opts, (d, which) -> {
                    if (which == 0) {
                        OfflineMusicManager mgr = OfflineMusicManager.getInstance(this);
                        mgr.addBatchListener(new OfflineMusicManager.BatchListener() {
                            @Override public void onProgress(int done, int total, String title) {}
                            @Override public void onFinished(int ok, int fail) {
                                Toast.makeText(HomeActivity.this,
                                        "批量离线完成：成功 " + ok + " 首" + (fail > 0 ? "，失败 " + fail + " 首" : ""),
                                        Toast.LENGTH_LONG).show();
                            }
                        });
                        mgr.enqueueBatch(audioItems, curBr, true);
                        Toast.makeText(this, "已开始后台批量下载（含歌词与封面，跳过已下载）", Toast.LENGTH_SHORT).show();
                    } else {
                        String[] labels = OfflineMusicManager.BITRATE_LABELS;
                        int chk = 2;
                        for (int i = 0; i < OfflineMusicManager.BITRATE_VALUES.length; i++) {
                            if (OfflineMusicManager.BITRATE_VALUES[i].equals(curBr)) { chk = i; break; }
                        }
                        new AlertDialog.Builder(this)
                                .setTitle("选择离线下载最大码率")
                                .setSingleChoiceItems(labels, chk, (d2, w2) -> {
                                    OfflineMusicManager.setPreferredBitrate(this, OfflineMusicManager.BITRATE_VALUES[w2]);
                                    Toast.makeText(this, "已设置最大码率：" + labels[w2], Toast.LENGTH_SHORT).show();
                                    d2.dismiss();
                                })
                                .show();
                    }
                })
                .show();
    }

    private void showLocalOfflineBrowseDialog() {
        OfflineMusicManager mgr = OfflineMusicManager.getInstance(this);
        List<PlayListItem> offList = mgr.getOfflinePlayListItems();
        if (offList.isEmpty()) {
            Toast.makeText(this, "暂无本地离线音乐", Toast.LENGTH_SHORT).show();
            return;
        }
        String[] opts = {
                "▶ 进入本地模式顺序播放 (" + offList.size() + " 首 · " + mgr.getTotalSizeText() + ")",
                "🔀 进入本地模式随机播放",
                "🗑 清空本地离线音乐缓存"
        };
        new AlertDialog.Builder(this)
                .setTitle("🎧 本地离线音乐模式")
                .setItems(opts, (d, which) -> {
                    if (which == 0) {
                        MusicPlaybackService.startQueue(this, offList, 0);
                        startActivity(new Intent(this, MusicPlayerActivity.class));
                    } else if (which == 1) {
                        java.util.Collections.shuffle(offList, new java.util.Random());
                        MusicPlaybackService.startQueue(this, offList, 0);
                        startActivity(new Intent(this, MusicPlayerActivity.class));
                    } else {
                        mgr.clearAll();
                        Toast.makeText(this, "已清空本地离线音乐", Toast.LENGTH_SHORT).show();
                    }
                })
                .show();
    }

    /** 文件夹长按弹窗选择：顺序播放、继续播放、随机播放 或 打开文件夹 */
    private void showFolderActionDialog(final PlayListItem folderItem) {
        if (folderItem == null) return;
        final String title = folderItem.getDisplayTitle();
        final String[] options = new String[] {
                "▶ 顺序播放 (从第一首开始)",
                "⏯ 继续播放 (上次播放列表与进度)",
                "🔀 随机播放 (打乱顺序)",
                "📂 打开文件夹"
        };
        new AlertDialog.Builder(this)
                .setTitle("📁 " + title)
                .setItems(options, (dialog, which) -> {
                    if (which == 0) {
                        playAllInFolder(folderItem.guid, title, PLAY_MODE_SEQUENTIAL);
                    } else if (which == 1) {
                        playAllInFolder(folderItem.guid, title, PLAY_MODE_RESUME);
                    } else if (which == 2) {
                        playAllInFolder(folderItem.guid, title, PLAY_MODE_SHUFFLE);
                    } else if (which == 3) {
                        onItemClick(folderItem);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 媒体库长按弹窗选择：顺序播放、继续播放、随机播放 或 打开媒体库 */
    private void showLibActionDialog(final MediaDbItem lib) {
        if (lib == null) return;
        final String title = lib.title != null ? lib.title : "媒体库";
        final String[] options = new String[] {
                "▶ 顺序播放 (从第一项开始)",
                "⏯ 继续播放 (上次播放列表与进度)",
                "🔀 随机播放 (打乱顺序)",
                "📂 打开目录"
        };
        new AlertDialog.Builder(this)
                .setTitle("📁 " + title)
                .setItems(options, (dialog, which) -> {
                    if (which == 0) {
                        playAllInFolder(lib.guid, title, PLAY_MODE_SEQUENTIAL);
                    } else if (which == 1) {
                        playAllInFolder(lib.guid, title, PLAY_MODE_RESUME);
                    } else if (which == 2) {
                        playAllInFolder(lib.guid, title, PLAY_MODE_SHUFFLE);
                    } else if (which == 3) {
                        browseItems(lib.guid, lib.title);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 视频文件长按选择：连续播放、随机播放 或 播放单个 */
    private void showFileActionDialog(final PlayListItem fileItem) {
        if (fileItem == null) return;
        final String title = fileItem.getDisplayTitle();
        boolean hasBrowseList = savedBrowseList != null && !savedBrowseList.isEmpty();
        final String[] options = hasBrowseList
                ? new String[] { "▶ 从此处开始连续播放", "🔀 随机播放当前文件夹", "▶ 仅播放此项", "ℹ️ 查看详情" }
                : new String[] { "▶ 播放此项", "ℹ️ 查看详情" };
        new AlertDialog.Builder(this)
                .setTitle("🎞️ " + title)
                .setItems(options, (dialog, which) -> {
                    if (hasBrowseList) {
                        if (which == 0) {
                            startPlaylistPlayback(savedBrowseTitle != null ? savedBrowseTitle : "文件夹播放",
                                    savedBrowseList, currentBrowseGuid, PLAY_MODE_SEQUENTIAL, fileItem.guid);
                        } else if (which == 1) {
                            startPlaylistPlayback(savedBrowseTitle != null ? savedBrowseTitle : "文件夹播放",
                                    savedBrowseList, currentBrowseGuid, PLAY_MODE_SHUFFLE, null);
                        } else if (which == 2) {
                            launchPlayer(fileItem.guid, fileItem.title, fileItem.tvTitle != null ? fileItem.tvTitle : "",
                                    fileItem.episodeNumber, fileItem.poster, fileItem.getCategoryLabel(),
                                    fileItem.ts, fileItem.duration, fileItem.parentGuid, true);
                        } else {
                            showDetail(fileItem);
                        }
                    } else {
                        if (which == 0) {
                            launchPlayer(fileItem.guid, fileItem.title, fileItem.tvTitle != null ? fileItem.tvTitle : "",
                                    fileItem.episodeNumber, fileItem.poster, fileItem.getCategoryLabel(),
                                    fileItem.ts, fileItem.duration, fileItem.parentGuid);
                        } else {
                            showDetail(fileItem);
                        }
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 加载并以播放列表方式连续播放指定文件夹 */
    private void playAllInFolder(final String folderGuid, final String folderTitle, final int playMode) {
        if (folderGuid == null || folderGuid.isEmpty()) return;
        Toast.makeText(this, "正在获取「" + folderTitle + "」文件列表...", Toast.LENGTH_SHORT).show();

        String sortColumn = MediaSortHelper.getApiSortColumn(getSortField());
        String sortType = MediaSortHelper.getApiSortType(getSortOrder());
        final boolean useParent = folderGuid.startsWith("fv_");
        ItemListRequest request = useParent
                ? ItemListRequest.browseFolderByParent(folderGuid)
                : new ItemListRequest(folderGuid, BROWSE_TYPES, false, sortColumn, sortType, 500);
        request.pageSize = 500;

        apiManager.getApi().getItemList(request).enqueue(new Callback<ApiResponse<ItemListResponse>>() {
            @Override
            public void onResponse(Call<ApiResponse<ItemListResponse>> call,
                                   Response<ApiResponse<ItemListResponse>> response) {
                if (!response.isSuccessful() || response.body() == null || response.body().code != 0
                        || response.body().data == null || response.body().data.list == null) {
                    Toast.makeText(HomeActivity.this, "获取播放列表失败", Toast.LENGTH_SHORT).show();
                    return;
                }
                List<PlayListItem> list = response.body().data.list;
                startPlaylistPlayback(folderTitle, list, folderGuid, playMode, null);
            }

            @Override
            public void onFailure(Call<ApiResponse<ItemListResponse>> call, Throwable t) {
                Toast.makeText(HomeActivity.this, "加载失败: " + t.getMessage(), Toast.LENGTH_SHORT).show();
            }
        });
    }

    /** 播放当前已浏览文件夹中的所有文件（弹窗选择顺序播放、继续播放或随机播放） */
    private void playAllCurrentFolder() {
        if (savedBrowseList == null || savedBrowseList.isEmpty()) {
            Toast.makeText(this, "当前文件夹为空", Toast.LENGTH_SHORT).show();
            return;
        }
        final String title = savedBrowseTitle != null ? savedBrowseTitle : "当前目录";
        final String[] options = new String[] {
                "▶ 顺序播放 (从第一首开始)",
                "⏯ 继续播放 (上次播放列表与进度)",
                "🔀 随机播放 (打乱顺序)"
        };
        new AlertDialog.Builder(this)
                .setTitle("▶ 全部播放 · " + title)
                .setItems(options, (dialog, which) -> {
                    int mode = (which == 1) ? PLAY_MODE_RESUME : (which == 2 ? PLAY_MODE_SHUFFLE : PLAY_MODE_SEQUENTIAL);
                    startPlaylistPlayback(title, savedBrowseList, currentBrowseGuid, mode, null);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 过滤出可播放项并按播放模式（顺序、继续、随机）连续播放 */
    private void startPlaylistPlayback(String playlistTitle, List<PlayListItem> rawList,
                                       String folderGuid, int playMode, String startGuid) {
        startPlaylistPlayback(playlistTitle, rawList, folderGuid, playMode, startGuid, -1);
    }

    private void startPlaylistPlayback(String playlistTitle, List<PlayListItem> rawList,
                                       String folderGuid, int playMode, String startGuid, long initialTs) {
        if (rawList == null || rawList.isEmpty()) {
            Toast.makeText(this, "该文件夹下没有可播放的视频", Toast.LENGTH_SHORT).show();
            return;
        }
        List<PlayListItem> playableList = new ArrayList<>();
        for (PlayListItem pli : rawList) {
            if (pli != null && !pli.isFolder() && !"TV".equals(pli.type)) {
                playableList.add(pli);
            }
        }
        if (playableList.isEmpty()) {
            Toast.makeText(this, "该文件夹下没有可直接播放的视频（若有子文件夹请进入子文件夹播放）", Toast.LENGTH_LONG).show();
            return;
        }

        // 默认按设置排序
        MediaSortHelper.sortItems(playableList, getSortField(), getSortOrder(), false);

        int startIndex = 0;
        long targetTs = 0;

        if (playMode == PLAY_MODE_SHUFFLE) {
            java.util.Collections.shuffle(playableList, new java.util.Random());
            startIndex = 0;
            targetTs = 0;
            Toast.makeText(this, "🔀 随机打乱共 " + playableList.size() + " 首，开始播放", Toast.LENGTH_SHORT).show();
        } else if (playMode == PLAY_MODE_RESUME) {
            // 查找上次播放记录
            String lastGuid = null;
            long lastTs = 0;
            if (folderGuid != null && !folderGuid.isEmpty()) {
                lastGuid = prefs.getString("folder_last_guid_" + folderGuid, null);
                lastTs = prefs.getLong("folder_last_ts_" + folderGuid, 0L);
            }
            boolean found = false;
            if (lastGuid != null) {
                for (int i = 0; i < playableList.size(); i++) {
                    if (lastGuid.equals(playableList.get(i).guid)) {
                        startIndex = i;
                        targetTs = lastTs;
                        found = true;
                        break;
                    }
                }
            }
            if (!found) {
                // 服务端进度兜底：寻找进度大于0的项
                for (int i = 0; i < playableList.size(); i++) {
                    PlayListItem item = playableList.get(i);
                    if (item.ts > 0) {
                        startIndex = i;
                        targetTs = item.ts;
                        found = true;
                        break;
                    }
                }
            }
            if (found) {
                PlayListItem resumeItem = playableList.get(startIndex);
                Toast.makeText(this, "⏯ 从第 " + (startIndex + 1) + "/" + playableList.size() + " 首「"
                        + resumeItem.getDisplayTitle() + "」继续播放", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "未找到上次播放记录，从第一项开始播放", Toast.LENGTH_SHORT).show();
            }
        } else {
            // PLAY_MODE_SEQUENTIAL
            if (startGuid != null) {
                for (int i = 0; i < playableList.size(); i++) {
                    if (startGuid.equals(playableList.get(i).guid)) {
                        startIndex = i;
                        break;
                    }
                }
            }
            if (initialTs >= 0) {
                targetTs = initialTs;
            }
        }

        String finalTitle = (playMode == PLAY_MODE_SHUFFLE) ? (playlistTitle + " (随机)") : playlistTitle;
        PlaylistHolder.setPlaylist(finalTitle, playableList);

        PlayListItem first = playableList.get(startIndex);
        if ("Audio".equalsIgnoreCase(first.type)) {
            List<PlayListItem> audioList = new ArrayList<>();
            int audioIdx = 0;
            for (PlayListItem pli : playableList) {
                if ("Audio".equalsIgnoreCase(pli.type)) {
                    if (first.guid != null && first.guid.equals(pli.guid)) audioIdx = audioList.size();
                    audioList.add(pli);
                }
            }
            if (!audioList.isEmpty()) {
                MusicPlaybackService.startQueue(this, audioList, audioIdx);
                startActivity(new Intent(this, MusicPlayerActivity.class));
                return;
            }
        }
        long ts = (targetTs > 0) ? targetTs : first.ts;
        if (first.duration > 0 && first.duration < 600) {
            ts = 0;
        }

        Intent intent = new Intent(this, PlayerActivity.class);
        intent.putExtra("guid", first.guid);
        intent.putExtra("title", first.getDisplayTitle());
        intent.putExtra("tv_title", first.tvTitle != null ? first.tvTitle : "");
        intent.putExtra("episode_number", first.episodeNumber);
        intent.putExtra("poster", first.poster);
        intent.putExtra("category", first.getCategoryLabel());
        intent.putExtra("ts", ts);
        intent.putExtra("duration", first.duration);
        intent.putExtra("parent_guid", first.parentGuid);
        intent.putExtra("is_folder_playlist", true);
        intent.putExtra("folder_guid", folderGuid);
        intent.putExtra("playlist_title", finalTitle);
        startActivity(intent);
    }


    // ==================== 详情页（getPlayInfo → 按类型展示） ====================


    private void showDetail(PlayListItem item) {
        if (item != null && "Audio".equalsIgnoreCase(item.type)) {
            launchPlayer(item.guid, item.getDisplayTitle(), "", 0, item.poster, "Audio", 0, item.duration, item.parentGuid);
            return;
        }
        switchTab(0);
        detailInLibrary = false;
        detailContainer = moviesContainer;
        showingDetail = true;
        showingOverview = false;
        showingEpisodes = false;
        moviesContainer.removeAllViews();
        tvMoviesLoading.setVisibility(View.VISIBLE);
        tvMoviesLoading.setText("加载中...");

        Map<String, String> body = new HashMap<>();
        body.put("item_guid", item.guid);
        apiManager.getApi().getPlayInfo(body).enqueue(new Callback<ApiResponse<PlayInfoResponse>>() {
            @Override
            public void onResponse(Call<ApiResponse<PlayInfoResponse>> call,
                                   Response<ApiResponse<PlayInfoResponse>> response) {
                tvMoviesLoading.setVisibility(View.GONE);
                if (!response.isSuccessful() || response.body() == null || response.body().code != 0
                        || response.body().data == null) {
                    Toast.makeText(HomeActivity.this, "获取详情失败", Toast.LENGTH_SHORT).show();
                    return;
                }
                buildDetailPage(item, response.body().data);
            }
            @Override
            public void onFailure(Call<ApiResponse<PlayInfoResponse>> call, Throwable t) {
                tvMoviesLoading.setVisibility(View.GONE);
                Toast.makeText(HomeActivity.this, "网络错误: " + t.getMessage(), Toast.LENGTH_SHORT).show();
            }
        });
    }

    /** 构建详情页（自动适应横竖屏） */

    private void buildDetailPage(PlayListItem item, PlayInfoResponse info) {
        detailContainer = detailInLibrary ? libraryContainer : moviesContainer;
        detailContainer.removeAllViews();
        savedDetailItem = item; savedDetailInfo = info;
        updateTopBarState();

        boolean isLandscape = getResources().getConfiguration().orientation
                == android.content.res.Configuration.ORIENTATION_LANDSCAPE;

        String epTitle = info.item != null && info.item.title != null ? info.item.title : item.title;
        String series = info.item != null && info.item.tvTitle != null ? info.item.tvTitle : "";
        int epNum = info.item != null ? info.item.episodeNumber : 0;

        // 海报路径
        String posterPath = info.getBackdropPath();
        if (posterPath == null || (info.item != null && info.item.backdrops == null)) {
            posterPath = info.getPosterPath();
        }
        if (posterPath == null) posterPath = item.poster;
        String pUrl = makePosterUrl(posterPath);

        if (isLandscape) {
            // ====== 横屏：左图右信息 ======
            int screenH = getResources().getDisplayMetrics().heightPixels;
            // 减去状态栏高度
            int sbId = getResources().getIdentifier("status_bar_height", "dimen", "android");
            if (sbId > 0) screenH -= getResources().getDimensionPixelSize(sbId);
            // 减去导航栏高度（手机底部虚拟键）
            int nbId = getResources().getIdentifier("navigation_bar_height", "dimen", "android");
            if (nbId > 0) screenH -= getResources().getDimensionPixelSize(nbId);
            // 减去底部菜单栏（tabBar 52dp + divider 1dp）
            screenH -= (int)(53 * getResources().getDisplayMetrics().density);
            LinearLayout rootRow = new LinearLayout(this);
            rootRow.setOrientation(LinearLayout.HORIZONTAL);
            rootRow.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

            // 左侧海报（宽度由屏幕高度推导，图片按原始比例显示，不变形）
            String posterLand = makePosterUrl(item.poster);
            RoundedImageView posterL = new RoundedImageView(this);
            int posterW = screenH * 2 / 3;
            posterL.setLayoutParams(new LinearLayout.LayoutParams(posterW, screenH));
            posterL.setAdjustViewBounds(true);
            posterL.setScaleType(ImageView.ScaleType.FIT_CENTER);
            posterL.setBackgroundColor(0xFF1A1A1A);
            posterL.setCornerRadius(10);
            if (posterLand != null) {
                posterL.setTag(posterLand);
                new Handler(Looper.getMainLooper()).post(() ->
                        SimpleImageLoader.load(posterLand, posterL, apiManager.getClient()));
            }
            rootRow.addView(posterL);

            // 右侧滚动内容
            androidx.core.widget.NestedScrollView svL = new androidx.core.widget.NestedScrollView(this);
            svL.setLayoutParams(new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.MATCH_PARENT, 1));
            LinearLayout contentL = new LinearLayout(this);
            contentL.setOrientation(LinearLayout.VERTICAL);
            contentL.setPadding(16, 12, 16, 20);
            buildDetailContent(contentL, item, info, epTitle, series, epNum, pUrl);
            svL.addView(contentL);
            rootRow.addView(svL);

            detailContainer.addView(rootRow);
        } else {
            // ====== 竖屏：原布局 ======
            androidx.core.widget.NestedScrollView scrollView = new androidx.core.widget.NestedScrollView(this);
            scrollView.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            LinearLayout content = new LinearLayout(this);
            content.setOrientation(LinearLayout.VERTICAL);
            content.setPadding(14, 0, 14, 20);

            // 海报
            RoundedImageView poster = new RoundedImageView(this);
            poster.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            poster.setAdjustViewBounds(true);
            poster.setScaleType(ImageView.ScaleType.FIT_CENTER);
            poster.setBackgroundColor(0xFF2A2A2A);
            poster.setCornerRadius(10);
            if (pUrl != null) { poster.setTag(pUrl); }
            content.addView(poster);
            if (pUrl != null) {
                new Handler(Looper.getMainLooper()).post(() ->
                        SimpleImageLoader.load(pUrl, poster, apiManager.getClient()));
            }
            content.addView(makeSpacer(12));

            buildDetailContent(content, item, info, epTitle, series, epNum, pUrl);

            scrollView.addView(content);
            detailContainer.addView(scrollView);
        }
    }

    /** 构建详情页的核心内容（元数据、播放按钮、剧集列表） */
    private void buildDetailContent(LinearLayout content, PlayListItem item, PlayInfoResponse info,
                                     String epTitle, String series, int epNum, String pUrl) {
        // 元数据卡片
        LinearLayout metaCard = new LinearLayout(this);
        metaCard.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        metaCard.setOrientation(LinearLayout.VERTICAL);
        metaCard.setBackgroundResource(R.drawable.bg_card);
        metaCard.setPadding(16, 16, 16, 16);

        String typeStr = info.type != null ? info.type : item.type;
        String typeLabel = "电影";
        if ("Episode".equals(typeStr)) typeLabel = "剧集";
        else if ("TV".equals(typeStr)) typeLabel = "剧集";
        else if ("Video".equals(typeStr)) typeLabel = "视频";

        int runtime = info.item != null ? info.item.runtime : item.runtime;
        // 评分（直接取 PlayListItem 的 vote_average，保留一位小数）
        String voteRaw = item.voteAverage;
        Log.d("Detail", "vote_average from item=" + voteRaw
                + " from info=" + (info.item != null ? info.item.voteAverage : "null"));
        String voteLabel = null;
        if (voteRaw != null && !voteRaw.isEmpty() && !voteRaw.equals("0") && !voteRaw.equals("0.0")) {
            try {
                float v = Float.parseFloat(voteRaw);
                // vote_average 是 0~10 分
                if (v > 0) voteLabel = String.format("%.1f", v);
            } catch (NumberFormatException ignored) {}
        }

        // 标题
        String bigTitle = epTitle != null ? epTitle : "";
        if (!series.isEmpty() && !series.equals(epTitle)) {
            bigTitle = series + (epNum > 0 ? " " + epNum + "集" : "") + (epTitle != null ? " " + epTitle : "");
        }
        TextView titleBig = new TextView(this);
        titleBig.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        titleBig.setText(bigTitle.trim());
        titleBig.setTextColor(0xFFFFFFFF);
        titleBig.setTextSize(22);
        titleBig.setTypeface(Typeface.DEFAULT_BOLD);
        metaCard.addView(titleBig);

        // 元数据行
        TextView meta = new TextView(this);
        meta.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        meta.setPadding(0, 8, 0, 0);
        StringBuilder mt = new StringBuilder(typeLabel);

        // 剧集信息: tv_title + parent_title
        if (info.item != null && info.item.tvTitle != null && !info.item.tvTitle.isEmpty()) {
            mt.append("  ").append(info.item.tvTitle);
        }
        if (info.item != null && info.item.parentTitle != null && !info.item.parentTitle.isEmpty()) {
            mt.append("  ").append(info.item.parentTitle);
        }
        if (info.item != null && info.item.episodeNumber > 0) {
            mt.append("  第").append(info.item.episodeNumber).append("集");
        }
        if (runtime > 0) {
            long durSec = info.item != null && info.item.duration > 0 ? info.item.duration : runtime * 60L;
            mt.append("  ·  ").append(formatDuration(durSec));
        }
        if (voteLabel != null) mt.append("  ·  ⭐").append(voteLabel);
        // 分辨率
        if (info.item != null && info.item.mediaStream != null
                && info.item.mediaStream.resolutions != null
                && !info.item.mediaStream.resolutions.isEmpty()) {
            mt.append("  ·  ").append(info.item.mediaStream.resolutions.get(0));
        }
        // 音轨
        if (info.item != null && info.item.mediaStream != null
                && info.item.mediaStream.audioType != null
                && !info.item.mediaStream.audioType.isEmpty()) {
            mt.append("  ·  ").append(info.item.mediaStream.audioType.get(0));
        }
        // 日期
        String date = info.item != null && info.item.releaseDate != null
                ? info.item.releaseDate : (info.item != null ? info.item.airDate : null);
        if (date != null && !date.isEmpty()) mt.append("  ·  ").append(date);

        meta.setText(mt.toString());
        meta.setTextColor(0xFFB0B0B0);
        meta.setTextSize(13);
        metaCard.addView(meta);

        content.addView(metaCard);
        content.addView(makeSpacer(12));

        // 简介卡片（限制最大高度，防过长遮挡播放按钮）
        String overview = info.item != null && info.item.overview != null
                ? info.item.overview : item.overview;
        if (overview != null && !overview.isEmpty()) {
            LinearLayout overviewCard = new LinearLayout(this);
            overviewCard.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            overviewCard.setOrientation(LinearLayout.VERTICAL);
            overviewCard.setBackgroundResource(R.drawable.bg_card);
            overviewCard.setPadding(16, 16, 16, 16);

            TextView ovLabel = new TextView(this);
            ovLabel.setText("简介");
            ovLabel.setTextColor(0xFF78909C);
            ovLabel.setTextSize(11);
            overviewCard.addView(ovLabel);

            androidx.core.widget.NestedScrollView ovScroll = new androidx.core.widget.NestedScrollView(this);
            ovScroll.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 300));
            ovScroll.setFocusable(true);
            ovScroll.setDescendantFocusability(ViewGroup.FOCUS_BEFORE_DESCENDANTS);
            ovScroll.setOnFocusChangeListener((v, hasFocus) ->
                    v.setBackgroundColor(hasFocus ? 0x33FFFFFF : 0x00000000));
            TextView ov = new TextView(this);
            ov.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            ov.setPadding(0, 6, 0, 0);
            ov.setText(overview);
            ov.setTextColor(0xFFCCCCCC);
            ov.setTextSize(14);
            ov.setLineSpacing(6, 1);
            ovScroll.addView(ov);
            overviewCard.addView(ovScroll);
            content.addView(overviewCard);
            content.addView(makeSpacer(12));
        }

        // 播放时间优先用 play/info 接口返回的 ts
        final String pGuid = item.guid;
        final String pTitle = item.title;
        final String pTV = info.item != null && info.item.tvTitle != null ? info.item.tvTitle : "";
        final String pPoster = item.poster;
        final String pCat = item.getCategoryLabel();
        // 总时长优先用 play/info 接口的 duration，其次是 runtime 转秒、item.duration
        long rawDur = info.item != null && info.item.duration > 0 ? info.item.duration : 0;
        if (rawDur <= 0 && info.item != null && info.item.runtime > 0) rawDur = info.item.runtime * 60L;
        if (rawDur <= 0) rawDur = item.duration;
        final long pDur = rawDur;

        // 自动续播对10分钟(<600s)以下视频不生效，避免MV类视频从末尾几秒开始播
        long initTs = info.ts > 0 ? info.ts : (item.ts > 0 ? item.ts : 0);
        if (pDur > 0 && pDur < 600) {
            initTs = 0;
        }
        final long pTs = initTs;

        final int pEp = item.episodeNumber;
        final int progressPct = pDur > 0 ? Math.max(0, Math.min(100, (int)(pTs * 100 / pDur))) : 0;
        final String pParentGuid = info.parentGuid != null && !info.parentGuid.isEmpty() ? info.parentGuid : item.parentGuid;

        Log.d("Detail", "pParentGuid=" + pParentGuid + " info.parentGuid=" + (info.parentGuid != null ? info.parentGuid : "null") + " item.parentGuid=" + (item.parentGuid != null ? item.parentGuid : "null"));
        Log.d("Detail", "继续播放: info.ts=" + info.ts + " pTs=" + pTs
                + " info.item.duration=" + (info.item != null ? info.item.duration : "null")
                + " info.item.runtime=" + (info.item != null ? info.item.runtime : "null")
                + " item.duration=" + item.duration + " → pDur=" + pDur + " pTs=" + pTs);

        // 播放按钮（自适应高度）
        FrameLayout playFrame = new FrameLayout(this);
        playFrame.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        playFrame.setMinimumHeight(120);

        // 圆角背景（进度条用两层：蓝色 + 灰色）
        if (progressPct > 0) {
            LinearLayout progressLayer = new LinearLayout(this);
            progressLayer.setLayoutParams(new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            progressLayer.setOrientation(LinearLayout.HORIZONTAL);
            progressLayer.setWeightSum(100);

            // 圆角裁剪：用 GradientDrawable 做背景
            float r = 10 * getResources().getDisplayMetrics().density;
            android.graphics.drawable.GradientDrawable gd = new android.graphics.drawable.GradientDrawable();
            gd.setCornerRadii(new float[]{r, r, r, r, r, r, r, r});
            gd.setColor(0xFF455A64);
            progressLayer.setBackgroundDrawable(gd);

            View fill = new View(HomeActivity.this);
            fill.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, progressPct));
            android.graphics.drawable.GradientDrawable fillGd = new android.graphics.drawable.GradientDrawable();
            fillGd.setCornerRadii(new float[]{r, r, 0, 0, 0, 0, r, r});
            fillGd.setColor(0xFF2D6CDF);
            fill.setBackgroundDrawable(fillGd);
            progressLayer.addView(fill);

            View rest = new View(HomeActivity.this);
            rest.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 100 - progressPct));
            rest.setBackgroundColor(0x00000000); // 透明
            progressLayer.addView(rest);

            playFrame.addView(progressLayer);
        } else {
            playFrame.setBackgroundResource(R.drawable.bg_btn_primary);
        }

        // 按钮本身（透明背景）
        Button playBtn = new Button(this);
        playBtn.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        playBtn.setBackgroundDrawable(null);
        playBtn.setFocusable(true);
        playBtn.setGravity(Gravity.CENTER);
        playBtn.setTextColor(0xFFFFFFFF);
        playBtn.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) {
                playBtn.setBackgroundColor(0x55FFFFFF);
                playBtn.setScaleX(1.05f);
                playBtn.setScaleY(1.05f);
            } else {
                playBtn.setBackgroundColor(0x00000000);
                playBtn.setScaleX(1.0f);
                playBtn.setScaleY(1.0f);
            }
        });
        if (pTs > 0) {
            playBtn.setTextSize(16);
            playBtn.setText("▶  继续播放\n" + formatDuration(pTs) + " / " + formatDuration(pDur));
        } else {
            playBtn.setTextSize(22);
            playBtn.setText("▶  播放");
        }

        playBtn.setOnClickListener(v -> {
            Object tag = playBtn.getTag();
            long finalDur = tag instanceof Long ? (Long) tag : pDur;
            launchPlayer(pGuid, pTitle, pTV, pEp, pPoster, pCat, pTs, finalDur, pParentGuid);
        });
        playFrame.addView(playBtn);
        content.addView(playFrame);
        playBtn.post(() -> playBtn.requestFocus());

        // Episode 类型 → 先加载季列表，点击某季后加载该季剧集
        if (item.guid != null && !item.guid.isEmpty()) {
            content.addView(makeSpacer(16));
            loadSeasons(content, item.guid, item, playBtn, pTs);
        }

    }

    /** 加载剧集列表并按季分组 */
    private void loadEpisodes(final LinearLayout content, final String parentGuid, final PlayListItem item,
                              final Button playBtn, final long pTs, final String historyEpGuid) {
        apiManager.getApi().getEpisodeList(parentGuid).enqueue(new Callback<ApiResponse<List<PlayListItem>>>() {
            @Override
            public void onResponse(Call<ApiResponse<List<PlayListItem>>> call,
                                   Response<ApiResponse<List<PlayListItem>>> response) {
                if (!response.isSuccessful() || response.body() == null || response.body().code != 0
                        || response.body().data == null || response.body().data.isEmpty()) return;
                List<PlayListItem> episodes = response.body().data;
                showSeasons(content, episodes, item);
                // 从剧集列表找到历史记录对应的那一集，用它的精确时长覆盖按钮
                if (historyEpGuid != null) {
                    for (PlayListItem ep : episodes) {
                        if (ep.guid.equals(historyEpGuid) && ep.duration > 0) {
                            long epDur = ep.duration;
                            Log.d("Detail", "剧集列表匹配到历史剧集, 时长: " + formatDuration(epDur));
                            playBtn.setText("▶  继续播放\n" + formatDuration(pTs) + " / " + formatDuration(epDur));
                            // 通过 setTag 把修正后的时长传给点击监听
                            playBtn.setTag(epDur);
                            break;
                        }
                    }
                }
            }
            @Override public void onFailure(Call<ApiResponse<List<PlayListItem>>> call, Throwable t) {}
        });
    }

    /** 加载季列表（从 season/list 接口获取） */
    private void loadSeasons(final LinearLayout content, final String itemGuid, final PlayListItem item,
                              final Button playBtn, final long pTs) {
        TextView h = new TextView(this);
        h.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        h.setPadding(0, 14, 0, 14);
        h.setText("选择剧集");
        h.setTextColor(0xFFEEEEEE);
        h.setTextSize(22);
        h.setTypeface(Typeface.DEFAULT_BOLD);
        content.addView(h);

        apiManager.getApi().getSeasonList(itemGuid).enqueue(new Callback<ApiResponse<List<PlayListItem>>>() {
            @Override
            public void onResponse(Call<ApiResponse<List<PlayListItem>>> call,
                                   Response<ApiResponse<List<PlayListItem>>> response) {
                if (!response.isSuccessful() || response.body() == null || response.body().code != 0
                        || response.body().data == null || response.body().data.isEmpty()) return;
                List<PlayListItem> seasons = response.body().data;
                for (final PlayListItem season : seasons) {
                    LinearLayout card = new LinearLayout(HomeActivity.this);
                    card.setOrientation(LinearLayout.HORIZONTAL);
                    card.setBackgroundResource(R.drawable.bg_media_card);
                    card.setPadding(16, 18, 16, 18);
                    card.setFocusable(true);
                    card.setMinimumHeight(56);

                    LinearLayout tc = new LinearLayout(HomeActivity.this);
                    tc.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
                    tc.setOrientation(LinearLayout.VERTICAL);
                    tc.setGravity(Gravity.CENTER_VERTICAL);
                    TextView st = new TextView(HomeActivity.this);
                    st.setTextSize(16);
                    st.setTextColor(0xFFEEEEEE);
                    st.setText("第 " + season.seasonNumber + " 季");
                    tc.addView(st);
                    TextView ss = new TextView(HomeActivity.this);
                    ss.setTextSize(12);
                    ss.setTextColor(0xFF808080);
                    ss.setText(season.localNumberOfEpisodes > 0 ? season.localNumberOfEpisodes + " 集" : "");
                    tc.addView(ss);
                    card.addView(tc);

                    TextView ar = new TextView(HomeActivity.this);
                    ar.setLayoutParams(new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                    ar.setText(">");
                    ar.setTextColor(0xFF808080);
                    ar.setTextSize(20);
                    ar.setGravity(Gravity.CENTER);
                    ar.setPadding(8, 0, 0, 0);
                    card.addView(ar);

                    final int sn = season.seasonNumber > 0 ? season.seasonNumber : 1;
                    card.setOnClickListener(v -> loadEpisodesForSeason(season.guid, sn, item));
                    content.addView(card);
                    content.addView(makeSpacer(12));
                }
            }
            @Override public void onFailure(Call<ApiResponse<List<PlayListItem>>> call, Throwable t) {}
        });
    }

    /** 加载某季的剧集列表（点击季后调用，直接显示剧集） */
    private void loadEpisodesForSeason(String seasonGuid, int seasonNumber, PlayListItem original) {
        showingEpisodes = true;
        detailContainer = detailInLibrary ? libraryContainer : moviesContainer;
        detailContainer.removeAllViews();
        // 标题
        TextView h = new TextView(this);
        h.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 78));
        h.setGravity(Gravity.CENTER);
        h.setText("第 " + seasonNumber + " 季");
        h.setTextColor(0xFFEEEEEE);
        h.setTextSize(21);
        h.setTypeface(Typeface.DEFAULT_BOLD);
        detailContainer.addView(h);
        detailContainer.addView(makeSpacer(8));

        apiManager.getApi().getEpisodeList(seasonGuid).enqueue(new Callback<ApiResponse<List<PlayListItem>>>() {
            @Override
            public void onResponse(Call<ApiResponse<List<PlayListItem>>> call,
                                   Response<ApiResponse<List<PlayListItem>>> response) {
                if (!response.isSuccessful() || response.body() == null || response.body().code != 0
                        || response.body().data == null || response.body().data.isEmpty()) return;
                for (PlayListItem ep : response.body().data) {
                    detailContainer.addView(makeEpisodeItem(ep, ep.guid.equals(original.guid)));
                    detailContainer.addView(makeSpacer(8));
                }
            }
            @Override public void onFailure(Call<ApiResponse<List<PlayListItem>>> call, Throwable t) {}
        });
    }

    /** 显示季列表 */

    private void showSeasons(LinearLayout content, List<PlayListItem> episodes, PlayListItem item) {
        Map<Integer, List<PlayListItem>> map = new HashMap<>();
        for (PlayListItem ep : episodes) {
            int sn = ep.seasonNumber > 0 ? ep.seasonNumber : 1;
            if (!map.containsKey(sn)) map.put(sn, new ArrayList<PlayListItem>());
            map.get(sn).add(ep);
        }

        TextView h = new TextView(this);
        h.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        h.setPadding(0, 14, 0, 14);
        h.setText("选择剧集");
        h.setTextColor(0xFFEEEEEE);
        h.setTextSize(22);
        h.setTypeface(Typeface.DEFAULT_BOLD);
        content.addView(h);

        List<Integer> nums = new ArrayList<>(map.keySet());
        java.util.Collections.sort(nums);
        for (final int sn : nums) {
            final List<PlayListItem> eps = map.get(sn);
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.HORIZONTAL);
            card.setBackgroundResource(R.drawable.bg_media_card);
            card.setPadding(16, 18, 16, 18);
            card.setFocusable(true);
            card.setMinimumHeight(56);

            LinearLayout tc = new LinearLayout(this);
            tc.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            tc.setOrientation(LinearLayout.VERTICAL);
            tc.setGravity(Gravity.CENTER_VERTICAL);
            TextView st = new TextView(this); st.setTextSize(16); st.setTextColor(0xFFEEEEEE);
            st.setText("第 " + sn + " 季"); tc.addView(st);
            TextView ss = new TextView(this); ss.setTextSize(12); ss.setTextColor(0xFF808080);
            ss.setText(eps.size() + " 集"); tc.addView(ss);
            card.addView(tc);

            TextView ar = new TextView(this);
            ar.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            ar.setText(">"); ar.setTextColor(0xFF808080); ar.setTextSize(20);
            ar.setGravity(Gravity.CENTER); ar.setPadding(8, 0, 0, 0);
            card.addView(ar);

            card.setOnClickListener(v -> showEpisodes(eps, sn, sn == 1 ? item : item));
            content.addView(card);
            content.addView(makeSpacer(6));
        }
    }

    /** 显示某季剧集 */

    private void showEpisodes(List<PlayListItem> eps, int sn, PlayListItem original) {
        showingEpisodes = true;
        detailContainer = detailInLibrary ? libraryContainer : moviesContainer;
        detailContainer.removeAllViews();

        // 标题
        TextView h = new TextView(this);
        h.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 78));
        h.setGravity(Gravity.CENTER);
        h.setText("第 " + sn + " 季");
        h.setTextColor(0xFFEEEEEE); h.setTextSize(21);
        h.setTypeface(Typeface.DEFAULT_BOLD);
        detailContainer.addView(h);
        detailContainer.addView(makeSpacer(8));

        for (PlayListItem ep : eps) {
            detailContainer.addView(makeEpisodeItem(ep, ep.guid.equals(original.guid)));
            detailContainer.addView(makeSpacer(8));
        }
    }

    /** 剧集条目卡片 */

    private View makeEpisodeItem(PlayListItem ep, boolean isCurrent) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setBackgroundResource(R.drawable.bg_media_card);
        card.setPadding(16, 18, 16, 18);
        card.setFocusable(true);
        card.setMinimumHeight(72);

        final String eg = ep.guid;
        final String et = ep.title;
        final String eTV = ep.tvTitle != null ? ep.tvTitle : "";
        final int eEp = ep.episodeNumber;
        final String epPo = ep.poster;
        final String epCa = ep.getCategoryLabel();
        final long epTs = ep.ts > 0 ? ep.ts : 0;
        final long epDu = ep.duration;
        final String epPG = ep.parentGuid;

        card.setOnClickListener(v -> launchPlayer(eg, et, eTV, eEp, epPo, epCa, epTs, epDu, epPG));

        LinearLayout infoCol = new LinearLayout(this);
        infoCol.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 1));
        infoCol.setOrientation(LinearLayout.HORIZONTAL);
        infoCol.setGravity(Gravity.CENTER_VERTICAL);

        TextView epNum = new TextView(this);
        epNum.setLayoutParams(new LinearLayout.LayoutParams(130, ViewGroup.LayoutParams.WRAP_CONTENT));
        epNum.setTextSize(15);
        epNum.setTextColor(isCurrent ? 0xFF81C784 : 0xFFB0B0B0);
        epNum.setText("EP" + (ep.episodeNumber > 0 ? ep.episodeNumber : "?"));
        infoCol.addView(epNum);

        LinearLayout rightCol = new LinearLayout(this);
        rightCol.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        rightCol.setOrientation(LinearLayout.HORIZONTAL);
        rightCol.setGravity(Gravity.CENTER_VERTICAL);

        TextView epTitle = new TextView(this);
        epTitle.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        epTitle.setTextSize(15);
        epTitle.setTextColor(isCurrent ? 0xFF81C784 : 0xFFEEEEEE);
        epTitle.setSingleLine(true);
        epTitle.setEllipsize(TextUtils.TruncateAt.END);
        StringBuilder titleText = new StringBuilder();
        titleText.append(ep.title != null ? ep.title : "未知");
        if (ep.duration > 0) titleText.append("  ").append(formatDuration(ep.duration));
        if (ep.watched == 1) titleText.append("  ✓");
        epTitle.setText(titleText.toString());
        rightCol.addView(epTitle);

        infoCol.addView(rightCol);
        card.addView(infoCol);

        if (isCurrent) {
            TextView badge = new TextView(this);
            badge.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
            badge.setGravity(Gravity.CENTER);
            badge.setText("当前");
            badge.setTextColor(0xFF81C784);
            badge.setTextSize(12);
            badge.setPadding(8, 0, 0, 8);
            card.addView(badge);
        } else {
            Button playEp = new Button(this);
            playEp.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, 76));
            playEp.setBackgroundResource(R.drawable.bg_btn_primary);
            playEp.setText("播放");
            playEp.setTextColor(0xFFEEEEEE);
            playEp.setTextSize(12);
            playEp.setFocusable(true);
            playEp.setOnFocusChangeListener((v, hasFocus) -> {
                playEp.setScaleX(hasFocus ? 1.08f : 1.0f);
                playEp.setScaleY(hasFocus ? 1.08f : 1.0f);
            });
            playEp.setPadding(14, 0, 14, 0);
            playEp.setOnClickListener(v -> launchPlayer(eg, et, eTV, eEp, epPo, epCa, epTs, epDu, epPG));
            card.addView(playEp);
        }
        return card;
    }


    // ==================== 媒体库 Tab ====================


    private void loadMediaLibraries() {
        isSearching = false;
        etSearch.setVisibility(View.VISIBLE);
        clearContainer(libraryContainer, tvLibraryLoading, tvLibraryEmpty);
        tvLibraryLoading.setVisibility(View.VISIBLE);

        final int[] retryCount = {1};
        apiManager.getApi().getMediaDbList().enqueue(new Callback<ApiResponse<List<MediaDbItem>>>() {
            @Override
            public void onResponse(Call<ApiResponse<List<MediaDbItem>>> call,
                                   Response<ApiResponse<List<MediaDbItem>>> response) {
                tvLibraryLoading.setVisibility(View.GONE);
                // Auth Failed 时重试一次
                if (response.body() != null && response.body().code == -2 && retryCount[0] > 0) {
                    retryCount[0]--;
                    Log.d("Home", "Auth Failed，重试中...");
                    call.clone().enqueue(this);
                    return;
                }
                if (response.isSuccessful() && response.body() != null && response.body().code == 0
                        && response.body().data != null && !response.body().data.isEmpty()) {
                    mediaLibraries.clear();
                    List<MediaDbItem> filteredLibs = new ArrayList<>();
                    for (MediaDbItem lib : response.body().data) {
                        if (!lib.refreshDisabled) filteredLibs.add(lib);
                    }
                    mediaLibraries.addAll(filteredLibs);
                    populateLibGrid(libraryContainer, filteredLibs);
                    return;
                }
                tvLibraryEmpty.setVisibility(View.VISIBLE);
            }
            @Override
            public void onFailure(Call<ApiResponse<List<MediaDbItem>>> call, Throwable t) {
                tvLibraryLoading.setVisibility(View.GONE);
                tvLibraryEmpty.setText("加载失败: " + t.getMessage());
                tvLibraryEmpty.setVisibility(View.VISIBLE);
            }
        });
    }

    // ==================== 搜索 ====================

    private void setupSearch() {
        etSearch.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH
                    || actionId == EditorInfo.IME_ACTION_DONE
                    || actionId == EditorInfo.IME_ACTION_GO
                    || actionId == EditorInfo.IME_ACTION_UNSPECIFIED
                    || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                String q = v.getText().toString().trim();
                performSearch(q);
                hideKeyboard();
                return true;
            }
            return false;
        });
    }

    private void hideKeyboard() {
        android.view.inputmethod.InputMethodManager imm =
                (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(etSearch.getWindowToken(), 0);
    }

    private void performSearch(final String query) {
        if (query.isEmpty()) {
            clearSearch();
            return;
        }
        isSearching = true;
        libraryContainer.removeAllViews();
        tvLibraryLoading.setVisibility(View.VISIBLE);
        tvLibraryLoading.setText("搜索中...");

        final boolean pinyinEnabled = prefs.getBoolean("pref_pinyin_search", true);

        SearchHelper.search(apiManager, query, new SearchHelper.SearchCallback() {
            @Override
            public void onResults(List<PlayListItem> results) {
                tvLibraryLoading.setVisibility(View.GONE);
                showSearchResults(results);
            }

            @Override
            public void onEmpty() {
                if (pinyinEnabled && savedBrowseList != null && !savedBrowseList.isEmpty()) {
                    List<PlayListItem> pinyinMatches = new ArrayList<>();
                    for (PlayListItem item : savedBrowseList) {
                        if (item != null && PinyinUtils.match(item.getDisplayTitle(), query)) {
                            pinyinMatches.add(item);
                        }
                    }
                    if (!pinyinMatches.isEmpty()) {
                        tvLibraryLoading.setVisibility(View.GONE);
                        showSearchResults(pinyinMatches);
                        return;
                    }
                }
                tvLibraryLoading.setVisibility(View.GONE);
                showSearchEmpty();
            }

            @Override
            public void onError(String msg) {
                if (pinyinEnabled && savedBrowseList != null && !savedBrowseList.isEmpty()) {
                    List<PlayListItem> pinyinMatches = new ArrayList<>();
                    for (PlayListItem item : savedBrowseList) {
                        if (item != null && PinyinUtils.match(item.getDisplayTitle(), query)) {
                            pinyinMatches.add(item);
                        }
                    }
                    if (!pinyinMatches.isEmpty()) {
                        tvLibraryLoading.setVisibility(View.GONE);
                        showSearchResults(pinyinMatches);
                        return;
                    }
                }
                tvLibraryLoading.setVisibility(View.GONE);
                tvLibraryEmpty.setText(msg);
                tvLibraryEmpty.setVisibility(View.VISIBLE);
            }
        });
    }

    private void showSearchResults(List<PlayListItem> results) {
        savedBrowseList = results;
        savedBrowseTitle = "搜索: " + etSearch.getText().toString().trim();
        MediaSortHelper.sortItems(results, getSortField(), getSortOrder(), isFoldersFirst());
        renderBrowseAll(libraryContainer, results, savedBrowseTitle);
    }

    private void showSearchEmpty() {
        libraryContainer.removeAllViews();
        TextView empty = new TextView(this);
        empty.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 120));
        empty.setGravity(Gravity.CENTER);
        empty.setText("搜索无结果");
        empty.setTextColor(0xFF808080);
        empty.setTextSize(14);
        libraryContainer.addView(empty);
    }

    private void clearSearch() {
        if (isSearching) {
            isSearching = false;
            etSearch.setText("");
            etSearch.setVisibility(View.VISIBLE);
            loadMediaLibraries();
        }
    }

    private void populateLibGrid(LinearLayout cont, List<MediaDbItem> libs) {
        cont.removeAllViews();
        if (libs == null || libs.isEmpty()) {
            tvLibraryEmpty.setVisibility(View.VISIBLE);
            return;
        }
        tvLibraryEmpty.setVisibility(View.GONE);

        // 顶部控制栏
        LinearLayout topBar = new LinearLayout(this);
        topBar.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setBackgroundResource(R.drawable.bg_input);
        topBar.setPadding(16, 12, 16, 12);

        TextView titleTv = new TextView(this);
        titleTv.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        titleTv.setText("📁 媒体库分类 (" + libs.size() + " 个)");
        titleTv.setTextColor(0xFFEEEEEE);
        titleTv.setTextSize(15);
        titleTv.setTypeface(Typeface.DEFAULT_BOLD);
        topBar.addView(titleTv);

        Button viewToggleBtn = new Button(this);
        viewToggleBtn.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, (int)(38 * getResources().getDisplayMetrics().density)));
        viewToggleBtn.setBackgroundResource(R.drawable.bg_btn_primary);
        viewToggleBtn.setText(isListViewMode() ? "📁 列表视图" : "▦ 卡片视图");
        viewToggleBtn.setTextColor(0xFFEEEEEE);
        viewToggleBtn.setTextSize(12);
        viewToggleBtn.setFocusable(true);
        viewToggleBtn.setPadding(16, 0, 16, 0);
        viewToggleBtn.setOnClickListener(v -> toggleViewMode());
        topBar.addView(viewToggleBtn);

        cont.addView(topBar);
        cont.addView(makeSpacer(8));

        for (int i = 0; i < libs.size(); i++) {
            cont.addView(makeLibFolderRow(libs.get(i)));
            cont.addView(makeSpacer(6));
        }
    }


    private View makeLibCard(MediaDbItem lib) {
        return makeLibFolderRow(lib);
    }


    // ==================== 设置 ====================


    private void setupSettings() {
        tvSettingUsername.setText("用户名: " + prefs.getString("user", ""));
        String d = prefs.getString(PREF_DECODER, "hardware");
        tvDecoderValue.setText("hardware".equals(d) ? "硬解" : "软解");
        rlDecoderSetting.setOnClickListener(v -> toggleDecoder());

        // 快进退步长
        final int[] savedStep = {prefs.getInt("seek_step", 10)};
        tvSeekStepValue.setText(savedStep[0] + "s");
        rlSeekStep.setOnClickListener(v -> {
            android.app.AlertDialog.Builder b = new android.app.AlertDialog.Builder(this);
            b.setTitle("快进退步长（秒）");
            final android.widget.EditText input = new android.widget.EditText(this);
            input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
            input.setText(String.valueOf(savedStep[0]));
            input.setSelection(input.getText().length());
            b.setView(input);
            b.setPositiveButton("保存", (dialog, which) -> {
                try {
                    int val = Integer.parseInt(input.getText().toString().trim());
                    if (val < 1) val = 1;
                    if (val > 300) val = 300;
                    prefs.edit().putInt("seek_step", val).apply();
                    tvSeekStepValue.setText(val + "s");
                    savedStep[0] = val;
                } catch (Exception ignored) {}
            });
            b.setNegativeButton("取消", null);
            b.show();
        });

        // 影视菜单与列表设置
        if (tvViewModeValue != null) {
            tvViewModeValue.setText(isListViewMode() ? "文件夹/文件列表" : "海报墙模式");
        }
        if (rlViewModeSetting != null) {
            rlViewModeSetting.setOnClickListener(v -> {
                String[] modes = {"文件夹/文件列表 (推荐)", "海报墙模式"};
                int curIndex = isListViewMode() ? 0 : 1;
                new android.app.AlertDialog.Builder(this)
                        .setTitle("所有影视菜单显示模式")
                        .setSingleChoiceItems(modes, curIndex, (dialog, which) -> {
                            String newMode = which == 0 ? "list" : "grid";
                            prefs.edit().putString(PREF_VIEW_MODE, newMode).apply();
                            if (tvViewModeValue != null) {
                                tvViewModeValue.setText(which == 0 ? "文件夹/文件列表" : "海报墙模式");
                            }
                            dialog.dismiss();
                            if (currentTab == 0 && showingOverview) {
                                showOverview();
                            } else if (currentTab == 1 && savedBrowseGuid == null) {
                                loadMediaLibraries();
                            }
                        })
                        .setNegativeButton("取消", null)
                        .show();
            });
        }

        if (tvSortFieldValue != null) {
            tvSortFieldValue.setText(MediaSortHelper.SORT_FIELD_LABELS[getSortField()]);
        }
        if (rlSortFieldSetting != null) {
            rlSortFieldSetting.setOnClickListener(v -> {
                new android.app.AlertDialog.Builder(this)
                        .setTitle("默认排序字段")
                        .setSingleChoiceItems(MediaSortHelper.SORT_FIELD_LABELS, getSortField(), (dialog, which) -> {
                            prefs.edit().putInt(PREF_SORT_FIELD, which).apply();
                            if (tvSortFieldValue != null) {
                                tvSortFieldValue.setText(MediaSortHelper.SORT_FIELD_LABELS[which]);
                            }
                            dialog.dismiss();
                            onSortChanged();
                        })
                        .setNegativeButton("取消", null)
                        .show();
            });
        }

        if (tvSortOrderValue != null) {
            tvSortOrderValue.setText(MediaSortHelper.SORT_ORDER_LABELS[getSortOrder()]);
        }
        if (rlSortOrderSetting != null) {
            rlSortOrderSetting.setOnClickListener(v -> {
                new android.app.AlertDialog.Builder(this)
                        .setTitle("默认排序顺序")
                        .setSingleChoiceItems(MediaSortHelper.SORT_ORDER_LABELS, getSortOrder(), (dialog, which) -> {
                            prefs.edit().putInt(PREF_SORT_ORDER, which).apply();
                            if (tvSortOrderValue != null) {
                                tvSortOrderValue.setText(MediaSortHelper.SORT_ORDER_LABELS[which]);
                            }
                            dialog.dismiss();
                            onSortChanged();
                        })
                        .setNegativeButton("取消", null)
                        .show();
            });
        }

        if (tvFoldersFirstValue != null) {
            tvFoldersFirstValue.setText(isFoldersFirst() ? "开启" : "关闭");
        }
        if (rlFoldersFirstSetting != null) {
            rlFoldersFirstSetting.setOnClickListener(v -> {
                boolean next = !isFoldersFirst();
                prefs.edit().putBoolean(PREF_FOLDERS_FIRST, next).apply();
                if (tvFoldersFirstValue != null) {
                    tvFoldersFirstValue.setText(next ? "开启" : "关闭");
                }
                onSortChanged();
            });
        }

        if (tvFolderHierarchyValue != null) {
            tvFolderHierarchyValue.setText(isFolderHierarchyMode() ? "真实文件夹层级" : "全库平铺聚合");
        }
        if (rlFolderHierarchySetting != null) {
            rlFolderHierarchySetting.setOnClickListener(v -> {
                String[] items = {"真实文件夹层级 (逐级展开目录，不打散平铺) [推荐]", "全库平铺聚合 (媒体库内所有影视汇集平铺)"};
                int cur = isFolderHierarchyMode() ? 0 : 1;
                new AlertDialog.Builder(this)
                        .setTitle("目录浏览层级")
                        .setSingleChoiceItems(items, cur, (dialog, which) -> {
                            boolean mode = (which == 0);
                            prefs.edit().putBoolean(PREF_FOLDER_HIERARCHY, mode).apply();
                            if (tvFolderHierarchyValue != null) {
                                tvFolderHierarchyValue.setText(mode ? "真实文件夹层级" : "全库平铺聚合");
                            }
                            dialog.dismiss();
                            if (savedBrowseGuid != null && currentBrowseContainer != null) {
                                reFetchLibraryItems();
                            } else if (currentTab == 1) {
                                loadMediaLibraries();
                            } else if (currentTab == 0 && showingOverview) {
                                showOverview();
                            }
                        })
                        .setNegativeButton("取消", null)
                        .show();
            });
        }

        // ========== 车载与播放体验增强设置 ==========

        // 1. 播放模式实时切换
        final String[] playModeLabels = {"🔁 列表循环 (默认)", "🔀 随机播放", "🔂 单曲循环"};
        int savedPlayMode = prefs.getInt("pref_play_mode", 0);
        if (tvPlayModeValue != null) {
            tvPlayModeValue.setText(playModeLabels[Math.max(0, Math.min(2, savedPlayMode))]);
        }
        if (rlPlayModeSetting != null) {
            rlPlayModeSetting.setOnClickListener(v -> {
                int cur = prefs.getInt("pref_play_mode", 0);
                new AlertDialog.Builder(this)
                        .setTitle("播放模式设置")
                        .setSingleChoiceItems(playModeLabels, cur, (dialog, which) -> {
                            prefs.edit().putInt("pref_play_mode", which).apply();
                            if (tvPlayModeValue != null) {
                                tvPlayModeValue.setText(playModeLabels[which]);
                            }
                            dialog.dismiss();
                        })
                        .setNegativeButton("取消", null)
                        .show();
            });
        }

        // 2. 息屏听歌 / 纯音频防眩目模式
        boolean screenOffEnabled = prefs.getBoolean("pref_screen_off_enabled", true);
        if (tvScreenOffValue != null) {
            tvScreenOffValue.setText(screenOffEnabled ? "已开启 (播放界面按🌙键纯黑)" : "已关闭");
        }
        if (rlScreenOffSetting != null) {
            rlScreenOffSetting.setOnClickListener(v -> {
                boolean cur = prefs.getBoolean("pref_screen_off_enabled", true);
                boolean next = !cur;
                prefs.edit().putBoolean("pref_screen_off_enabled", next).apply();
                if (tvScreenOffValue != null) {
                    tvScreenOffValue.setText(next ? "已开启 (播放界面按🌙键纯黑)" : "已关闭");
                }
            });
        }

        // 3. 启动应用自动继续播放
        boolean autoResumeEnabled = prefs.getBoolean("pref_auto_resume", true);
        if (tvAutoResumeValue != null) {
            tvAutoResumeValue.setText(autoResumeEnabled ? "开机自动继续播放 (默认开启)" : "已关闭 (手动选择)");
        }
        if (rlAutoResumeSetting != null) {
            rlAutoResumeSetting.setOnClickListener(v -> {
                boolean cur = prefs.getBoolean("pref_auto_resume", true);
                boolean next = !cur;
                prefs.edit().putBoolean("pref_auto_resume", next).apply();
                if (tvAutoResumeValue != null) {
                    tvAutoResumeValue.setText(next ? "开机自动继续播放 (默认开启)" : "已关闭 (手动选择)");
                }
            });
        }

        // 4. 拼音首字母模糊搜索
        boolean pinyinEnabled = prefs.getBoolean("pref_pinyin_search", true);
        if (tvPinyinSearchValue != null) {
            tvPinyinSearchValue.setText(pinyinEnabled ? "拼音首字母匹配 (如 ZJL 搜周杰伦)" : "仅精确匹配");
        }
        if (rlPinyinSearchSetting != null) {
            rlPinyinSearchSetting.setOnClickListener(v -> {
                boolean cur = prefs.getBoolean("pref_pinyin_search", true);
                boolean next = !cur;
                prefs.edit().putBoolean("pref_pinyin_search", next).apply();
                if (tvPinyinSearchValue != null) {
                    tvPinyinSearchValue.setText(next ? "拼音首字母匹配 (如 ZJL 搜周杰伦)" : "仅精确匹配");
                }
            });
        }

        // 5. 遥控器菜单键 (Menu) 快速呼出控制面板
        boolean menuPanelEnabled = prefs.getBoolean("pref_menu_panel", true);
        if (tvMenuPanelValue != null) {
            tvMenuPanelValue.setText(menuPanelEnabled ? "呼出控制面板 (推荐)" : "切换视频详细信息");
        }
        if (rlMenuPanelSetting != null) {
            rlMenuPanelSetting.setOnClickListener(v -> {
                boolean cur = prefs.getBoolean("pref_menu_panel", true);
                boolean next = !cur;
                prefs.edit().putBoolean("pref_menu_panel", next).apply();
                if (tvMenuPanelValue != null) {
                    tvMenuPanelValue.setText(next ? "呼出控制面板 (推荐)" : "切换视频详细信息");
                }
            });
        }

        // 6. 长按方向键左右平滑递增加速快进
        boolean dpadAccelEnabled = prefs.getBoolean("pref_dpad_accel", true);
        if (tvDpadAccelValue != null) {
            tvDpadAccelValue.setText(dpadAccelEnabled ? "平滑递增 (5s/15s/30s/60s)" : "固定步长");
        }
        if (rlDpadAccelSetting != null) {
            rlDpadAccelSetting.setOnClickListener(v -> {
                boolean cur = prefs.getBoolean("pref_dpad_accel", true);
                boolean next = !cur;
                prefs.edit().putBoolean("pref_dpad_accel", next).apply();
                if (tvDpadAccelValue != null) {
                    tvDpadAccelValue.setText(next ? "平滑递增 (5s/15s/30s/60s)" : "固定步长");
                }
            });
        }

        // 7. 睡眠倒计时 / 定时关闭默认设置
        final String[] sleepTimerOptions = {"关闭", "15 分钟", "30 分钟", "60 分钟", "90 分钟", "播完本项停止"};
        final int[] sleepTimerValues = {0, 15, 30, 60, 90, -1};
        int savedSleepTimer = prefs.getInt("pref_sleep_timer_default", 0);
        int sleepTimerIdx = 0;
        for (int i = 0; i < sleepTimerValues.length; i++) {
            if (sleepTimerValues[i] == savedSleepTimer) {
                sleepTimerIdx = i;
                break;
            }
        }
        if (tvSleepTimerValue != null) {
            tvSleepTimerValue.setText(sleepTimerOptions[sleepTimerIdx]);
        }
        if (rlSleepTimerSetting != null) {
            rlSleepTimerSetting.setOnClickListener(v -> {
                int cur = prefs.getInt("pref_sleep_timer_default", 0);
                int sel = 0;
                for (int i = 0; i < sleepTimerValues.length; i++) {
                    if (sleepTimerValues[i] == cur) {
                        sel = i;
                        break;
                    }
                }
                new AlertDialog.Builder(this)
                        .setTitle("定时关闭默认设置")
                        .setSingleChoiceItems(sleepTimerOptions, sel, (dialog, which) -> {
                            prefs.edit().putInt("pref_sleep_timer_default", sleepTimerValues[which]).apply();
                            if (tvSleepTimerValue != null) {
                                tvSleepTimerValue.setText(sleepTimerOptions[which]);
                            }
                            dialog.dismiss();
                        })
                        .setNegativeButton("取消", null)
                        .show();
            });
        }

        // 8. 车载人声对白增强 (动态增益中频对白)
        boolean vocalBoostEnabled = prefs.getBoolean("pref_vocal_boost", true);
        if (tvVocalBoostValue != null) {
            tvVocalBoostValue.setText(vocalBoostEnabled ? "已开启 (+6dB 消除路噪增强对白)" : "已关闭");
        }
        if (rlVocalBoostSetting != null) {
            rlVocalBoostSetting.setOnClickListener(v -> {
                boolean cur = prefs.getBoolean("pref_vocal_boost", true);
                boolean next = !cur;
                prefs.edit().putBoolean("pref_vocal_boost", next).apply();
                if (tvVocalBoostValue != null) {
                    tvVocalBoostValue.setText(next ? "已开启 (+6dB 消除路噪增强对白)" : "已关闭");
                }
            });
        }

        // 9. 默认起播清晰度
        final String[] qualityLabels = {"⚡ 智能自适应 (ABR 720P~360P)", "原画 (Direct Play)", "1080P 高清 (8M)", "720P 标清 (4M)", "480P 流畅 (1.5M)", "360P 极速省流 (800K)", "纯音频极速 (128K)"};
        final String[] qualityKeys = {"auto", "original", "1080p", "720p", "480p", "360p", "audio"};
        String curQ = prefs.getString("pref_default_quality", "auto");
        int qIdx = 0;
        for (int i = 0; i < qualityKeys.length; i++) {
            if (qualityKeys[i].equals(curQ)) { qIdx = i; break; }
        }
        if (tvDefaultQualityValue != null) tvDefaultQualityValue.setText(qualityLabels[qIdx]);
        if (rlDefaultQualitySetting != null) {
            rlDefaultQualitySetting.setOnClickListener(v -> {
                String savedQ = prefs.getString("pref_default_quality", "auto");
                int sel = 0;
                for (int i = 0; i < qualityKeys.length; i++) {
                    if (qualityKeys[i].equals(savedQ)) { sel = i; break; }
                }
                new AlertDialog.Builder(this)
                        .setTitle("默认起播清晰度（弱网建议选 720P 或 480P）")
                        .setSingleChoiceItems(qualityLabels, sel, (dialog, which) -> {
                            prefs.edit().putString("pref_default_quality", qualityKeys[which]).apply();
                            if (tvDefaultQualityValue != null) tvDefaultQualityValue.setText(qualityLabels[which]);
                            dialog.dismiss();
                        })
                        .setNegativeButton("取消", null)
                        .show();
            });
        }

        // 10. 弱网缓冲区大小
        final String[] bufferLabels = {"极速起播 (1.5s / 15s)", "标准均衡 (2.5s / 50s)", "弱网大缓冲 (4s / 120s 隧道推荐)"};
        final String[] bufferKeys = {"fast", "balanced", "weak_net"};
        String curB = prefs.getString("pref_buffer_profile", "balanced");
        int bIdx = 1;
        for (int i = 0; i < bufferKeys.length; i++) {
            if (bufferKeys[i].equals(curB)) { bIdx = i; break; }
        }
        if (tvBufferProfileValue != null) tvBufferProfileValue.setText(bufferLabels[bIdx]);
        if (rlBufferProfileSetting != null) {
            rlBufferProfileSetting.setOnClickListener(v -> {
                String savedB = prefs.getString("pref_buffer_profile", "balanced");
                int sel = 1;
                for (int i = 0; i < bufferKeys.length; i++) {
                    if (bufferKeys[i].equals(savedB)) { sel = i; break; }
                }
                new AlertDialog.Builder(this)
                        .setTitle("播放器缓冲区策略")
                        .setSingleChoiceItems(bufferLabels, sel, (dialog, which) -> {
                            prefs.edit().putString("pref_buffer_profile", bufferKeys[which]).apply();
                            if (tvBufferProfileValue != null) tvBufferProfileValue.setText(bufferLabels[which]);
                            dialog.dismiss();
                        })
                        .setNegativeButton("取消", null)
                        .show();
            });
        }

        // 11. 弱网自动重连与降级
        boolean weakNetRetry = prefs.getBoolean("pref_weak_net_retry", true);
        if (tvWeakNetRetryValue != null) {
            tvWeakNetRetryValue.setText(weakNetRetry ? "已开启 (断线重连+原画降级)" : "已关闭");
        }
        if (rlWeakNetRetrySetting != null) {
            rlWeakNetRetrySetting.setOnClickListener(v -> {
                boolean next = !prefs.getBoolean("pref_weak_net_retry", true);
                prefs.edit().putBoolean("pref_weak_net_retry", next).apply();
                if (tvWeakNetRetryValue != null) {
                    tvWeakNetRetryValue.setText(next ? "已开启 (断线重连+原画降级)" : "已关闭");
                }
            });
        }

        // 12. 转码视频编码 (H.264 / HEVC H.265)
        String curCodec = prefs.getString("pref_video_codec", "h264");
        if (tvVideoCodecValue != null) {
            tvVideoCodecValue.setText("hevc".equals(curCodec) ? "HEVC/H.265 (省流40%)" : "H.264 (兼容优先)");
        }
        if (rlVideoCodecSetting != null) {
            rlVideoCodecSetting.setOnClickListener(v -> {
                String c = prefs.getString("pref_video_codec", "h264");
                String next = "hevc".equals(c) ? "h264" : "hevc";
                prefs.edit().putString("pref_video_codec", next).apply();
                if (tvVideoCodecValue != null) {
                    tvVideoCodecValue.setText("hevc".equals(next) ? "HEVC/H.265 (省流40%)" : "H.264 (兼容优先)");
                }
            });
        }

        // 13. HLS 切片封装 (TS / fMP4)
        String curCont = prefs.getString("pref_hls_container", "ts");
        if (tvHlsContainerValue != null) {
            tvHlsContainerValue.setText("fmp4".equals(curCont) ? "fMP4 (低封装开销)" : "MPEG-TS (经典兼容)");
        }
        if (rlHlsContainerSetting != null) {
            rlHlsContainerSetting.setOnClickListener(v -> {
                String c = prefs.getString("pref_hls_container", "ts");
                String next = "fmp4".equals(c) ? "ts" : "fmp4";
                prefs.edit().putString("pref_hls_container", next).apply();
                if (tvHlsContainerValue != null) {
                    tvHlsContainerValue.setText("fmp4".equals(next) ? "fMP4 (低封装开销)" : "MPEG-TS (经典兼容)");
                }
            });
        }

        // 14. HLS 切片时长 (2s / 4s / 6s)
        int curSeg = prefs.getInt("pref_hls_seg_sec", 2);
        if (tvHlsSegValue != null) {
            tvHlsSegValue.setText(curSeg == 4 ? "4 秒 (弱网稳流推荐)" : curSeg == 6 ? "6 秒 (超低请求数)" : "2 秒 (极速起播)");
        }
        if (rlHlsSegSetting != null) {
            rlHlsSegSetting.setOnClickListener(v -> {
                int s = prefs.getInt("pref_hls_seg_sec", 2);
                int next = (s == 2) ? 4 : (s == 4 ? 6 : 2);
                prefs.edit().putInt("pref_hls_seg_sec", next).apply();
                if (tvHlsSegValue != null) {
                    tvHlsSegValue.setText(next == 4 ? "4 秒 (弱网稳流推荐)" : next == 6 ? "6 秒 (超低请求数)" : "2 秒 (极速起播)");
                }
            });
        }

        // 15. 接口 Gzip 压缩
        boolean curGzip = prefs.getBoolean("pref_http_gzip", true);
        com.fntv.app.api.AuthInterceptor.enableGzip = curGzip;
        if (tvHttpGzipValue != null) {
            tvHttpGzipValue.setText(curGzip ? "已开启 (省75%流量)" : "已关闭 (纯文本对照)");
        }
        if (rlHttpGzipSetting != null) {
            rlHttpGzipSetting.setOnClickListener(v -> {
                boolean next = !prefs.getBoolean("pref_http_gzip", true);
                prefs.edit().putBoolean("pref_http_gzip", next).apply();
                com.fntv.app.api.AuthInterceptor.enableGzip = next;
                if (tvHttpGzipValue != null) {
                    tvHttpGzipValue.setText(next ? "已开启 (省75%流量)" : "已关闭 (纯文本对照)");
                }
            });
        }

        // 16. 本地视频环形缓存
        boolean curVCache = prefs.getBoolean("pref_video_cache", true);
        if (tvVideoCacheValue != null) {
            tvVideoCacheValue.setText(curVCache ? "已开启 (150MB 环形)" : "已关闭");
        }
        if (rlVideoCacheSetting != null) {
            rlVideoCacheSetting.setOnClickListener(v -> {
                boolean next = !prefs.getBoolean("pref_video_cache", true);
                prefs.edit().putBoolean("pref_video_cache", next).apply();
                if (tvVideoCacheValue != null) {
                    tvVideoCacheValue.setText(next ? "已开启 (150MB 环形)" : "已关闭");
                }
            });
        }

        // 17. HLS 切片并发预取
        boolean curPrefetch = prefs.getBoolean("pref_hls_prefetch", true);
        if (tvHlsPrefetchValue != null) {
            tvHlsPrefetchValue.setText(curPrefetch ? "已开启 (并发拉下2片)" : "已关闭");
        }
        if (rlHlsPrefetchSetting != null) {
            rlHlsPrefetchSetting.setOnClickListener(v -> {
                boolean next = !prefs.getBoolean("pref_hls_prefetch", true);
                prefs.edit().putBoolean("pref_hls_prefetch", next).apply();
                if (tvHlsPrefetchValue != null) {
                    tvHlsPrefetchValue.setText(next ? "已开启 (并发拉下2片)" : "已关闭");
                }
            });
        }

        // 18. NAS 全硬件解码转码
        boolean curHwDec = prefs.getBoolean("pref_server_hwdecode", true);
        if (tvServerHwDecodeValue != null) {
            tvServerHwDecodeValue.setText(curHwDec ? "已开启 (VAAPI 零拷贝)" : "已关闭 (CPU 解码+VAAPI)");
        }
        if (rlServerHwDecodeSetting != null) {
            rlServerHwDecodeSetting.setOnClickListener(v -> {
                boolean next = !prefs.getBoolean("pref_server_hwdecode", true);
                prefs.edit().putBoolean("pref_server_hwdecode", next).apply();
                if (tvServerHwDecodeValue != null) {
                    tvServerHwDecodeValue.setText(next ? "已开启 (VAAPI 零拷贝)" : "已关闭 (CPU 解码+VAAPI)");
                }
            });
        }

        // 19. 车机 MediaStyle 通知卡片联动 (默认关闭，支持的新款车机可开启)
        boolean curMediaStyle = prefs.getBoolean("pref_media_style_notif", false);
        if (tvMediaStyleNotifValue != null) {
            tvMediaStyleNotifValue.setText(curMediaStyle ? "已开启 (桌面/方向盘联动)" : "已关闭");
        }
        if (rlMediaStyleNotifSetting != null) {
            rlMediaStyleNotifSetting.setOnClickListener(v -> {
                boolean next = !prefs.getBoolean("pref_media_style_notif", false);
                prefs.edit().putBoolean("pref_media_style_notif", next).apply();
                if (tvMediaStyleNotifValue != null) {
                    tvMediaStyleNotifValue.setText(next ? "已开启 (桌面/方向盘联动)" : "已关闭");
                }
            });
        }

        // 20. 在线音乐 HLS 环形缓存
        boolean curMusicCache = prefs.getBoolean("pref_music_cache", true);
        if (tvMusicCacheValue != null) {
            tvMusicCacheValue.setText(curMusicCache ? "已开启 (回放免流量)" : "已关闭");
        }
        if (rlMusicCacheSetting != null) {
            rlMusicCacheSetting.setOnClickListener(v -> {
                boolean next = !prefs.getBoolean("pref_music_cache", true);
                prefs.edit().putBoolean("pref_music_cache", next).apply();
                if (tvMusicCacheValue != null) {
                    tvMusicCacheValue.setText(next ? "已开启 (回放免流量)" : "已关闭");
                }
            });
        }

        // 21. 弱网海报缩略图省流 (300px)
        boolean curLowImg = prefs.getBoolean("pref_low_bw_img", true);
        SimpleImageLoader.lowBandwidthMode = curLowImg;
        if (tvLowBwImgValue != null) {
            tvLowBwImgValue.setText(curLowImg ? "已开启 (省75%图片流量)" : "已关闭 (原图)");
        }
        if (rlLowBwImgSetting != null) {
            rlLowBwImgSetting.setOnClickListener(v -> {
                boolean next = !prefs.getBoolean("pref_low_bw_img", true);
                prefs.edit().putBoolean("pref_low_bw_img", next).apply();
                SimpleImageLoader.lowBandwidthMode = next;
                if (tvLowBwImgValue != null) {
                    tvLowBwImgValue.setText(next ? "已开启 (省75%图片流量)" : "已关闭 (原图)");
                }
            });
        }

        // 22. Wi-Fi / 4G 切换自动续播
        boolean curNetRes = prefs.getBoolean("pref_net_auto_resume", true);
        if (tvNetAutoResumeValue != null) {
            tvNetAutoResumeValue.setText(curNetRes ? "已开启 (含剧集末段预热)" : "已关闭");
        }
        if (rlNetAutoResumeSetting != null) {
            rlNetAutoResumeSetting.setOnClickListener(v -> {
                boolean next = !prefs.getBoolean("pref_net_auto_resume", true);
                prefs.edit().putBoolean("pref_net_auto_resume", next).putBoolean("pref_next_ep_prewarm", next).apply();
                if (tvNetAutoResumeValue != null) {
                    tvNetAutoResumeValue.setText(next ? "已开启 (含剧集末段预热)" : "已关闭");
                }
            });
        }

        apiManager.getApi().getUserInfo().enqueue(new Callback<ApiResponse<UserInfoResponse>>() {
            @Override
            public void onResponse(Call<ApiResponse<UserInfoResponse>> call,
                                   Response<ApiResponse<UserInfoResponse>> response) {
                if (response.isSuccessful() && response.body() != null && response.body().code == 0
                        && response.body().data != null) {
                    tvSettingUsername.setText("用户名: " + response.body().data.getDisplayName());
                }
            }
            @Override public void onFailure(Call<ApiResponse<UserInfoResponse>> call, Throwable t) {}
        });
    }


    private void toggleDecoder() {
        String cur = prefs.getString(PREF_DECODER, "hardware");
        if ("hardware".equals(cur)) {
            prefs.edit().putString(PREF_DECODER, "software").apply();
            tvDecoderValue.setText("软解");
            Toast.makeText(this, "解码: 软解 (CPU)", Toast.LENGTH_SHORT).show();
        } else {
            prefs.edit().putString(PREF_DECODER, "hardware").apply();
            tvDecoderValue.setText("硬解");
            Toast.makeText(this, "解码: 硬解 (GPU)", Toast.LENGTH_SHORT).show();
        }
    }


    // ==================== 登出 ====================


    private void setupLogout() {
        btnLogout.setOnClickListener(v -> logout());
    }


    private void logout() {
        apiManager.setToken(null);
        Toast.makeText(this, "已退出", Toast.LENGTH_SHORT).show();
        Intent intent = new Intent(this, MainActivity.class);
        intent.putExtra("skip_auto_login", true);
        startActivity(intent);
        finish();
    }

    // ==================== 顶部导航、全局迷你播放器与音乐模式中心 ====================

    private void setupTopAndMiniPlayer() {
        if (btnTopBack != null) {
            btnTopBack.setOnClickListener(v -> onKeyDown(KeyEvent.KEYCODE_BACK, new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK)));
        }
        if (btnTopNowPlaying != null) {
            btnTopNowPlaying.setOnClickListener(v -> startActivity(new Intent(this, MusicPlayerActivity.class)));
        }
        if (miniPlayerInfo != null) {
            miniPlayerInfo.setOnClickListener(v -> startActivity(new Intent(this, MusicPlayerActivity.class)));
        }
        if (btnMiniPrev != null) {
            btnMiniPrev.setOnClickListener(v -> {
                MusicPlaybackService ms = MusicPlaybackService.getInstance();
                if (ms != null) ms.playPrevious();
            });
        }
        if (btnMiniPlayPause != null) {
            btnMiniPlayPause.setOnClickListener(v -> {
                MusicPlaybackService ms = MusicPlaybackService.getInstance();
                if (ms != null) ms.togglePlayPause();
                else MusicPlaybackService.startPendingIfNeeded(this);
            });
        }
        if (btnMiniNext != null) {
            btnMiniNext.setOnClickListener(v -> {
                MusicPlaybackService ms = MusicPlaybackService.getInstance();
                if (ms != null) ms.playNext(false);
            });
        }
        if (btnMiniStop != null) {
            btnMiniStop.setOnClickListener(v -> {
                MusicPlaybackService ms = MusicPlaybackService.getInstance();
                if (ms != null) ms.stopAndRelease();
                if (miniPlayerBar != null) miniPlayerBar.setVisibility(View.GONE);
                if (btnTopNowPlaying != null) btnTopNowPlaying.setVisibility(View.GONE);
            });
        }
    }

    private void updateTopBarState() {
        if (tvSection != null) {
            if (currentTab == 0) {
                tvSection.setText(showingDetail ? "🎬 详情" : (savedBrowseTitle != null && !showingOverview ? "🎬 " + savedBrowseTitle : "🎬 影视模式"));
                tvSection.setTextColor(0xFF38BDF8);
            } else if (currentTab == 4) {
                tvSection.setText("🎵 音乐播放器模式");
                tvSection.setTextColor(0xFF34D399);
            } else if (currentTab == 1) {
                tvSection.setText(showingDetail ? "📁 详情" : (savedBrowseTitle != null ? "📁 " + savedBrowseTitle : "📁 媒体库"));
                tvSection.setTextColor(0xFF38BDF8);
            } else if (currentTab == 2) {
                tvSection.setText("📂 远程文件");
                tvSection.setTextColor(0xFF38BDF8);
            } else {
                tvSection.setText("⚙ 设置");
                tvSection.setTextColor(0xFF94A3B8);
            }
        }
        if (btnTopBack != null) {
            boolean canGoBack = showingDetail || showingEpisodes || isSearching || !folderNavStack.isEmpty()
                    || ((currentTab == 0 || currentTab == 1) && savedBrowseGuid != null)
                    || (currentTab == 2 && remoteFileBrowser != null && remoteFileBrowser.canGoBack());
            btnTopBack.setVisibility(canGoBack ? View.VISIBLE : View.GONE);
        }
    }

    private void updateMiniPlayer() {
        MusicPlaybackService ms = MusicPlaybackService.getInstance();
        PlayListItem item = ms != null ? ms.getCurrentItem() : null;
        if (item == null) {
            if (miniPlayerBar != null) miniPlayerBar.setVisibility(View.GONE);
            if (btnTopNowPlaying != null) btnTopNowPlaying.setVisibility(View.GONE);
            return;
        }
        if (miniPlayerBar != null) miniPlayerBar.setVisibility(View.VISIBLE);
        if (btnTopNowPlaying != null) btnTopNowPlaying.setVisibility(View.VISIBLE);
        if (tvMiniTitle != null) tvMiniTitle.setText(item.title != null ? item.title : "未知曲目");
        if (tvMiniSub != null) {
            String artist = ms.getCurrentArtist();
            boolean playing = ms.isPlaying();
            boolean isOff = OfflineMusicManager.getInstance(this).isDownloaded(item.guid);
            String br = OfflineMusicManager.getOnlineBitrate(this);
            String q = isOff ? "本地离线" : ("original".equalsIgnoreCase(br) ? "原画" : br + "k");
            String stateTxt = playing ? "正在播放" : "已暂停";
            String sub = stateTxt + " · " + q + (artist != null && !artist.isEmpty() ? " · " + artist : "");
            tvMiniSub.setText(sub);
            tvMiniSub.setTextColor(playing ? 0xFF34D399 : 0xFF94A3B8);
        }
        if (btnMiniPlayPause != null) {
            btnMiniPlayPause.setText(ms.isPlaying() ? "⏸" : "▶");
        }
        if (ivMiniCover != null) {
            java.io.File localCov = OfflineMusicManager.getInstance(this).getLocalCoverFile(item.guid);
            if (localCov != null) {
                ivMiniCover.setImageURI(android.net.Uri.fromFile(localCov));
            } else if (baseUrl != null && !baseUrl.isEmpty() && item.guid != null) {
                SimpleImageLoader.load(baseUrl + "/v/api/v1/img/" + item.guid, ivMiniCover, apiManager.getClient());
            }
        }
    }

    @Override
    public void onTrackChanged(PlayListItem item, int index, int total) {
        miniHandler.post(() -> {
            updateMiniPlayer();
            refreshMusicHubHighlight();
            miniHandler.removeCallbacks(miniProgressTask);
            miniHandler.post(miniProgressTask);
        });
    }

    @Override
    public void onPlayStateChanged(boolean playing) {
        miniHandler.post(this::updateMiniPlayer);
    }

    @Override
    public void onLyricsLoaded(com.fntv.app.util.LyricsParser.Result lyrics, String artist, String album) {
        miniHandler.post(this::updateMiniPlayer);
    }

    @Override
    public void onServiceStopped() {
        miniHandler.post(() -> {
            if (miniPlayerBar != null) miniPlayerBar.setVisibility(View.GONE);
            if (btnTopNowPlaying != null) btnTopNowPlaying.setVisibility(View.GONE);
        });
    }

    private boolean isMusicLibrary(MediaDbItem lib) {
        if (lib == null) return false;
        String cat = lib.category != null ? lib.category.toLowerCase() : "";
        String mode = lib.mode != null ? lib.mode.toLowerCase() : "";
        String title = lib.title != null ? lib.title.toLowerCase() : "";
        return "music".equals(mode) || "audio".equals(cat) || "music".equals(cat)
                || title.contains("音乐") || title.contains("music") || title.contains("歌");
    }

    private void saveNavState() {
        if (prefs == null) return;
        SharedPreferences.Editor ed = prefs.edit();
        ed.putInt("nav_last_tab", currentTab);
        ed.putString("nav_music_lib_guid", selectedMusicLibGuid != null ? selectedMusicLibGuid : "");
        if (savedBrowseGuid != null && !savedBrowseGuid.isEmpty()) {
            ed.putString("nav_lib_guid", savedBrowseGuid);
            ed.putString("nav_lib_title", savedBrowseTitle != null ? savedBrowseTitle : "");
            ed.putBoolean("nav_lib_is_folder", currentBrowseParentIsFolder);
            ed.putString("nav_lib_single_child", currentBrowseSingleChildGuid != null ? currentBrowseSingleChildGuid : "");
            try {
                org.json.JSONArray arr = new org.json.JSONArray();
                for (FolderNavState st : folderNavStack) {
                    org.json.JSONObject o = new org.json.JSONObject();
                    o.put("guid", st.guid != null ? st.guid : "");
                    o.put("title", st.title != null ? st.title : "");
                    o.put("singleChildGuid", st.singleChildGuid != null ? st.singleChildGuid : "");
                    o.put("parentIsFolder", st.parentIsFolder);
                    arr.put(o);
                }
                ed.putString("nav_folder_stack", arr.toString());
            } catch (Exception ignored) {}
        } else {
            ed.remove("nav_lib_guid");
            ed.remove("nav_lib_title");
            ed.remove("nav_lib_is_folder");
            ed.remove("nav_lib_single_child");
            ed.remove("nav_folder_stack");
        }
        ed.apply();
    }

    private void restoreNavState() {
        if (prefs == null) return;
        String guid = prefs.getString("nav_lib_guid", "");
        if (guid == null || guid.isEmpty()) return;
        String title = prefs.getString("nav_lib_title", "");
        boolean isFolder = prefs.getBoolean("nav_lib_is_folder", true);
        String singleChild = prefs.getString("nav_lib_single_child", "");
        folderNavStack.clear();
        try {
            String stackJson = prefs.getString("nav_folder_stack", "[]");
            org.json.JSONArray arr = new org.json.JSONArray(stackJson);
            for (int i = 0; i < arr.length(); i++) {
                org.json.JSONObject o = arr.getJSONObject(i);
                String sg = o.optString("singleChildGuid", "");
                folderNavStack.add(new FolderNavState(
                        o.optString("guid", ""),
                        o.optString("title", ""),
                        sg.isEmpty() ? null : sg,
                        o.optBoolean("parentIsFolder", true),
                        null));
            }
        } catch (Exception ignored) {}
        LinearLayout targetContainer = (currentTab == 1) ? libraryContainer : moviesContainer;
        TextView targetLoading = (currentTab == 1) ? tvLibraryLoading : tvMoviesLoading;
        browseItemsInContainer(guid, title, targetContainer, targetLoading,
                singleChild.isEmpty() ? null : singleChild, isFolder);
    }

    private void refreshMusicHubHighlight() {
        if (musicHubContainer == null) return;
        MusicPlaybackService ms = MusicPlaybackService.getInstance();
        String playingGuid = (ms != null && ms.getCurrentItem() != null) ? ms.getCurrentItem().guid : null;
        for (int i = 0; i < musicHubContainer.getChildCount(); i++) {
            View child = musicHubContainer.getChildAt(i);
            if (child instanceof LinearLayout) {
                LinearLayout box = (LinearLayout) child;
                for (int j = 0; j < box.getChildCount(); j++) {
                    View row = box.getChildAt(j);
                    Object tag = row.getTag();
                    if (tag instanceof String && row instanceof LinearLayout) {
                        boolean isCur = playingGuid != null && playingGuid.equals(tag);
                        LinearLayout lr = (LinearLayout) row;
                        if (lr.getChildCount() >= 2 && lr.getChildAt(0) instanceof TextView && lr.getChildAt(1) instanceof TextView) {
                            TextView numTv = (TextView) lr.getChildAt(0);
                            TextView titleTv = (TextView) lr.getChildAt(1);
                            if (isCur) {
                                numTv.setText("▶");
                                numTv.setTextColor(0xFF34D399);
                                titleTv.setTextColor(0xFF34D399);
                                lr.setBackgroundColor(0xFF0F2922);
                            } else {
                                Object idxObj = numTv.getTag();
                                if (idxObj instanceof String) numTv.setText((String) idxObj);
                                numTv.setTextColor(0xFF64748B);
                                titleTv.setTextColor(0xFFF8FAFC);
                                lr.setBackgroundResource(R.drawable.bg_card);
                            }
                        }
                    }
                }
            }
        }
    }

    private void buildMusicHub() {
        if (musicHubContainer == null) return;
        musicHubBuilt = true;
        musicHubContainer.removeAllViews();
        float d = getResources().getDisplayMetrics().density;

        // 1. 顶部音乐模式概览卡片
        LinearLayout heroCard = new LinearLayout(this);
        heroCard.setOrientation(LinearLayout.VERTICAL);
        heroCard.setBackgroundColor(0xFF111827);
        heroCard.setPadding((int)(14*d), (int)(14*d), (int)(14*d), (int)(14*d));
        LinearLayout.LayoutParams heroLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        heroLp.bottomMargin = (int)(12*d);
        heroCard.setLayoutParams(heroLp);

        TextView heroTitle = new TextView(this);
        heroTitle.setText("🎵 音乐播放器模式");
        heroTitle.setTextColor(0xFFFFFFFF);
        heroTitle.setTextSize(17);
        heroTitle.setTypeface(null, Typeface.BOLD);
        heroCard.addView(heroTitle);

        String onlineBr = OfflineMusicManager.getOnlineBitrate(this);
        String brTxt = "original".equalsIgnoreCase(onlineBr) ? "原画直连" : onlineBr + "k HLS 流媒体";
        int offCnt = OfflineMusicManager.getInstance(this).getTrackCount();
        TextView heroSub = new TextView(this);
        heroSub.setText("边下边播实时转码 · 当前在线码率：" + brTxt + " · 本地离线：" + offCnt + " 首");
        heroSub.setTextColor(0xFF94A3B8);
        heroSub.setTextSize(12);
        heroSub.setPadding(0, (int)(4*d), 0, (int)(10*d));
        heroCard.addView(heroSub);

        // 快捷功能按钮行
        HorizontalScrollView hsv = new HorizontalScrollView(this);
        hsv.setHorizontalScrollBarEnabled(false);
        LinearLayout actionRow = new LinearLayout(this);
        actionRow.setOrientation(LinearLayout.HORIZONTAL);

        Button btnOpenPlayer = makeHubActionBtn("🎧 进入歌词播放器", 0xFF34D399, v -> {
            MusicPlaybackService ms = MusicPlaybackService.getInstance();
            if (ms != null && ms.getCurrentItem() != null) {
                startActivity(new Intent(this, MusicPlayerActivity.class));
            } else {
                Toast.makeText(this, "请先选择一首歌曲开始播放", Toast.LENGTH_SHORT).show();
            }
        });
        actionRow.addView(btnOpenPlayer);

        String savedMusicSummary = MusicPlaybackService.getSavedSessionSummary(this);
        if (savedMusicSummary != null && (MusicPlaybackService.getInstance() == null || MusicPlaybackService.getInstance().getCurrentItem() == null)) {
            Button btnResumeLast = makeHubActionBtn("▶ 续播: " + savedMusicSummary, 0xFFFBBF24, v -> {
                if (MusicPlaybackService.resumeLastSession(this)) {
                    startActivity(new Intent(this, MusicPlayerActivity.class));
                }
            });
            actionRow.addView(btnResumeLast);
        }

        if (offCnt > 0) {
            Button btnPlayOffline = makeHubActionBtn("💾 播放本地离线 (" + offCnt + ")", 0xFF38BDF8, v -> {
                List<PlayListItem> offList = OfflineMusicManager.getInstance(this).getOfflinePlayListItems();
                if (!offList.isEmpty()) {
                    MusicPlaybackService.startQueue(this, offList, 0);
                    startActivity(new Intent(this, MusicPlayerActivity.class));
                }
            });
            actionRow.addView(btnPlayOffline);
        }

        Button btnBitrate = makeHubActionBtn("⚡ 在线音质: " + ("original".equalsIgnoreCase(onlineBr) ? "原画" : onlineBr + "k"), 0xFFE2E8F0, v -> {
            String cur = OfflineMusicManager.getOnlineBitrate(this);
            int checked = 3;
            for (int i = 0; i < OfflineMusicManager.BITRATE_VALUES.length; i++) {
                if (OfflineMusicManager.BITRATE_VALUES[i].equals(cur)) { checked = i; break; }
            }
            new AlertDialog.Builder(this)
                    .setTitle("选择在线听歌最大码率")
                    .setSingleChoiceItems(OfflineMusicManager.BITRATE_LABELS, checked, (dlg, which) -> {
                        OfflineMusicManager.setOnlineBitrate(this, OfflineMusicManager.BITRATE_VALUES[which]);
                        MusicPlaybackService s = MusicPlaybackService.getInstance();
                        if (s != null) s.reloadForOnlineBitrateChange();
                        dlg.dismiss();
                        buildMusicHub();
                    }).show();
        });
        actionRow.addView(btnBitrate);

        hsv.addView(actionRow);
        heroCard.addView(hsv);
        musicHubContainer.addView(heroCard);

        // 2. 识别音乐媒体库
        List<MediaDbItem> musicLibs = new ArrayList<>();
        for (MediaDbItem lib : mediaLibraries) {
            String cat = lib.category != null ? lib.category.toLowerCase() : "";
            String mode = lib.mode != null ? lib.mode.toLowerCase() : "";
            String title = lib.title != null ? lib.title.toLowerCase() : "";
            if ("music".equals(mode) || "audio".equals(cat) || "music".equals(cat)
                    || title.contains("音乐") || title.contains("music") || title.contains("歌")) {
                musicLibs.add(lib);
            }
        }

        if (musicLibs.isEmpty() && mediaLibraries.isEmpty()) {
            TextView tv = new TextView(this);
            tv.setText("正在加载媒体库...");
            tv.setTextColor(0xFF94A3B8);
            tv.setPadding((int)(8*d), (int)(16*d), (int)(8*d), (int)(16*d));
            musicHubContainer.addView(tv);
            return;
        }

        if (musicLibs.isEmpty()) {
            TextView tv = new TextView(this);
            tv.setText("未检测到单独的音乐库，您可前往「媒体库」或「文件」中直接点击音频文件播放，或播放本地离线音乐。");
            tv.setTextColor(0xFF94A3B8);
            tv.setTextSize(13);
            tv.setPadding((int)(8*d), (int)(12*d), (int)(8*d), (int)(12*d));
            musicHubContainer.addView(tv);
            return;
        }

        TextView libHeader = new TextView(this);
        libHeader.setText("音乐媒体库");
        libHeader.setTextColor(0xFFF8FAFC);
        libHeader.setTextSize(15);
        libHeader.setTypeface(null, Typeface.BOLD);
        libHeader.setPadding(0, (int)(4*d), 0, (int)(8*d));
        musicHubContainer.addView(libHeader);

        for (final MediaDbItem lib : musicLibs) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setBackgroundResource(R.drawable.bg_card);
            row.setPadding((int)(14*d), (int)(12*d), (int)(14*d), (int)(12*d));
            row.setFocusable(true);
            row.setClickable(true);
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rlp.bottomMargin = (int)(8*d);
            row.setLayoutParams(rlp);

            TextView icon = new TextView(this);
            icon.setText("💿");
            icon.setTextSize(20);
            icon.setPadding(0, 0, (int)(12*d), 0);
            row.addView(icon);

            TextView name = new TextView(this);
            name.setText(lib.title != null ? lib.title : "音乐库");
            name.setTextColor(0xFFFFFFFF);
            name.setTextSize(15);
            name.setTypeface(null, Typeface.BOLD);
            name.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            row.addView(name);

            TextView go = new TextView(this);
            go.setText("浏览 ›");
            go.setTextColor(0xFF34D399);
            go.setTextSize(13);
            row.addView(go);

            row.setOnClickListener(v -> {
                switchTab(1);
                browseItemsInContainer(lib.guid, lib.title, libraryContainer, tvLibraryLoading, null, isLibFolderForm(lib));
            });
            musicHubContainer.addView(row);
        }

        // 自动加载第一个音乐库的曲目列表，实现“点开音乐页就能直接听歌”
        final MediaDbItem firstLib = musicLibs.get(0);
        final LinearLayout trackListBox = new LinearLayout(this);
        trackListBox.setOrientation(LinearLayout.VERTICAL);
        musicHubContainer.addView(trackListBox);
        loadMusicTracksForHub(firstLib.guid, firstLib.title, trackListBox);
    }

    private Button makeHubActionBtn(String text, int textColor, View.OnClickListener l) {
        float d = getResources().getDisplayMetrics().density;
        Button b = new Button(this);
        b.setText(text);
        b.setTextColor(textColor);
        b.setTextSize(12);
        b.setBackgroundResource(R.drawable.bg_input);
        b.setFocusable(true);
        b.setPadding((int)(12*d), 0, (int)(12*d), 0);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, (int)(36*d));
        lp.rightMargin = (int)(8*d);
        b.setLayoutParams(lp);
        b.setOnClickListener(l);
        return b;
    }

    private void loadMusicTracksForHub(String libGuid, String libTitle, final LinearLayout container) {
        float d = getResources().getDisplayMetrics().density;
        final TextView loadingTv = new TextView(this);
        loadingTv.setText("正在加载「" + libTitle + "」歌曲...");
        loadingTv.setTextColor(0xFF94A3B8);
        loadingTv.setTextSize(12);
        loadingTv.setPadding(0, (int)(8*d), 0, (int)(8*d));
        container.addView(loadingTv);

        ItemListRequest req = new ItemListRequest(
                libGuid,
                Arrays.asList("Audio", "Music", "Directory", "Folder"),
                false,
                "create_time",
                "DESC",
                200);
        apiManager.getApi().getItemList(req).enqueue(new Callback<ApiResponse<ItemListResponse>>() {
            @Override
            public void onResponse(Call<ApiResponse<ItemListResponse>> call, Response<ApiResponse<ItemListResponse>> response) {
                container.removeView(loadingTv);
                if (!response.isSuccessful() || response.body() == null || response.body().data == null || response.body().data.list == null) {
                    return;
                }
                List<PlayListItem> all = response.body().data.list;
                final List<PlayListItem> songs = new ArrayList<>();
                for (PlayListItem it : all) {
                    if (it != null && !it.isFolder()) songs.add(it);
                }
                if (songs.isEmpty()) return;

                LinearLayout topRow = new LinearLayout(HomeActivity.this);
                topRow.setOrientation(LinearLayout.HORIZONTAL);
                topRow.setGravity(Gravity.CENTER_VERTICAL);
                topRow.setPadding(0, (int)(10*d), 0, (int)(8*d));

                TextView hdr = new TextView(HomeActivity.this);
                hdr.setText("全部歌曲 (" + songs.size() + ")");
                hdr.setTextColor(0xFFF8FAFC);
                hdr.setTextSize(15);
                hdr.setTypeface(null, Typeface.BOLD);
                hdr.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                topRow.addView(hdr);

                Button btnPlayAll = makeHubActionBtn("▶ 顺序播放", 0xFF34D399, v -> {
                    MusicPlaybackService.setPlayModeStatic(HomeActivity.this, MusicPlaybackService.MODE_LOOP_ALL);
                    MusicPlaybackService.startQueue(HomeActivity.this, songs, 0);
                    startActivity(new Intent(HomeActivity.this, MusicPlayerActivity.class));
                });
                topRow.addView(btnPlayAll);

                Button btnShuffleAll = makeHubActionBtn("🔀 随机播放", 0xFF38BDF8, v -> {
                    MusicPlaybackService.setPlayModeStatic(HomeActivity.this, MusicPlaybackService.MODE_SHUFFLE);
                    int rIdx = new java.util.Random().nextInt(songs.size());
                    MusicPlaybackService.startQueue(HomeActivity.this, songs, rIdx);
                    startActivity(new Intent(HomeActivity.this, MusicPlayerActivity.class));
                });
                topRow.addView(btnShuffleAll);
                container.addView(topRow);

                for (int i = 0; i < songs.size(); i++) {
                    final int idx = i;
                    final PlayListItem song = songs.get(i);
                    LinearLayout row = new LinearLayout(HomeActivity.this);
                    row.setTag(song.guid);
                    row.setOrientation(LinearLayout.HORIZONTAL);
                    row.setGravity(Gravity.CENTER_VERTICAL);
                    row.setBackgroundResource(R.drawable.bg_card);
                    row.setPadding((int)(12*d), (int)(10*d), (int)(12*d), (int)(10*d));
                    row.setFocusable(true);
                    row.setClickable(true);
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                    lp.bottomMargin = (int)(6*d);
                    row.setLayoutParams(lp);

                    TextView num = new TextView(HomeActivity.this);
                    String numStr = String.valueOf(i + 1);
                    num.setText(numStr);
                    num.setTag(numStr);
                    num.setTextColor(0xFF64748B);
                    num.setTextSize(12);
                    num.setWidth((int)(28*d));
                    row.addView(num);

                    TextView titleTv = new TextView(HomeActivity.this);
                    titleTv.setText(song.title != null ? song.title : "未知歌曲");
                    titleTv.setTextColor(0xFFF8FAFC);
                    titleTv.setTextSize(14);
                    titleTv.setSingleLine(true);
                    titleTv.setEllipsize(TextUtils.TruncateAt.END);
                    titleTv.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                    row.addView(titleTv);

                    if (song.duration > 0) {
                        TextView durTv = new TextView(HomeActivity.this);
                        durTv.setText(formatDuration(song.duration));
                        durTv.setTextColor(0xFF94A3B8);
                        durTv.setTextSize(12);
                        durTv.setPadding((int)(8*d), 0, 0, 0);
                        row.addView(durTv);
                    }

                    row.setOnClickListener(v -> {
                        MusicPlaybackService.startQueue(HomeActivity.this, songs, idx);
                        startActivity(new Intent(HomeActivity.this, MusicPlayerActivity.class));
                    });
                    container.addView(row);
                }
                refreshMusicHubHighlight();
            }
            @Override
            public void onFailure(Call<ApiResponse<ItemListResponse>> call, Throwable t) {
                container.removeView(loadingTv);
            }
        });
    }


    // ==================== 工具 ====================


    private void clearContainer(LinearLayout c, TextView l, TextView e) {
        l.setVisibility(View.GONE);
        e.setVisibility(View.GONE);
        for (int i = c.getChildCount() - 1; i >= 0; i--) {
            View v = c.getChildAt(i);
            if (v != l && v != e) c.removeView(v);
        }
    }


    private View makeSpacer(int h) {
        View v = new View(HomeActivity.this);
        v.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, h));
        return v;
    }


    private String formatDuration(long sec) {
        if (sec <= 0) return "";
        long s = sec % 60;
        long m = (sec / 60) % 60;
        long h = sec / 3600;
        if (h > 0) return h + "h" + m + "m" + s + "s";
        return m + "分" + s + "秒";
    }

    /** 异步平滑加载图片 */
    private void loadImagesLazily(ViewGroup container, int index) {
        if (container == null) return;
        List<ImageView> targets = new ArrayList<>();
        collectImageViews(container, targets);
        if (targets.isEmpty()) return;

        for (ImageView iv : targets) {
            Object tag = iv.getTag();
            if (tag instanceof String) {
                String url = (String) tag;
                if (url.startsWith("http")) {
                    SimpleImageLoader.load(url, iv, apiManager.getClient());
                }
            }
        }
    }


    private void collectImageViews(ViewGroup parent, List<ImageView> out) {
        for (int i = 0; i < parent.getChildCount(); i++) {
            View child = parent.getChildAt(i);
            if (child instanceof ImageView) {
                out.add((ImageView) child);
            } else if (child instanceof ViewGroup) {
                collectImageViews((ViewGroup) child, out);
            }
        }
    }


    // ==================== 直播频道 ====================


    /** 加载直播频道（total>0 则显示预览区） */
    private void loadLiveChannels() {
        try {
            ItemListRequest liveReq = ItemListRequest.browseLiveChannels();
            Log.d("LiveChannel", "请求体: " + new com.google.gson.Gson().toJson(liveReq));
            Log.d("LiveChannel", "loadLiveChannels 开始请求...");
            apiManager.getApi().getItemList(liveReq).enqueue(new Callback<ApiResponse<ItemListResponse>>() {
                @Override
                public void onResponse(Call<ApiResponse<ItemListResponse>> call,
                                       Response<ApiResponse<ItemListResponse>> response) {
                    try {
                        Log.d("LiveChannel", "响应 code=" + response.code()
                                + " isSuccessful=" + response.isSuccessful());
                        // 打印请求信息
                        okhttp3.Request req = call.request();
                        Log.d("LiveChannel", "请求URL: " + req.url());
                        Log.d("LiveChannel", "请求Method: " + req.method());
                        Log.d("LiveChannel", "请求Headers:");
                        for (int i = 0; i < req.headers().size(); i++) {
                            Log.d("LiveChannel", "  " + req.headers().name(i) + ": " + req.headers().value(i));
                        }
                        if (response.body() != null) {
                            Log.d("LiveChannel", "body code=" + response.body().code
                                    + " msg=" + response.body().msg
                                    + " data=" + new com.google.gson.Gson().toJson(response.body()));
                        } else {
                            Log.w("LiveChannel", "body=null");
                        }
                        if (!response.isSuccessful() || response.body() == null || response.body().code != 0
                                || response.body().data == null || response.body().data.list == null
                                || response.body().data.list.isEmpty()) return;
                        List<PlayListItem> items = response.body().data.list;
                        int total = response.body().data.total;
                        Log.d("LiveChannel", "直播频道: total=" + total + " items=" + items.size());
                        if (total > 0) {
                            List<PlayListItem> preview = items.size() > 20 ? items.subList(0, 20) : items;
                            fillLiveChannelPreview(preview, total);
                        }
                    } catch (Exception e) {
                        Log.e("LiveChannel", "onResponse 异常", e);
                    }
                }
                @Override
                public void onFailure(Call<ApiResponse<ItemListResponse>> call, Throwable t) {
                    Log.e("LiveChannel", "请求失败: " + t.getMessage(), t);
                }
            });
        } catch (Exception e) {
            Log.e("LiveChannel", "loadLiveChannels 异常", e);
        }
    }

    /** 填充直播频道预览区（先移除旧的再添加） */
    private void fillLiveChannelPreview(List<PlayListItem> items, int total) {
        // 移除已有的直播频道区域
        for (int i = moviesContainer.getChildCount() - 1; i >= 0; i--) {
            View v = moviesContainer.getChildAt(i);
            if (v instanceof LinearLayout && "live_channel".equals(v.getTag())) {
                moviesContainer.removeView(v);
            }
        }

        LinearLayout section = new LinearLayout(this);
        section.setOrientation(LinearLayout.VERTICAL);
        section.setTag("live_channel");
        section.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // ── 标题行 ──
        LinearLayout headerRow = new LinearLayout(this);
        headerRow.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        headerRow.setOrientation(LinearLayout.HORIZONTAL);
        headerRow.setPadding(6, 24, 6, 18);
        headerRow.setGravity(Gravity.CENTER_VERTICAL);
        headerRow.setMinimumHeight(68);
        headerRow.setId(View.generateViewId());

        TextView header = new TextView(this);
        header.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        header.setText("直播频道");
        header.setTextColor(0xFFEEEEEE);
        header.setTextSize(18);
        header.setTypeface(Typeface.DEFAULT_BOLD);
        headerRow.addView(header);

        Button viewAll = new Button(this);
        viewAll.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, 68));
        viewAll.setBackgroundResource(R.drawable.bg_input);
        viewAll.setText("查看全部 ›");
        viewAll.setTextColor(0xFFB0B0B0);
        viewAll.setTextSize(14);
        viewAll.setFocusable(true);
        viewAll.setId(View.generateViewId());
        viewAll.setPadding(24, 0, 24, 0);
        viewAll.setOnClickListener(v -> browseLiveChannels());
        viewAll.setOnFocusChangeListener((v, hasFocus) -> {
            viewAll.setTextColor(hasFocus ? 0xFF81C784 : 0xFFB0B0B0);
            viewAll.setBackgroundColor(hasFocus ? 0x44FFFFFF : 0x00000000);
        });
        headerRow.addView(viewAll);
        section.addView(headerRow);

        // ── 横向滚动卡片预览（和其他媒体库预览一致的高度）──
        HorizontalScrollView hsv = new HorizontalScrollView(this);
        hsv.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 410));
        hsv.setScrollBarStyle(View.SCROLLBARS_INSIDE_OVERLAY);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(12, 8, 12, 8);

            // 焦点链：卡片 ↑ 到查看全部按钮，↓ 到底部菜单栏
            for (int i = 0; i < items.size(); i++) {
                View card = makeLiveChannelCard(items.get(i));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(220, 380);
                lp.setMargins(10, 0, 10, 0);
                card.setLayoutParams(lp);
                card.setNextFocusUpId(viewAll.getId());
                card.setNextFocusDownId(tabMovies.getId());
                row.addView(card);
            }

        hsv.addView(row);
        section.addView(hsv);
        section.addView(makeSpacer(16));

        // 插入到 moviesContainer
        moviesContainer.addView(section);
    }

    /** 直播频道卡片（和其他卡片样式一致，图片区域空白） */
    private View makeLiveChannelCard(PlayListItem item) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.bg_media_card);
        card.setPadding(6, 6, 6, 6);
        card.setFocusable(true);

        // 频道全名 + 彩色背景
        String shortName = item.title != null && !item.title.isEmpty() ? item.title : "?";
        int[] colors = {0xFFE53935, 0xFF1E88E5, 0xFF43A047, 0xFFFB8C00,
                        0xFF8E24AA, 0xFF00ACC1, 0xFF6D4C41, 0xFF546E7A};
        int colorIdx = item.guid != null ? Math.abs(item.guid.hashCode() % colors.length) : 0;
        float r = 10 * getResources().getDisplayMetrics().density;
        android.graphics.drawable.GradientDrawable badgeBg = new android.graphics.drawable.GradientDrawable();
        badgeBg.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        badgeBg.setCornerRadius(r);
        badgeBg.setColor(colors[colorIdx]);
        TextView channelBadge = new TextView(this);
        channelBadge.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 280));
        channelBadge.setGravity(Gravity.CENTER);
        channelBadge.setText(shortName);
        channelBadge.setTextColor(0xFFFFFFFF);
        int len = shortName.length();
        channelBadge.setTextSize(len <= 2 ? 56 : len <= 4 ? 40 : len <= 6 ? 30 : 22);
        channelBadge.setTypeface(Typeface.DEFAULT_BOLD);
        channelBadge.setBackground(badgeBg);
        card.addView(channelBadge);

        // 底部文字条：类型 + 标题
        LinearLayout textBar = new LinearLayout(this);
        textBar.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 88));
        textBar.setOrientation(LinearLayout.VERTICAL);
        textBar.setGravity(Gravity.CENTER_VERTICAL);
        textBar.setPadding(0, 4, 0, 4);

        TextView tag = new TextView(this);
        tag.setTextSize(9);
        tag.setTextColor(0xFF78909C);
        tag.setText("直播");
        textBar.addView(tag);

        final TextView title = new TextView(this);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.MARQUEE);
        title.setMarqueeRepeatLimit(-1);
        title.setTextSize(11);
        title.setTextColor(0xFFEEEEEE);
        title.setText(item.title != null ? item.title : "未知");
        textBar.addView(title);

        card.addView(textBar);

        card.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                title.setSelected(hasFocus);
                card.setScaleX(hasFocus ? 1.05f : 1.0f);
                card.setScaleY(hasFocus ? 1.05f : 1.0f);
                card.setBackgroundResource(hasFocus ? R.drawable.bg_input : R.drawable.bg_media_card);
            }
        });

        card.setTag(item);
        card.setOnClickListener(v -> {
            PlayListItem it = (PlayListItem) card.getTag();
            launchPlayer(it.guid, it.title, "", 0, null, "LiveChannel", 0, 0, it.parentGuid);
        });
        return card;
    }

    /** 直播频道查看全部 */
    private void browseLiveChannels() {
        savedDetailItem = null;
        savedDetailInfo = null;
        showingEpisodes = false;
        showingOverview = false;
        savedLiveChannelTitle = "直播频道";
        moviesContainer.removeAllViews();
        tvMoviesLoading.setVisibility(View.VISIBLE);
        tvMoviesLoading.setText("加载直播频道...");

        apiManager.getApi().getItemList(ItemListRequest.browseLiveChannels()).enqueue(new Callback<ApiResponse<ItemListResponse>>() {
            @Override
            public void onResponse(Call<ApiResponse<ItemListResponse>> call,
                                   Response<ApiResponse<ItemListResponse>> response) {
                tvMoviesLoading.setVisibility(View.GONE);
                if (!response.isSuccessful() || response.body() == null || response.body().code != 0
                        || response.body().data == null || response.body().data.list == null
                        || response.body().data.list.isEmpty()) {
                    TextView e = new TextView(HomeActivity.this);
                    e.setLayoutParams(new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, 120));
                    e.setGravity(Gravity.CENTER);
                    e.setText("暂无直播频道");
                    e.setTextColor(0xFF808080);
                    e.setTextSize(14);
                    moviesContainer.addView(e);
                    return;
                }
                List<PlayListItem> list = response.body().data.list;
                int total = response.body().data.total;
                Log.d("LiveChannel", "直播频道查看全部: total=" + total + " items=" + list.size()
                        + " resp=" + new com.google.gson.Gson().toJson(response.body()));
                renderLiveChannelGrid(list, total);
            }
            @Override
            public void onFailure(Call<ApiResponse<ItemListResponse>> call, Throwable t) {
                tvMoviesLoading.setVisibility(View.GONE);
                Toast.makeText(HomeActivity.this, "加载直播频道失败", Toast.LENGTH_SHORT).show();
            }
        });
    }

    /** 渲染直播频道网格（自适应列数） */
    private void renderLiveChannelGrid(List<PlayListItem> list, int total) {
        // 标题
        TextView h = new TextView(this);
        h.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        h.setPadding(6, 8, 6, 4);
        h.setText("直播频道  (" + total + "项)");
        h.setTextColor(0xFFEEEEEE);
        h.setTextSize(14);
        moviesContainer.addView(h);

        // 自适应列数
        float density = getResources().getDisplayMetrics().density;
        int cols = Math.max(3, (int) (getResources().getDisplayMetrics().widthPixels / (130 * density)));

        for (int idx = 0; idx < list.size(); idx += cols) {
            LinearLayout row = new LinearLayout(this);
            row.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            row.setOrientation(LinearLayout.HORIZONTAL);
            int inRow = Math.min(cols, list.size() - idx);
            for (int c = 0; c < cols && idx + c < list.size(); c++) {
                PlayListItem pli = list.get(idx + c);
                View card = makeLiveChannelCard(pli);
                // 图片占位区域高度与其他卡片一致：基于列数自适应
                if (card instanceof ViewGroup) {
                    View ch = ((ViewGroup) card).getChildAt(0);
                    int posterH = Math.min(550, (getResources().getDisplayMetrics().widthPixels / cols) * 3 / 2);
                    ch.setLayoutParams(new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, posterH));
                }
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
                lp.rightMargin = 6;
                lp.leftMargin = 6;
                card.setLayoutParams(lp);
                row.addView(card);
            }
            // 补齐空位
            for (int e = inRow; e < cols; e++) {
                View spacer = new View(this);
                spacer.setLayoutParams(new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.MATCH_PARENT, 1));
                row.addView(spacer);
            }
            moviesContainer.addView(row);
            moviesContainer.addView(makeSpacer(8));
        }
    }


    // ==================== 按键 ====================

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_ESCAPE) {
            if (currentTab == 2 && remoteFileBrowser != null && remoteFileBrowser.handleBack()) {
                return true;
            }
            // 搜索框有焦点 → 隐藏键盘并清除搜索
            if (etSearch.isFocused()) {
                hideKeyboard();
                etSearch.clearFocus();
            }
            // 搜索模式 → 清除搜索
            if (isSearching) {
                clearSearch();
                return true;
            }
            // 剧集选择页 → 返回详情页
            if (showingEpisodes && savedDetailItem != null && savedDetailInfo != null) {
                showingEpisodes = false;
                buildDetailPage(savedDetailItem, savedDetailInfo);
                return true;
            }
            // 详情页 → 返回上层（文件夹浏览列表、媒体库或概览）
            if (showingDetail) {
                showingDetail = false;
                if (savedBrowseList != null && currentBrowseContainer != null) {
                    renderBrowseAll(currentBrowseContainer, savedBrowseList, savedBrowseTitle);
                    return true;
                } else if (!folderNavStack.isEmpty()) {
                    goBackFolder();
                    return true;
                } else if (currentTab == 1) {
                    loadMediaLibraries();
                    return true;
                } else {
                    loadOverview();
                    return true;
                }
            }
            // 子文件夹导航回退
            if (!folderNavStack.isEmpty()) {
                goBackFolder();
                return true;
            }
            // 媒体库浏览中 → 返回媒体库首页
            if (currentTab == 1 && savedBrowseGuid != null) {
                savedBrowseGuid = null; savedBrowseList = null;
                folderNavStack.clear();
                loadMediaLibraries();
                return true;
            }
            // 影视 Tab 浏览全部 → 返回影视概览
            if (currentTab == 0 && savedBrowseGuid != null) {
                savedBrowseGuid = null; savedBrowseList = null;
                folderNavStack.clear();
                loadOverview();
                return true;
            }
            if (!showingOverview) {
                loadOverview();
                return true;
            }
            if (backPressedTime + 2000 > System.currentTimeMillis()) {
                finish();
            } else {
                backPressedTime = System.currentTimeMillis();
                Toast.makeText(this, "再按一次返回桌面", Toast.LENGTH_SHORT).show();
            }
            return true;
        }

        // 遥控器长按追踪：按下确认键时启动长按追踪，分发 onKeyLongPress
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
            event.startTracking();
        }

        // 遥控器菜单键(MENU) / 选项键：直接触发当前焦点项的长按操作（如文件夹长按“全部播放”）
        if (keyCode == KeyEvent.KEYCODE_MENU || keyCode == KeyEvent.KEYCODE_BUTTON_Y) {
            View focus = getCurrentFocus();
            if (focus != null && focus.isLongClickable()) {
                return focus.performLongClick();
            }
        }

        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyLongPress(int keyCode, KeyEvent event) {
        // 遥控器长按确认键（OK/ENTER）触发对应卡片/文件夹的长按操作（全部播放等）
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
            View focus = getCurrentFocus();
            if (focus != null && focus.isLongClickable()) {
                return focus.performLongClick();
            }
        }
        return super.onKeyLongPress(keyCode, event);
    }
}
