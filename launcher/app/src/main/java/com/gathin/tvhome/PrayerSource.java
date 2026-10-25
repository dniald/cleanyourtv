package com.gathin.tvhome;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The Solat app's prayer day and quotes, read from its local ContentProvider
 * ({@code com.gathin.tv.solat.provider}). Home stays offline: the Solat app owns the network and keeps this
 * table up to date, and this class only maps the two cursors.
 *
 * <p>{@link #load} is meant to run on a worker thread and never throws. A missing provider, a missing column
 * or a malformed value leaves the field at its neutral value; an unreadable or empty today row returns
 * {@code null}, so Home can keep the strip hidden. Quote rows are optional and an empty table yields an empty
 * list, never {@code null}.
 */
final class PrayerSource {

    static final String AUTHORITY = "com.gathin.tv.solat.provider";
    private static final Uri TODAY = Uri.parse("content://" + AUTHORITY + "/today");
    private static final Uri QUOTES = Uri.parse("content://" + AUTHORITY + "/quotes");

    /** The weather columns' "no reading" value (the provider's contract). */
    private static final int WEATHER_ABSENT = -999;
    private static final long DAY_SECONDS = 86400L;

    /** Index of each prayer on the timeline, in the order the widget shows them. */
    private static final int[] PRAYERS = {1, 4, 5, 6, 7};

    private PrayerSource() {}

    /** One hadith or Quran line from the quotes table. */
    static final class Quote {
        final String text;
        final String source;

        Quote(String text, String source) {
            this.text = text;
            this.source = source;
        }
    }

    /** One row of the today table, plus the quotes shown under the timeline. */
    static final class Data {
        final String zone;
        final String zoneLabel;
        final String hijri;
        final String timezone;
        /**
         * Eight epoch seconds in the provider's order: imsak, fajr, syuruk, dhuha, dhuhr, asr, maghrib,
         * isha. Missing columns are 0.
         */
        final long[] times;
        final long tomorrowFajr;
        final int weatherNow;
        final int weatherHigh;
        final int weatherLow;
        final List<Quote> quotes;

        Data(String zone, String zoneLabel, String hijri, String timezone, long[] times,
             long tomorrowFajr, int weatherNow, int weatherHigh, int weatherLow,
             List<Quote> quotes) {
            this.zone = zone == null ? "" : zone;
            this.zoneLabel = zoneLabel == null ? "" : zoneLabel;
            this.hijri = hijri == null ? "" : hijri;
            this.timezone = timezone == null ? "" : timezone;
            this.times = times == null ? new long[8] : times;
            this.tomorrowFajr = tomorrowFajr;
            this.weatherNow = weatherNow;
            this.weatherHigh = weatherHigh;
            this.weatherLow = weatherLow;
            this.quotes = quotes == null ? Collections.emptyList() : Collections.unmodifiableList(quotes);
        }

        /**
         * The prayer being counted down to, as a timeline column 0 to 4 (Fajr, Dhuhr, Asr, Maghrib,
         * Isha), or -1 when the row has no usable times. After Isha this is 0, because the next prayer is
         * tomorrow's Fajr; {@link #nextAt} then returns {@code tomorrowFajr}.
         */
        int nextIndex(long now) {
            if (times == null || times.length < 8) return -1;
            if (times[PRAYERS[0]] > now) return 0;
            if (times[PRAYERS[1]] > now) return 1;
            if (times[PRAYERS[2]] > now) return 2;
            if (times[PRAYERS[3]] > now) return 3;
            if (times[PRAYERS[4]] > now) return 4;
            return times[PRAYERS[4]] > 0 ? 0 : -1;
        }

        /** Epoch seconds of the next prayer, or 0 when there is no usable row. */
        long nextAt(long now) {
            int next = nextIndex(now);
            if (next < 0) return 0;
            if (next == 0 && times[PRAYERS[0]] <= now) {
                // Before Fajr this is today's; after Isha the day's Fajr has passed and the provider's
                // tomorrow_fajr is the target, with Isha + a day as the fallback.
                if (tomorrowFajr > 0) return tomorrowFajr;
                long isha = times[PRAYERS[4]];
                return isha > 0 ? isha + DAY_SECONDS : 0;
            }
            return times[PRAYERS[next]];
        }

    }

    /**
     * Reads today's row and the quotes from the Solat provider. Returns {@code null} on any provider or
     * cursor problem, including an empty today table, so a TV without the Solat app simply shows no strip.
     * Cursors are always closed.
     */
    static Data load(Context context) {
        if (context == null) return null;
        Cursor today = null;
        Cursor quotes = null;
        try {
            ContentResolver resolver = context.getContentResolver();
            today = resolver.query(TODAY, null, null, null, null);
            if (today == null || !today.moveToFirst()) return null;

            long[] times = new long[8];
            times[0] = number(today, "imsak");
            times[1] = number(today, "fajr");
            times[2] = number(today, "syuruk");
            times[3] = number(today, "dhuha");
            times[4] = number(today, "dhuhr");
            times[5] = number(today, "asr");
            times[6] = number(today, "maghrib");
            times[7] = number(today, "isha");
            long tomorrowFajr = number(today, "tomorrow_fajr");
            int weatherNow = (int) number(today, "weather_now", WEATHER_ABSENT);
            int weatherHigh = (int) number(today, "weather_high", WEATHER_ABSENT);
            int weatherLow = (int) number(today, "weather_low", WEATHER_ABSENT);

            List<Quote> list = new ArrayList<>();
            quotes = resolver.query(QUOTES, null, null, null, null);
            if (quotes != null) {
                while (quotes.moveToNext()) {
                    list.add(new Quote(text(quotes, "text"), text(quotes, "source")));
                }
            }
            return new Data(text(today, "zone"), text(today, "zone_label"),
                    text(today, "hijri"), text(today, "timezone"), times, tomorrowFajr,
                    weatherNow, weatherHigh, weatherLow, list);
        } catch (Exception e) {
            // A missing/disabled provider, an unreadable row or a revoked permission is not an error on
            // Home: there is simply nothing to draw.
            return null;
        } finally {
            close(quotes);
            close(today);
        }
    }

    private static void close(Cursor cursor) {
        if (cursor == null) return;
        try {
            cursor.close();
        } catch (Exception ignored) {
            // A provider that fails on close must not turn into a crash on Home.
        }
    }

    /** A text column by name; absent or null is empty. */
    private static String text(Cursor cursor, String column) {
        int index = cursor.getColumnIndex(column);
        return index < 0 || cursor.isNull(index) ? "" : cursor.getString(index);
    }

    /** A numeric column by name; absent or null is 0. */
    private static long number(Cursor cursor, String column) {
        return number(cursor, column, 0L);
    }

    /** A numeric column by name, or {@code fallback} when it is absent or null. */
    private static long number(Cursor cursor, String column, long fallback) {
        int index = cursor.getColumnIndex(column);
        return index < 0 || cursor.isNull(index) ? fallback : cursor.getLong(index);
    }
}
