package com.fntv.app.util;

import com.fntv.app.api.model.PlayListItem;
import java.text.Collator;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * 影视列表排序工具类
 * 支持按名称、按修改时间、按创建时间正反序排序，支持文件夹优先置顶
 */
public class MediaSortHelper {

    public static final int SORT_NAME = 0;        // 按名称
    public static final int SORT_MODIFY_TIME = 1; // 按修改时间
    public static final int SORT_CREATE_TIME = 2; // 按创建时间

    public static final int ORDER_ASC = 0;        // 正序 (升序)
    public static final int ORDER_DESC = 1;       // 反序 (降序)

    public static final String[] SORT_FIELD_LABELS = {"按名称", "按修改时间", "按创建时间"};
    public static final String[] SORT_ORDER_LABELS = {"正序 (升序)", "反序 (降序)"};

    private static final Collator COLLATOR = Collator.getInstance(Locale.CHINA);

    /**
     * 对列表进行排序
     * @param items 待排序列表
     * @param sortField SORT_NAME / SORT_MODIFY_TIME / SORT_CREATE_TIME
     * @param sortOrder ORDER_ASC / ORDER_DESC
     * @param foldersFirst 是否文件夹优先置顶
     */
    public static void sortItems(List<PlayListItem> items, final int sortField, final int sortOrder, final boolean foldersFirst) {
        if (items == null || items.size() <= 1) return;

        Collections.sort(items, new Comparator<PlayListItem>() {
            @Override
            public int compare(PlayListItem a, PlayListItem b) {
                if (a == null && b == null) return 0;
                if (a == null) return 1;
                if (b == null) return -1;

                // 1. 文件夹置顶处理
                if (foldersFirst) {
                    boolean aFolder = a.isFolder();
                    boolean bFolder = b.isFolder();
                    if (aFolder && !bFolder) return -1;
                    if (!aFolder && bFolder) return 1;
                }

                // 2. 根据选定字段对比
                int result = 0;
                switch (sortField) {
                    case SORT_NAME:
                        String nameA = a.getBestSortTitle();
                        String nameB = b.getBestSortTitle();
                        result = COLLATOR.compare(nameA, nameB);
                        break;

                    case SORT_MODIFY_TIME:
                        long mtimeA = a.getEffectiveModifyTimeMs();
                        long mtimeB = b.getEffectiveModifyTimeMs();
                        result = Long.compare(mtimeA, mtimeB);
                        if (result == 0) {
                            result = COLLATOR.compare(a.getBestSortTitle(), b.getBestSortTitle());
                        }
                        break;

                    case SORT_CREATE_TIME:
                        long ctimeA = a.getEffectiveCreateTimeMs();
                        long ctimeB = b.getEffectiveCreateTimeMs();
                        result = Long.compare(ctimeA, ctimeB);
                        if (result == 0) {
                            result = COLLATOR.compare(a.getBestSortTitle(), b.getBestSortTitle());
                        }
                        break;

                    default:
                        result = COLLATOR.compare(a.getBestSortTitle(), b.getBestSortTitle());
                        break;
                }

                // 3. 升降序调整
                return sortOrder == ORDER_DESC ? -result : result;
            }
        });
    }

    /**
     * 转换为 API 对应的 sort_column 字段
     */
    public static String getApiSortColumn(int sortField) {
        switch (sortField) {
            case SORT_NAME:
                return "sort_title";
            case SORT_MODIFY_TIME:
                return "modify_time";
            case SORT_CREATE_TIME:
                return "create_time";
            default:
                return "create_time";
        }
    }

    /**
     * 转换为 API 对应的 sort_type
     */
    public static String getApiSortType(int sortOrder) {
        return sortOrder == ORDER_ASC ? "ASC" : "DESC";
    }
}
