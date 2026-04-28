package dvspf.modid.client.partyfinder;

import java.util.List;
import java.util.Optional;

/**
 * Abstraction over wherever party listings are stored.
 *
 * <p>A listing is just an ad — the actual Wynncraft party state lives on the server
 * via {@code /party}, and this mod doesn't try to track it. So the repo only does
 * CRUD on listings; joining is just a {@code /msg} side-effect handled higher up.
 *
 * <p>For the MVP this is backed by {@link FakePartyRepository} (in-memory). Later,
 * swap in an HTTP-backed implementation that talks to your guild server — the rest
 * of the mod doesn't need to change.
 */
public interface PartyRepository {

    /** Return all current listings, freshest first. */
    List<Party> list();

    /** Return all listings whose category equals {@code category} (case-insensitive). */
    List<Party> listByCategory(String category);

    /** Look up a listing by its short id. */
    Optional<Party> findById(String id);

    /** Look up a listing posted by {@code leader} (case-insensitive), if any. */
    Optional<Party> findByLeader(String leader);

    /** Persist a new listing. Returns the stored listing. */
    Party create(Party party);

    /** Remove a listing by id. Returns true if a listing was removed. */
    boolean delete(String id);

    /** Toggle the {@code full} flag on a listing. Returns true if the listing exists. */
    boolean setFull(String id, boolean full);

    /** Hook for the future remote backend — fake repo can no-op this. */
    default void refresh() {
        // no-op for in-memory implementations
    }
}
