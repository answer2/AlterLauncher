package com.answer.launcher.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.text.TextPaint;
import android.widget.ImageView;

import androidx.annotation.Nullable;

import com.answer.launcher.R;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;

/**
 * Puts an account's face in an {@link ImageView}, and always puts something there.
 *
 * <p>The launcher has had an avatar slot on the home card since the first commit and has never
 * filled it — the XBL token chain carries a gamertag but no picture, so the slot showed the same
 * generic circle for every player. {@code SessionValidator} now hands over the Xbox
 * {@code DisplayPicRaw} URL; this is the other half of that: fetch it off the main thread, crop it
 * to the circle every slot in this app uses, and drop it in.
 *
 * <p>Two rules shape the implementation. The first is that the slot is never empty: until the
 * picture arrives — and forever, if it never does — the circle is drawn from the gamertag's first
 * character in the theme's own container colors, which is also exactly what an account with no
 * sign-in looks like once {@code gamertag} is null and the placeholder icon takes over. The second
 * is that a late answer must never overwrite a view that has moved on, so the URL is parked in the
 * view's tag and the bitmap is only applied while the tag still says the same thing. An
 * {@code ImageView} is recycled across list rows and a fragment can be rebuilt while a download is
 * in flight; without that check the previous account's face would end up on the next one's card.
 */
public final class AvatarLoader {

    /** Letters are drawn once at this size and scaled by the view, so one bitmap fits every slot. */
    private static final int LETTER_SIZE_PX = 192;
    /** Xbox serves 1080² originals; decoding one at full size to show it at 48dp is a memory bug. */
    private static final int MAX_DECODE_PX = 256;
    private static final int MAX_DOWNLOAD_BYTES = 4 * 1024 * 1024;

    private AvatarLoader() {
    }

    /**
     * Shows {@code source}'s picture in {@code view}, or a letter avatar built from
     * {@code gamertag}. {@code source} is a locally cached head PNG path (the normal case — the
     * launcher caches Persona's head image next to the account) or an http(s) URL. A null/blank
     * source, a failed load or a blank gamertag all degrade quietly: the letter, then the account
     * icon, and never an empty slot or a crash.
     */
    public static void load(@Nullable ImageView view, @Nullable String source,
                            @Nullable String gamertag) {
        if (view == null) {
            return;
        }
        String src = source == null ? "" : source.trim();
        if (src.isEmpty()) {
            // Clearing the tag is what abandons a load still running for the previous account.
            view.setTag(null);
            drawLetter(view, gamertag);
            return;
        }
        // Already showing this exact picture: do nothing. Without this guard every bridge-driven
        // repaint of the account card would redraw the letter and re-fetch the head, which reads
        // as the avatar flickering on a timer. It now loads once per fresh view (re-entry).
        if (src.equals(view.getTag())) {
            return;
        }
        drawLetter(view, gamertag);
        view.setTag(src);
        new Thread(() -> {
            Bitmap picture = src.startsWith("http") ? fetchUrl(src) : decodeFile(src);
            if (picture == null) {
                return; // the letter already painted stays; a missing avatar is not an error state
            }
            Bitmap rounded = toRounded(picture);
            picture.recycle();
            view.post(() -> {
                if (src.equals(view.getTag())) {
                    view.setImageBitmap(rounded);
                }
            });
        }, "avatar-load").start();
    }

    /** Corner radius of the avatar tile, as a fraction of its side — a soft rounded square. */
    private static final float CORNER_FRACTION = 0.22f;

    /** The theme's container colors, a bold first character: the identity without a picture. */
    private static void drawLetter(ImageView view, @Nullable String gamertag) {
        String name = gamertag == null ? "" : gamertag.trim();
        if (name.isEmpty()) {
            view.setImageResource(R.drawable.ic_account_circle);
            return;
        }
        Context ctx = view.getContext();
        int size = LETTER_SIZE_PX;
        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        Paint tile = new Paint(Paint.ANTI_ALIAS_FLAG);
        tile.setColor(ctx.getColor(R.color.md_theme_primaryContainer));
        float radius = size * CORNER_FRACTION;
        canvas.drawRoundRect(new RectF(0, 0, size, size), radius, radius, tile);

        int codePoint = name.codePointAt(0);
        String letter = new String(Character.toChars(codePoint)).toUpperCase(Locale.ROOT);
        TextPaint text = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        text.setColor(ctx.getColor(R.color.md_theme_onPrimaryContainer));
        text.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        text.setTextSize(size * 0.46f);
        Paint.FontMetrics metrics = text.getFontMetrics();
        float baseline = size / 2f - (metrics.ascent + metrics.descent) / 2f;
        canvas.drawText(letter, size / 2f, baseline, text);
        view.setImageBitmap(bitmap);
    }

    /** Decodes a locally cached head PNG, subsampled to a display size. Null if unreadable. */
    @Nullable
    private static Bitmap decodeFile(String path) {
        try {
            java.io.File f = new java.io.File(path);
            if (!f.isFile() || f.length() == 0) {
                return null;
            }
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(path, bounds);
            BitmapFactory.Options decode = new BitmapFactory.Options();
            decode.inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight);
            return BitmapFactory.decodeFile(path, decode);
        } catch (Throwable failed) {
            return null;
        }
    }

    /**
     * Downloads and decodes one picture. Blocking, and called from a worker thread only. The bytes
     * are buffered so the size can be measured before the bitmap is allocated — the two-pass
     * {@code inJustDecodeBounds} trick would otherwise need the response opened twice.
     */
    @Nullable
    private static Bitmap fetchUrl(String url) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(15000);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent", "Minecraft-Avatar/1.0");
            int code = conn.getResponseCode();
            if (code < 200 || code >= 400) {
                return null;
            }
            byte[] data = readAll(conn.getInputStream());
            if (data == null || data.length == 0) {
                return null;
            }
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, bounds);
            BitmapFactory.Options decode = new BitmapFactory.Options();
            decode.inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight);
            return BitmapFactory.decodeByteArray(data, 0, data.length, decode);
        } catch (Throwable failed) {
            return null;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    /** The power-of-two subsample that brings the longest edge under {@link #MAX_DECODE_PX}. */
    private static int sampleSize(int width, int height) {
        int sample = 1;
        while (Math.max(width, height) / sample > MAX_DECODE_PX) {
            sample *= 2;
        }
        return sample;
    }

    @Nullable
    private static byte[] readAll(InputStream in) throws java.io.IOException {
        try (InputStream stream = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream(64 * 1024);
            byte[] buffer = new byte[8192];
            int total = 0;
            int read;
            while ((read = stream.read(buffer)) > 0) {
                total += read;
                if (total > MAX_DOWNLOAD_BYTES) {
                    return null;
                }
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        }
    }

    /**
     * Crops to the centered square and clips to a rounded square. Persona heads arrive square, but
     * the crop lets a non-square answer keep working, and the rounded clip (soft corners, not a
     * circle) is what makes the tile look like it belongs in this app.
     */
    private static Bitmap toRounded(Bitmap source) {
        int side = Math.max(1, Math.min(source.getWidth(), source.getHeight()));
        int left = (source.getWidth() - side) / 2;
        int top = (source.getHeight() - side) / 2;
        int out = Math.min(side, LETTER_SIZE_PX);
        Bitmap rounded = Bitmap.createBitmap(out, out, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(rounded);
        float radius = out * CORNER_FRACTION;
        Paint clip = new Paint(Paint.ANTI_ALIAS_FLAG);
        canvas.drawRoundRect(new RectF(0, 0, out, out), radius, radius, clip);
        Paint draw = new Paint(Paint.ANTI_ALIAS_FLAG);
        draw.setFilterBitmap(true);
        draw.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC_IN));
        Rect from = new Rect(left, top, left + side, top + side);
        canvas.drawBitmap(source, from, new RectF(0, 0, out, out), draw);
        return rounded;
    }
}
