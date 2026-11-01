package dev.lavalink.youtube.clients.skeleton;

import com.sedmelluq.discord.lavaplayer.tools.DataFormatTools;
import com.sedmelluq.discord.lavaplayer.tools.JsonBrowser;
import com.sedmelluq.discord.lavaplayer.tools.Units;
import org.jetbrains.annotations.NotNull;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpInterface;
import dev.lavalink.youtube.CannotBeLoaded;
import dev.lavalink.youtube.YoutubeAudioSourceManager;
import dev.lavalink.youtube.cipher.CipherManager.CachedPlayerScript;
import dev.lavalink.youtube.track.format.StreamFormat;
import dev.lavalink.youtube.track.format.TrackFormats;
import org.apache.http.entity.ContentType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static com.sedmelluq.discord.lavaplayer.tools.DataFormatTools.decodeUrlEncodedItems;
import static com.sedmelluq.discord.lavaplayer.tools.Units.CONTENT_LENGTH_UNKNOWN;

/**
 * This class is deprecated.
 * Extend the non-thumbnail counterpart and override the {@link Client#buildAudioTrack(YoutubeAudioSourceManager, JsonBrowser, String, String, long, String, boolean)}
 * method instead.
 */
public abstract class ThumbnailStreamingNonMusicClient extends ThumbnailNonMusicClient {
    private static final Logger log = LoggerFactory.getLogger(ThumbnailStreamingNonMusicClient.class);

    protected static String DEFAULT_SIGNATURE_KEY = "signature";

    @Override
    public TrackFormats loadFormats(@NotNull YoutubeAudioSourceManager source,
                                    @NotNull HttpInterface httpInterface,
                                    @NotNull String videoId) throws CannotBeLoaded, IOException {
        JsonBrowser json = loadTrackInfoFromInnertube(source, httpInterface, videoId, null, true);
        JsonBrowser playabilityStatus = json.get("playabilityStatus");
        JsonBrowser videoDetails = json.get("videoDetails");
        CachedPlayerScript playerScript = source.getCipherManager().getCachedPlayerScript(httpInterface);

        boolean isLive = videoDetails.get("isLive").asBoolean(false);

        if ("OK".equals(playabilityStatus.get("status").text()) && playabilityStatus.get("reason").safeText().contains("This live event has ended")) {
            // Long videos after ending of stream don't contain contentLength field as they
            // are still being processed by YouTube.
            isLive = true;
        }

        JsonBrowser streamingData = json.get("streamingData");
        JsonBrowser mergedFormats = streamingData.get("formats");
        JsonBrowser adaptiveFormats = streamingData.get("adaptiveFormats");

        String defaultAudioLanguage = videoDetails.get("defaultAudioLanguage").text();
        String originalTrackId = findOriginalAudioTrackId(adaptiveFormats, defaultAudioLanguage);

        if (originalTrackId != null) {
            log.info("Identified ORIGINAL audio track id for this video: '{}'", originalTrackId);
        }

        List<StreamFormat> formats = new ArrayList<>();
        boolean anyFailures = false;

        for (JsonBrowser merged : mergedFormats.values()) {
            anyFailures = anyFailures || !extractFormat(merged, formats, isLive, originalTrackId, defaultAudioLanguage);
        }

        for (JsonBrowser adaptive : adaptiveFormats.values()) {
            anyFailures = anyFailures || !extractFormat(adaptive, formats, isLive, originalTrackId, defaultAudioLanguage);
        }

        if (formats.isEmpty() && anyFailures) {
            log.warn("Loading formats either failed to load or were skipped due to missing fields, json: {}", streamingData.format());
        }

        return new TrackFormats(formats, playerScript.url);
    }

    protected boolean extractFormat(@NotNull JsonBrowser formatJson,
                                    @NotNull List<StreamFormat> formats,
                                    boolean isLive) {
        return extractFormat(formatJson, formats, isLive, null, null);
    }

    protected boolean extractFormat(@NotNull JsonBrowser formatJson,
                                    @NotNull List<StreamFormat> formats,
                                    boolean isLive,
                                    @Nullable String defaultAudioLanguage) {
        return extractFormat(formatJson, formats, isLive, null, defaultAudioLanguage);
    }

    protected boolean extractFormat(@NotNull JsonBrowser formatJson,
                                    @NotNull List<StreamFormat> formats,
                                    boolean isLive,
                                    @Nullable String originalTrackId,
                                    @Nullable String defaultAudioLanguage) {
        if (formatJson.isNull() || !formatJson.isMap()) {
            return false;
        }

        String url = formatJson.get("url").text();
        String cipher = formatJson.get("signatureCipher").text();

        Map<String, String> cipherInfo = cipher != null
            ? decodeUrlEncodedItems(cipher, true)
            : Collections.emptyMap();

        Map<String, String> urlMap = DataFormatTools.isNullOrEmpty(url)
            ? decodeUrlEncodedItems(cipherInfo.get("url"), false)
            : decodeUrlEncodedItems(url, false);

        try {
            long contentLength = formatJson.get("contentLength").asLong(CONTENT_LENGTH_UNKNOWN);

            if (contentLength == CONTENT_LENGTH_UNKNOWN && !isLive) {
                log.debug("Track is not a live stream, but no contentLength in format {}, skipping", formatJson.format());
                return true; // this isn't considered fatal.
            }

            formats.add(new StreamFormat(
                ContentType.parse(formatJson.get("mimeType").text()),
                (int) formatJson.get("itag").asLong(-1L),
                formatJson.get("bitrate").asLong(Units.BITRATE_UNKNOWN),
                contentLength,
                formatJson.get("audioChannels").asLong(2),
                cipherInfo.getOrDefault("url", url),
                urlMap.get("n"),
                cipherInfo.get("s"),
                cipherInfo.getOrDefault("sp", DEFAULT_SIGNATURE_KEY),
                resolveIsDefaultAudioTrack(formatJson.get("audioTrack"), originalTrackId, defaultAudioLanguage),
                formatJson.get("isDrc").asBoolean(false)
            ));

            return true;
        } catch (RuntimeException e) {
            log.debug("Failed to parse format {}, skipping", formatJson, e);
            return false;
        }
    }

    @Nullable
    protected static String findOriginalAudioTrackId(@NotNull JsonBrowser adaptiveFormats,
                                                     @Nullable String defaultAudioLanguage) {
        // 1. Look for explicit original markers in xtags, id, or displayName
        for (JsonBrowser format : adaptiveFormats.values()) {
            JsonBrowser audioTrack = format.get("audioTrack");
            if (audioTrack.isNull()) {
                continue;
            }

            String id = audioTrack.get("id").text();
            String displayName = audioTrack.get("displayName").text();
            String xtags = format.get("xtags").text();

            if (xtags != null) {
                String lowerXtags = xtags.toLowerCase();
                if (lowerXtags.contains("acont=original") || lowerXtags.contains("acont:original")) {
                    return id;
                }
            }

            if (id != null && id.toLowerCase().contains("original")) {
                return id;
            }

            if (displayName != null && isOriginalDisplayName(displayName)) {
                return id;
            }
        }

        // 2. Match with defaultAudioLanguage if available
        if (defaultAudioLanguage != null) {
            String lang = defaultAudioLanguage.toLowerCase();
            int hyphen = lang.indexOf('-');
            if (hyphen != -1) {
                lang = lang.substring(0, hyphen);
            }

            for (JsonBrowser format : adaptiveFormats.values()) {
                JsonBrowser audioTrack = format.get("audioTrack");
                if (audioTrack.isNull()) {
                    continue;
                }

                String id = audioTrack.get("id").text();
                if (id != null) {
                    String lowerId = id.toLowerCase();
                    if (lowerId.startsWith(lang) || lowerId.contains("." + lang) || lowerId.contains("-" + lang)) {
                        return id;
                    }
                }
            }
        }

        return null;
    }

    protected static boolean isOriginalDisplayName(@NotNull String displayName) {
        String lower = displayName.toLowerCase();
        return lower.contains("original") || lower.contains("d'origine") || lower.contains("originalton")
                || lower.contains("оригинал") || lower.contains("أصلي") || lower.contains("الأصلي")
                || lower.contains("صوت أصلي") || lower.contains("원본") || lower.contains("오리지널")
                || lower.contains("オリジナル") || lower.contains("原声") || lower.contains("原音")
                || lower.contains("原始") || lower.contains("orijinal") || lower.contains("originale");
    }

    protected static boolean isDubbedDisplayName(@NotNull String displayName) {
        String lower = displayName.toLowerCase();
        return lower.contains("dubbed") || lower.contains("auto-generated") || lower.contains("auto-dubbed")
                || lower.contains("translated") || lower.contains("doubl") || lower.contains("dobl")
                || lower.contains("synchronisiert") || lower.contains("дубл") || lower.contains("مدبلج")
                || lower.contains("دبلجة") || lower.contains("تلقائي") || lower.contains("تم إنشاؤه")
                || lower.contains("مترجم") || lower.contains("더빙") || lower.contains("吹き替え")
                || lower.contains("配音") || lower.contains("descriptive") || lower.contains("description");
    }

    protected static boolean resolveIsDefaultAudioTrack(@NotNull JsonBrowser audioTrack) {
        return resolveIsDefaultAudioTrack(audioTrack, null, null);
    }

    protected static boolean resolveIsDefaultAudioTrack(@NotNull JsonBrowser audioTrack,
                                                        @Nullable String defaultAudioLanguage) {
        return resolveIsDefaultAudioTrack(audioTrack, null, defaultAudioLanguage);
    }

    protected static boolean resolveIsDefaultAudioTrack(@NotNull JsonBrowser audioTrack,
                                                        @Nullable String originalTrackId,
                                                        @Nullable String defaultAudioLanguage) {
        if (audioTrack.isNull()) {
            // No audioTrack field -> single-audio-track video -> treat as default.
            return true;
        }

        String id          = audioTrack.get("id").text();
        String displayName = audioTrack.get("displayName").text();
        boolean youtubeFlag = audioTrack.get("audioIsDefault").asBoolean(true);

        // A. If an original audio track was identified for this video, STRICTLY enforce it
        if (originalTrackId != null && id != null) {
            if (id.equalsIgnoreCase(originalTrackId)) {
                log.info("Audio track '{}' (id='{}') -> MATCHES originalTrackId '{}' (default=true)",
                    displayName, id, originalTrackId);
                return true;
            } else {
                log.info("Audio track '{}' (id='{}') -> DUBBED differs from originalTrackId '{}' (default=false)",
                    displayName, id, originalTrackId);
                return false;
            }
        }

        // B. Fallback checks:
        // 1. Check audioTrack.id
        if (id != null) {
            String lowerId = id.toLowerCase();
            if (lowerId.contains("original")) {
                log.info("Audio track '{}' (id='{}') -> ORIGINAL via id (default=true)", displayName, id);
                return true;
            }
            if (lowerId.contains("dubbed") || lowerId.contains(".dub") || lowerId.contains("_dub")
                    || lowerId.contains("descriptive") || lowerId.contains("description")) {
                log.info("Audio track '{}' (id='{}') -> DUBBED via id (default=false)", displayName, id);
                return false;
            }
        }

        // 2. Check displayName (multi-language keywords)
        if (displayName != null) {
            if (isOriginalDisplayName(displayName)) {
                log.info("Audio track '{}' (id='{}') -> ORIGINAL via displayName (default=true)", displayName, id);
                return true;
            }
            if (isDubbedDisplayName(displayName)) {
                log.info("Audio track '{}' (id='{}') -> DUBBED via displayName (default=false)", displayName, id);
                return false;
            }
        }

        // 3. Check videoDetails.defaultAudioLanguage (creator's original recording language)
        if (defaultAudioLanguage != null && id != null) {
            String lang = defaultAudioLanguage.toLowerCase();
            int hyphen = lang.indexOf('-');
            if (hyphen != -1) {
                lang = lang.substring(0, hyphen);
            }
            String lowerId = id.toLowerCase();
            if (lowerId.startsWith(lang) || lowerId.contains("." + lang) || lowerId.contains("-" + lang)) {
                log.info("Audio track '{}' (id='{}') -> ORIGINAL matches defaultAudioLanguage '{}' (default=true)",
                    displayName, id, defaultAudioLanguage);
                return true;
            } else {
                log.info("Audio track '{}' (id='{}') -> DUBBED differs from defaultAudioLanguage '{}' (default=false)",
                    displayName, id, defaultAudioLanguage);
                return false;
            }
        }

        // 4. Fallback: respect YouTube's own flag
        log.info("Audio track '{}' (id='{}', youtubeFlag={}) -> using YouTube audioIsDefault flag",
            displayName, id, youtubeFlag);
        return youtubeFlag;
    }
}
