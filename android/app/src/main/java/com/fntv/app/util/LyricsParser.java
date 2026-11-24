package com.fntv.app.util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * LRC 同步歌词解析器（参考 NavMusic / Ultrasonic LyricsParser 实现，兼容 Android 4.4+）。
 */
public class LyricsParser {

    public static class TimedLyric {
        public final long startMs;
        public final String text;

        public TimedLyric(long startMs, String text) {
            this.startMs = startMs;
            this.text = text;
        }
    }

    public static class Result {
        public final List<TimedLyric> timed;
        public final List<String> plainLines;

        public Result(List<TimedLyric> timed, List<String> plainLines) {
            this.timed = timed;
            this.plainLines = plainLines;
        }

        public boolean hasTimed() {
            return timed != null && !timed.isEmpty();
        }

        public boolean isEmpty() {
            return (timed == null || timed.isEmpty()) && (plainLines == null || plainLines.isEmpty());
        }
    }

    private static final Pattern TIME_TAG = Pattern.compile("\\[(?:(\\d{1,3}):)?(\\d{1,2}):(\\d{1,2})(?:[.:](\\d{1,3}))?\\]");
    private static final Pattern OFFSET_TAG = Pattern.compile("\\[offset:\\s*([+-]?\\d+(?:\\.\\d+)?)\\s*\\]", Pattern.CASE_INSENSITIVE);

    public static Result parse(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return new Result(Collections.<TimedLyric>emptyList(), Collections.<String>emptyList());
        }

        long globalOffsetMs = 0L;
        List<TimedLyric> timed = new ArrayList<>();
        List<String> plain = new ArrayList<>();

        String[] lines = raw.split("\\r?\\n");
        for (String line : lines) {
            if (line == null) continue;
            Matcher offM = OFFSET_TAG.matcher(line);
            if (offM.find()) {
                globalOffsetMs = parseOffset(offM.group(1));
                continue;
            }

            Matcher tm = TIME_TAG.matcher(line);
            List<long[]> starts = new ArrayList<>();
            int lastEnd = 0;
            while (tm.find()) {
                long h = parseLongSafe(tm.group(1));
                long m = parseLongSafe(tm.group(2));
                long s = parseLongSafe(tm.group(3));
                long frac = parseFractionMs(tm.group(4));
                starts.add(new long[]{h * 3_600_000L + m * 60_000L + s * 1_000L + frac});
                lastEnd = tm.end();
            }

            if (!starts.isEmpty()) {
                String text = line.substring(lastEnd).trim();
                for (long[] st : starts) {
                    timed.add(new TimedLyric(Math.max(0L, st[0] + globalOffsetMs), text.isEmpty() ? "♪" : text));
                }
            } else {
                String trimmed = line.trim();
                if (!trimmed.isEmpty() && !trimmed.startsWith("[")) {
                    plain.add(trimmed);
                }
            }
        }

        Collections.sort(timed, new Comparator<TimedLyric>() {
            @Override
            public int compare(TimedLyric a, TimedLyric b) {
                return Long.compare(a.startMs, b.startMs);
            }
        });
        return new Result(timed, plain);
    }

    public static int lineIndexAt(List<TimedLyric> lines, long positionMs) {
        if (lines == null || lines.isEmpty()) return -1;
        int low = 0;
        int high = lines.size() - 1;
        int found = -1;
        while (low <= high) {
            int mid = (low + high) >>> 1;
            if (lines.get(mid).startMs <= positionMs) {
                found = mid;
                low = mid + 1;
            } else {
                high = mid - 1;
            }
        }
        return found;
    }

    private static long parseOffset(String v) {
        try {
            double d = Double.parseDouble(v);
            return v.contains(".") ? (long) (d * 1000.0) : (long) d;
        } catch (Exception e) {
            return 0L;
        }
    }

    private static long parseLongSafe(String s) {
        if (s == null || s.isEmpty()) return 0L;
        try {
            return Long.parseLong(s);
        } catch (Exception e) {
            return 0L;
        }
    }

    private static long parseFractionMs(String frac) {
        if (frac == null || frac.isEmpty()) return 0L;
        try {
            if (frac.length() == 1) return Long.parseLong(frac) * 100L;
            if (frac.length() == 2) return Long.parseLong(frac) * 10L;
            return Long.parseLong(frac.substring(0, 3));
        } catch (Exception e) {
            return 0L;
        }
    }
}
