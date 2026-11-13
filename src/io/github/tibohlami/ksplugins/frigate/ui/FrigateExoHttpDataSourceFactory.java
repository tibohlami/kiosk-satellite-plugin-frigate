package io.github.tibohlami.ksplugins.frigate.ui;

import android.net.Uri;
import androidx.media3.common.C;
import androidx.media3.common.PlaybackException;
import androidx.media3.datasource.BaseDataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.HttpDataSource;
import io.github.tibohlami.ksplugins.frigate.api.FrigateHttpHelper;
import io.github.tibohlami.ksplugins.frigate.config.FrigateConfig;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Media3 HttpDataSource.Factory backed by FrigateHttpHelper so ExoPlayer HLS VOD
 * respects FrigateConfig.isAllowInsecureSsl() without altering global TLS defaults.
 */
public final class FrigateExoHttpDataSourceFactory implements HttpDataSource.Factory {

    private final FrigateConfig config;
    private final Map<String, String> defaultProperties = new HashMap<>();
    private int connectTimeoutMs = 8000;
    private int readTimeoutMs = 15000;

    public FrigateExoHttpDataSourceFactory(FrigateConfig config) {
        this.config = config;
    }

    public FrigateExoHttpDataSourceFactory setConnectTimeoutMs(int connectTimeoutMs) {
        this.connectTimeoutMs = connectTimeoutMs;
        return this;
    }

    public FrigateExoHttpDataSourceFactory setReadTimeoutMs(int readTimeoutMs) {
        this.readTimeoutMs = readTimeoutMs;
        return this;
    }

    @Override
    public HttpDataSource.Factory setDefaultRequestProperties(Map<String, String> defaultRequestProperties) {
        synchronized (defaultProperties) {
            defaultProperties.clear();
            if (defaultRequestProperties != null) {
                defaultProperties.putAll(defaultRequestProperties);
            }
        }
        return this;
    }

    @Override
    public HttpDataSource createDataSource() {
        Map<String, String> snapshot;
        synchronized (defaultProperties) {
            snapshot = new HashMap<>(defaultProperties);
        }
        return new FrigateHttpDataSource(config, snapshot, connectTimeoutMs, readTimeoutMs);
    }

    private static final class FrigateHttpDataSource extends BaseDataSource implements HttpDataSource {

        private static final int MAX_REDIRECTS = 10;

        private final FrigateConfig config;
        private final Map<String, String> defaultProps;
        private final Map<String, String> instanceProps = new HashMap<>();
        private final int connectTimeoutMs;
        private final int readTimeoutMs;

        private DataSpec dataSpec;
        private HttpURLConnection connection;
        private InputStream inputStream;
        private boolean opened;
        private int responseCode = -1;
        private long bytesToRead = C.LENGTH_UNSET;
        private long bytesRead;

        FrigateHttpDataSource(FrigateConfig config, Map<String, String> defaultProps, int connectTimeoutMs, int readTimeoutMs) {
            super(true);
            this.config = config;
            this.defaultProps = defaultProps != null ? defaultProps : Collections.<String, String>emptyMap();
            this.connectTimeoutMs = connectTimeoutMs;
            this.readTimeoutMs = readTimeoutMs;
        }

        @Override
        public void setRequestProperty(String name, String value) {
            synchronized (instanceProps) {
                instanceProps.put(name, value);
            }
        }

        @Override
        public void clearRequestProperty(String name) {
            synchronized (instanceProps) {
                instanceProps.remove(name);
            }
        }

        @Override
        public void clearAllRequestProperties() {
            synchronized (instanceProps) {
                instanceProps.clear();
            }
        }

        @Override
        public int getResponseCode() {
            return (connection == null || responseCode <= 0) ? -1 : responseCode;
        }

        @Override
        public Map<String, List<String>> getResponseHeaders() {
            return connection == null ? Collections.<String, List<String>>emptyMap() : connection.getHeaderFields();
        }

        @Override
        public Uri getUri() {
            if (connection != null) {
                return Uri.parse(connection.getURL().toString());
            }
            return dataSpec != null ? dataSpec.uri : null;
        }

        @Override
        public long open(DataSpec dataSpec) throws HttpDataSourceException {
            this.dataSpec = dataSpec;
            this.bytesRead = 0;
            this.bytesToRead = 0;
            transferInitializing(dataSpec);

            try {
                connection = makeConnectionFollowingRedirects(dataSpec);
                responseCode = connection.getResponseCode();
                String responseMessage = connection.getResponseMessage();

                if (responseCode < 200 || responseCode > 299) {
                    Map<String, List<String>> headers = connection.getHeaderFields();
                    byte[] errorBody = readStreamQuietly(connection.getErrorStream());
                    closeConnectionQuietly();
                    throw new InvalidResponseCodeException(
                            responseCode, responseMessage, null, headers, dataSpec, errorBody);
                }

                long contentLength = -1;
                String lenHeader = connection.getHeaderField("Content-Length");
                if (lenHeader != null) {
                    try {
                        contentLength = Long.parseLong(lenHeader.trim());
                    } catch (NumberFormatException ignored) {}
                }
                if (dataSpec.length != C.LENGTH_UNSET) {
                    bytesToRead = dataSpec.length;
                } else if (contentLength != -1) {
                    bytesToRead = contentLength;
                } else {
                    bytesToRead = C.LENGTH_UNSET;
                }

                inputStream = connection.getInputStream();
            } catch (HttpDataSourceException e) {
                closeConnectionQuietly();
                throw e;
            } catch (IOException e) {
                closeConnectionQuietly();
                throw new HttpDataSourceException(
                        e, dataSpec, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                        HttpDataSourceException.TYPE_OPEN);
            }

            opened = true;
            transferStarted(dataSpec);
            return bytesToRead;
        }

        private HttpURLConnection makeConnectionFollowingRedirects(DataSpec spec) throws IOException {
            URL url = new URL(spec.uri.toString());
            int method = spec.httpMethod;
            byte[] body = spec.httpBody;
            for (int redirectCount = 0; redirectCount <= MAX_REDIRECTS; redirectCount++) {
                HttpURLConnection conn = FrigateHttpHelper.openConnection(url, config);
                conn.setConnectTimeout(connectTimeoutMs);
                conn.setReadTimeout(readTimeoutMs);
                conn.setInstanceFollowRedirects(false);

                Map<String, String> merged = new HashMap<>(defaultProps);
                synchronized (instanceProps) {
                    merged.putAll(instanceProps);
                }
                if (spec.httpRequestHeaders != null) {
                    merged.putAll(spec.httpRequestHeaders);
                }
                boolean isSameHost = url.getHost().equalsIgnoreCase(spec.uri.getHost());
                for (Map.Entry<String, String> entry : merged.entrySet()) {
                    String key = entry.getKey();
                    if (!isSameHost && ("Authorization".equalsIgnoreCase(key) || "Cookie".equalsIgnoreCase(key))) {
                        continue;
                    }
                    conn.setRequestProperty(key, entry.getValue());
                }

                if (spec.position != 0 || spec.length != C.LENGTH_UNSET) {
                    String range = "bytes=" + spec.position + "-";
                    if (spec.length != C.LENGTH_UNSET) {
                        range += (spec.position + spec.length - 1);
                    }
                    conn.setRequestProperty("Range", range);
                }
                if (!spec.isFlagSet(DataSpec.FLAG_ALLOW_GZIP)) {
                    conn.setRequestProperty("Accept-Encoding", "identity");
                }

                conn.setRequestMethod(DataSpec.getStringForHttpMethod(method));
                if (body != null) {
                    conn.setDoOutput(true);
                    conn.setFixedLengthStreamingMode(body.length);
                    try (OutputStream out = conn.getOutputStream()) {
                        out.write(body);
                    }
                } else {
                    conn.connect();
                }

                int code = conn.getResponseCode();
                if (code == 300 || code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                    String location = conn.getHeaderField("Location");
                    conn.disconnect();
                    if (location == null) {
                        throw new IOException("Redirect without Location header");
                    }
                    url = new URL(url, location);
                    if (code != 307 && code != 308 && method == DataSpec.HTTP_METHOD_POST) {
                        method = DataSpec.HTTP_METHOD_GET;
                        body = null;
                    }
                    continue;
                }
                return conn;
            }
            throw new IOException("Too many redirects");
        }

        @Override
        public int read(byte[] buffer, int offset, int readLength) throws HttpDataSourceException {
            if (readLength == 0) {
                return 0;
            }
            if (bytesToRead != C.LENGTH_UNSET) {
                long remaining = bytesToRead - bytesRead;
                if (remaining == 0) {
                    return C.RESULT_END_OF_INPUT;
                }
                readLength = (int) Math.min(readLength, remaining);
            }
            try {
                int read = inputStream.read(buffer, offset, readLength);
                if (read == -1) {
                    if (bytesToRead != C.LENGTH_UNSET && bytesRead < bytesToRead) {
                        throw new EOFException();
                    }
                    return C.RESULT_END_OF_INPUT;
                }
                bytesRead += read;
                bytesTransferred(read);
                return read;
            } catch (IOException e) {
                throw HttpDataSourceException.createForIOException(
                        e, dataSpec, HttpDataSourceException.TYPE_READ);
            }
        }

        @Override
        public void close() throws HttpDataSourceException {
            try {
                if (inputStream != null) {
                    try {
                        inputStream.close();
                    } catch (IOException e) {
                        throw new HttpDataSourceException(
                                e, dataSpec, PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
                                HttpDataSourceException.TYPE_CLOSE);
                    }
                }
            } finally {
                inputStream = null;
                closeConnectionQuietly();
                if (opened) {
                    opened = false;
                    transferEnded();
                }
                responseCode = -1;
            }
        }

        private void closeConnectionQuietly() {
            if (connection != null) {
                try {
                    connection.disconnect();
                } catch (Exception ignored) {}
                connection = null;
            }
        }

        private static byte[] readStreamQuietly(InputStream is) {
            if (is == null) return new byte[0];
            try (InputStream in = is; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buf = new byte[1024];
                int r;
                while ((r = in.read(buf)) != -1) {
                    out.write(buf, 0, r);
                }
                return out.toByteArray();
            } catch (IOException e) {
                return new byte[0];
            }
        }
    }
}
