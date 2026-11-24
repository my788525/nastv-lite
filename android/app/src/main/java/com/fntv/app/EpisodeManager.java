package com.fntv.app;

import android.content.Context;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.Toast;

import com.fntv.app.api.FnApiManager;
import com.fntv.app.api.model.ApiResponse;
import com.fntv.app.api.model.ItemListRequest;
import com.fntv.app.api.model.ItemListResponse;
import com.fntv.app.api.model.PlayListItem;
import com.fntv.app.util.MediaSortHelper;

import java.util.List;

import retrofit2.Call;
import retrofit2.Response;

/** 剧集列表管理 — 加载、切换剧集 */
public class EpisodeManager {

    public static final int PLAY_MODE_SEQUENTIAL = 0;
    public static final int PLAY_MODE_SHUFFLE = 1;
    public static final int PLAY_MODE_REPEAT_ONE = 2;

    public interface Callback {
        String getBaseUrl();
        String getParentGuid();
        String getItemGuid();
        int getEpisodeNumber();
        FnApiManager getApiManager();
        Context getContext();
        void onSwitchEpisode(String guid, String title);
        String getParentTitle();
        boolean isForceSingle();
        void onCustomPlaylistLoaded(String folderGuid);
        void onEpisodesLoaded();
    }

    private List<PlayListItem> episodeList;
    private int currentEpIndex = -1;
    private boolean loadingEpisodes = false;
    private boolean isCustomPlaylist = false;
    private int playMode = PLAY_MODE_SEQUENTIAL;
    private String playlistTitle;
    private final Callback cb;
    private final Button btnEpisodeList;
    private final Button btnNextEp;
    private String currentGuid;
    private String currentTitle;

    private static final String TAG = "Player";

    public EpisodeManager(Callback cb, Button btnEpisodeList, Button btnNextEp) {
        this.cb = cb;
        this.btnEpisodeList = btnEpisodeList;
        this.btnNextEp = btnNextEp;
    }

    public int getPlayMode() {
        return playMode;
    }

    public void setPlayMode(int mode) {
        this.playMode = mode;
        updateNextBtn();
    }

    public int cyclePlayMode() {
        this.playMode = (this.playMode + 1) % 3;
        updateNextBtn();
        return this.playMode;
    }

    public static String getPlayModeName(int mode) {
        switch (mode) {
            case PLAY_MODE_SHUFFLE: return "🔀 随机播放";
            case PLAY_MODE_REPEAT_ONE: return "🔂 单曲循环";
            case PLAY_MODE_SEQUENTIAL:
            default: return "🔁 列表循环";
        }
    }

    public static String getPlayModeShortName(int mode) {
        switch (mode) {
            case PLAY_MODE_SHUFFLE: return "🔀 随机";
            case PLAY_MODE_REPEAT_ONE: return "🔂 单曲";
            case PLAY_MODE_SEQUENTIAL:
            default: return "🔁 列表";
        }
    }

    /** 设置自定义文件夹/文件列表播放列表 */
    public void setCustomPlaylist(List<PlayListItem> list, String title, String initialGuid) {
        this.episodeList = list != null ? new java.util.ArrayList<>(list) : null;
        this.isCustomPlaylist = true;
        this.playlistTitle = title;
        this.currentEpIndex = -1;
        if (episodeList != null) {
            for (int i = 0; i < episodeList.size(); i++) {
                PlayListItem item = episodeList.get(i);
                if (item != null && item.guid != null && item.guid.equals(initialGuid)) {
                    currentEpIndex = i;
                    break;
                }
            }
            if (currentEpIndex < 0 && !episodeList.isEmpty()) {
                currentEpIndex = 0;
            }
        }
        PlaylistHolder.setPlaylist(title, this.episodeList);
        if (cb != null) {
            cb.onCustomPlaylistLoaded(cb.getParentGuid());
            cb.onEpisodesLoaded();
        }
        if (btnEpisodeList != null) {
            btnEpisodeList.setVisibility(View.VISIBLE);
        }
        updateBtnText();
        updateNextBtn();
    }

    public boolean isCustomPlaylist() {
        return isCustomPlaylist;
    }

    public String getPlaylistTitle() {
        return playlistTitle;
    }

    public int getCurrentEpIndex() {
        return currentEpIndex;
    }

    public int getEpisodeCount() {
        return episodeList != null ? episodeList.size() : 0;
    }

    public PlayListItem getCurrentEpisodeItem() {
        if (episodeList != null && currentEpIndex >= 0 && currentEpIndex < episodeList.size()) {
            return episodeList.get(currentEpIndex);
        }
        return null;
    }

    /** 同步当前项索引 */
    public void syncCurrentIndex() {
        if (episodeList == null || episodeList.isEmpty() || cb == null) return;
        String itemGuid = cb.getItemGuid();
        if (itemGuid != null) {
            for (int i = 0; i < episodeList.size(); i++) {
                PlayListItem item = episodeList.get(i);
                if (item != null && itemGuid.equals(item.guid)) {
                    currentEpIndex = i;
                    break;
                }
            }
        }
        if (currentEpIndex < 0 && cb.getEpisodeNumber() > 0) {
            int epNum = cb.getEpisodeNumber();
            for (int i = 0; i < episodeList.size(); i++) {
                PlayListItem item = episodeList.get(i);
                if (item != null && item.episodeNumber == epNum) {
                    currentEpIndex = i;
                    break;
                }
            }
        }
    }

    /** 获取下一个视频/剧集项（若自定义列表支持循环，在末尾时返回第0项；若单曲循环返回当前项；若随机播放返回随机项） */
    public PlayListItem getNextEpisodeItem() {
        if (episodeList == null || episodeList.isEmpty()) return null;
        if (currentEpIndex < 0) {
            syncCurrentIndex();
        }
        if (playMode == PLAY_MODE_REPEAT_ONE) {
            if (currentEpIndex >= 0 && currentEpIndex < episodeList.size()) {
                return episodeList.get(currentEpIndex);
            }
        }
        if (playMode == PLAY_MODE_SHUFFLE && episodeList.size() > 1) {
            int randIdx = (int) (Math.random() * episodeList.size());
            if (randIdx == currentEpIndex) {
                randIdx = (randIdx + 1) % episodeList.size();
            }
            return episodeList.get(randIdx);
        }
        int nextIndex = currentEpIndex + 1;
        if (nextIndex >= episodeList.size()) {
            if (isCustomPlaylist) {
                nextIndex = 0; // 文件夹循环连播
            } else {
                return null;
            }
        }
        if (nextIndex >= 0 && nextIndex < episodeList.size()) {
            return episodeList.get(nextIndex);
        }
        return null;
    }

    /** 获取下一个视频/剧集项索引 */
    public int getNextEpIndex() {
        if (episodeList == null || episodeList.isEmpty()) return -1;
        if (currentEpIndex < 0) {
            syncCurrentIndex();
        }
        if (playMode == PLAY_MODE_REPEAT_ONE) {
            return currentEpIndex;
        }
        if (playMode == PLAY_MODE_SHUFFLE && episodeList.size() > 1) {
            int randIdx = (int) (Math.random() * episodeList.size());
            if (randIdx == currentEpIndex) {
                randIdx = (randIdx + 1) % episodeList.size();
            }
            return randIdx;
        }
        int nextIndex = currentEpIndex + 1;
        if (nextIndex >= episodeList.size()) {
            if (isCustomPlaylist) {
                return 0;
            } else {
                return -1;
            }
        }
        return nextIndex;
    }

    /** 是否有下一集/下一首 */
    public boolean hasNext() {
        if (episodeList == null || episodeList.isEmpty()) return false;
        if (playMode == PLAY_MODE_REPEAT_ONE || playMode == PLAY_MODE_SHUFFLE) return true;
        if (isCustomPlaylist) return true; // 自定义文件夹播放列表支持列表循环连播
        return currentEpIndex >= 0 && currentEpIndex < episodeList.size() - 1;
    }

    /** 是否有上一集/上一首 */
    public boolean hasPrev() {
        if (episodeList == null || episodeList.isEmpty()) return false;
        if (playMode == PLAY_MODE_REPEAT_ONE || playMode == PLAY_MODE_SHUFFLE) return true;
        if (isCustomPlaylist) return true; // 自定义文件夹播放列表支持列表循环连播
        return currentEpIndex > 0 && currentEpIndex < episodeList.size();
    }

    /** 是否正在加载 */
    public boolean isLoading() { return loadingEpisodes; }

    /** 是否已加载列表 */
    public boolean isLoaded() { return episodeList != null && !episodeList.isEmpty(); }

    /** 加载剧集列表 */
    public void loadList(String parentGuid) {
        if (isCustomPlaylist) return; // 自定义文件夹播放列表无需从接口拉取电视剧集
        if (parentGuid == null || parentGuid.isEmpty()) return;
        loadingEpisodes = true;
        Log.d(TAG, "getEpisodeList 请求: " + cb.getBaseUrl() + "/v/api/v1/episode/list/" + parentGuid);
        cb.getApiManager().getApi().getEpisodeList(parentGuid).enqueue(
                new retrofit2.Callback<ApiResponse<List<PlayListItem>>>() {
                    @Override
                    public void onResponse(Call<ApiResponse<List<PlayListItem>>> call,
                                           Response<ApiResponse<List<PlayListItem>>> resp) {
                        loadingEpisodes = false;
                        Log.d(TAG, "getEpisodeList 响应 code=" + resp.code()
                                + " body=" + (resp.body() != null ? "code=" + resp.body().code + " size="
                                + (resp.body().data != null ? resp.body().data.size() : "null") : "null"));
                        if (resp.isSuccessful() && resp.body() != null && resp.body().code == 0
                                && resp.body().data != null && !resp.body().data.isEmpty()) {
                            episodeList = resp.body().data;
                            isCustomPlaylist = false;
                            currentEpIndex = -1;
                            int epNum = cb.getEpisodeNumber();
                            String itemGuid = cb.getItemGuid();
                            for (int i = 0; i < episodeList.size(); i++) {
                                PlayListItem ep = episodeList.get(i);
                                if (ep.guid.equals(itemGuid)) {
                                    currentEpIndex = i;
                                    break;
                                }
                                if (currentEpIndex < 0 && epNum > 0 && ep.episodeNumber == epNum) {
                                    currentEpIndex = i;
                                }
                            }
                            Log.d(TAG, "getEpisodeList 成功: " + episodeList.size() + " 集, currentIdx=" + currentEpIndex
                                    + " epNum=" + epNum + " itemGuid=" + itemGuid);
                            if (btnEpisodeList != null) btnEpisodeList.setVisibility(View.VISIBLE);
                            updateBtnText();
                            updateNextBtn();
                            if (cb != null) cb.onEpisodesLoaded();
                        } else {
                            // 不是电视剧集，或者剧集列表为空：尝试加载同文件夹下的同级视频
                            loadFolderSiblings(parentGuid);
                        }
                    }

                    @Override
                    public void onFailure(Call<ApiResponse<List<PlayListItem>>> call, Throwable t) {
                        loadingEpisodes = false;
                        Log.e(TAG, "getEpisodeList 失败: " + t.getMessage());
                        loadFolderSiblings(parentGuid);
                    }
                });
    }

    /** 尝试加载同一文件夹下的兄弟视频，若存在其他视频则自动加入播放队列 */
    private void loadFolderSiblings(final String parentGuid) {
        if (parentGuid == null || parentGuid.isEmpty()) return;
        if (cb != null && cb.isForceSingle()) return;
        loadingEpisodes = true;
        Log.d(TAG, "尝试按文件夹加载同级视频: parentGuid=" + parentGuid);
        final boolean useParent = parentGuid.startsWith("fv_");
        ItemListRequest req = useParent
                ? ItemListRequest.browseFolderByParent(parentGuid)
                : new ItemListRequest(parentGuid, java.util.Arrays.asList("Movie", "TV", "Directory", "Video", "Episode"), false, "sort_title", "ASC", 500);
        req.pageSize = 500;

        cb.getApiManager().getApi().getItemList(req).enqueue(new retrofit2.Callback<ApiResponse<ItemListResponse>>() {
            @Override
            public void onResponse(Call<ApiResponse<ItemListResponse>> call, Response<ApiResponse<ItemListResponse>> resp) {
                loadingEpisodes = false;
                List<PlayListItem> items = null;
                if (resp.isSuccessful() && resp.body() != null && resp.body().code == 0
                        && resp.body().data != null && resp.body().data.list != null) {
                    items = resp.body().data.list;
                }

                List<PlayListItem> playableList = new java.util.ArrayList<>();
                if (items != null) {
                    for (PlayListItem it : items) {
                        if (it != null && !it.isFolder() && !"TV".equals(it.type)) {
                            playableList.add(it);
                        }
                    }
                }

                if (playableList.size() > 1) {
                    Log.d(TAG, "同文件夹下发现 " + playableList.size() + " 个视频，自动加入播放队列");
                    MediaSortHelper.sortItems(playableList, MediaSortHelper.SORT_NAME, MediaSortHelper.ORDER_ASC, false);
                    String title = cb.getParentTitle();
                    if (title == null || title.isEmpty()) title = "文件夹播放";
                    setCustomPlaylist(playableList, title, cb.getItemGuid());
                    if (cb.getContext() != null) {
                        Toast.makeText(cb.getContext(), "📁 已将同文件夹内 " + playableList.size() + " 个视频加入播放列表", Toast.LENGTH_SHORT).show();
                    }
                } else if (items == null || items.isEmpty()) {
                    // 若首次查询为空，尝试另一种形态请求（useParent 互换）兜底
                    retryFolderSiblingsWithAlt(parentGuid, !useParent);
                }
            }

            @Override
            public void onFailure(Call<ApiResponse<ItemListResponse>> call, Throwable t) {
                loadingEpisodes = false;
                retryFolderSiblingsWithAlt(parentGuid, !useParent);
            }
        });
    }

    private void retryFolderSiblingsWithAlt(final String parentGuid, boolean useParent) {
        if (cb != null && cb.isForceSingle()) return;
        ItemListRequest req = useParent
                ? ItemListRequest.browseFolderByParent(parentGuid)
                : new ItemListRequest(parentGuid, java.util.Arrays.asList("Movie", "TV", "Directory", "Video", "Episode"), false, "sort_title", "ASC", 500);
        req.pageSize = 500;
        loadingEpisodes = true;
        cb.getApiManager().getApi().getItemList(req).enqueue(new retrofit2.Callback<ApiResponse<ItemListResponse>>() {
            @Override
            public void onResponse(Call<ApiResponse<ItemListResponse>> call, Response<ApiResponse<ItemListResponse>> resp) {
                loadingEpisodes = false;
                if (resp.isSuccessful() && resp.body() != null && resp.body().code == 0
                        && resp.body().data != null && resp.body().data.list != null) {
                    List<PlayListItem> items = resp.body().data.list;
                    List<PlayListItem> playableList = new java.util.ArrayList<>();
                    for (PlayListItem it : items) {
                        if (it != null && !it.isFolder() && !"TV".equals(it.type)) {
                            playableList.add(it);
                        }
                    }
                    if (playableList.size() > 1) {
                        Log.d(TAG, "备选查询同文件夹下发现 " + playableList.size() + " 个视频，自动加入播放队列");
                        MediaSortHelper.sortItems(playableList, MediaSortHelper.SORT_NAME, MediaSortHelper.ORDER_ASC, false);
                        String title = cb.getParentTitle();
                        if (title == null || title.isEmpty()) title = "文件夹播放";
                        setCustomPlaylist(playableList, title, cb.getItemGuid());
                        if (cb.getContext() != null) {
                            Toast.makeText(cb.getContext(), "📁 已将同文件夹内 " + playableList.size() + " 个视频加入播放列表", Toast.LENGTH_SHORT).show();
                        }
                    }
                }
            }

            @Override
            public void onFailure(Call<ApiResponse<ItemListResponse>> call, Throwable t) {
                loadingEpisodes = false;
            }
        });
    }

    /** 播放下一个项目（下一集/下一首MV） */
    public void playNext() {
        if (!hasNext()) return;
        if (playMode == PLAY_MODE_REPEAT_ONE) {
            if (currentEpIndex >= 0 && currentEpIndex < episodeList.size()) {
                PlayListItem cur = episodeList.get(currentEpIndex);
                currentGuid = cur.guid;
                currentTitle = cur.getDisplayTitle() != null ? cur.getDisplayTitle() : cur.title;
                updateBtnText();
                updateNextBtn();
                cb.onSwitchEpisode(currentGuid, currentTitle);
                return;
            }
        }
        if (playMode == PLAY_MODE_SHUFFLE && episodeList.size() > 1) {
            int randIdx = (int) (Math.random() * episodeList.size());
            if (randIdx == currentEpIndex) {
                randIdx = (randIdx + 1) % episodeList.size();
            }
            currentEpIndex = randIdx;
            PlayListItem next = episodeList.get(currentEpIndex);
            currentGuid = next.guid;
            currentTitle = next.getDisplayTitle() != null ? next.getDisplayTitle() : next.title;
            updateBtnText();
            updateNextBtn();
            cb.onSwitchEpisode(currentGuid, currentTitle);
            return;
        }
        int nextIndex = currentEpIndex + 1;
        if (nextIndex >= episodeList.size()) {
            if (isCustomPlaylist) {
                nextIndex = 0; // 文件夹循环连播
            } else {
                return;
            }
        }
        currentEpIndex = nextIndex;
        PlayListItem next = episodeList.get(currentEpIndex);
        currentGuid = next.guid;
        currentTitle = next.getDisplayTitle() != null ? next.getDisplayTitle() : next.title;
        updateBtnText();
        updateNextBtn();
        cb.onSwitchEpisode(currentGuid, currentTitle);
    }

    /** 播放上一个项目（方向盘/遥控上键） */
    public void playPrev() {
        if (!hasPrev()) return;
        if (playMode == PLAY_MODE_REPEAT_ONE) {
            if (currentEpIndex >= 0 && currentEpIndex < episodeList.size()) {
                PlayListItem cur = episodeList.get(currentEpIndex);
                currentGuid = cur.guid;
                currentTitle = cur.getDisplayTitle() != null ? cur.getDisplayTitle() : cur.title;
                updateBtnText();
                updateNextBtn();
                cb.onSwitchEpisode(currentGuid, currentTitle);
                return;
            }
        }
        if (playMode == PLAY_MODE_SHUFFLE && episodeList.size() > 1) {
            int randIdx = (int) (Math.random() * episodeList.size());
            if (randIdx == currentEpIndex) {
                randIdx = (randIdx + 1) % episodeList.size();
            }
            currentEpIndex = randIdx;
            PlayListItem prev = episodeList.get(currentEpIndex);
            currentGuid = prev.guid;
            currentTitle = prev.getDisplayTitle() != null ? prev.getDisplayTitle() : prev.title;
            updateBtnText();
            updateNextBtn();
            cb.onSwitchEpisode(currentGuid, currentTitle);
            return;
        }
        int prevIndex = currentEpIndex - 1;
        if (prevIndex < 0) {
            if (isCustomPlaylist) {
                prevIndex = episodeList.size() - 1; // 文件夹循环连播
            } else {
                return;
            }
        }
        currentEpIndex = prevIndex;
        PlayListItem prev = episodeList.get(currentEpIndex);
        currentGuid = prev.guid;
        currentTitle = prev.getDisplayTitle() != null ? prev.getDisplayTitle() : prev.title;
        updateBtnText();
        updateNextBtn();
        cb.onSwitchEpisode(currentGuid, currentTitle);
    }

    /** 显示剧集/播放列表选择器 */
    public void showPicker() {
        if (episodeList == null || episodeList.isEmpty()) return;

        // 每次点开播放列表前，精准根据当前正在播放的 GUID 同步当前项索引
        String activeGuid = cb != null ? cb.getItemGuid() : null;
        if (activeGuid != null) {
            for (int i = 0; i < episodeList.size(); i++) {
                PlayListItem ep = episodeList.get(i);
                if (ep != null && activeGuid.equals(ep.guid)) {
                    currentEpIndex = i;
                    break;
                }
            }
        }

        final String[] items = new String[episodeList.size()];
        for (int i = 0; i < episodeList.size(); i++) {
            PlayListItem ep = episodeList.get(i);
            String title = ep.getDisplayTitle() != null ? ep.getDisplayTitle() : (ep.title != null ? ep.title : "");
            String dur = ep.getFormattedDuration();
            boolean isCurrent = (i == currentEpIndex);
            if (isCustomPlaylist) {
                items[i] = (isCurrent ? "▶ " : "") + (i + 1) + ". " + title
                        + (isCurrent ? "  [正在播放]" : (dur.isEmpty() ? "" : "  (" + dur + ")"));
            } else {
                items[i] = (isCurrent ? "▶ " : "") + "EP" + (ep.episodeNumber > 0 ? ep.episodeNumber : (i + 1))
                        + "  " + title + (isCurrent ? "  [正在播放]" : (dur.isEmpty() ? "" : "  (" + dur + ")"));
            }
        }
        String dlgTitle = isCustomPlaylist
                ? ((playlistTitle != null && !playlistTitle.isEmpty()) ? ("播放列表 · " + playlistTitle) : "播放列表")
                : "选择剧集";

        android.app.AlertDialog dialog = new android.app.AlertDialog.Builder(cb.getContext())
                .setTitle(dlgTitle)
                .setSingleChoiceItems(items, currentEpIndex, (d, which) -> {
                    d.dismiss();
                    if (which >= 0 && which < episodeList.size()) {
                        PlayListItem s = episodeList.get(which);
                        currentEpIndex = which;
                        currentGuid = s.guid;
                        currentTitle = s.getDisplayTitle() != null ? s.getDisplayTitle() : s.title;
                        updateBtnText();
                        updateNextBtn();
                        cb.onSwitchEpisode(currentGuid, currentTitle);
                    }
                })
                .setNegativeButton("取消", null)
                .create();
        dialog.show();

        // 核心优化：弹窗展示后精确定位滚动到当前播放位置，并使当前播放项获得焦点，避免默认停留在最前
        final android.widget.ListView listView = dialog.getListView();
        if (listView != null && currentEpIndex >= 0) {
            final int targetPos = currentEpIndex;
            listView.post(() -> {
                int offset = (int) (80 * cb.getContext().getResources().getDisplayMetrics().density);
                listView.setSelectionFromTop(targetPos, offset);
                listView.setSelection(targetPos);
                listView.requestFocus();
            });
        }
    }

    /** 更新按钮文字 */
    private void updateBtnText() {
        if (btnEpisodeList != null) {
            if (isCustomPlaylist && episodeList != null) {
                btnEpisodeList.setText("列表 (" + (currentEpIndex + 1) + "/" + episodeList.size() + ")");
            } else {
                btnEpisodeList.setText("剧集");
            }
        }
        if (btnNextEp != null) {
            btnNextEp.setText(isCustomPlaylist ? "下一首" : "下一集");
        }
    }

    /** 重置状态（仅对非自定义列表重置；若是自定义列表则保留） */
    public void reset() {
        if (isCustomPlaylist) return;
        episodeList = null;
        currentEpIndex = -1;
        loadingEpisodes = false;
    }

    /** 强制清除所有播放列表 */
    public void clear() {
        isCustomPlaylist = false;
        playlistTitle = null;
        episodeList = null;
        currentEpIndex = -1;
        loadingEpisodes = false;
    }

    private void updateNextBtn() {
        if (btnNextEp != null) {
            btnNextEp.setVisibility(hasNext() ? View.VISIBLE : View.GONE);
        }
    }
}
