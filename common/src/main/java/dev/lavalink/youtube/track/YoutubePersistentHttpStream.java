package dev.lavalink.youtube.track;

import com.sedmelluq.discord.lavaplayer.tools.io.HttpInterface;
import com.sedmelluq.discord.lavaplayer.tools.io.PersistentHttpStream;
import org.apache.http.Header;
import org.apache.http.client.utils.URIBuilder;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A resilient persistent HTTP stream implementation for YouTube playback that:
 * 1. Automatically advances across chunk boundaries without premature EOF.
 * 2. Transparently refreshes expired/403 streaming URLs mid-stream at the exact byte offset.
 * 3. Handles seek and skip operations safely by resetting chunk range bounds.
 */
public class YoutubePersistentHttpStream extends PersistentHttpStream {
    private static final Logger log = LoggerFactory.getLogger(YoutubePersistentHttpStream.class);

    // Valid range for requesting without throttling is 0-11862014
    private static final long BUFFER_SIZE = 11862014;
    private static final Pattern CONTENT_RANGE_TOTAL = Pattern.compile(".*/(\\d+)$");

    @FunctionalInterface
    public interface UrlRefresher {
        @Nullable
        URI refreshUrl() throws Exception;
    }

    private final UrlRefresher urlRefresher;
    private volatile URI currentContentUrl;
    private long rangeEnd;

    public YoutubePersistentHttpStream(HttpInterface httpInterface, URI contentUrl, long contentLength) {
        this(httpInterface, contentUrl, contentLength, null);
    }

    public YoutubePersistentHttpStream(HttpInterface httpInterface, URI contentUrl, long contentLength, @Nullable UrlRefresher urlRefresher) {
        super(httpInterface, contentUrl, contentLength);
        this.currentContentUrl = contentUrl;
        this.urlRefresher = urlRefresher;
    }

    @Override
    public int read() throws IOException {
        byte[] b = new byte[1];
        int res = internalRead(b, 0, 1, true);
        return res == -1 ? -1 : (b[0] & 0xFF);
    }

    @Override
    protected URI getConnectUrl() {
        if (!currentContentUrl.toString().contains("rn=")) {
            URI rangeUrl = getNextRangeUrl();
            log.debug("Range URL: {}", rangeUrl);
            return rangeUrl;
        } else {
            return currentContentUrl;
        }
    }

    @Override
    protected int internalRead(byte[] b, int off, int len, boolean attemptReconnect) throws IOException {
        int retries = 0;
        int maxRetries = 4;

        while (true) {
            safeConnect();

            try {
                int result = currentContent.read(b, off, len);

                if (result > 0) {
                    position += result;
                    return result;
                } else if (result == 0) {
                    return 0;
                }

                // result == -1: EOF of current HTTP chunk
                boolean isRealEnd = (contentLength > 0 && position >= contentLength);
                if (isRealEnd) {
                    log.debug("Reached end of audio stream at position {} / {} bytes", position, contentLength);
                    return -1;
                }

                log.debug("HTTP chunk ended at position {} / {} bytes, reconnecting for next chunk",
                        position, contentLength);
                close();
            } catch (IOException e) {
                log.warn("Network IO error at byte position {}: {}", position, e.getMessage());
                close();
                retries++;
                if (retries >= maxRetries) {
                    throw e;
                }
                try {
                    Thread.sleep(100L * retries);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
    }

    @Override
    protected long internalSkip(long n, boolean attemptReconnect) throws IOException {
        int retries = 0;
        int maxRetries = 4;

        while (true) {
            safeConnect();

            try {
                long result = currentContent.skip(n);

                if (result > 0) {
                    position += result;
                    return result;
                } else if (result == 0) {
                    return 0;
                }

                boolean isRealEnd = (contentLength > 0 && position >= contentLength);
                if (isRealEnd) {
                    return -1;
                }

                close();
            } catch (IOException e) {
                log.warn("Network IO error during skip at byte position {}: {}", position, e.getMessage());
                close();
                retries++;
                if (retries >= maxRetries) {
                    throw e;
                }
                try {
                    Thread.sleep(100L * retries);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
    }

    private void safeConnect() throws IOException {
        if (currentContent != null) {
            return;
        }

        int maxRetries = 3;
        Throwable lastError = null;

        for (int attempt = 0; attempt < maxRetries; attempt++) {
            try {
                connect(false);
                if (currentContent != null) {
                    checkAndExtractTotalLength();
                    return;
                }
            } catch (Throwable t) {
                lastError = t;
                log.warn("Stream connect attempt {}/{} failed at byte position {}: {}",
                        attempt + 1, maxRetries, position, t.getMessage());
                close();

                if (isAuthOr403Error(t) && urlRefresher != null) {
                    try {
                        log.info("Refreshing streaming URL due to auth/403 at position {}...", position);
                        URI refreshed = urlRefresher.refreshUrl();
                        if (refreshed != null) {
                            this.currentContentUrl = refreshed;
                            log.info("Successfully refreshed streaming URL for byte position {}", position);
                        }
                    } catch (Exception ex) {
                        log.warn("URL refresher failed: {}", ex.getMessage());
                    }
                }

                try {
                    Thread.sleep(150L * (attempt + 1));
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Stream connect interrupted", ie);
                }
            }
        }

        if (lastError instanceof IOException) {
            throw (IOException) lastError;
        } else if (lastError instanceof RuntimeException) {
            throw (RuntimeException) lastError;
        } else {
            throw new IOException("Failed to establish stream connection at position " + position, lastError);
        }
    }

    private void checkAndExtractTotalLength() {
        if (contentLength <= 0 && getCurrentResponse() != null) {
            Header rangeHeader = getCurrentResponse().getFirstHeader("Content-Range");
            if (rangeHeader != null) {
                Matcher matcher = CONTENT_RANGE_TOTAL.matcher(rangeHeader.getValue().trim());
                if (matcher.find()) {
                    try {
                        long total = Long.parseLong(matcher.group(1));
                        if (total > 0) {
                            this.contentLength = total;
                            log.debug("Discovered stream total contentLength: {} bytes", total);
                        }
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
        }
    }

    private URI getNextRangeUrl() {
        rangeEnd = position + BUFFER_SIZE;

        if (contentLength > 0 && rangeEnd > contentLength) {
            rangeEnd = contentLength;
        }

        try {
            if (contentLength > 0) {
                return new URIBuilder(currentContentUrl).setParameter("range", position + "-" + rangeEnd).build();
            } else {
                return new URIBuilder(currentContentUrl).setParameter("range", position + "-" + (position + BUFFER_SIZE)).build();
            }
        } catch (URISyntaxException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    protected void seekHard(long position) throws IOException {
        close();
        this.position = position;
        this.rangeEnd = 0;
    }

    @Override
    public boolean canSeekHard() {
        return true;
    }

    @Override
    protected boolean useHeadersForRange() {
        return false;
    }

    private static boolean isAuthOr403Error(Throwable error) {
        Throwable t = error;
        int depth = 0;
        while (t != null && depth++ < 20) {
            String msg = t.getMessage();
            if (msg != null) {
                String lower = msg.toLowerCase();
                if (lower.contains("403") || lower.contains("401") || lower.contains("requires login")
                        || lower.contains("not a bot") || lower.contains("forbidden") || lower.contains("unauthorized")) {
                    return true;
                }
            }
            t = t.getCause();
        }
        return false;
    }
}
