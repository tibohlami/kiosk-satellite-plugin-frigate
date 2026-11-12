// SPDX-License-Identifier: Apache-2.0
package me.jxl.kiosk.plugins.frigate.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.widget.ImageView;
import android.widget.TextView;
import me.jxl.kiosk.plugins.KsTheme;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Modern Material Design vector icons provider.
 * Supplies standard Material Design 24x24 vector paths with high-DPI scaling,
 * anti-aliased drawing, and seamless fallback to KsTheme native icons.
 */
public final class MaterialIcons {

    public static final String HOME = "mdi:home";
    public static final String PERSON = "mdi:account";
    public static final String CAR = "mdi:car";
    public static final String PETS = "mdi:paw";
    public static final String PACKAGE = "mdi:package-variant-closed";
    public static final String BICYCLE = "mdi:bicycle";
    public static final String REFRESH = "mdi:refresh";
    public static final String CLOSE = "mdi:close";
    public static final String PLAY = "mdi:play";
    public static final String PAUSE = "mdi:pause";
    public static final String REWIND_10 = "mdi:rewind-10";
    public static final String FORWARD_10 = "mdi:fast-forward-10";
    public static final String REPEAT = "mdi:repeat";
    public static final String CAMERA = "mdi:cctv";
    public static final String MOVIE = "mdi:movie-open";
    public static final String LIVE = "mdi:record-circle";
    public static final String CALENDAR = "mdi:calendar-clock";
    public static final String VOLUME_UP = "mdi:volume-high";
    public static final String VOLUME_OFF = "mdi:volume-off";
    public static final String ZOOM_IN = "mdi:magnify-plus-outline";
    public static final String ZOOM_OUT = "mdi:magnify-minus-outline";
    public static final String BOUNDING_BOX = "mdi:vector-square";

    private static final Map<String, String> SVG_PATHS = new HashMap<>();
    private static final Map<String, Path> PARSED_PATHS = new HashMap<>();

    static {
        // Standard Material Design Icons (24x24 coordinate grid)
        SVG_PATHS.put(HOME, "M10 20v-6h4v6h5v-8h3L12 3 2 12h3v8z");
        SVG_PATHS.put(PERSON, "M12 12c2.21 0 4-1.79 4-4s-1.79-4-4-4-4 1.79-4 4 1.79 4 4 4zm0 2c-2.67 0-8 1.34-8 4v2h16v-2c0-2.66-5.33-4-8-4z");
        SVG_PATHS.put(CAR, "M18.92 6.01C18.72 5.42 18.16 5 17.5 5h-11c-.66 0-1.21.42-1.42 1.01L3 12v8c0 .55.45 1 1 1h1c.55 0 1-.45 1-1v-1h12v1c0 .55.45 1 1 1h1c.55 0 1-.45 1-1v-8l-2.08-5.99zM6.85 7h10.29l1.04 3H5.81l1.04-3zM19 17H5v-4.66l.12-.34h13.77l.11.34V17z");
        SVG_PATHS.put(PETS, "M4.5 9.5C5.88 9.5 7 8.38 7 7s-1.12-2.5-2.5-2.5S2 5.62 2 7s1.12 2.5 2.5 2.5zm15 0c1.38 0 2.5-1.12 2.5-2.5S20.88 4.5 19.5 4.5 17 5.62 17 7s1.12 2.5 2.5 2.5zm-10.75-.75c1.24 0 2.25-1.01 2.25-2.25S9.99 4.25 8.75 4.25 6.5 5.26 6.5 6.5s1.01 2.25 2.25 2.25zm6.5 0c1.24 0 2.25-1.01 2.25-2.25S14.24 4.25 13 4.25s-2.25 1.01-2.25 2.25 1.01 2.25 2.25 2.25zM12 11.5c-3.1 0-6 2.05-6 5.5 0 2.5 2.05 4.5 4.5 4.5 1.25 0 2.1-.65 2.5-.65.4 0 1.25.65 2.5.65 2.45 0 4.5-2 4.5-4.5 0-3.45-2.9-5.5-6-5.5z");
        SVG_PATHS.put(PACKAGE, "M12 2L3 7v10l9 5 9-5V7l-9-5zm0 2.24L18.15 7 12 9.76 5.85 7 12 4.24zM5 8.76l6 2.67v8.03L5 16.8V8.76zm8 10.7V11.43l6-2.67v8.04l-6 2.66z");
        SVG_PATHS.put(BICYCLE, "M15.5 5.5c1.1 0 2-.9 2-2s-.9-2-2-2-2 .9-2 2 .9 2 2 2zM5 12c-2.8 0-5 2.2-5 5s2.2 5 5 5 5-2.2 5-5-2.2-5-5-5zm0 8.5c-1.9 0-3.5-1.6-3.5-3.5s1.6-3.5 3.5-3.5 3.5 1.6 3.5 3.5-1.6 3.5-3.5 3.5zm7-10l-1.9-3.2c-.4-.6-1-1-1.7-1H5v2h3.1l1.4 2.4L7 14.5c-.3.4-.4.8-.4 1.3 0 1.2 1 2.2 2.2 2.2h.2l3.4-3.4 2.6 2.6V20h2v-3.8l-3-3 1.9-3.8c.8 1.4 2.3 2.4 4.1 2.5v-2c-1.7-.1-3-1.3-3.6-2.9L15 5.9c-.3-.5-.8-.9-1.4-.9h-1.6zM19 12c-2.8 0-5 2.2-5 5s2.2 5 5 5 5-2.2 5-5-2.2-5-5-5zm0 8.5c-1.9 0-3.5-1.6-3.5-3.5s1.6-3.5 3.5-3.5 3.5 1.6 3.5 3.5-1.6 3.5-3.5 3.5z");
        SVG_PATHS.put(REFRESH, "M17.65 6.35C16.2 4.9 14.21 4 12 4c-4.42 0-7.99 3.58-7.99 8s3.57 8 7.99 8c3.73 0 6.84-2.55 7.73-6h-2.08c-.82 2.33-3.04 4-5.65 4-3.31 0-6-2.69-6-6s2.69-6 6-6c1.66 0 3.14.69 4.22 1.78L13 11h7V4l-2.35 2.35z");
        SVG_PATHS.put(CLOSE, "M19 6.41L17.59 5 12 10.59 6.41 5 5 6.41 10.59 12 5 17.59 6.41 19 12 13.41 17.59 19 19 17.59 13.41 12z");
        SVG_PATHS.put(PLAY, "M8 5v14l11-7z");
        SVG_PATHS.put(PAUSE, "M6 19h4V5H6v14zm8-14v14h4V5h-4z");
        SVG_PATHS.put(REWIND_10, "M12 5V1L7 6l5 5V7c3.31 0 6 2.69 6 6s-2.69 6-6 6-6-2.69-6-6H4c0 4.42 3.58 8 8 8s8-3.58 8-8-3.58-8-8-8zm-1.1 11h-.85v-3.26l-1.01.31v-.67l1.78-.59h.08V16zm4.29-1.73c0 .35-.07.65-.2.89-.13.25-.32.43-.56.56-.24.13-.53.19-.86.19s-.62-.06-.86-.19c-.24-.13-.43-.32-.56-.56-.13-.24-.2-.54-.2-.89v-1.12c0-.35.07-.65.2-.89.13-.25.32-.43.56-.56.24-.13.53-.19.86-.19s.62.06.86.19c.24.13.43.32.56.56.13.24.2.54.2.89v1.12zm-.86-1.34c0-.28-.05-.48-.14-.62-.09-.13-.23-.2-.42-.2s-.33.07-.42.2c-.09.13-.14.34-.14.62v1.55c0 .28.05.49.14.62.09.14.23.2.42.2s.33-.07.42-.2c.09-.14.14-.34.14-.62v-1.55z");
        SVG_PATHS.put(FORWARD_10, "M12 5V1l5 5-5 5V7c-3.31 0-6 2.69-6 6s2.69 6 6 6 6-2.69 6-6h2c0 4.42-3.58 8-8 8s-8-3.58-8-8 3.58-8 8-8zm-1.1 11h-.85v-3.26l-1.01.31v-.67l1.78-.59h.08V16zm4.29-1.73c0 .35-.07.65-.2.89-.13.25-.32.43-.56.56-.24.13-.53.19-.86.19s-.62-.06-.86-.19c-.24-.13-.43-.32-.56-.56-.13-.24-.2-.54-.2-.89v-1.12c0-.35.07-.65.2-.89.13-.25.32-.43.56-.56.24-.13.53-.19.86-.19s.62.06.86.19c.24.13.43.32.56.56.13.24.2.54.2.89v1.12zm-.86-1.34c0-.28-.05-.48-.14-.62-.09-.13-.23-.2-.42-.2s-.33.07-.42.2c-.09.13-.14.34-.14.62v1.55c0 .28.05.49.14.62.09.14.23.2.42.2s.33-.07.42-.2c.09-.14.14-.34.14-.62v-1.55z");
        SVG_PATHS.put(REPEAT, "M7 7h10v3l4-4-4-4v3H5v6h2V7zm10 10H7v-3l-4 4 4 4v-3h12v-6h-2v4z");
        SVG_PATHS.put(CAMERA, "M17 10.5V7c0-.55-.45-1-1-1H4c-.55 0-1 .45-1 1v10c0 .55.45 1 1 1h12c.55 0 1-.45 1-1v-3.5l4 4v-11l-4 4z");
        SVG_PATHS.put(MOVIE, "M4 6H2v14c0 1.1.9 2 2 2h14v-2H4V6zm16-4H8c-1.1 0-2 .9-2 2v12c0 1.1.9 2 2 2h12c1.1 0 2-.9 2-2V4c0-1.1-.9-2-2-2zm0 14H8V4h12v12z");
        SVG_PATHS.put(LIVE, "M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm0 14c-2.21 0-4-1.79-4-4s1.79-4 4-4 4 1.79 4 4-1.79 4-4 4z");
        SVG_PATHS.put(CALENDAR, "M19 3h-1V1h-2v2H8V1H6v2H5c-1.11 0-1.99.9-1.99 2L3 19c0 1.1.89 2 2 2h14c1.1 0 2-.9 2-2V5c0-1.1-.9-2-2-2zm0 16H5V8h14v11zM7 10h5v5H7z");
        SVG_PATHS.put(VOLUME_UP, "M3 9v6h4l5 5V4L7 9H3zm13.5 3c0-1.77-1.02-3.29-2.5-4.03v8.05c1.48-.73 2.5-2.25 2.5-4.02zM14 3.23v2.06c2.89.86 5 3.54 5 6.71s-2.11 5.85-5 6.71v2.06c4.01-.91 7-4.49 7-8.77s-2.99-7.86-7-8.77z");
        SVG_PATHS.put(VOLUME_OFF, "M16.5 12c0-1.77-1.02-3.29-2.5-4.03v2.21l2.45 2.45c.03-.2.05-.41.05-.63zm2.5 0c0 .94-.2 1.82-.54 2.64l1.51 1.51C20.63 14.91 21 13.5 21 12c0-4.28-2.99-7.86-7-8.77v2.06c2.89.86 5 3.54 5 6.71zM4.27 3L3 4.27 7.73 9H3v6h4l5 5v-6.73l4.25 4.25c-.67.52-1.42.93-2.25 1.18v2.06c1.38-.31 2.63-.95 3.69-1.81L19.73 21 21 19.73l-9-9L4.27 3zM12 4L9.91 6.09 12 8.18V4z");
        // Pure cubic-bezier Material magnifying glass with + / - (no SVG arc command needed)
        SVG_PATHS.put(ZOOM_IN,
                "M9.5 3C13.09 3 16 5.91 16 9.5C16 13.09 13.09 16 9.5 16C5.91 16 3 13.09 3 9.5C3 5.91 5.91 3 9.5 3Z"
                + "M9.5 5C7.01 5 5 7.01 5 9.5C5 11.99 7.01 14 9.5 14C11.99 14 14 11.99 14 9.5C14 7.01 11.99 5 9.5 5Z"
                + "M14.3 12.9L20.6 19.2L19.2 20.6L12.9 14.3Z"
                + "M8.8 6.8H10.2V8.8H12.2V10.2H10.2V12.2H8.8V10.2H6.8V8.8H8.8Z");
        SVG_PATHS.put(ZOOM_OUT,
                "M9.5 3C13.09 3 16 5.91 16 9.5C16 13.09 13.09 16 9.5 16C5.91 16 3 13.09 3 9.5C3 5.91 5.91 3 9.5 3Z"
                + "M9.5 5C7.01 5 5 7.01 5 9.5C5 11.99 7.01 14 9.5 14C11.99 14 14 11.99 14 9.5C14 7.01 11.99 5 9.5 5Z"
                + "M14.3 12.9L20.6 19.2L19.2 20.6L12.9 14.3Z"
                + "M6.8 8.8H12.2V10.2H6.8Z");
        SVG_PATHS.put(BOUNDING_BOX,
                "M3 3h6v2H5v4H3V3z M15 3h6v6h-2V5h-4V3z M3 15h2v4h4v2H3v-6z M19 15h2v6h-6v-2h4v-4z "
                + "M8 8h8v2H8V8z M8 14h8v2H8v-2z M8 10h2v4H8v-4z M14 10h2v4h-2v-4z");
    }

    private MaterialIcons() {}

    public static VectorDrawable getDrawable(String name, int color, int sizePx) {
        return getDrawable(name, color, sizePx, null);
    }

    /**
     * Creates a vector Drawable for the specified Material icon name.
     */
    public static VectorDrawable getDrawable(String name, int color, int sizePx, KsTheme theme) {
        Path path = null;
        if (theme != null) {
            path = theme.iconPath(name);
        }
        if (path == null) {
            path = getPath(name);
        }
        if (path == null) {
            return null;
        }
        return new VectorDrawable(path, color, sizePx);
    }

    public static ImageView createIconView(Context context, String name, int color, int sizePx, KsTheme theme) {
        ImageView iv = new ImageView(context);
        iv.setImageDrawable(getDrawable(name, color, sizePx, theme));
        iv.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        return iv;
    }

    public static void setIconOnTextView(TextView view, String name, int color, int sizePx, int paddingPx, KsTheme theme) {
        VectorDrawable d = getDrawable(name, color, sizePx, theme);
        if (d != null) {
            d.setBounds(0, 0, sizePx, sizePx);
            view.setCompoundDrawables(d, null, null, null);
            view.setCompoundDrawablePadding(paddingPx);
        }
    }

    public static synchronized Path getPath(String name) {
        if (PARSED_PATHS.containsKey(name)) {
            return PARSED_PATHS.get(name);
        }
        String d = SVG_PATHS.get(name);
        if (d == null) return null;
        Path p = parseSvgPath(d);
        PARSED_PATHS.put(name, p);
        return p;
    }

    private static Path parseSvgPath(String d) {
        Path path = new Path();
        if (d == null || d.trim().isEmpty()) return path;

        int i = 0;
        int len = d.length();
        char cmd = 0;
        float curX = 0, curY = 0;
        float startX = 0, startY = 0;
        float lastControlX = 0, lastControlY = 0;

        while (i < len) {
            char c = d.charAt(i);
            if (Character.isWhitespace(c) || c == ',') {
                i++;
                continue;
            }
            if (Character.isLetter(c)) {
                cmd = c;
                i++;
            }

            List<Float> args = new ArrayList<>();
            while (i < len) {
                while (i < len && (Character.isWhitespace(d.charAt(i)) || d.charAt(i) == ',')) i++;
                if (i >= len) break;
                char nextC = d.charAt(i);
                if (Character.isLetter(nextC) && nextC != 'e' && nextC != 'E') break;
                int numStart = i;
                if (d.charAt(i) == '-' || d.charAt(i) == '+') i++;
                boolean hasDot = false;
                while (i < len) {
                    char ch = d.charAt(i);
                    if (Character.isDigit(ch)) {
                        i++;
                    } else if (ch == '.' && !hasDot) {
                        hasDot = true;
                        i++;
                    } else if ((ch == 'e' || ch == 'E') && i < len - 1) {
                        i++;
                        if (i < len && (d.charAt(i) == '-' || d.charAt(i) == '+')) i++;
                    } else {
                        break;
                    }
                }
                if (i > numStart) {
                    try {
                        args.add(Float.parseFloat(d.substring(numStart, i)));
                    } catch (Exception ignored) {}
                }
            }

            int argIdx = 0;
            switch (cmd) {
                case 'M':
                case 'm': {
                    boolean rel = (cmd == 'm');
                    while (argIdx + 1 < args.size()) {
                        float x = args.get(argIdx++);
                        float y = args.get(argIdx++);
                        if (rel) { x += curX; y += curY; }
                        if (argIdx == 2) {
                            path.moveTo(x, y);
                            startX = x; startY = y;
                        } else {
                            path.lineTo(x, y);
                        }
                        curX = x; curY = y;
                        lastControlX = curX; lastControlY = curY;
                    }
                    break;
                }
                case 'L':
                case 'l': {
                    boolean rel = (cmd == 'l');
                    while (argIdx + 1 < args.size()) {
                        float x = args.get(argIdx++);
                        float y = args.get(argIdx++);
                        if (rel) { x += curX; y += curY; }
                        path.lineTo(x, y);
                        curX = x; curY = y;
                        lastControlX = curX; lastControlY = curY;
                    }
                    break;
                }
                case 'H':
                case 'h': {
                    boolean rel = (cmd == 'h');
                    while (argIdx < args.size()) {
                        float x = args.get(argIdx++);
                        if (rel) x += curX;
                        path.lineTo(x, curY);
                        curX = x;
                        lastControlX = curX; lastControlY = curY;
                    }
                    break;
                }
                case 'V':
                case 'v': {
                    boolean rel = (cmd == 'v');
                    while (argIdx < args.size()) {
                        float y = args.get(argIdx++);
                        if (rel) y += curY;
                        path.lineTo(curX, y);
                        curY = y;
                        lastControlX = curX; lastControlY = curY;
                    }
                    break;
                }
                case 'C':
                case 'c': {
                    boolean rel = (cmd == 'c');
                    while (argIdx + 5 < args.size()) {
                        float x1 = args.get(argIdx++);
                        float y1 = args.get(argIdx++);
                        float x2 = args.get(argIdx++);
                        float y2 = args.get(argIdx++);
                        float x = args.get(argIdx++);
                        float y = args.get(argIdx++);
                        if (rel) {
                            x1 += curX; y1 += curY;
                            x2 += curX; y2 += curY;
                            x += curX; y += curY;
                        }
                        path.cubicTo(x1, y1, x2, y2, x, y);
                        lastControlX = x2; lastControlY = y2;
                        curX = x; curY = y;
                    }
                    break;
                }
                case 'S':
                case 's': {
                    boolean rel = (cmd == 's');
                    while (argIdx + 3 < args.size()) {
                        float x2 = args.get(argIdx++);
                        float y2 = args.get(argIdx++);
                        float x = args.get(argIdx++);
                        float y = args.get(argIdx++);
                        if (rel) {
                            x2 += curX; y2 += curY;
                            x += curX; y += curY;
                        }
                        float x1 = 2 * curX - lastControlX;
                        float y1 = 2 * curY - lastControlY;
                        path.cubicTo(x1, y1, x2, y2, x, y);
                        lastControlX = x2; lastControlY = y2;
                        curX = x; curY = y;
                    }
                    break;
                }
                case 'Z':
                case 'z': {
                    path.close();
                    curX = startX; curY = startY;
                    lastControlX = curX; lastControlY = curY;
                    break;
                }
            }
        }
        return path;
    }

    public static class VectorDrawable extends Drawable {
        private final Path originalPath;
        private final Path scaledPath = new Path();
        private final Matrix matrix = new Matrix();
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private int color;
        private final int size;

        public VectorDrawable(Path path, int color, int size) {
            this.originalPath = new Path(path);
            this.color = color;
            this.size = size;
            paint.setColor(color);
            paint.setStyle(Paint.Style.FILL);
            setBounds(0, 0, size, size);
        }

        public void setColor(int color) {
            this.color = color;
            paint.setColor(color);
            invalidateSelf();
        }

        public int getColor() {
            return color;
        }

        @Override
        public void draw(Canvas canvas) {
            Rect bounds = getBounds();
            if (bounds.isEmpty()) return;
            matrix.reset();
            matrix.setScale(bounds.width() / 24f, bounds.height() / 24f);
            matrix.postTranslate(bounds.left, bounds.top);
            scaledPath.reset();
            originalPath.transform(matrix, scaledPath);
            canvas.drawPath(scaledPath, paint);
        }

        @Override
        public void setAlpha(int alpha) {
            paint.setAlpha(alpha);
            invalidateSelf();
        }

        @Override
        public void setColorFilter(ColorFilter colorFilter) {
            paint.setColorFilter(colorFilter);
            invalidateSelf();
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }

        @Override
        public int getIntrinsicWidth() {
            return size;
        }

        @Override
        public int getIntrinsicHeight() {
            return size;
        }
    }
}
