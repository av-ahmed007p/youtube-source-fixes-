package dev.lavalink.youtube.track;

import com.sedmelluq.discord.lavaplayer.container.matroska.MatroskaAudioTrack;
import com.sedmelluq.discord.lavaplayer.container.mpeg.MpegAudioTrack;
import com.sedmelluq.discord.lavaplayer.source.AudioSourceManager;
import com.sedmelluq.discord.lavaplayer.tools.ExceptionTools;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException.Severity;
import com.sedmelluq.discord.lavaplayer.tools.JsonBrowser;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpInterface;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import com.sedmelluq.discord.lavaplayer.track.DelegatedAudioTrack;
import com.sedmelluq.discord.lavaplayer.track.InternalAudioTrack;
import com.sedmelluq.discord.lavaplayer.track.playback.LocalAudioTrackExecutor;
import dev.lavalink.youtube.*;
import dev.lavalink.youtube.UrlTools.UrlInfo;
import dev.lavalink.youtube.cipher.ScriptExtractionException;
import dev.lavalink.youtube.clients.skeleton.Client;
import dev.lavalink.youtube.sabr.FormatId;
import dev.lavalink.youtube.sabr.SabrClientInfo;
import dev.lavalink.youtube.sabr.SabrStream;
import dev.lavalink.youtube.track.format.StreamFormat;
import dev.lavalink.youtube.track.format.TrackFormats;
import org.apache.http.Header;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

import static com.sedmelluq.discord.lavaplayer.container.Formats.MIME_AUDIO_WEBM;
import static com.sedmelluq.discord.lavaplayer.tools.DataFormatTools.decodeUrlEncodedItems;
import static com.sedmelluq.discord.lavaplayer.tools.Units.CONTENT_LENGTH_UNKNOWN;
import static dev.lavalink.youtube.http.YoutubeOauth2Handler.OAUTH_INJECT_CONTEXT_ATTRIBUTE;

/**
 * Audio track that handles processing Youtube videos as audio tracks.
 */
public class YoutubeAudioTrack extends DelegatedAudioTrack {
  private static final Logger log = LoggerFactory.getLogger(YoutubeAudioTrack.class);

  // This field is used to determine at what point should a stream be discarded.
  // If an error is thrown and the executor's position is larger than this number,
  // the stream URL will not be renewed.
  public static long BAD_STREAM_POSITION_THRESHOLD_MS = 3000;

  private final YoutubeAudioSourceManager sourceManager;

  /**
   * @param trackInfo Track info
   * @param sourceManager Source manager which was used to find this track
   */
  public YoutubeAudioTrack(@NotNull AudioTrackInfo trackInfo,
                           @NotNull YoutubeAudioSourceManager sourceManager) {
    super(trackInfo);
    this.sourceManager = sourceManager;
  }

  private long probeContentLength(HttpInterface httpInterface, URI url) {
    HttpGet request = new HttpGet(url);
    request.setHeader("Range", "bytes=0-0");

    try (CloseableHttpResponse response = httpInterface.execute(request)) {
      int status = response.getStatusLine().getStatusCode();
      Header header = response.getFirstHeader("Content-Range");

      if (status == 206 && header != null) {
        String value = header.getValue();
        int slash = value.lastIndexOf('/');

        if (slash != -1) {
          String length = value.substring(slash + 1);
          return Long.parseLong(length);
        }
      }
    } catch (Exception e) {
      log.debug("Failed to probe content length for {}", url, e);
    }

    return CONTENT_LENGTH_UNKNOWN;
  }

  @Override
  public void process(LocalAudioTrackExecutor localExecutor) throws Exception {
    List<Client> clients = sourceManager.getClientHealthManager().getOrderedPlaybackClients();

    if (clients.isEmpty()) {
      throw new FriendlyException("This video cannot be played", Severity.COMMON,
          new RuntimeException("None of the registered clients supports loading of formats"));
    }

    try (HttpInterface httpInterface = sourceManager.getInterface()) {
      setupUserDataOAuth(httpInterface);

      long resumePositionMs = 0;
      int resumeAttempts = 0;
      final int MAX_RESUME_ATTEMPTS = 5;
      List<ClientException> exceptions = new ArrayList<>();

      while (resumeAttempts < MAX_RESUME_ATTEMPTS) {
        long currentPos = localExecutor.getPosition();
        if (currentPos > resumePositionMs) {
          resumePositionMs = currentPos;
        }

        boolean playedAnyFrames = resumePositionMs > 0;

        for (Client client : clients) {
          httpInterface.getContext().setAttribute(Client.OAUTH_CLIENT_ATTRIBUTE, client.supportsOAuth());

          try {
            processWithClient(localExecutor, httpInterface, client, resumePositionMs);
            sourceManager.getClientHealthManager().onSuccess(client);
            return;
          } catch (CannotBeLoaded e) {
            if (!playedAnyFrames) {
              sourceManager.getClientHealthManager().onFailure(client, e);
            }
            throw e;
          } catch (Exception e) {
            long newPos = localExecutor.getPosition();
            if (newPos > resumePositionMs) {
              resumePositionMs = newPos;
              playedAnyFrames = true;
            }

            if (!playedAnyFrames) {
              sourceManager.getClientHealthManager().onFailure(client, e);
              log.debug("Client \"{}\" failed during initial loading, proceeding", client.getIdentifier(), e);
            } else {
              log.warn("Playback interrupted at {}ms with client \"{}\" ({}), attempting seamless resumption",
                  resumePositionMs, client.getIdentifier(), e.getMessage());
            }

            exceptions.add(new ClientException(e.getMessage(), client, e));

            if (playedAnyFrames) {
              // We were actively playing! Break client loop to restart from the healthiest client with resumePositionMs
              break;
            }
          }
        }

        if (!playedAnyFrames && !exceptions.isEmpty()) {
          // Never managed to play any audio on any client -> genuinely unavailable
          throw new AllClientsFailedException(exceptions);
        }

        resumeAttempts++;
        try {
          Thread.sleep(150L * resumeAttempts);
        } catch (InterruptedException ie) {
          Thread.currentThread().interrupt();
          break;
        }
      }

      if (!exceptions.isEmpty()) {
        throw new AllClientsFailedException(exceptions);
      }
    } catch (CannotBeLoaded e) {
      throw ExceptionTools.wrapUnfriendlyExceptions("This video is unavailable", Severity.SUSPICIOUS, e.getCause());
    }
  }

  private void setupUserDataOAuth(HttpInterface httpInterface) {
    try {
      Object userData = getUserData();
      if (userData != null) {
        JsonBrowser jsonUserData = JsonBrowser.parse(userData.toString());
        if (!jsonUserData.get("oauth-token").isNull()) {
          httpInterface.getContext().setAttribute(OAUTH_INJECT_CONTEXT_ATTRIBUTE, jsonUserData.get("oauth-token").text());
        }
      }
    } catch (IOException e) {
      log.debug("Failed to parse token from userData", e);
    }
  }

  private void processWithClient(LocalAudioTrackExecutor localExecutor,
                                 HttpInterface httpInterface,
                                 Client client,
                                 long resumePositionMs) throws CannotBeLoaded, Exception {
    TrackFormats formats = client.loadFormats(sourceManager, httpInterface, getIdentifier());

    if (formats == null) {
      throw new FriendlyException("This video cannot be played", Severity.SUSPICIOUS, null);
    }

    StreamFormat format = formats.getBestFormat();

    if (format == null) {
      throw new FriendlyException("No supported format found", Severity.SUSPICIOUS, null);
    }

    if (format.isSabr()) {
      processSabr(localExecutor, httpInterface, client, formats, format, resumePositionMs);
      return;
    }

    FormatWithUrl augmentedFormat = augmentFormatWithUrl(httpInterface, client, formats, format);
    log.debug("Starting track with URL from client {}: {}", client.getIdentifier(), augmentedFormat.signedUrl);

    String query = augmentedFormat.signedUrl.getRawQuery();
    boolean isLegacyFormat = query != null && query.contains("itag=18");
    boolean isStream = trackInfo.isStream || (!isLegacyFormat && augmentedFormat.format.getContentLength() == CONTENT_LENGTH_UNKNOWN);

    long contentLength = augmentedFormat.format.getContentLength();

    if (!trackInfo.isStream && contentLength == CONTENT_LENGTH_UNKNOWN) {
      contentLength = probeContentLength(httpInterface, augmentedFormat.signedUrl);
    }

    if (isStream) {
      processStream(localExecutor, httpInterface, augmentedFormat);
    } else {
      processStatic(localExecutor, httpInterface, client, formats, augmentedFormat, resumePositionMs, contentLength);
    }
  }

  private void processSabr(LocalAudioTrackExecutor localExecutor,
                           HttpInterface httpInterface,
                           Client client,
                           TrackFormats formats,
                           StreamFormat format,
                           long resumePositionMs) throws Exception {
    SabrClientInfo clientInfo = client.getSabrClientInfo(httpInterface);
    String serverAbrStreamingUrl = formats.getServerAbrStreamingUrl();
    String ustreamerConfig = formats.getVideoPlaybackUstreamerConfig();

    if (clientInfo == null || serverAbrStreamingUrl == null || ustreamerConfig == null) {
      throw new FriendlyException("This video cannot be played", Severity.COMMON,
          new RuntimeException("Client " + client.getIdentifier() + " does not support SABR playback " +
              "(missing client info, streaming URL or ustreamer config)"));
    }

    URI resolvedUrl = resolveSabrUrl(httpInterface, client, formats, format, serverAbrStreamingUrl);
    byte[] ustreamerBytes = decodeBase64(ustreamerConfig);
    byte[] poTokenBytes = formats.getPoToken() != null ? decodeBase64(formats.getPoToken()) : null;
    FormatId formatId = new FormatId(format.getItag(), format.getLastModified(), format.getXtags());

    log.debug("Starting SABR track from client {} (itag {}): {}", client.getIdentifier(), format.getItag(), resolvedUrl);

    try (SabrStream stream = new SabrStream(httpInterface, resolvedUrl, ustreamerBytes, poTokenBytes,
        clientInfo, formatId, format.isDrc(), format.getContentLength(), trackInfo.length)) {
      if (resumePositionMs > 0) {
        log.info("Seamlessly resuming SABR playback from {}ms", resumePositionMs);
        localExecutor.setPosition(resumePositionMs);
      }
      if (format.getType().getMimeType().endsWith("/webm")) {
        processDelegate(new MatroskaAudioTrack(trackInfo, stream), localExecutor);
      } else {
        processDelegate(new MpegAudioTrack(trackInfo, stream), localExecutor);
      }
    }
  }

  @NotNull
  private URI resolveSabrUrl(HttpInterface httpInterface,
                             Client client,
                             TrackFormats formats,
                             StreamFormat format,
                             String serverAbrStreamingUrl) throws IOException, URISyntaxException {
    if (!client.requirePlayerScript()) {
      return new URI(serverAbrStreamingUrl);
    }

    UrlTools.UrlInfo urlInfo = UrlTools.getUrlInfo(serverAbrStreamingUrl, true);
    String nParameter = urlInfo.parameters.get("n");

    if (nParameter == null) {
      return new URI(serverAbrStreamingUrl);
    }

    // Reuse the cipher manager's throttling ('n') transform by wrapping the ABR URL in a synthetic format.
    StreamFormat synthetic = new StreamFormat(format.getType(), format.getItag(), 0, 0, 0,
        serverAbrStreamingUrl, nParameter, null, "signature", true, false);
    return sourceManager.getCipherManager().resolveFormatUrl(httpInterface, formats.getPlayerScriptUrl(), synthetic);
  }

  private static byte[] decodeBase64(@NotNull String input) {
    // Normalise URL-safe base64 to standard base64 and pad, so both encodings are accepted.
    String normalized = input.replace('-', '+').replace('_', '/');

    switch (normalized.length() % 4) {
      case 2:
        normalized += "==";
        break;
      case 3:
        normalized += "=";
        break;
      default:
        break;
    }

    return Base64.getDecoder().decode(normalized);
  }

  private void processStatic(LocalAudioTrackExecutor localExecutor,
                             HttpInterface httpInterface,
                             Client client,
                             TrackFormats formats,
                             FormatWithUrl augmentedFormat,
                             long resumePositionMs,
                             long contentLength) throws Exception {
    YoutubePersistentHttpStream.UrlRefresher urlRefresher =
        createUrlRefresher(httpInterface, client, augmentedFormat.format, augmentedFormat);

    YoutubePersistentHttpStream stream = null;

    try {
      stream = new YoutubePersistentHttpStream(httpInterface, augmentedFormat.signedUrl, contentLength, urlRefresher);

      InternalAudioTrack delegateTrack;
      if (augmentedFormat.format.getType().getMimeType().endsWith("/webm")) {
        delegateTrack = new MatroskaAudioTrack(trackInfo, stream);
      } else {
        delegateTrack = new MpegAudioTrack(trackInfo, stream);
      }

      if (resumePositionMs > 0) {
        log.info("Seamlessly resuming playback from {}ms (preventing song restart)", resumePositionMs);
        localExecutor.setPosition(resumePositionMs);
      }

      processDelegate(delegateTrack, localExecutor);
    } finally {
      if (stream != null) {
        try {
          stream.close();
        } catch (Exception ignored) {
        }
      }
    }
  }

  private YoutubePersistentHttpStream.UrlRefresher createUrlRefresher(HttpInterface httpInterface,
                                                                      Client initialClient,
                                                                      StreamFormat initialFormat,
                                                                      FormatWithUrl initialAugmentedFormat) {
    AtomicReference<FormatWithUrl> currentAugmented = new AtomicReference<>(initialAugmentedFormat);

    return () -> {
      FormatWithUrl current = currentAugmented.get();

      // 1. First fast fallback: alternative CDN host (mn replacement in URL)
      if (current != null) {
        FormatWithUrl cdnFallback = current.getFallback();
        if (cdnFallback != null) {
          log.info("Switching to alternative CDN host: {}", cdnFallback.signedUrl);
          currentAugmented.set(cdnFallback);
          return cdnFallback.signedUrl;
        }
      }

      // 2. Request fresh formats and streaming URL from current client
      try {
        log.info("Requesting fresh streaming URL for track {} with client {}",
            getIdentifier(), initialClient.getIdentifier());
        TrackFormats freshFormats = initialClient.loadFormats(sourceManager, httpInterface, getIdentifier());
        if (freshFormats != null) {
          StreamFormat matching = findMatchingFormat(freshFormats, initialFormat);
          if (matching != null) {
            FormatWithUrl refreshed = augmentFormatWithUrl(httpInterface, initialClient, freshFormats, matching);
            currentAugmented.set(refreshed);
            log.info("Successfully refreshed streaming URL using client {}", initialClient.getIdentifier());
            return refreshed.signedUrl;
          }
        }
      } catch (Throwable e) {
        log.warn("Current client {} failed to refresh format URL: {}", initialClient.getIdentifier(), e.getMessage());
      }

      // 3. Fallback: try other registered playback clients
      List<Client> playbackClients = sourceManager.getClientHealthManager().getOrderedPlaybackClients();
      for (Client fallbackClient : playbackClients) {
        if (fallbackClient == initialClient) continue;
        try {
          TrackFormats freshFormats = fallbackClient.loadFormats(sourceManager, httpInterface, getIdentifier());
          if (freshFormats != null) {
            StreamFormat matching = findMatchingFormat(freshFormats, initialFormat);
            if (matching != null) {
              FormatWithUrl refreshed = augmentFormatWithUrl(httpInterface, fallbackClient, freshFormats, matching);
              currentAugmented.set(refreshed);
              log.info("Successfully refreshed streaming URL using fallback client {}", fallbackClient.getIdentifier());
              return refreshed.signedUrl;
            }
          }
        } catch (Throwable e) {
          log.debug("Fallback client {} failed to refresh format: {}", fallbackClient.getIdentifier(), e.getMessage());
        }
      }

      return null;
    };
  }

  @Nullable
  private StreamFormat findMatchingFormat(TrackFormats formats, StreamFormat target) {
    for (StreamFormat f : formats.getFormats()) {
      if (f.getItag() == target.getItag()) {
        return f;
      }
    }
    for (StreamFormat f : formats.getFormats()) {
      if (f.getType().getMimeType().equals(target.getType().getMimeType())) {
        return f;
      }
    }
    return formats.getBestFormat();
  }

  private void processStream(LocalAudioTrackExecutor localExecutor,
                             HttpInterface httpInterface,
                             FormatWithUrl augmentedFormat) throws Exception {
    if (MIME_AUDIO_WEBM.equals(augmentedFormat.format.getType().getMimeType())) {
      throw new FriendlyException("YouTube WebM streams are currently not supported.", Severity.COMMON, null);
    }

    processDelegate(new YoutubeMpegStreamAudioTrack(trackInfo, httpInterface, augmentedFormat.signedUrl), localExecutor);
  }

  @NotNull
  private FormatWithUrl augmentFormatWithUrl(@NotNull HttpInterface httpInterface,
                                             @NotNull Client client,
                                             @NotNull TrackFormats formats,
                                             @NotNull StreamFormat format) throws CannotBeLoaded, Exception {
    if (!client.supportsFormatLoading()) {
      throw new RuntimeException(client.getIdentifier() + " does not support loading of formats!");
    }

    URI resolvedUrl = format.getUrl();
    if (client.requirePlayerScript()) {
      resolvedUrl = sourceManager.getCipherManager()
              .resolveFormatUrl(httpInterface, formats.getPlayerScriptUrl(), format);
      resolvedUrl = client.transformPlaybackUri(format.getUrl(), resolvedUrl, formats.getPoToken());
    }

    return new FormatWithUrl(format, resolvedUrl);
  }

  @Override
  protected AudioTrack makeShallowClone() {
    return new YoutubeAudioTrack(trackInfo, sourceManager);
  }

  @Override
  public AudioSourceManager getSourceManager() {
    return sourceManager;
  }

  @Override
  public boolean isSeekable() {
    return true;
  }

  private static class FormatWithUrl {
    private final StreamFormat format;
    private final URI signedUrl;

    private FormatWithUrl(@NotNull StreamFormat format,
                          @NotNull URI signedUrl) {
      this.format = format;
      this.signedUrl = signedUrl;
    }

    public boolean isExpired() {
      UrlInfo urlInfo = UrlTools.getUrlInfo(signedUrl.toString(), true);
      String expire = urlInfo.parameters.get("expire");

      if (expire == null) {
        return false;
      }

      long expiresAbsMillis = Long.parseLong(expire) * 1000;
      return System.currentTimeMillis() >= expiresAbsMillis;
    }

    @Nullable
    public FormatWithUrl getFallback() {
      String signedString = signedUrl.toString();
      Map<String, String> urlParameters = decodeUrlEncodedItems(signedString, false);

      String mn = urlParameters.get("mn");

      if (mn == null) {
        return null;
      }

      String[] hosts = mn.split(",");

      if (hosts.length < 2) {
        log.warn("Cannot fallback, available hosts: {}", String.join(", ", hosts));
        return null;
      }

      String newUrl = signedString.replaceFirst(hosts[0], hosts[1]);

      try {
        URI uri = new URI(newUrl);
        return new FormatWithUrl(format, uri);
      } catch (URISyntaxException e) {
        return null;
      }
    }
  }
}
