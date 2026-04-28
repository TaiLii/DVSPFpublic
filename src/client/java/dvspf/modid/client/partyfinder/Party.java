package dvspf.modid.client.partyfinder;

import java.util.UUID;

/**
 * A party listing — basically an "ad" a leader posts to say
 * "I'm running a Wynncraft party doing X, ping me if you want in."
 *
 * <p>Important: this is NOT the actual Wynncraft party. The Wynncraft {@code /party}
 * system is the source of truth for who's in what party. This object is just a
 * post on the bulletin board. Joining a listing in dpf only sends a {@code /msg}
 * to the leader; the leader still has to {@code /party invite} the requester
 * manually.
 */
public class Party {

    private final String id;
    private final String leader;
    private final String category;
    /** Desired party size (e.g. 4 for a 4-player run). Just informational. */
    private final int size;
    private final long createdAtMillis;

    private String note;
    /** Leader-toggled flag: when true, /dpf join is refused for this listing. */
    private boolean full;

    public Party(String id, String leader, String category, int size, String note) {
        this(id, leader, category, size, note, System.currentTimeMillis(), false);
    }

    /**
     * Full constructor used when deserializing from the backend — preserves the
     * server-assigned timestamp and full-flag so state stays consistent across clients.
     */
    public Party(String id, String leader, String category, int size, String note,
                 long createdAtMillis, boolean full) {
        this.id = id;
        this.leader = leader;
        this.category = category;
        this.size = Math.max(2, Math.min(10, size)); // Wynncraft party cap is 10
        this.note = note == null ? "" : note;
        this.createdAtMillis = createdAtMillis;
        this.full = full;
    }

    /** Backwards-compatible 6-arg ctor — defaults full=false. */
    public Party(String id, String leader, String category, int size, String note, long createdAtMillis) {
        this(id, leader, category, size, note, createdAtMillis, false);
    }

    /** Convenience: build a fresh listing with a generated short id. */
    public static Party newListing(String leader, String category, int size, String note) {
        return new Party(UUID.randomUUID().toString().substring(0, 6), leader, category, size, note);
    }

    public String getId() {
        return id;
    }

    public String getLeader() {
        return leader;
    }

    public String getCategory() {
        return category;
    }

    public int getSize() {
        return size;
    }

    public String getNote() {
        return note;
    }

    public void setNote(String note) {
        this.note = note == null ? "" : note;
    }

    public boolean isFull() {
        return full;
    }

    public void setFull(boolean full) {
        this.full = full;
    }

    public long getCreatedAtMillis() {
        return createdAtMillis;
    }

    /** Minutes since this listing was posted — handy for the list UI. */
    public long getAgeMinutes() {
        return Math.max(0, (System.currentTimeMillis() - createdAtMillis) / 60_000L);
    }
}
