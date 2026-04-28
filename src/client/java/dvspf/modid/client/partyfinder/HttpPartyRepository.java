package dvspf.modid.client.partyfinder;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * {@link PartyRepository} backed by the dvspf HTTP service.
 *
 * <p>Read model: a background daemon thread polls {@code GET /api/listings}
 * every {@code pollIntervalSeconds}; the result replaces a {@code volatile}
 * snapshot reference. {@link #list()} just returns that snapshot — cheap, no
 * locking, no blocking on I/O from the render thread.
 *
 * <p>Write model: {@link #create(Party)} and {@link #delete(String)} hit the
 * server synchronously with a short timeout (commands and GUI clicks are rare),
 * then immediately call {@link #refresh()} so the writer sees their own change
 * before the next scheduled poll.
 *
 * <p>All network failures are swallowed and logged — the mod stays responsive,
 * worst case the listing list goes stale until the next successful poll.
 */
public class HttpPartyRepository implements PartyRepository {

    private static final Logger LOG = LoggerFactory.getLogger("dvspf");

    private final URI listingsUri;
    private final String apiKey;
    private final HttpClient http;
    private final ScheduledExecutorService poller;

    /** Latest snapshot. Replaced atomically by the poller; reads see whole list. */
    private volatile List<Party> cached = Collections.emptyList();

    public HttpPartyRepository(String apiUrl, String apiKey, int pollIntervalSeconds) {
        if (apiUrl == null || apiUrl.isBlank()) {
            throw new IllegalArgumentException("apiUrl must not be empty");
        }
        this.listingsUri = URI.create(apiUrl + "/api/listings");
        this.apiKey = apiKey == null ? "" : apiKey;
        this.http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

        this.poller = Executors.newSingleThreadScheduledExecutor(daemonFactory("dvspf-poller"));
        // First poll runs ASAP so the GUI isn't empty for 15s after startup.
        this.poller.scheduleWithFixedDelay(this::pollSafe, 0, pollIntervalSeconds, TimeUnit.SECONDS);

        LOG.info("dvspf HTTP repo started: {} (poll every {}s)", apiUrl, pollIntervalSeconds);
    }

    // --- read API (served from cache) -------------------------------------

    @Override
    public List<Party> list() {
        return cached; // immutable snapshot
    }

    @Override
    public List<Party> listByCategory(String category) {
        if (category == null || category.isBlank()) return list();
        List<Party> out = new ArrayList<>();
        for (Party p : cached) {
            if (p.getCategory().equalsIgnoreCase(category)) out.add(p);
        }
        return out;
    }

    @Override
    public Optional<Party> findById(String id) {
        if (id == null) return Optional.empty();
        for (Party p : cached) {
            if (p.getId().equals(id)) return Optional.of(p);
        }
        return Optional.empty();
    }

    @Override
    public Optional<Party> findByLeader(String leader) {
        if (leader == null) return Optional.empty();
        for (Party p : cached) {
            if (p.getLeader().equalsIgnoreCase(leader)) return Optional.of(p);
        }
        return Optional.empty();
    }

    // --- write API (synchronous; small timeout) ---------------------------

    @Override
    public Party create(Party party) {
        JsonObject body = new JsonObject();
        body.addProperty("leader", party.getLeader());
        body.addProperty("category", party.getCategory());
        body.addProperty("size", party.getSize());
        body.addProperty("note", party.getNote());

        try {
            HttpResponse<String> resp = send(HttpRequest.newBuilder()
                .uri(listingsUri)
                .timeout(Duration.ofSeconds(3))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString())));
            if (resp.statusCode() / 100 != 2) {
                LOG.warn("dvspf create failed: {} {}", resp.statusCode(), trim(resp.body()));
                return party; // best-effort: hand back the optimistic copy
            }
            Party stored = parseParty(JsonParser.parseString(resp.body()).getAsJsonObject());
            refresh(); // make sure /pf my and the GUI see the new id straight away
            return stored;
        } catch (Exception e) {
            LOG.warn("dvspf create failed: {}", e.getMessage());
            return party;
        }
    }

    @Override
    public boolean delete(String id) {
        try {
            HttpResponse<String> resp = send(HttpRequest.newBuilder()
                .uri(URI.create(listingsUri + "/" + id))
                .timeout(Duration.ofSeconds(3))
                .DELETE());
            refresh();
            return resp.statusCode() / 100 == 2;
        } catch (Exception e) {
            LOG.warn("dvspf delete failed: {}", e.getMessage());
            return false;
        }
    }

    @Override
    public boolean setFull(String id, boolean full) {
        JsonObject body = new JsonObject();
        body.addProperty("full", full);
        try {
            HttpResponse<String> resp = send(HttpRequest.newBuilder()
                .uri(URI.create(listingsUri + "/" + id + "/full"))
                .timeout(Duration.ofSeconds(3))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString())));
            refresh();
            return resp.statusCode() / 100 == 2;
        } catch (Exception e) {
            LOG.warn("dvspf setFull failed: {}", e.getMessage());
            return false;
        }
    }

    @Override
    public void refresh() {
        pollSafe();
    }

    // --- polling ----------------------------------------------------------

    private void pollSafe() {
        try {
            HttpResponse<String> resp = send(HttpRequest.newBuilder()
                .uri(listingsUri)
                .timeout(Duration.ofSeconds(8))
                .GET());
            if (resp.statusCode() / 100 != 2) {
                LOG.warn("dvspf poll non-2xx: {} {}", resp.statusCode(), trim(resp.body()));
                return;
            }
            JsonArray arr = JsonParser.parseString(resp.body()).getAsJsonArray();
            List<Party> next = new ArrayList<>(arr.size());
            for (JsonElement el : arr) {
                next.add(parseParty(el.getAsJsonObject()));
            }
            cached = Collections.unmodifiableList(next);
        } catch (Exception e) {
            // Connection errors are noisy in normal operation (server sleeping on
            // free tier, user offline, etc) — log at info, not warn.
            LOG.info("dvspf poll failed: {}", e.getMessage());
        }
    }

    // --- helpers ----------------------------------------------------------

    private HttpResponse<String> send(HttpRequest.Builder builder) throws Exception {
        if (!apiKey.isEmpty()) {
            builder.header("X-Guild-Key", apiKey);
        }
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static Party parseParty(JsonObject o) {
        return new Party(
            o.get("id").getAsString(),
            o.get("leader").getAsString(),
            o.get("category").getAsString(),
            o.get("size").getAsInt(),
            o.has("note") && !o.get("note").isJsonNull() ? o.get("note").getAsString() : "",
            o.has("createdAtMillis") && !o.get("createdAtMillis").isJsonNull()
                ? o.get("createdAtMillis").getAsLong()
                : System.currentTimeMillis(),
            o.has("full") && !o.get("full").isJsonNull() && o.get("full").getAsBoolean()
        );
    }

    private static String trim(String s) {
        if (s == null) return "";
        return s.length() > 200 ? s.substring(0, 200) + "..." : s;
    }

    /** Daemon-thread factory so the poller doesn't block JVM shutdown. */
    private static ThreadFactory daemonFactory(String namePrefix) {
        AtomicInteger n = new AtomicInteger(0);
        return r -> {
            Thread t = new Thread(r, namePrefix + "-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
    }
}
