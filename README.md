# youtube-source
A rewritten YouTube source manager for Lavaplayer.

This source aims to provide robustness by leveraging multiple InnerTube clients
for requests. Where one client fails, another will try to load the request.
Which clients are used is entirely configurable.

## Table of Contents
- [Common](#common)
  - Information about the `common` module and usage of.
- [V2](#v2)
  - Information about the `v2` module and usage of.
- [Plugin](#plugin)
  - Information about the `plugin` module and usage of.
- [Available Clients](#available-clients)
  - Information about the clients provided by `youtube-source`, as well as their advantages/disadvantages.
- [Using OAuth tokens](#using-oauth-tokens)
  - Information on using OAuth tokens with `youtube-source`.
- [Using a remote webpo generator](#using-a-remote-webpo-generator)
  - Information on using a remote service to generate `poToken` values.
- [Using a remote cipher server](#using-a-remote-cipher-server)
  - Information on using a remote cipher server with `youtube-source`.
- [REST Routes (`plugin` only)](#rest-routes-plugin-only)
  - Information on the REST routes provided by the `youtube-source` plugin module.
- [Stability Improvements & Fixes](#stability-improvements--fixes)
  - Seamless resumption, self-healing streams, SABR fixes, and anti-dubbing enforcement.
- [Migration Information](#migration-from-lavaplayers-built-in-youtube-source)
  - Information on migrating from Lavaplayer's built-in Youtube source manager.
- [Additional Support](#additional-support)
  - For everything else.

## common
This module provides the base source manager, which can be used with any
`com.sedmelluq.discord.lavaplayer` packages still on major version `1`.

<details>
<summary>Using in Gradle:</summary>

```kotlin
repositories {
  // replace with https://maven.lavalink.dev/snapshots if you want to use a snapshot version.
  maven(url = "https://maven.lavalink.dev/releases")
}

dependencies {
  // Replace VERSION with the current version as shown by the Releases tab or a long commit hash `-SNAPSHOT` for snapshots.
  implementation("dev.lavalink.youtube:common:VERSION")
}
```

</details>
Example usage:

```java
YoutubeAudioSourceManager youtube = new YoutubeAudioSourceManager();
// Optionally, you may instantiate the source with a custom options, such as toggling use of searching, and clients.
YoutubeAudioSourceManager youtube = new YoutubeAudioSourceManager(/*allowSearch:*/ true, new Client[] { new Music(), new Web(), new AndroidTestsuite() });
```

You may also extend the `Client` interface to support additional InnerTube clients. There are a few abstract classes to
make this easier, notably, `MusicClient` (for `music.youtube.com` InnerTube clients), `NonMusicClient` (for youtube.com
innertube clients) and `StreamingNonMusicClient` (for clients that can be used to stream videos).

Support for IP rotation has been included, and can be achieved using the following:
```java
AbstractRoutePlanner routePlanner = new ...
YoutubeIpRotatorSetup rotator = new YoutubeIpRotatorSetup(routePlanner);

// 'youtube' is the variable holding your YoutubeAudioSourceManager instance.
rotator.forConfiguration(youtube.getHttpInterfaceManager(), false)
    .withMainDelegateFilter(youtube.getContextFilter()) // IMPORTANT
    .setup();
```

## v2
This modules expands on `common` by providing additional support for
Lavaplayer `2.x` clients, such as [Lavalink-Devs/Lavaplayer](https://github.com/lavalink-devs/lavaplayer).
Such features currently include thumbnail support within `AudioTrackInfo`.
Additional clients are included that provide access to this additional information.
These clients are suffixed with `Thumbnail`, such as `WebWithThumbnail`, `AndroidWithThumbnail` etc.

<details>
<summary>Using in Gradle:</summary>

```kotlin
repositories {
  // replace with https://maven.lavalink.dev/snapshots if you want to use a snapshot version.
  maven(url = "https://maven.lavalink.dev/releases")
}

dependencies {
  // Replace VERSION with the current version as shown by the Releases tab or a long commit hash `-SNAPSHOT` for snapshots.
  implementation("dev.lavalink.youtube:v2:VERSION")
}
```

</details>

Example usage:
```java
// same as the 'common' module but there are additional clients that provide video thumbnails in the returned metadata.
YoutubeAudioSourceManager youtube = new YoutubeAudioSourceManager(/*allowSearch:*/ true, new Client[] { new MusicWithThumbnail(), new WebWithThumbnail(), new AndroidTestsuiteWithThumbnail() });
```

## plugin
This module serves as the plugin for use with [Lavalink](https://github.com/lavalink-devs/Lavalink).

To use this plugin with Lavalink, you must declare the dependency.

<details>
<summary>Using with Lavalink v3 / v4 (JitPack):</summary>

```yaml
lavalink:
  plugins:
    - dependency: "com.github.av-ahmed007p.youtube-source-fixes-:youtube-plugin:v1.0.0"
      repository: "https://jitpack.io"
```

</details>

<details>
<summary>Direct Download (Manual / Pterodactyl):</summary>

Download the compiled JAR from [GitHub Releases](https://github.com/av-ahmed007p/youtube-source-fixes-/releases/latest) and place it in the `plugins/` directory:
- Direct link: `https://github.com/av-ahmed007p/youtube-source-fixes-/releases/latest/download/youtube-plugin.jar`

</details>


Configuring the plugin:
> [!IMPORTANT]
> You must make sure to disable the built-in YouTube source like so:
```yaml
lavalink:
  server:
    sources:
      youtube: false
```

> [!NOTE]
> Existing options, such as `ratelimit` and `youtubePlaylistLoadLimit` will be picked up automatically by the plugin,
> so these don't need changing.
> 
```yaml
plugins:
  youtube:
    enabled: true # Whether this source can be used.
    allowSearch: true # Whether "ytsearch:" and "ytmsearch:" can be used.
    allowDirectVideoIds: true # Whether just video IDs can match. If false, only complete URLs will be loaded.
    allowDirectPlaylistIds: true # Whether just playlist IDs can match. If false, only complete URLs will be loaded.
    # The clients to use for track loading. See below for a list of valid clients.
    # Clients are queried in the order they are given (so the first client is queried first and so on...)
    clients:
      - MUSIC
      - ANDROID_VR
      - WEB
      - WEBEMBEDDED 
```

### Advanced Options
```yaml
    # The below section of the config allows setting specific options for each client, such as the requests they will handle.
    # If an option, or client, is unspecified, then the default option value/client values will be used instead.
    # If a client is configured, but is not registered above, the options for that client will be ignored.
    # WARNING!: THE BELOW CONFIG IS FOR ILLUSTRATION PURPOSES. DO NOT COPY OR USE THIS WITHOUT
    # WARNING!: UNDERSTANDING WHAT IT DOES. MISCONFIGURATION WILL HINDER YOUTUBE-SOURCE'S ABILITY TO WORK PROPERLY.

    # Write the names of clients as they are specified under the heading "Available Clients".
    clientOptions:
      WEB:
        # Example: Disabling a client's playback capabilities.
        playback: false
        videoLoading: false # Disables loading of videos for this client. A client may still be used for playback even if this is set to 'false'.
      WEBEMBEDDED:
        # Example: Configuring a client to exclusively be used for video loading and playback.
        playlistLoading: false # Disables loading of playlists and mixes.
        searching: false # Disables the ability to search for videos.
```

## Available Clients
Currently, the following clients are available for use:

| Identifier        | Opus Formats | OAuth/Pot | Age-restriction Support | Playback Support | Metadata Support              | Additional Notes                                     |
|-------------------|--------------|-----------|-------------------------|------------------|-------------------------------|------------------------------------------------------|
| `MUSIC`           | No           | Pot       | No                      | Yes              | Search                        | YouTube music search support via `ytmsearch:` prefix |
| `WEB`             | Yes          | Pot       | No                      | Yes + Livestream | Video, Search, Playlist, Mix  |                                                      |
| `MWEB`            | Yes          | Pot       | Yes                     | Yes + Livestream | Video, Search, Playlist, Mix  |                                                      |
| `WEBEMBEDDED`     | Yes          | Pot       | Limited                 | Yes + Livestream | Video                         |                                                      |
| `ANDROID`         | Yes          | No        | No                      | Yes + Livestream | Video, Search, Playlist, Mix  | Heavily restricted, frequently dysfunctional         |
| `ANDROID_MUSIC`   | Yes          | No        | No                      | Yes              | Video, Search, Mix            |                                                      |
| `ANDROID_VR`      | Yes          | No        | No                      | Yes + Livestream | Video, Search, Playlist, Mix  |                                                      |
| `IOS`             | No           | No        | No                      | Yes + Livestream | Video, Search, Playlist, Mix  |                                                      |
| `TV`              | Yes          | Yes       | With OAuth              | Yes + Livestream | Video                         | Prefers signin                                       |
| `TVHTML5_SIMPLY`  | Yes          | Pot       | No                      | Yes + Livestream | Video, Search, Mix            |                                                      |

> [!NOTE]
> Clients that do not return Opus formats will require transcoding.
> Livestreams do not yield Opus formats so will always require transcoding.


## Using OAuth Tokens
You may notice that some requests are flagged by YouTube, causing an error message asking you to sign in to confirm you're not a bot.
With OAuth integration, you can request that `youtube-source` use your account credentials to appear as a normal user, with varying degrees
of efficacy. **You do _not_ need to use `poToken` with OAuth.**

> [!WARNING]
> Similar to the `poToken` method, this is NOT a silver bullet solution, and worst case could get your account terminated!
> For this reason, it is advised that **you use burner accounts and NOT your primary!**
> This method may also trigger ratelimit errors if used in a high traffic environment.
> USE WITH CAUTION!

> [!NOTE]
> You may need to set your log level for `dev.lavalink.youtube.http.YoutubeOauth2Handler` to `INFO`, to see additional information
> within your terminal regarding completing the OAuth flow.

> [!NOTE]
> If you do not have a refresh token, then do not supply one. The source will output your refresh token into your terminal upon
> successfully completing the OAuth flow at least **once**. If you do not see your token, you may need to configure your
> logging (see above note).

You can instruct `youtube-source` to use OAuth with the following:

### Lavaplayer
```java
YoutubeAudioSourceManager source = new YoutubeAudioSourceManager();
// This will trigger an OAuth flow, where you will be instructed to head to YouTube's OAuth page and input a code.
// This is safe, as it only uses YouTube's official OAuth flow. No tokens are seen or stored by us.
source.useOauth2(null, false);

// If you already have a refresh token, you can instruct the source to use it, skipping the OAuth flow entirely.
// You can also set the `skipInitialization` parameter, which skips the OAuth flow. This should only be used
// if you intend to supply a refresh token later on. You **must** either complete the OAuth flow or supply
// a refresh token for OAuth integration to work.
source.useOauth2("your refresh token", true);
```

### Lavalink
```yaml
plugins:
  youtube:
    enabled: true
    oauth:
      # setting "enabled: true" is the bare minimum to get OAuth working.
      enabled: true

      # if you have a refresh token, you may set it below (make sure to uncomment the line to apply it).
      # setting a valid refresh token will skip the OAuth flow entirely. See above note on how to retrieve
      # your refreshToken.
      # refreshToken: "paste your refresh token here if applicable"

      # Set this if you don't want the OAuth flow to be triggered, if you intend to supply a refresh token later.
      # Initialization is skipped automatically if a valid refresh token is supplied. Leave this commented if you're
      # completing the OAuth flow for the first time/do not have a refresh token.
      # skipInitialization: true
```

### Passing an oauth token from your client
Another option to use oauth is by using oauth access tokens that are managed from your client. In this case your 
bot/client provides LavaLink with the token to use when playing a track. To do this simply add the oauth access token 
to a track's [userData](https://lavalink.dev/api/rest#track) field in a json format when updating the player to 
play a track like:
```json
{
  "oauth-token": "access token to use"
}
```

## Using a remote webpo generator

This service mints a content-bound PoToken for `WEB`, `MWEB`, and `MUSIC`,
a visitor data-bound poToken for `TVHTML5_SIMPLY` and `WEB_EMBEDDED_PLAYER`, and a `visitor data-bound`/`living-room-potoken-id` poToken for `TVHTML5`. It generates the poToken (Proof of Origin Token) by solving the `botguard attestation` challenge, in several ways.
Use [webpo-generator](https://github.com/ashton045/webpo-generator), a Node.js REST API to mint the tokens to their respective innertube client's playback.
Check out the repository for more information on how to set it up.

If you want to implement your own, you can follow the [webpo-generator API specification](https://github.com/ashton045/webpo-generator#endpoints).

### Lavaplayer
```java
YoutubeSourceOptions options = new YoutubeSourceOptions()
    .setRemotePoToken("http://localhost:8080", "can_you_pass_it");
YoutubeAudioSourceManager sourceManager = new YoutubeAudioSourceManager(options, ...);
```

### Lavalink
```yaml
plugins:
  youtube:
    remotePot:
      url: "http://localhost:8080"
      pass: "can_you_pass_it" # optional
```

## Using a remote cipher server

It becomes harder and harder to keep up with YouTube's cipher changes, as they become more frequent and complex.
To help with this, you can use a remote cipher server to handle signature deciphering for you.
You can use [yt-cipher](https://github.com/kikkia/yt-cipher), which is a simple Deno server that exposes a REST API for deciphering signatures.
Check out the repository for more information on how to set it up.

If you want to implement your own, you can follow the [yt-cipher API specification](https://github.com/kikkia/yt-cipher#api-specification).

### Lavaplayer
```java
YoutubeSourceOptions options = new YoutubeSourceOptions()
     // The base URL of your remote cipher server & the password to authenticate with your remote cipher server, along with an identifier for metrics.
    .setRemoteCipher("http://localhost:8001", "your_secret_password", "user agent");
YoutubeAudioSourceManager sourceManager = new YoutubeAudioSourceManager(options, ...);
```

### Lavalink
```yaml
plugins:
  youtube:
    remoteCipher:
      url: "http://localhost:8001" # The base URL of your remote cipher server.
      password: "your_secret_password" # The password to authenticate with your remote cipher server.
      userAgent: "your_service_name" # Optional user-agent header, used for metrics on the backend. 
```

## REST routes (`plugin` only)
### `POST` `/youtube`

Body:

> [!NOTE]
> You do not need to provide everything as it is shown.
> For example, you can specify just `refreshToken` and `skipInitialization`.

```json
{
  "refreshToken": "your new refresh token",
  "skipInitialization": true
}
```

Response:

If the YouTube source is not enabled, or the `refreshToken` is invalid:
`500 - Internal Server Error`

Otherwise:
`204 - No Content`

### `GET` `/youtube`

Response:

If the YouTube source is not enabled:
`500 - Internal Server Error`

Otherwise:
```json
{
  "refreshToken": "your current refresh token, or null"
}
```

### `GET` `/youtube/stream/{videoId}`

Query parameters:

| Key          | Value Type | Required | Notes                                                                                                                                                                       |
|--------------|------------|----------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| itag         | integer    | No       | The [itag](https://gist.github.com/AgentOak/34d47c65b1d28829bb17c24c04a0096f) of the desired format. If unspecified, youtube-source's default format selector will be used. |
| withClient   | string     | No       | The identifier of the client to use for streaming. Uses all clients if unspecified.                                                                                         |

Response:

If `videoId` could not be found or loaded, or the `itag` does not exist, or if no client supports format loading:
`400 - Bad Request`

Otherwise:
`200 - OK` accompanied by the selected format stream (audio or video). `Content-Type` header will be set appropriately.

### `GET` `/youtube/oauth/{refreshToken}`

Response:

If the `refreshToken` is invalid, expired, or cannot be processed:
`500 - Internal Server Error`

If the refresh process succeeds and a new access token is generated:
`200 - OK` accompanied by the new access token in JSON format.

Example response:
```json
{
  "access_token": "AccessToken",
  "expires_in": 69420,
  "scope": "used scope",
  "token_type": "type"
}
```



---

## Stability Improvements & Fixes

This section documents internal stability enhancements and bug fixes applied to `youtube-source` to improve playback resilience, especially in long-running or high-traffic Lavalink deployments.

### 1. Seamless Mid-Stream Resumption

**Problem:** When YouTube returned a transient decoding error mid-playback (e.g. `Something went wrong when decoding the track` at ~50 seconds), the track would restart from the beginning instead of resuming from where it stopped.

**Fix:** `YoutubeAudioTrack` now captures the current playback position (`localExecutor.getPosition()`) before any retry attempt. On resumption, `localExecutor.setPosition(...)` is called with the saved offset, allowing playback to continue seamlessly from where it was interrupted — with no audible gap or restart.

**Affected file:** [`YoutubeAudioTrack.java`](common/src/main/java/dev/lavalink/youtube/track/YoutubeAudioTrack.java)

---

### 2. Self-Healing Persistent HTTP Streams

**Problem:** Mid-stream 403 (Forbidden) or 401 (Unauthorized) errors from YouTube's CDN would cause chunk reads to silently fail or throw exceptions that propagated as decoder errors, cutting off playback.

**Fix:** `YoutubePersistentHttpStream` now:
- Detects HTTP 403/401 error responses **inline** during chunk reads (including via cause-chain inspection for errors wrapped by Matroska/MPEG decoders).
- Invokes a `UrlRefresher` callback to obtain a fresh stream URL and CDN fallback at the **exact byte offset** that failed.
- Transparently retries the read with the new URL — no interruption to the audio pipeline.

**Affected file:** [`YoutubePersistentHttpStream.java`](common/src/main/java/dev/lavalink/youtube/track/YoutubePersistentHttpStream.java)

---

### 3. Direct Stream Prioritization over SABR

**Problem:** YouTube's SABR (Server-Adaptive Bitrate) streaming protocol reported incorrect/short durations and caused early stream termination around the 50-second mark, leading to abrupt track cutoffs even on healthy connections.

**Fix applied in three places:**

- **`TrackFormats.java`** — `isBetterFormat()` now strongly prefers non-SABR (direct progressive) formats when both are available, preventing accidental SABR selection.
- **`StreamingNonMusicClient.java`** — `preferSabrPlayback()` returns `false` by default for all streaming non-music clients.
- **`Tv.java`** — `preferSabrPlayback()` returns `false` to disable SABR on the `TV` client specifically.

**Affected files:**
- [`TrackFormats.java`](common/src/main/java/dev/lavalink/youtube/track/format/TrackFormats.java)
- [`StreamingNonMusicClient.java`](common/src/main/java/dev/lavalink/youtube/clients/skeleton/StreamingNonMusicClient.java)
- [`Tv.java`](common/src/main/java/dev/lavalink/youtube/clients/Tv.java)

---

### 4. SABR Stream Stability Fixes

**Problem:** `SabrStream` reported incorrect player time and had premature finish conditions that caused the streaming session to end before the audio was fully delivered.

**Fix:** Corrected duration calculation logic and player time reporting inside `SabrStream` to accurately reflect remaining content, preventing premature stream termination.

**Affected file:** [`SabrStream.java`](common/src/main/java/dev/lavalink/youtube/sabr/SabrStream.java)

### 5. Smart Multi-Client Fallback & "All Clients Failed" Fix

**Problem:** When a stream URL expired or a CDN returned a permanent 403 error mid-playback, the player threw an `AllClientsFailedException` (falsely reporting that all registered clients failed), stopping the track entirely — even though other healthy clients were available.

**Fix:** A three-tier **URL Refresh** strategy (`UrlRefresher`) was added inside `YoutubeAudioTrack`, applied automatically whenever a chunk read fails:

| Tier | Strategy | Description |
|------|----------|-------------|
| **1** | CDN Fallback | Switches to an alternative CDN host using the `mn` parameter already embedded in the signed URL — instant, zero-cost |
| **2** | Re-fetch from Same Client | Calls `client.loadFormats(...)` again to get a brand new signed URL at the same itag, then resumes from the failed byte offset |
| **3** | Try All Other Clients | Iterates over all other registered and healthy playback clients in order, using the first one that successfully returns a matching format |

Additionally, the outer **retry loop** (`MAX_RESUME_ATTEMPTS = 5`) in `process()` ensures that if playback was already underway (i.e. frames were played), the system will retry with the healthiest client from the beginning of the client list — with an exponential back-off (`150ms × attempt`) — instead of immediately throwing `AllClientsFailedException`.

`AllClientsFailedException` is only thrown if **no audio frames were ever played** and all clients failed during initial load, which is the correct behavior for genuinely unavailable videos.

**Affected file:** [`YoutubeAudioTrack.java`](common/src/main/java/dev/lavalink/youtube/track/YoutubeAudioTrack.java)

---

### 6. Original Audio Track Enforcement & Anti-Auto-Dubbing Fix

**Problem:** YouTube frequently serves auto-dubbed or translated audio tracks (e.g. auto-generated voiceovers in different languages based on server IP or user locale) instead of the creator's authentic original soundtrack for multi-language videos (such as MrBeast, multilingual music, and instructional videos).

**Fix:** `StreamingNonMusicClient` implements robust multi-language audio track detection to enforce the original soundtrack:
- Inspects YouTube metadata including `xtags` (`acont=original`), audio track IDs, and localized display names across 14+ languages (English, Arabic, French, German, Russian, Korean, Japanese, Chinese, Turkish, Italian, and others) to pinpoint and lock onto the **original** audio track ID.
- Automatically flags dubbed, auto-generated, translated, or descriptive tracks (`isDubbedDisplayName`) with `isDefaultAudioTrack = false`.
- `TrackFormats.getBestFormat()` strictly filters out all dubbed tracks and selects only the authentic original audio stream, completely preventing unwanted voice dubbing.

**Affected files:**
- [`StreamingNonMusicClient.java`](common/src/main/java/dev/lavalink/youtube/clients/skeleton/StreamingNonMusicClient.java)
- [`TrackFormats.java`](common/src/main/java/dev/lavalink/youtube/track/format/TrackFormats.java)

---

### Summary of Changed Files

| File | Change |
|------|--------|
| `YoutubeAudioTrack.java` | Seamless position-aware resumption + 3-tier URL refresh + retry loop (fixes `AllClientsFailedException`) |
| `YoutubePersistentHttpStream.java` | Self-healing URL refresh on 403/401 mid-stream errors |
| `TrackFormats.java` | Prefers direct progressive streams over SABR + enforces original audio track filtering |
| `StreamingNonMusicClient.java` | Disables SABR by default + multi-language anti-dubbing original track enforcement |
| `Tv.java` | Disables SABR for the TV client |
| `SabrStream.java` | Fixes premature finish and incorrect duration reporting |
| `ClientHealthManager.java` | Circuit breaker tracking for client failure isolation |

---

## Migration from Lavaplayer's built-in YouTube source

This client is intended as a direct replacement for Lavaplayer's built-in `YoutubeAudioSourceManager`,
which has been deprecated in a recent release of [Lavalink-Devs/Lavaplayer](https://github.com/lavalink-devs/lavaplayer).

When using `AudioSourceManagers.registerRemoteSources(AudioPlayerManager)`, Lavaplayer will register its own
deprecated `YoutubeAudioSourceManager`, which must be disabled.
Some versions of Lavaplayer may include an optional `excludeSources` parameter, allowing you to toggle the adding of the source.
If the version you are using does not support this, you will need to manually register each `AudioSourceManager` yourself.

First, create and register an instance of the supported `YoutubeAudioSourceManager` from the `youtube-source` package.
```java
AudioPlayerManager playerManager = new DefaultAudioPlayerManager();
YoutubeAudioSourceManager ytSourceManager = new dev.lavalink.youtube.YoutubeAudioSourceManager();
playerManager.registerSourceManager(ytSourceManager);
```

If your version of Lavaplayer supports an `excludeSources` parameter or equivalent, you may exclude the built-in
`YoutubeAudioSourceManager` using the following:
```java
AudioSourceManagers.registerRemoteSources(playerManager,
                                          com.sedmelluq.discord.lavaplayer.source.youtube.YoutubeAudioSourceManager.class);
```

Otherwise, you will need to register each source manager individually.

In addition, there are a few significant changes to note:

- This source's class structure differs so if you had custom classes that you were initialising
  the source manager with (e.g. an overridden `YoutubeTrackDetailsLoader`), this **is not** compatible
  with this source manager.

## Versioning Policy
This project follows [Semantic Versioning](https://semver.org/), except in the case of [client](#available-clients) removal.
Typically, clients are not removed unless there is good reason, such as being deprecated, irreparably broken or removed from YouTube's client lifecycle.
In such scenarios, we anticipate that you have ceased usage of such clients prior to their removal, so do not expect any code breakage,
however we advise that you periodically check and keep your client list up to date due to this.

## Additional Support
If you need additional help with using this source, that's not covered here or in any of the issues, 
[join our Discord server](https://discord.gg/ZW4s47Ppw4).