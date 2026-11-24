package com.fntv.app;

import com.fntv.app.api.model.PlayListItem;

import java.util.ArrayList;
import java.util.List;

/**
 * 跨 Activity 传递的播放列表持有者，避免 Intent 传递超大列表导致 TransactionTooLargeException
 */
public class PlaylistHolder {

    private static List<PlayListItem> playlist;
    private static String playlistTitle;

    public static synchronized void setPlaylist(String title, List<PlayListItem> list) {
        playlistTitle = title;
        playlist = (list != null) ? new ArrayList<>(list) : null;
    }

    public static synchronized List<PlayListItem> getPlaylist() {
        return playlist != null ? new ArrayList<>(playlist) : null;
    }

    public static synchronized String getPlaylistTitle() {
        return playlistTitle;
    }

    public static synchronized void clear() {
        playlist = null;
        playlistTitle = null;
    }
}
