package me.jxl.kiosk.plugins.frigate.ui;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.widget.ImageView;
import me.jxl.kiosk.plugins.frigate.api.FrigateHttpHelper;
import me.jxl.kiosk.plugins.frigate.config.FrigateConfig;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/**
 * Ultra-fast, lightweight asynchronous thumbnail image loader with memory caching for events
 * and uncached real-time fetching for live camera snapshots.
 */
public class AsyncImageLoader {

    private static final int MAX_CACHE_ENTRIES = 120;
    private static final int MAX_LATEST_SNAPSHOT_ENTRIES = 24;
    private static final Map<String, Bitmap> LATEST_SNAPSHOT_CACHE = Collections.synchronizedMap(
            new LinkedHashMap<String, Bitmap>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Bitmap> eldest) {
                    return size() > MAX_LATEST_SNAPSHOT_ENTRIES;
                }
            });
    private final Map<String, Bitmap> memoryCache;
    private final Map<ImageView, String> viewUrlMap;
    private final ExecutorService executor;
    private final Handler mainHandler;
    private String authToken;
    private FrigateConfig configProvider;
    private volatile boolean isShutdown = false;

    public AsyncImageLoader() {
        this.memoryCache = Collections.synchronizedMap(new LinkedHashMap<String, Bitmap>(64, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Bitmap> eldest) {
                return size() > MAX_CACHE_ENTRIES;
            }
        });
        this.viewUrlMap = Collections.synchronizedMap(new LinkedHashMap<>());
        this.executor = Executors.newFixedThreadPool(4);
        this.mainHandler = new Handler(Looper.getMainLooper());
    }

    public void setAuthToken(String token) {
        this.authToken = token;
    }

    public void setConfig(FrigateConfig config) {
        this.configProvider = config;
    }

    public void load(String urlString, ImageView imageView) {
        boolean bypassCache = urlString != null && urlString.contains("/latest.jpg");
        loadInternal(urlString, imageView, !bypassCache);
    }

    /**
     * Always fetches a fresh image from the server without consulting or storing in the memory cache.
     */
    public void loadFresh(String urlString, ImageView imageView) {
        loadInternal(urlString, imageView, false);
    }

    private void loadInternal(String urlString, ImageView imageView, boolean useCache) {
        if (isShutdown || executor.isShutdown() || urlString == null || urlString.isEmpty() || imageView == null) {
            return;
        }

        viewUrlMap.put(imageView, urlString);

        if (useCache) {
            Bitmap cached = memoryCache.get(urlString);
            if (cached != null) {
                imageView.setImageBitmap(cached);
                return;
            }
        }

        // Fetch asynchronously
        try {
            executor.execute(() -> {
                if (isShutdown) return;
                Bitmap bitmap = fetchBitmap(urlString, useCache);
                if (bitmap != null && !isShutdown) {
                    if (useCache) {
                        memoryCache.put(urlString, bitmap);
                    }
                    String latestKey = normalizeLatestSnapshotKey(urlString);
                    if (latestKey != null) {
                        LATEST_SNAPSHOT_CACHE.put(latestKey, bitmap);
                    }
                    final Bitmap finalBitmap = bitmap;
                    mainHandler.post(() -> {
                        if (isShutdown) return;
                        String expectedUrl = viewUrlMap.get(imageView);
                        if (urlString.equals(expectedUrl)) {
                            imageView.setImageBitmap(finalBitmap);
                        }
                    });
                }
            });
        } catch (RejectedExecutionException ignored) {
            // Loader already shut down while a pending view refresh fired
        }
    }

    private Bitmap fetchBitmap(String urlString, boolean useCache) {
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
                return BitmapFactory.decodeByteArray(data, 0, data.length);
            }
        } catch (Exception ignored) {
            // Silently fallback if thumbnail is not ready or network fails
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
        return null;
    }

    /**
     * Returns the most recently fetched {@code /latest.jpg} snapshot for a camera URL, if any.
     */
    public static Bitmap getLastKnownSnapshot(String urlString) {
        String key = normalizeLatestSnapshotKey(urlString);
        return (key != null) ? LATEST_SNAPSHOT_CACHE.get(key) : null;
    }

    private static String normalizeLatestSnapshotKey(String urlString) {
        if (urlString == null || urlString.isEmpty()) return null;
        int qIdx = urlString.indexOf('?');
        String base = (qIdx >= 0) ? urlString.substring(0, qIdx) : urlString;
        return base.endsWith("/latest.jpg") ? base : null;
    }

    public void clearCache() {
        memoryCache.clear();
        viewUrlMap.clear();
    }

    public void shutdown() {
        isShutdown = true;
        mainHandler.removeCallbacksAndMessages(null);
        clearCache();
        executor.shutdownNow();
    }
}
