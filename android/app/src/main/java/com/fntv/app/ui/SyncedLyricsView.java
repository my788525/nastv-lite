package com.fntv.app.ui;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.fntv.app.util.LyricsParser;

import java.util.ArrayList;
import java.util.List;

/**
 * 同步滚动歌词视图（兼容 Android 4.4+，支持 LRC 时间轴高亮自动滚动与纯文本兜底）。
 */
public class SyncedLyricsView extends ScrollView {

    public interface OnLineClickListener {
        void onSeekTo(long positionMs);
    }

    private LinearLayout container;
    private final List<TextView> lineViews = new ArrayList<>();
    private LyricsParser.Result currentResult;
    private int activeIndex = -2;
    private int baseFontSp = 15;
    private OnLineClickListener lineClickListener;

    public SyncedLyricsView(Context context) {
        super(context);
        init(context);
    }

    public SyncedLyricsView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    private void init(Context context) {
        setVerticalScrollBarEnabled(false);
        setFillViewport(true);
        container = new LinearLayout(context);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setGravity(Gravity.CENTER_HORIZONTAL);
        int pad = dp(24);
        container.setPadding(dp(16), pad, dp(16), pad);
        addView(container, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
    }

    public void setOnLineClickListener(OnLineClickListener l) {
        this.lineClickListener = l;
    }

    public void setFontSizeSp(int sp) {
        this.baseFontSp = Math.max(11, Math.min(32, sp));
        for (int i = 0; i < lineViews.size(); i++) {
            TextView tv = lineViews.get(i);
            boolean active = (i == activeIndex);
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, active ? (baseFontSp + 3) : baseFontSp);
        }
    }

    public void setLyrics(LyricsParser.Result result, String emptyHint) {
        this.currentResult = result;
        this.activeIndex = -2;
        container.removeAllViews();
        lineViews.clear();

        if (result == null || result.isEmpty()) {
            TextView tv = makeLineView(emptyHint != null ? emptyHint : "暂无歌词", false);
            tv.setTextColor(Color.parseColor("#64748B"));
            container.addView(tv);
            return;
        }

        if (result.hasTimed()) {
            for (int i = 0; i < result.timed.size(); i++) {
                final LyricsParser.TimedLyric item = result.timed.get(i);
                TextView tv = makeLineView(item.text, false);
                tv.setOnClickListener(new OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        if (lineClickListener != null) {
                            lineClickListener.onSeekTo(item.startMs);
                        }
                    }
                });
                lineViews.add(tv);
                container.addView(tv);
            }
        } else {
            for (String line : result.plainLines) {
                TextView tv = makeLineView(line, false);
                lineViews.add(tv);
                container.addView(tv);
            }
        }
        scrollTo(0, 0);
    }

    public void updatePosition(long positionMs) {
        if (currentResult == null || !currentResult.hasTimed() || lineViews.isEmpty()) return;
        int idx = LyricsParser.lineIndexAt(currentResult.timed, positionMs);
        if (idx == activeIndex) return;
        activeIndex = idx;

        for (int i = 0; i < lineViews.size(); i++) {
            TextView tv = lineViews.get(i);
            if (i == idx) {
                tv.setTextColor(Color.parseColor("#38BDF8"));
                tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, baseFontSp + 3);
                tv.setTypeface(null, Typeface.BOLD);
            } else {
                tv.setTextColor(Color.parseColor("#94A3B8"));
                tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, baseFontSp);
                tv.setTypeface(null, Typeface.NORMAL);
            }
        }

        if (idx >= 0 && idx < lineViews.size()) {
            final View target = lineViews.get(idx);
            post(new Runnable() {
                @Override
                public void run() {
                    int scrollY = target.getTop() - (getHeight() / 2) + (target.getHeight() / 2);
                    smoothScrollTo(0, Math.max(0, scrollY));
                }
            });
        }
    }

    private TextView makeLineView(String text, boolean active) {
        TextView tv = new TextView(getContext());
        tv.setText(text);
        tv.setGravity(Gravity.CENTER);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, active ? (baseFontSp + 3) : baseFontSp);
        tv.setTextColor(active ? Color.parseColor("#38BDF8") : Color.parseColor("#94A3B8"));
        tv.setPadding(dp(8), dp(8), dp(8), dp(8));
        tv.setLineSpacing(dp(2), 1.15f);
        return tv;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
