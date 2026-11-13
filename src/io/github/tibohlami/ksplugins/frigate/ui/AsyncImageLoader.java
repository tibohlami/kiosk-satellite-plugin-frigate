package io.github.tibohlami.ksplugins.frigate.ui;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;
import io.github.tibohlami.ksplugins.frigate.api.FrigateHttpHelper;
import io.github.tibohlami.ksplugins.frigate.config.FrigateConfig;
import me.jxl.kiosk.plugins.PluginHost;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Ultra-fast, lightweight asynchronous thumbnail image loader with byte-budgeted memory caching,
 * weak view references, in-flight duplicate suppression, downsampling, and full cleanup on stop.
 */
public class AsyncImageLoader {

    // Cap caches by bytes based on available heap: at most 10 MB total
    private static final int MAX_SNAPSHOT_BYTES = Math.min(8 * 1024 * 1024, (int) (Runtime.getRuntime().maxMemory() / 24));
    private static final int MAX_THUMB_BYTES = Math.min(12 * 1024 * 1024, (int) (Runtime.getRuntime().maxMemory() / 16));

    private static final LruCache<String, Bitmap> LATEST_SNAPSHOT_CACHE = new LruCache<String, Bitmap>(MAX_SNAPSHOT_BYTES) {
        @Override
        protected int sizeOf(String key, Bitmap bitmap) {
            return bitmap != null ? bitmap.getByteCount() : 0;
        }
    };

    private final LruCache<String, Bitmap> memoryCache;
    private final Map<ImageView, String> viewUrlMap;
    private final Set<String> inFlightUrls;
    private final ExecutorService executor;
    private final Handler mainHandler;
    private PluginHost host;
    private String authToken;
    private FrigateConfig configProvider;
    private volatile boolean isShutdown = false;

    public AsyncImageLoader() {
        this.memoryCache = new LruCache<String, Bitmap>(MAX_THUMB_BYTES) {
            @Override
            protected int sizeOf(String key, Bitmap bitmap) {
                return bitmap != null ? bitmap.getByteCount() : 0;
            }
        };
        // WeakHashMap ensures ImageViews are never leaked when views or overlays are rebuilt/closed
        this.viewUrlMap = Collections.synchronizedMap(new WeakHashMap<>());
        this.inFlightUrls = Collections.synchronizedSet(new HashSet<>());
        this.executor = Executors.newFixedThreadPool(4);
        this.mainHandler = new Handler(Looper.getMainLooper());
    }

    public void setHost(PluginHost host) {
        this.host = host;
    }

    public void setAuthToken(String token) {
        this.authToken = token;
    }

    public void setConfig(FrigateConfig config) {
        this.configProvider = config;
    }

    public void load(String urlString, ImageView imageView) {
        boolean bypassCache = urlString != null && urlString.contains("/latest.jpg");
        loadInternal(urlString, imageView, !bypassCache, 480, 270);
    }

    public void load(String urlString, ImageView imageView, int reqWidth, int reqHeight) {
        boolean bypassCache = urlString != null && urlString.contains("/latest.jpg");
        loadInternal(urlString, imageView, !bypassCache, reqWidth, reqHeight);
    }

    /**
     * Always fetches a fresh image from the server without consulting or storing in the memory cache,
     * skipping duplicate requests if one is already in flight for this exact URL.
     */
    public void loadFresh(String urlString, ImageView imageView) {
        loadFresh(urlString, imageView, 640, 360);
    }

    public void loadFresh(String urlString, ImageView imageView, int reqWidth, int reqHeight) {
        loadInternal(urlString, imageView, false, reqWidth, reqHeight);
    }

    private void loadInternal(String urlString, ImageView imageView, boolean useCache, int reqWidth, int reqHeight) {
        if (isShutdown || executor.isShutdown() || urlString == null || urlString.isEmpty() || imageView == null) {
            return;
        }

        viewUrlMap.put(imageView, urlString);

        if (useCache) {
            synchronized (memoryCache) {
                Bitmap cached = memoryCache.get(urlString);
                if (cached != null && !cached.isRecycled()) {
                    imageView.setImageBitmap(cached);
                    return;
                }
            }
        }

        // Avoid re-queuing duplicate snapshots if an identical request is already loading
        if (!inFlightUrls.add(urlString)) {
            return;
        }

        try {
            executor.execute(() -> {
                try {
                    if (isShutdown) return;
                    Bitmap bitmap = fetchBitmap(urlString, useCache, reqWidth, reqHeight);
                    if (bitmap != null && !isShutdown) {
                        if (useCache) {
                            synchronized (memoryCache) {
                                memoryCache.put(urlString, bitmap);
                            }
                        }
                        String latestKey = normalizeLatestSnapshotKey(urlString);
                        if (latestKey != null) {
                            synchronized (LATEST_SNAPSHOT_CACHE) {
                                LATEST_SNAPSHOT_CACHE.put(latestKey, bitmap);
                            }
                        }
                        final Bitmap finalBitmap = bitmap;
                        mainHandler.post(() -> {
                            try {
                                if (isShutdown) return;
                                String expectedUrl = viewUrlMap.get(imageView);
                                if (urlString.equals(expectedUrl)) {
                                    imageView.setImageBitmap(finalBitmap);
                                }
                            } catch (Throwable ignored) {}
                        });
                    }
                } catch (Throwable t) {
                    // Shield the thread from any escaping error or OutOfMemoryError
                } finally {
                    inFlightUrls.remove(urlString);
                }
            });
        } catch (Throwable ignored) {
            inFlightUrls.remove(urlString);
        }
    }

    private Bitmap fetchBitmap(String urlString, boolean useCache, int reqWidth, int reqHeight) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlString);
            conn = FrigateHttpHelper.openConnection(url, configProvider);
            conn.setConnectTimeout(6000);
            conn.setReadTimeout(8000);
            if (!useCache) {
                conn.setUseCaches(false);
                conn.setRequestProperty("Cache-Control", "no-cache");
            }

            String activeToken = (configProvider != null) ? configProvider.getAuthToken() : authToken;
            FrigateHttpHelper.applyAuthHeaders(conn, activeToken);

            if (conn.getResponseCode() == 200) {
                byte[] data;
                try (InputStream in = conn.getInputStream();
                     ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                    byte[] buffer = new byte[8192];
                    int read;
                    while ((read = in.read(buffer)) != -1) {
                        if (isShutdown) return null;
                        out.write(buffer, 0, read);
                    }
                    data = out.toByteArray();
                }
                FrigateHttpHelper.logImageDebug(host, configProvider, "GET", urlString, data.length);
                return decodeSampledBitmap(data, reqWidth, reqHeight);
            }
        } catch (Throwable ignored) {
            // Silently fallback if thumbnail is not ready or network fails
        } finally {
            if (conn != null) {
                try { conn.disconnect(); } catch (Throwable ignored) {}
            }
        }
        return null;
    }

    public static Bitmap decodeSampledBitmap(byte[] data, int reqWidth, int reqHeight) {
        if (data == null || data.length == 0) return null;
        try {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, options);
            if (options.outWidth <= 0 || options.outHeight <= 0) return null;

            int targetW = reqWidth > 0 ? reqWidth : 640;
            int targetH = reqHeight > 0 ? reqHeight : 360;
            options.inSampleSize = calculateInSampleSize(options, targetW, targetH);
            options.inJustDecodeBounds = false;
            // RGB_565 cuts memory usage by 50% compared to ARGB_8888 and is ideal for camera stills
            options.inPreferredConfig = Bitmap.Config.RGB_565;
            return BitmapFactory.decodeByteArray(data, 0, data.length, options);
        } catch (Throwable t) {
            return null;
        }
    }

    private static int calculateInSampleSize(BitmapFactory.Options options, int reqWidth, int reqHeight) {
        final int height = options.outHeight;
        final int width = options.outWidth;
        int inSampleSize = 1;
        if (height > reqHeight || width > reqWidth) {
            final int halfHeight = height / 2;
            final int halfWidth = width / 2;
            while ((halfHeight / inSampleSize) >= reqHeight && (halfWidth / inSampleSize) >= reqWidth) {
                inSampleSize *= 2;
            }
        }
        return Math.max(1, inSampleSize);
    }

    /**
     * Returns the most recently fetched {@code /latest.jpg} snapshot for a camera URL, if any.
     */
    public static Bitmap getLastKnownSnapshot(String urlString) {
        String key = normalizeLatestSnapshotKey(urlString);
        if (key == null) return null;
        synchronized (LATEST_SNAPSHOT_CACHE) {
            return LATEST_SNAPSHOT_CACHE.get(key);
        }
    }

    private static String normalizeLatestSnapshotKey(String urlString) {
        if (urlString == null || urlString.isEmpty()) return null;
        int qIdx = urlString.indexOf('?');
        String base = (qIdx >= 0) ? urlString.substring(0, qIdx) : urlString;
        return base.endsWith("/latest.jpg") ? base : null;
    }

    public void clearCache() {
        synchronized (memoryCache) {
            memoryCache.evictAll();
        }
        viewUrlMap.clear();
        inFlightUrls.clear();
    }

    public static void clearAllStaticCaches() {
        synchronized (LATEST_SNAPSHOT_CACHE) {
            LATEST_SNAPSHOT_CACHE.evictAll();
        }
    }

    public void shutdown() {
        isShutdown = true;
        mainHandler.removeCallbacksAndMessages(null);
        clearCache();
        clearAllStaticCaches();
        executor.shutdownNow();
    }
}
