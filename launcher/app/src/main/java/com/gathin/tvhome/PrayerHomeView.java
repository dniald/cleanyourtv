package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.text.format.DateFormat;
import android.util.AttributeSet;
import android.view.View;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * The WidgeTV-style prayer timeline: five equal columns (Fajr, Dhuhr, Asr, Maghrib, Isha) over a thin
 * track, with a gold marker that drifts between the prayers and a dim gold night run. The next column is
 * gold and reads "ASR · IN 3H 44M"; past prayers are mist and future ones plaster, in the Solat app's
 * Afnon palette.
 *
 * <p>The view only draws: it has no timers, handlers or animation, takes no focus and no keys. Home calls
 * {@link #setData} when a worker has read {@link PrayerSource}, and {@link #refresh} about once a minute;
 * {@link #onDraw} reuses its paints, rectangle and string builder so a redraw allocates nothing.
 */
public final class PrayerHomeView extends View {

    /** The five prayers on the timeline, in order. */
    private static final String[] NAMES = {"FAJR", "DHUHR", "ASR", "MAGHRIB", "ISHA"};
    /** Where each one sits in {@link PrayerSource.Data#times}. */
    private static final int[] PRAYER_ROWS = {1, 4, 5, 6, 7};

    // Afnon's Mihrab tokens, as in the Solat app.
    private static final int GOLD = 0xFFD8B46A;
    private static final int GOLD_BRIGHT = 0xFFF0D79C;
    private static final int PLASTER = 0xFFEFE7D6;
    private static final int MIST = 0xFF9FB0CC;
    private static final int TRACK = 0x559FB0CC;

    private static final int GLASS_TOP = 0x2A0B1626;
    private static final int GLASS_BOTTOM = 0x3D091528;

    private static final String NO_TIME = "--:--";
    private static final long DAY_SECONDS = 86400L;

    private final float density;
    private final float scaledDensity;
    private final Typeface light;
    private final Typeface medium;
    private final SimpleDateFormat clockFormat;

    private final Paint glassFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint nightPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint goldPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint goldDimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint haloPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint timePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint namePaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final RectF slab = new RectF();
    private final StringBuilder line = new StringBuilder(24);
    private final Date scratchDate = new Date();

    private PrayerSource.Data data;

    public PrayerHomeView(Context context, AttributeSet attrs) {
        super(context, attrs);
        density = context.getResources().getDisplayMetrics().density;
        scaledDensity = context.getResources().getDisplayMetrics().scaledDensity;
        light = UiFont.get(context, R.string.font_light);
        medium = UiFont.get(context, R.string.font_medium);

        // The Solat app pins its clock to Kuala Lumpur; matching it keeps Home and the app in step.
        boolean hour24 = DateFormat.is24HourFormat(context);
        clockFormat = new SimpleDateFormat(hour24 ? "HH:mm" : "h:mm", Locale.ENGLISH);
        clockFormat.setTimeZone(TimeZone.getTimeZone("Asia/Kuala_Lumpur"));
        glassFill.setStyle(Paint.Style.FILL);
        glassFill.setColor(GLASS_TOP);

        trackPaint.setStyle(Paint.Style.STROKE);
        trackPaint.setStrokeWidth(dp(2f));
        trackPaint.setStrokeCap(Paint.Cap.ROUND);
        trackPaint.setColor(TRACK);

        nightPaint.setStyle(Paint.Style.STROKE);
        nightPaint.setStrokeWidth(dp(1.5f));
        nightPaint.setStrokeCap(Paint.Cap.ROUND);
        nightPaint.setColor(TRACK);
        nightPaint.setAlpha(0x80);   // fainter than the live track

        goldPaint.setStyle(Paint.Style.STROKE);
        goldPaint.setStrokeWidth(dp(3f));
        goldPaint.setStrokeCap(Paint.Cap.ROUND);
        goldPaint.setColor(GOLD);

        goldDimPaint.setStyle(Paint.Style.STROKE);
        goldDimPaint.setStrokeWidth(dp(3f));
        goldDimPaint.setStrokeCap(Paint.Cap.ROUND);
        goldDimPaint.setColor(GOLD);
        goldDimPaint.setAlpha(0x66);

        haloPaint.setStyle(Paint.Style.FILL);
        haloPaint.setColor(GOLD);
        haloPaint.setAlpha(0x33);
        dotPaint.setStyle(Paint.Style.FILL);
        dotPaint.setColor(GOLD_BRIGHT);

        timePaint.setTextAlign(Paint.Align.CENTER);
        namePaint.setTextAlign(Paint.Align.CENTER);
        namePaint.setLetterSpacing(0.12f);

        setFocusable(false);
        setFocusableInTouchMode(false);
        setClickable(false);
        setLongClickable(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        setWillNotDraw(false);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        glassFill.setShader(new LinearGradient(0, 0, 0, h, GLASS_TOP, GLASS_BOTTOM,
                Shader.TileMode.CLAMP));
    }

    /** Shows a prayer day, or hides the drawing when {@code data} is null. */
    void setData(PrayerSource.Data data) {
        this.data = data;
        invalidate();
    }

    /** Redraws for the current minute; the countdown and the marker are computed in onDraw. */
    void refresh() {
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int width = getWidth();
        int height = getHeight();
        if (width <= 0 || height <= 0 || data == null) return;
        long[] times = data.times;
        if (times == null || times.length < 8) return;

        float w = width;
        float y = dp(14f);
        float column = w / 5f;
        float x0 = column * 0.5f;
        float x1 = column * 4.5f;

        // A soft dark-glass band, not a card: no rim, so the new blue/gold wallpaper stays visible.
        float inset = dp(2f);
        slab.set(inset, inset, w - inset, height - inset);
        canvas.drawRoundRect(slab, dp(18f), dp(18f), glassFill);

        // Night stubs outside Fajr and Isha, then the day's track.
        canvas.drawLine(0f, y, x0, y, nightPaint);
        canvas.drawLine(x1, y, w, y, nightPaint);
        canvas.drawLine(x0, y, x1, y, trackPaint);

        // Small ticks anchor each prayer to the track without another row of decoration.
        for (int i = 0; i < PRAYER_ROWS.length; i++) {
            float cx = x0 + stepFor(x0, x1) * i;
            canvas.drawLine(cx, y - dp(3f), cx, y + dp(3f), nightPaint);
        }

        long now = System.currentTimeMillis() / 1000L;
        int next = data.nextIndex(now);
        boolean hasNext = next >= 0;
        boolean beforeFajr = times[PRAYER_ROWS[0]] > 0 && now < times[PRAYER_ROWS[0]];
        boolean afterIsha = times[PRAYER_ROWS[4]] > 0 && now >= times[PRAYER_ROWS[4]];
        boolean night = beforeFajr || afterIsha;

        float marker = hasNext ? markerX(now, x0, x1, times, beforeFajr, afterIsha) : x0;
        if (hasNext) {
            if (night) {
                // Keep the whole day's gold visible, dimmed, while the marker runs outside it.
                canvas.drawLine(x0, y, x1, y, goldDimPaint);
            } else {
                canvas.drawLine(x0, y, Math.max(x0, Math.min(marker, x1)), y, goldPaint);
            }
            canvas.drawCircle(marker, y, dp(10f), haloPaint);
            canvas.drawCircle(marker, y, dp(5f), dotPaint);
        }

        // Five columns: time over the name, or the next prayer's "NAME · IN 3H 44M" countdown.
        float step = (x1 - x0) / 4f;
        for (int i = 0; i < PRAYER_ROWS.length; i++) {
            float cx = x0 + step * i;
            boolean isNext = hasNext && i == next;
            boolean past = !isNext && (afterIsha ? i != 0 : hasNext && i < next);
            int colour = isNext ? GOLD : (past ? MIST : PLASTER);

            timePaint.setColor(colour);
            timePaint.setTypeface(isNext ? medium : light);
            timePaint.setTextSize((isNext ? 27f : 24f) * scaledDensity);
            long at = times[PRAYER_ROWS[i]];
            String label = NO_TIME;
            if (at > 0) {
                scratchDate.setTime(at * 1000L);
                label = clockFormat.format(scratchDate);
            }
            drawCentred(canvas, label, cx, dp(50f), timePaint);

            namePaint.setColor(colour);
            namePaint.setTypeface(isNext ? medium : light);
            namePaint.setTextSize(11f * scaledDensity);
            if (isNext) {
                line.setLength(0);
                line.append(NAMES[i]).append(" · IN ");
                appendRemaining(line, data.nextAt(now) - now);
                canvas.drawText(line, 0, line.length(), cx, baseline(namePaint, dp(68f)), namePaint);
            } else {
                canvas.drawText(NAMES[i], cx, baseline(namePaint, dp(68f)), namePaint);
            }
        }
    }

    /**
     * Where the marker sits. Between two prayers it interpolates linearly; before Fajr and after Isha it
     * runs from Isha to Fajr (right edge to the Fajr column), so it drifts left over the night. Before
     * Fajr it uses last night's Isha, because the today row's Isha is still ahead.
     */
    private float markerX(long now, float x0, float x1, long[] times, boolean beforeFajr, boolean afterIsha) {
        if (afterIsha) {
            long isha = times[PRAYER_ROWS[4]];
            long dawn = data.tomorrowFajr > 0 ? data.tomorrowFajr : isha + DAY_SECONDS;
            return nightX(now, isha, dawn, x0, x1);
        }
        if (beforeFajr) {
            long isha = times[PRAYER_ROWS[4]];
            if (isha <= DAY_SECONDS) return x0;
            return nightX(now, isha - DAY_SECONDS, times[PRAYER_ROWS[0]], x0, x1);
        }
        int next = data.nextIndex(now);
        if (next <= 0) return x0;
        long previous = times[PRAYER_ROWS[next - 1]];
        long target = times[PRAYER_ROWS[next]];
        if (previous <= 0 || target <= previous) {
            return x0 + (x1 - x0) / 4f * next;
        }
        float t = (now - previous) / (float) (target - previous);
        if (t < 0f) t = 0f;
        if (t > 1f) t = 1f;
        float from = x0 + (x1 - x0) / 4f * (next - 1);
        float to = x0 + (x1 - x0) / 4f * next;
        return from + (to - from) * t;
    }

    /** The night run: t 0 at Isha sits at the right edge, t 1 at Fajr reaches the Fajr column. */
    private float nightX(long now, long start, long end, float x0, float x1) {
        if (end <= start) return x1;
        float t = (now - start) / (float) (end - start);
        if (t < 0f) t = 0f;
        if (t > 1f) t = 1f;
        return x1 - (x1 - x0) * t;
    }

    private float stepFor(float x0, float x1) {
        return (x1 - x0) / 4f;
    }

    /** Text with its typographic centre at {@code cy}, without allocating font metrics. */
    private void drawCentred(Canvas canvas, String text, float cx, float cy, Paint paint) {
        canvas.drawText(text, cx, baseline(paint, cy), paint);
    }

    private float baseline(Paint paint, float centreY) {
        return centreY - (paint.ascent() + paint.descent()) / 2f;
    }

    /** "3h 44m" above an hour, "44m" under it, as the gold line's uppercase suffix. */
    private void appendRemaining(StringBuilder out, long left) {
        if (left < 0) left = 0;
        long hours = left / 3600L;
        long minutes = (left % 3600L) / 60L;
        if (hours > 0) {
            out.append(hours).append('H').append(' ').append(minutes).append('M');
        } else {
            out.append(minutes).append('M');
        }
    }

    private float dp(float value) {
        return value * density;
    }
}
