package dev.lavalink.youtube.clients;

import dev.lavalink.youtube.clients.skeleton.Client;
import dev.lavalink.youtube.http.YoutubeOauth2Handler;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Intelligent Circuit Breaker and Adaptive Health Ordering for YouTube Clients.
 * Prevents YouTube IP bans by:
 * 1. Isolating bot-flagged clients on exponential cooldowns to avoid repeated suspicious requests.
 * 2. Promoting working clients (especially OAuth-authenticated clients) to the front of the line.
 * 3. Gracefully testing tripped clients after cooldown (Half-Open probing).
 */
public class ClientHealthManager {
    private static final Logger log = LoggerFactory.getLogger(ClientHealthManager.class);

    private static final long INITIAL_COOLDOWN_MS = TimeUnit.MINUTES.toMillis(5);
    private static final long MAX_COOLDOWN_MS = TimeUnit.HOURS.toMillis(1);

    private final Client[] allClients;
    private final YoutubeOauth2Handler oauth2Handler;
    private final Map<String, ClientStats> statsMap = new ConcurrentHashMap<>();

    public ClientHealthManager(@NotNull Client[] clients, @Nullable YoutubeOauth2Handler oauth2Handler) {
        this.allClients = clients;
        this.oauth2Handler = oauth2Handler;
        for (Client client : clients) {
            statsMap.put(client.getIdentifier(), new ClientStats(client.getIdentifier()));
        }
    }

    public static class ClientStats {
        final String identifier;
        volatile int consecutiveFailures = 0;
        volatile long cooldownUntil = 0;
        volatile long lastSuccessTime = 0;
        volatile long successCount = 0;

        ClientStats(String identifier) {
            this.identifier = identifier;
        }

        public boolean isTripped() {
            return System.currentTimeMillis() < cooldownUntil;
        }

        public long getRemainingCooldownSec() {
            long remaining = cooldownUntil - System.currentTimeMillis();
            return remaining > 0 ? TimeUnit.MILLISECONDS.toSeconds(remaining) : 0;
        }
    }

    @NotNull
    public List<Client> getOrderedClients() {
        return getOrderedClients(null);
    }

    /**
     * Returns clients ordered by health, OAuth capability, and success history.
     * Filtered for request handling if identifier is non-null.
     */
    @NotNull
    public List<Client> getOrderedClients(@Nullable String identifier) {
        List<Client> candidates = new ArrayList<>();
        for (Client c : allClients) {
            if (identifier == null || c.canHandleRequest(identifier)) {
                candidates.add(c);
            }
        }
        return sortClients(candidates);
    }

    /**
     * Returns playback-capable clients ordered by health and priority.
     */
    @NotNull
    public List<Client> getOrderedPlaybackClients() {
        List<Client> candidates = new ArrayList<>();
        for (Client c : allClients) {
            if (c.supportsFormatLoading()) {
                candidates.add(c);
            }
        }
        return sortClients(candidates);
    }

    private List<Client> sortClients(List<Client> candidates) {
        boolean oauthActive = oauth2Handler != null && oauth2Handler.isEnabled() && oauth2Handler.hasAccessToken();
        long now = System.currentTimeMillis();

        candidates.sort((c1, c2) -> {
            ClientStats s1 = statsMap.computeIfAbsent(c1.getIdentifier(), ClientStats::new);
            ClientStats s2 = statsMap.computeIfAbsent(c2.getIdentifier(), ClientStats::new);

            boolean tripped1 = now < s1.cooldownUntil;
            boolean tripped2 = now < s2.cooldownUntil;

            // 1. Untripped clients come before tripped clients
            if (tripped1 != tripped2) {
                return tripped1 ? 1 : -1;
            }

            // If both are tripped, pick the one closest to exiting cooldown
            if (tripped1) {
                return Long.compare(s1.cooldownUntil, s2.cooldownUntil);
            }

            // 2. If OAuth is active, prioritize clients that support OAuth (e.g. TVHTML5)
            if (oauthActive) {
                boolean o1 = c1.supportsOAuth();
                boolean o2 = c2.supportsOAuth();
                if (o1 != o2) {
                    return o1 ? -1 : 1;
                }
            }

            // 3. Prioritize by last success recency and success count
            if (s1.lastSuccessTime != s2.lastSuccessTime) {
                return Long.compare(s2.lastSuccessTime, s1.lastSuccessTime);
            }

            return Long.compare(s2.successCount, s1.successCount);
        });

        return candidates;
    }

    public void onSuccess(@NotNull Client client) {
        ClientStats stats = statsMap.computeIfAbsent(client.getIdentifier(), ClientStats::new);
        stats.consecutiveFailures = 0;
        stats.cooldownUntil = 0;
        stats.lastSuccessTime = System.currentTimeMillis();
        stats.successCount++;
        log.debug("Client \"{}\" marked healthy (total successes: {})", client.getIdentifier(), stats.successCount);
    }

    public void onFailure(@NotNull Client client, @Nullable Throwable error) {
        ClientStats stats = statsMap.computeIfAbsent(client.getIdentifier(), ClientStats::new);
        stats.consecutiveFailures++;

        boolean isBotBlock = isBotOrAuthError(error);

        if (isBotBlock) {
            long cooldown = Math.min(INITIAL_COOLDOWN_MS * (1L << Math.min(stats.consecutiveFailures - 1, 4)), MAX_COOLDOWN_MS);
            stats.cooldownUntil = System.currentTimeMillis() + cooldown;
            log.warn("CircuitBreaker: Client \"{}\" encountered bot/auth block ({}), quarantined for {} minutes to protect IP.",
                    client.getIdentifier(), error != null ? error.getMessage() : "unknown", TimeUnit.MILLISECONDS.toMinutes(cooldown));
        } else {
            // General failure, brief 30-second backoff if repeatedly failing
            if (stats.consecutiveFailures >= 3) {
                stats.cooldownUntil = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(30);
            }
        }
    }

    private boolean isBotOrAuthError(@Nullable Throwable error) {
        if (error == null) return false;
        String msg = error.getMessage();
        if (msg == null) return false;

        String lower = msg.toLowerCase();
        return lower.contains("requires login")
                || lower.contains("not a bot")
                || lower.contains("page needs to be reloaded")
                || lower.contains("not success status code: 403")
                || lower.contains("player api response: 400");
    }
}
