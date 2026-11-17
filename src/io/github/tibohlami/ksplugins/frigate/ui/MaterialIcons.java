// SPDX-License-Identifier: Apache-2.0
package io.github.tibohlami.ksplugins.frigate.ui;

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
    public static final String CAT = "mdi:cat";
    public static final String DOG = "mdi:dog";
    public static final String BIRD = "mdi:bird";
    public static final String MOTORCYCLE = "mdi:motorbike";
    public static final String BUS = "mdi:bus";
    public static final String TRUCK = "mdi:truck";
    public static final String BOAT = "mdi:sail-boat";
    public static final String HORSE = "mdi:horse";
    public static final String COW = "mdi:cow";
    public static final String RABBIT = "mdi:rabbit";
    public static final String BEAR = "mdi:teddy-bear";
    public static final String MOUSE = "mdi:rodent";
    public static final String FIRE = "mdi:fire";
    public static final String UMBRELLA = "mdi:umbrella";
    public static final String LICENSE_PLATE = "mdi:barcode-scan";
    public static final String BABY = "mdi:baby-face";
    public static final String STROLLER = "mdi:baby-carriage";
    public static final String TAG = "mdi:tag";
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
    public static final String PTZ = "mdi:crosshairs-gps";
    public static final String ARROW_UP = "mdi:chevron-up";
    public static final String ARROW_DOWN = "mdi:chevron-down";
    public static final String ARROW_LEFT = "mdi:chevron-left";
    public static final String ARROW_RIGHT = "mdi:chevron-right";

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
        SVG_PATHS.put(CAT,
                "M12 8 L10.67 8.09 C9.81 7.07 7.4 4.5 5 4.5 C5 4.5 3.03 7.46 4.96 11.41 C4.41 12.24 4.07 12.67 4 13.66 L2.07 13.95 L2.28 14.93 L4.04 14.67 L4.18 15.38 L2.61 16.32 L3.08 17.21 L4.53 16.32 C5.68 18.76 8.59 20 12 20 C15.41 20 18.32 18.76 19.47 16.32 L20.92 17.21 L21.39 16.32 L19.82 15.38 L19.96 14.67 L21.72 14.93 L21.93 13.95 L20 13.66 C19.93 12.67 19.59 12.24 19.04 11.41 C20.97 7.46 19 4.5 19 4.5 C16.6 4.5 14.19 7.07 13.33 8.09 L12 8 M9 11 C9.55 11 10 11.45 10 12 C10 12.55 9.55 13 9 13 C8.45 13 8 12.55 8 12 C8 11.45 8.45 11 9 11 M15 11 C15.55 11 16 11.45 16 12 C16 12.55 15.55 13 15 13 C14.45 13 14 12.55 14 12 C14 11.45 14.45 11 15 11 M11 14 H13 L12.3 15.39 C12.5 16.03 13.06 16.5 13.75 16.5 C14.58 16.5 15.25 15.83 15.25 15 H15.75 C15.75 16.1 14.85 17 13.75 17 C13 17 12.35 16.59 12 16 V16 H12 C11.65 16.59 11 17 10.25 17 C9.15 17 8.25 16.1 8.25 15 H8.75 C8.75 15.83 9.42 16.5 10.25 16.5 C10.94 16.5 11.5 16.03 11.7 15.39 L11 14 Z");
        SVG_PATHS.put(DOG,
                "M18 4 C16.29 4 15.25 4.33 14.65 4.61 C13.88 4.23 13 4 12 4 C11 4 10.12 4.23 9.35 4.61 C8.75 4.33 7.71 4 6 4 C3 4 1 12 1 14 C1 14.83 2.32 15.59 4.14 15.9 C4.78 18.14 7.8 19.85 11.5 20 V15.72 C10.91 15.35 10 14.68 10 14 C10 13 12 13 12 13 C12 13 14 13 14 14 C14 14.68 13.09 15.35 12.5 15.72 V20 C16.2 19.85 19.22 18.14 19.86 15.9 C21.68 15.59 23 14.83 23 14 C23 12 21 4 18 4 M4.15 13.87 C3.65 13.75 3.26 13.61 3 13.5 C3.25 10.73 5.2 6.4 6.05 6 C6.59 6 7 6.06 7.37 6.11 C5.27 8.42 4.44 12.04 4.15 13.87 M9 12 C8.45 12 8 11.55 8 11 C8 10.46 8.45 10 9 10 C9.55 10 10 10.45 10 11 C10 11.56 9.55 12 9 12 M15 12 C14.45 12 14 11.55 14 11 C14 10.46 14.45 10 15 10 C15.55 10 16 10.45 16 11 C16 11.56 15.55 12 15 12 M19.85 13.87 C19.56 12.04 18.73 8.42 16.63 6.11 C17 6.06 17.41 6 17.95 6 C18.8 6.4 20.75 10.73 21 13.5 C20.75 13.61 20.36 13.75 19.85 13.87 Z");
        SVG_PATHS.put(BIRD,
                "M23 11.5L19.95 10.37C19.69 9.22 19.04 8.56 19.04 8.56C17.4 6.92 14.75 6.92 13.11 8.56L11.63 10.04L5 3C4 7 5 11 7.45 14.22L2 19.5C2 19.5 10.89 21.5 16.07 17.45C18.83 15.29 19.45 14.03 19.84 12.7L23 11.5M17.71 11.72C17.32 12.11 16.68 12.11 16.29 11.72C15.9 11.33 15.9 10.7 16.29 10.31C16.68 9.92 17.32 9.92 17.71 10.31C18.1 10.7 18.1 11.33 17.71 11.72Z");
        SVG_PATHS.put(PTZ, "M12 8c-2.21 0-4 1.79-4 4s1.79 4 4 4 4-1.79 4-4-1.79-4-4-4zm8.94 3A8.994 8.994 0 0 0 13 3.06V1h-2v2.06A8.994 8.994 0 0 0 3.06 11H1v2h2.06A8.994 8.994 0 0 0 11 20.94V23h2v-2.06A8.994 8.994 0 0 0 20.94 13H23v-2h-2.06zM12 19c-3.87 0-7-3.13-7-7s3.13-7 7-7 7 3.13 7 7-3.13 7-7 7z");
        SVG_PATHS.put(ARROW_UP, "M7.41 15.41L12 10.83l4.59 4.58L18 14l-6-6-6 6z");
        SVG_PATHS.put(ARROW_DOWN, "M7.41 8.59L12 13.17l4.59-4.58L18 10l-6 6-6-6z");
        SVG_PATHS.put(ARROW_LEFT, "M15.41 16.59L10.83 12l4.58-4.59L14 6l-6 6 6 6z");
        SVG_PATHS.put(ARROW_RIGHT, "M8.59 16.59L13.17 12 8.59 7.41 10 6l6 6-6 6z");
        SVG_PATHS.put(MOTORCYCLE,
                "M17.42 10 L13.41 6 H9 V8 H12.59 L14.59 10 H6.5 C4 10 2 12 2 14.5 C2 17 4 19 6.5 19 C8.72 19 10.56 17.38 10.92 15.27 L13.04 14 C13 14.17 13 14.33 13 14.5 C13 17 15 19 17.5 19 C20 19 22 17 22 14.5 C22 12 20 10 17.5 10 M8.84 15.26 C8.5 16.27 7.58 17 6.47 17 C5.09 17 3.97 15.88 3.97 14.5 C3.97 13.12 5.09 12 6.47 12 C7.59 12 8.5 12.74 8.84 13.75 H6 V15.25 L8.84 15.26 M17.47 17 C16.09 17 14.97 15.88 14.97 14.5 C14.97 13.12 16.09 12 17.47 12 C18.85 12 19.97 13.12 19.97 14.5 C19.97 15.88 18.85 17 17.47 17 Z");
        SVG_PATHS.put(BUS,
                "M18 11 H6 V6 H18 M16.5 17 C15.67 17 15 16.33 15 15.5 C15 14.67 15.67 14 16.5 14 C17.33 14 18 14.67 18 15.5 C18 16.33 17.33 17 16.5 17 M7.5 17 C6.67 17 6 16.33 6 15.5 C6 14.67 6.67 14 7.5 14 C8.33 14 9 14.67 9 15.5 C9 16.33 8.33 17 7.5 17 M4 16 C4 16.88 4.39 17.67 5 18.22 V20 C5 20.55 5.45 21 6 21 H7 C7.55 21 8 20.55 8 20 V19 H16 V20 C16 20.55 16.45 21 17 21 H18 C18.55 21 19 20.55 19 20 V18.22 C19.61 17.67 20 16.88 20 16 V6 C20 2.5 16.42 2 12 2 C7.58 2 4 2.5 4 6 V16 Z");
        SVG_PATHS.put(TRUCK,
                "M18 18.5 C17.17 18.5 16.5 17.83 16.5 17 C16.5 16.17 17.17 15.5 18 15.5 C18.83 15.5 19.5 16.17 19.5 17 C19.5 17.83 18.83 18.5 18 18.5 M19.5 9.5 L21.46 12 H17 V9.5 M6 18.5 C5.17 18.5 4.5 17.83 4.5 17 C4.5 16.17 5.17 15.5 6 15.5 C6.83 15.5 7.5 16.17 7.5 17 C7.5 17.83 6.83 18.5 6 18.5 M20 8 H17 V4 H3 C1.89 4 1 4.89 1 6 V17 H3 C3 18.66 4.34 20 6 20 C7.66 20 9 18.66 9 17 H15 C15 18.66 16.34 20 18 20 C19.66 20 21 18.66 21 17 H23 V12 L20 8 Z");
        SVG_PATHS.put(BOAT,
                "M3 13.5L11 2.03V13.5H3M12.5 13.5C13.85 9.75 13.67 4.71 12.5 1C17.26 2.54 20.9 8.4 20.96 13.5H12.5M21.1 17.08C20.69 17.72 20.21 18.27 19.65 18.74C19 18.45 18.42 18 17.96 17.5C16.47 19.43 13.46 19.43 11.97 17.5C10.5 19.43 7.47 19.43 6 17.5C5.5 18 4.95 18.45 4.3 18.74C3.16 17.8 2.3 16.46 2 15H21.94C21.78 15.75 21.5 16.44 21.1 17.08M20.96 23C19.9 23 18.9 22.75 17.96 22.25C16.12 23.25 13.81 23.25 11.97 22.25C10.13 23.25 7.82 23.25 6 22.25C4.77 22.94 3.36 23.05 2 23V21C3.41 21.05 4.77 20.9 6 20C7.74 21.25 10.21 21.25 11.97 20C13.74 21.25 16.2 21.25 17.96 20C19.17 20.9 20.54 21.05 21.94 21V23H20.96Z");
        SVG_PATHS.put(HORSE,
                "M22 6V9.5L20.5 10L18.96 7.54C18.83 7.33 18.5 7.42 18.5 7.67V11.25C18.5 12.23 18.11 13.11 17.5 13.78V21H15V15C14.92 15 14.84 15 14.75 15C14.54 15 14.33 14.97 14.13 14.94L9.69 14.2L8.57 16.21L9.53 21H7L6 16.25C5.97 15.95 6 15.65 6.16 15.39L7.18 13.58C6.2 13.03 5.53 12 5.5 10.81C5.46 10.96 5.44 11.18 5.47 11.5C5.5 11.94 5.61 12.59 5.54 13.31C5.5 14.03 5.17 14.77 4.75 15.26C4.32 15.75 3.85 16.09 3.35 16.35L2.65 15.65C2.84 15.18 3.03 14.76 3.07 14.37C3.13 14 3.06 13.7 2.95 13.43L2.42 12.3C2.21 11.79 1.95 11.05 2 10.18C2.03 9.33 2.5 8.22 3.39 7.61C4.29 7 5.26 6.92 6.05 7.08C6.55 7.18 7.06 7.42 7.5 7.76C7.87 7.59 8.3 7.5 8.75 7.5H14.5V7C14.5 4.79 16.29 3 18.5 3H22L21.11 4.34C21.65 4.7 22 5.31 22 6Z");
        SVG_PATHS.put(COW,
                "M10.5 18 C10.78 18 11 18.22 11 18.5 C11 18.78 10.78 19 10.5 19 C10.22 19 10 18.78 10 18.5 C10 18.22 10.22 18 10.5 18 M13.5 18 C13.78 18 14 18.22 14 18.5 C14 18.78 13.78 19 13.5 19 C13.22 19 13 18.78 13 18.5 C13 18.22 13.22 18 13.5 18 M10 11 C10.55 11 11 11.45 11 12 C11 12.55 10.55 13 10 13 C9.45 13 9 12.55 9 12 C9 11.45 9.45 11 10 11 M14 11 C14.55 11 15 11.45 15 12 C15 12.55 14.55 13 14 13 C13.45 13 13 12.55 13 12 C13 11.45 13.45 11 14 11 M18 18 C18 20.21 15.31 22 12 22 C8.69 22 6 20.21 6 18 C6 17.1 6.45 16.27 7.2 15.6 C6.45 14.6 6 13.35 6 12 L6.12 10.78 C5.58 10.93 4.93 10.93 4.4 10.78 C3.38 10.5 1.84 9.35 2.07 8.55 C2.3 7.75 4.21 7.6 5.23 7.9 C5.82 8.07 6.45 8.5 6.82 8.96 L7.39 8.15 C6.79 7.05 7 4 10 3 L9.91 3.14 V3.14 C9.63 3.58 8.91 4.97 9.67 6.47 C10.39 6.17 11.17 6 12 6 C12.83 6 13.61 6.17 14.33 6.47 C15.09 4.97 14.37 3.58 14.09 3.14 L14 3 C17 4 17.21 7.05 16.61 8.15 L17.18 8.96 C17.55 8.5 18.18 8.07 18.77 7.9 C19.79 7.6 21.7 7.75 21.93 8.55 C22.16 9.35 20.62 10.5 19.6 10.78 C19.07 10.93 18.42 10.93 17.88 10.78 L18 12 C18 13.35 17.55 14.6 16.8 15.6 C17.55 16.27 18 17.1 18 18 M12 16 C9.79 16 8 16.9 8 18 C8 19.1 9.79 20 12 20 C14.21 20 16 19.1 16 18 C16 16.9 14.21 16 12 16 M12 14 C13.12 14 14.17 14.21 15.07 14.56 C15.65 13.87 16 13 16 12 C16 9.79 14.21 8 12 8 C9.79 8 8 9.79 8 12 C8 13 8.35 13.87 8.93 14.56 C9.83 14.21 10.88 14 12 14 M14.09 3.14 V3.14 Z");
        SVG_PATHS.put(RABBIT,
                "M18.05,21L15.32,16.26C15.32,14.53 14.25,13.42 12.95,13.42C12.05,13.42 11.27,13.92 10.87,14.66C11.2,14.47 11.59,14.37 12,14.37C13.3,14.37 14.36,15.43 14.36,16.73C14.36,18.04 13.31,19.11 12,19.11H15.3V21H6.79C6.55,21 6.3,20.91 6.12,20.72C5.75,20.35 5.75,19.75 6.12,19.38V19.38L6.62,18.88C6.28,18.73 6,18.5 5.72,18.26C5.5,18.76 5,19.11 4.42,19.11C3.64,19.11 3,18.47 3,17.68C3,16.9 3.64,16.26 4.42,16.26L4.89,16.34V14.37C4.89,11.75 7,9.63 9.63,9.63H9.65C11.77,9.64 13.42,10.47 13.42,9.16C13.42,8.23 13.62,7.86 13.96,7.34C13.23,7 12.4,6.79 11.53,6.79C11,6.79 10.58,6.37 10.58,5.84C10.58,5.41 10.86,5.05 11.25,4.93L10.58,4.89C10.06,4.89 9.63,4.47 9.63,3.95C9.63,3.42 10.06,3 10.58,3H11.53C13.63,3 15.47,4.15 16.46,5.85L16.74,5.84C17.45,5.84 18.11,6.07 18.65,6.45L19.1,6.83C21.27,8.78 21,10.1 21,10.11C21,11.39 19.94,12.44 18.65,12.44L18.16,12.39V12.47C18.16,13.58 17.68,14.57 16.93,15.27L20.24,21H18.05M18.16,7.74C17.63,7.74 17.21,8.16 17.21,8.68C17.21,9.21 17.63,9.63 18.16,9.63C18.68,9.63 19.11,9.21 19.11,8.68C19.11,8.16 18.68,7.74 18.16,7.74Z");
        SVG_PATHS.put(BEAR,
                "M15.75 19.13C14.92 19.13 14.25 18.29 14.25 17.25C14.25 16.22 14.92 15.38 15.75 15.38C16.58 15.38 17.25 16.22 17.25 17.25C17.25 18.29 16.58 19.13 15.75 19.13M12 11.25C10.76 11.25 9.75 10.41 9.75 9.38C9.75 8.34 10.76 7.5 12 7.5C13.24 7.5 14.25 8.34 14.25 9.38C14.25 10.41 13.24 11.25 12 11.25M8.25 19.13C7.42 19.13 6.75 18.29 6.75 17.25C6.75 16.22 7.42 15.38 8.25 15.38C9.08 15.38 9.75 16.22 9.75 17.25C9.75 18.29 9.08 19.13 8.25 19.13M12 8.25C12.41 8.25 12.75 8.59 12.75 9C12.75 9.41 12.41 9.75 12 9.75C11.59 9.75 11.25 9.41 11.25 9C11.25 8.59 11.59 8.25 12 8.25M18.75 12C18.43 12 18.12 12.07 17.84 12.2C17.36 11.59 16.71 11.07 15.93 10.67C16.5 9.87 16.84 8.9 16.84 7.85C16.84 7.83 16.84 7.81 16.84 7.79C17.93 7.56 18.75 6.59 18.75 5.42C18.75 4.09 17.66 3 16.33 3C15.64 3 15 3.29 14.58 3.75C13.83 3.28 12.95 3 12 3C11.05 3 10.16 3.28 9.42 3.75C9 3.29 8.36 3 7.67 3C6.34 3 5.25 4.09 5.25 5.42C5.25 6.58 6.07 7.55 7.15 7.79C7.15 7.81 7.15 7.83 7.15 7.85C7.15 8.9 7.5 9.88 8.06 10.67C7.29 11.07 6.64 11.59 6.16 12.2C5.88 12.07 5.57 12 5.25 12C4 12 3 13 3 14.25C3 15.5 4 16.5 5.25 16.5C5.27 16.5 5.29 16.5 5.31 16.5C5.27 16.74 5.25 17 5.25 17.25C5.25 19.32 6.59 21 8.25 21C9.26 21 10.15 20.37 10.7 19.41C11.12 19.47 11.55 19.5 12 19.5C12.45 19.5 12.88 19.47 13.3 19.41C13.85 20.37 14.74 21 15.75 21C17.41 21 18.75 19.32 18.75 17.25C18.75 17 18.73 16.74 18.69 16.5C18.71 16.5 18.73 16.5 18.75 16.5C20 16.5 21 15.5 21 14.25C21 13 20 12 18.75 12");
        SVG_PATHS.put(MOUSE,
                "M21.33 17.39C22.73 18.66 21.8 21 19.92 21H11.06C8.25 21 6 18.75 6 15.94V15.89C3.7 15.42 2 13.41 2 11C2 8.25 4.22 6 7 6H9.5C9.8 6 10 5.77 10 5.5S9.8 5 9.5 5H7V3H9.5C10.88 3 12 4.13 12 5.5C12 6.89 10.88 8 9.5 8H7C5.34 8 4 9.33 4 11C4 12.37 4.92 13.5 6.14 13.87C6.7 11.67 8.67 10 11.06 10C11.86 10 12.66 10.22 13.36 10.55C11.95 11.34 11 12.8 11 14.5C11 15.75 11.5 16.87 12.33 17.67L13.03 16.97C12.38 16.36 12 15.47 12 14.5C12 11.91 14.34 11 15.5 11C17.58 11 19.45 12.89 18.94 15.23L21.33 17.39M18 19C18.56 19 19 18.56 19 18S18.56 17 18 17 17 17.44 17 18 17.44 19 18 19Z");
        SVG_PATHS.put(FIRE,
                "M17.66 11.2C17.43 10.9 17.15 10.64 16.89 10.38C16.22 9.78 15.46 9.35 14.82 8.72C13.33 7.26 13 4.85 13.95 3C13 3.23 12.17 3.75 11.46 4.32C8.87 6.4 7.85 10.07 9.07 13.22C9.11 13.32 9.15 13.42 9.15 13.55C9.15 13.77 9 13.97 8.8 14.05C8.57 14.15 8.33 14.09 8.14 13.93C8.08 13.88 8.04 13.83 8 13.76C6.87 12.33 6.69 10.28 7.45 8.64C5.78 10 4.87 12.3 5 14.47C5.06 14.97 5.12 15.47 5.29 15.97C5.43 16.57 5.7 17.17 6 17.7C7.08 19.43 8.95 20.67 10.96 20.92C13.1 21.19 15.39 20.8 17.03 19.32C18.86 17.66 19.5 15 18.56 12.72L18.43 12.46C18.22 12 17.66 11.2 17.66 11.2M14.5 17.5C14.22 17.74 13.76 18 13.4 18.1C12.28 18.5 11.16 17.94 10.5 17.28C11.69 17 12.4 16.12 12.61 15.23C12.78 14.43 12.46 13.77 12.33 13C12.21 12.26 12.23 11.63 12.5 10.94C12.69 11.32 12.89 11.7 13.13 12C13.9 13 15.11 13.44 15.37 14.8C15.41 14.94 15.43 15.08 15.43 15.23C15.46 16.05 15.1 16.95 14.5 17.5H14.5Z");
        SVG_PATHS.put(UMBRELLA,
                "M12 2 C16.97 2 21 6.03 21 11 H13 V19 C13 20.66 11.66 22 10 22 C8.34 22 7 20.66 7 19 V18 H9 V19 C9 19.55 9.45 20 10 20 C10.55 20 11 19.55 11 19 V11 H3 C3 6.03 7.03 2 12 2 Z");
        SVG_PATHS.put(LICENSE_PLATE,
                "M4 6 H6 V18 H4 V6 M7 6 H8 V18 H7 V6 M9 6 H12 V18 H9 V6 M13 6 H14 V18 H13 V6 M16 6 H18 V18 H16 V6 M19 6 H20 V18 H19 V6 M2 4 V8 H0 V4 C0 2.9 0.9 2 2 2 H6 V4 H2 M22 2 C23.1 2 24 2.9 24 4 V8 H22 V4 H18 V2 H22 M2 16 V20 H6 V22 H2 C0.9 22 0 21.1 0 20 V16 H2 M22 20 V16 H24 V20 C24 21.1 23.1 22 22 22 H18 V20 H22 Z");
        SVG_PATHS.put(BABY,
                "M1 12 C1 10.19 2.2 8.66 3.86 8.17 C5.29 5.11 8.4 3 12 3 C15.6 3 18.71 5.11 20.15 8.17 C21.8 8.66 23 10.19 23 12 C23 13.81 21.8 15.34 20.15 15.83 C18.71 18.89 15.6 21 12 21 C8.4 21 5.29 18.89 3.86 15.83 C2.2 15.34 1 13.81 1 12 M14.5 9.25 C13.81 9.25 13.25 9.81 13.25 10.5 C13.25 11.19 13.81 11.75 14.5 11.75 C15.19 11.75 15.75 11.19 15.75 10.5 C15.75 9.81 15.19 9.25 14.5 9.25 M9.5 9.25 C8.81 9.25 8.25 9.81 8.25 10.5 C8.25 11.19 8.81 11.75 9.5 11.75 C10.19 11.75 10.75 11.19 10.75 10.5 C10.75 9.81 10.19 9.25 9.5 9.25 M7.5 14 C8.26 15.77 10 17 12 17 C14 17 15.74 15.77 16.5 14 H7.5 M3 12 C3 12.82 3.5 13.53 4.21 13.84 C4.07 13.25 4 12.63 4 12 C4 11.37 4.07 10.75 4.21 10.16 C3.5 10.47 3 11.18 3 12 M21 12 C21 11.18 20.5 10.47 19.79 10.16 C19.93 10.75 20 11.37 20 12 C20 12.63 19.93 13.25 19.79 13.84 C20.5 13.53 21 12.82 21 12 Z");
        SVG_PATHS.put(STROLLER,
                "M13 2 V10 H21 C21 5.58 17.42 2 13 2 M19.32 15.89 C20.37 14.54 21 12.84 21 11 H6.44 L5.5 9 H2 V11 H4.22 C4.22 11 6.11 15.07 6.34 15.42 C5.24 16 4.5 17.17 4.5 18.5 C4.5 20.43 6.07 22 8 22 C9.76 22 11.22 20.7 11.46 19 H13.54 C13.78 20.7 15.24 22 17 22 C18.93 22 20.5 20.43 20.5 18.5 C20.5 17.46 20.04 16.53 19.32 15.89 M8 20 C7.17 20 6.5 19.33 6.5 18.5 C6.5 17.67 7.17 17 8 17 C8.83 17 9.5 17.67 9.5 18.5 C9.5 19.33 8.83 20 8 20 M17 20 C16.17 20 15.5 19.33 15.5 18.5 C15.5 17.67 16.17 17 17 17 C17.83 17 18.5 17.67 18.5 18.5 C18.5 19.33 17.83 20 17 20 Z");
        SVG_PATHS.put(TAG,
                "M5.5 7 C4.67 7 4 6.33 4 5.5 C4 4.67 4.67 4 5.5 4 C6.33 4 7 4.67 7 5.5 C7 6.33 6.33 7 5.5 7 M21.41 11.58 L12.41 2.58 C12.05 2.22 11.55 2 11 2 H4 C2.89 2 2 2.89 2 4 V11 C2 11.55 2.22 12.05 2.59 12.41 L11.58 21.41 C11.95 21.77 12.45 22 13 22 C13.55 22 14.05 21.77 14.41 21.41 L21.41 14.41 C21.78 14.05 22 13.55 22 13 C22 12.44 21.77 11.94 21.41 11.58 Z");
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
        if (view == null) return;
        VectorDrawable d = getDrawable(name, color, sizePx, theme);
        if (d != null) {
            d.setBounds(0, 0, sizePx, sizePx);
            view.setCompoundDrawables(d, null, null, null);
            view.setCompoundDrawablePadding(paddingPx);
        }
        view.setIncludeFontPadding(false);
        int curGravity = view.getGravity();
        int hGravity = (curGravity & android.view.Gravity.HORIZONTAL_GRAVITY_MASK);
        if (hGravity == 0) hGravity = android.view.Gravity.START;
        view.setGravity(hGravity | android.view.Gravity.CENTER_VERTICAL);
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
