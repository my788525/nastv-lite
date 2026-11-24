package com.fntv.app.api.model;

import com.google.gson.annotations.SerializedName;
import java.util.List;

/**
 * 媒体列表项
 * 对应 Electron types.ts 中的 PlayListItem
 */
public class PlayListItem {

    public String guid;
    public String title;
    public String type;           // "Episode", "Video", "Folder" 等

    public String poster;

    @SerializedName("create_time")
    public String createTime;

    @SerializedName("modify_time")
    public String modifyTime;

    @SerializedName("created_at")
    public String createdAt;

    @SerializedName("updated_at")
    public String updatedAt;

    @SerializedName("release_date")
    public String releaseDate;

    @SerializedName("sort_title")
    public String sortTitle;

    @SerializedName("file_size")
    public long fileSize;

    @SerializedName("tv_title")
    public String tvTitle;

    @SerializedName("parent_title")
    public String parentTitle;

    @SerializedName("parent_guid")
    public String parentGuid;

    @SerializedName("path")
    public String path;

    @SerializedName("file_path")
    public String filePath;

    @SerializedName("folder_path")
    public String folderPath;

    @SerializedName("ancestor_name")
    public String ancestorName;   // 如 "日漫"

    @SerializedName("ancestor_category")
    public String ancestorCategory;

    public int watched;           // 1=已看, 0=未看
    public long ts;               // 播放进度(秒)

    public long duration;         // 视频时长(秒)

    @SerializedName("episode_number")
    public int episodeNumber;

    @SerializedName("season_number")
    public int seasonNumber;

    @SerializedName("vote_average")
    public String voteAverage;

    public String overview;       // 简介

    @SerializedName("runtime")
    public int runtime;           // 运行时长(分钟)

    @SerializedName("is_favorite")
    public int isFavorite;

    @SerializedName("watermark_percent")
    public double watermarkPercent;

    @SerializedName("video_guid")
    public String videoGuid;

    @SerializedName("audio_guid")
    public String audioGuid;

    @SerializedName("subtitle_guid")
    public String subtitleGuid;

    @SerializedName("media_guid")
    public String mediaGuid;

    @SerializedName("single_child_guid")
    public String singleChildGuid;

    @SerializedName("media_stream")
    public MediaStreamInfo mediaStream;

    @SerializedName("douban_id")
    public long doubanId;

    @SerializedName("imdb_id")
    public String imdbId;

    @SerializedName("trim_id")
    public String trimId;

    @SerializedName("air_date")
    public String airDate;

    @SerializedName("number_of_seasons")
    public int numberOfSeasons;

    @SerializedName("number_of_episodes")
    public int numberOfEpisodes;

    @SerializedName("local_number_of_seasons")
    public int localNumberOfSeasons;

    @SerializedName("local_number_of_episodes")
    public int localNumberOfEpisodes;

    public static class MediaStreamInfo {
        public List<String> resolutions;
        @SerializedName("audio_type")
        public Object audioType;
        @SerializedName("color_range_type")
        public Object colorRangeType;
    }

    /** 判断是否为文件夹/分类（可浏览进入）。
     *  注意：TV（电视剧/剧集 Series）不是文件夹——点击应走 getPlayInfo 详情页（渲染季/集），
     *  而非 browseItems 子目录浏览（其 getItemList 不请求 Season 类型，会导致空列表显示「暂无内容」）。 */
    public boolean isFolder() {
        return "Directory".equals(type)
                || "Folder".equals(type) || "folder".equals(type);
    }

    /** 判断是否为可播放的媒体（Movie: 电影, Video: 视频, Episode: 剧集） */
    public boolean isPlayable() {
        return "Movie".equals(type) || "Video".equals(type) || "Episode".equals(type);
    }

    /** 获取完整海报URL */
    public String getPosterUrl(String baseUrl) {
        if (poster != null && !poster.isEmpty()) {
            return baseUrl + "/v/api/v1/sys/img" + poster + "?w=400";
        }
        return null;
    }

    /** 获取显示标题 */
    public String getDisplayTitle() {
        if (tvTitle != null && !tvTitle.isEmpty()) {
            if (episodeNumber > 0) {
                return tvTitle + " 第" + episodeNumber + "集";
            }
            return tvTitle;
        }
        return title != null ? title : "";
    }

    /** 获取分类简要名称 */
    public String getCategoryLabel() {
        if (ancestorName != null && !ancestorName.isEmpty()) {
            return ancestorName;
        }
        if (ancestorCategory != null && !ancestorCategory.isEmpty()) {
            return ancestorCategory;
        }
        return parentTitle != null ? parentTitle : "未分类";
    }

    /** 获取文件路径或目录路径（如果有） */
    public String getDisplayFilePath() {
        if (filePath != null && !filePath.trim().isEmpty()) return filePath.trim();
        if (path != null && !path.trim().isEmpty()) return path.trim();
        if (folderPath != null && !folderPath.trim().isEmpty()) return folderPath.trim();
        return null;
    }

    /** 获取最佳排序标题 */
    public String getBestSortTitle() {
        if (sortTitle != null && !sortTitle.trim().isEmpty()) {
            return sortTitle.trim();
        }
        if (title != null && !title.trim().isEmpty()) {
            return title.trim();
        }
        if (tvTitle != null && !tvTitle.trim().isEmpty()) {
            return tvTitle.trim();
        }
        return "";
    }

    /** 解析时间字符串到毫秒 */
    private static long parseDateMs(String dateStr) {
        if (dateStr == null || dateStr.trim().isEmpty()) return 0L;
        String s = dateStr.trim();
        // 如果是纯纯数字字符串（秒或毫秒）
        try {
            if (s.matches("^\\d+$")) {
                long num = Long.parseLong(s);
                if (num < 10000000000L) { // 秒级时间戳
                    return num * 1000L;
                }
                return num;
            }
        } catch (Exception ignored) {}

        // 尝试常见日期格式
        String[] patterns = {
                "yyyy-MM-dd'T'HH:mm:ss.SSSX",
                "yyyy-MM-dd'T'HH:mm:ssX",
                "yyyy-MM-dd'T'HH:mm:ss",
                "yyyy-MM-dd HH:mm:ss",
                "yyyy-MM-dd"
        };
        for (String p : patterns) {
            try {
                java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat(p, java.util.Locale.CHINA);
                java.util.Date d = sdf.parse(s);
                if (d != null) return d.getTime();
            } catch (Exception ignored) {}
        }
        return 0L;
    }

    /** 获取修改时间戳（毫秒） */
    public long getEffectiveModifyTimeMs() {
        long ms = parseDateMs(modifyTime);
        if (ms > 0) return ms;
        ms = parseDateMs(updatedAt);
        if (ms > 0) return ms;
        return getEffectiveCreateTimeMs();
    }

    /** 获取创建时间戳（毫秒） */
    public long getEffectiveCreateTimeMs() {
        long ms = parseDateMs(createTime);
        if (ms > 0) return ms;
        ms = parseDateMs(createdAt);
        if (ms > 0) return ms;
        return parseDateMs(releaseDate);
    }

    /** 格式化显示日期 */
    public String getDisplayDate() {
        long ms = getEffectiveModifyTimeMs();
        if (ms <= 0) ms = getEffectiveCreateTimeMs();
        if (ms > 0) {
            java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.CHINA);
            return sdf.format(new java.util.Date(ms));
        }
        if (releaseDate != null && !releaseDate.trim().isEmpty()) {
            return releaseDate.trim();
        }
        return "";
    }

    /** 格式化视频时长 (秒 -> HH:mm:ss / mm:ss) */
    public String getFormattedDuration() {
        long totalSec = duration > 0 ? duration : (runtime > 0 ? runtime * 60L : 0);
        if (totalSec <= 0) return "";
        long hours = totalSec / 3600;
        long minutes = (totalSec % 3600) / 60;
        long seconds = totalSec % 60;
        if (hours > 0) {
            return String.format(java.util.Locale.CHINA, "%02d:%02d:%02d", hours, minutes, seconds);
        } else {
            return String.format(java.util.Locale.CHINA, "%02d:%02d", minutes, seconds);
        }
    }
}
