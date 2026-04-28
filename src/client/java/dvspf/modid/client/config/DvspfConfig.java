package dvspf.modid.client.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;

import net.fabricmc.loader.api.FabricLoader;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Reads (and creates if missing) the dvspf config file at
 * {@code <fabricConfigDir>/dvspf.json}.
 *
 * <p>Schema:
 * <pre>
 * {
 *   "apiUrl":              "https://your-backend.example.com",  // empty = use FakePartyRepository
 *   "apiKey":              "shared-guild-key",                  // optional, sent as X-Guild-Key
 *   "pollIntervalSeconds": 15
 * }
 * </pre>
 *
 * <p>Mutable POJO so Gson can populate it via reflection (no setters needed).
 */
public class DvspfConfig {

    private static final Logger LOG = LoggerFactory.getLogger("dvspf");
    private static final String FILENAME = "dvspf.json";

    /**
     * Base URL of the backend (no trailing slash). Empty means "no backend
     * configured — use the in-memory FakePartyRepository for local testing."
     */
    public String apiUrl = "https://backend-delicate-dream-3233.fly.dev";

    /** Optional shared guild key. Sent as {@code X-Guild-Key} header on every request. */
    public String apiKey = "f73b4d089521c6ea";

    /** How often the background poller hits {@code /api/listings}. */
    public int pollIntervalSeconds = 15;

    /** Read-or-create the config file. Never throws — returns defaults on any failure. */
    public static DvspfConfig load() {
        Path configPath = FabricLoader.getInstance().getConfigDir().resolve(FILENAME);
        try {
            if (!Files.exists(configPath)) {
                DvspfConfig defaults = new DvspfConfig();
                writeDefaults(configPath, defaults);
                LOG.info("Created default dvspf config at {}", configPath);
                return defaults;
            }
            try (Reader r = Files.newBufferedReader(configPath)) {
                DvspfConfig cfg = new Gson().fromJson(r, DvspfConfig.class);
                if (cfg == null) cfg = new DvspfConfig(); // empty file
                cfg.normalize();
                return cfg;
            }
        } catch (IOException | JsonSyntaxException e) {
            LOG.warn("Failed to read dvspf config at {}, using defaults: {}", configPath, e.getMessage());
            return new DvspfConfig();
        }
    }

    public boolean isBackendConfigured() {
        return apiUrl != null && !apiUrl.isBlank();
    }

    private void normalize() {
        if (apiUrl == null) apiUrl = "";
        apiUrl = apiUrl.trim();
        // Strip trailing slash so we can append paths cleanly later.
        while (apiUrl.endsWith("/")) apiUrl = apiUrl.substring(0, apiUrl.length() - 1);
        if (apiKey == null) apiKey = "";
        apiKey = apiKey.trim();
        if (pollIntervalSeconds < 5) pollIntervalSeconds = 5;       // don't hammer the server
        if (pollIntervalSeconds > 300) pollIntervalSeconds = 300;   // don't make the GUI feel dead
    }

    private static void writeDefaults(Path target, DvspfConfig cfg) throws IOException {
        Files.createDirectories(target.getParent());
        try (Writer w = Files.newBufferedWriter(target,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            new GsonBuilder().setPrettyPrinting().create().toJson(cfg, w);
        }
    }
}
