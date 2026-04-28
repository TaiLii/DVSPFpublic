package dvspf.modid.client.partyfinder;

import java.util.Optional;

/**
 * Singleton facade over the listing repository plus a tiny bit of "do I have a
 * listing posted right now" client state. Commands and the GUI talk to this class
 * and don't need to know which repo implementation is behind it.
 */
public final class PartyFinderManager {

    private static final PartyFinderManager INSTANCE = new PartyFinderManager();

    public static PartyFinderManager get() {
        return INSTANCE;
    }

    private PartyRepository repository = new FakePartyRepository();

    /** The id of the listing the local player has posted via this mod, or null. */
    private String myListingId;

    private PartyFinderManager() {}

    // --- repo wiring (call this once the real HTTP backend is ready) -----------

    public PartyRepository getRepository() {
        return repository;
    }

    public void setRepository(PartyRepository repository) {
        if (repository == null) throw new IllegalArgumentException("repository == null");
        this.repository = repository;
    }

    // --- "did I post a listing?" state -----------------------------------------

    /** Returns the listing this player posted, looked up by id then by leader name. */
    public Optional<Party> getMyListing(String localPlayerName) {
        if (myListingId != null) {
            Optional<Party> byId = repository.findById(myListingId);
            if (byId.isPresent()) return byId;
            // id stale (repo refreshed/restarted) — fall through to leader lookup
            myListingId = null;
        }
        if (localPlayerName == null) return Optional.empty();
        Optional<Party> byLeader = repository.findByLeader(localPlayerName);
        byLeader.ifPresent(p -> myListingId = p.getId());
        return byLeader;
    }

    public boolean hasListing(String localPlayerName) {
        return getMyListing(localPlayerName).isPresent();
    }

    public void setMyListingId(String listingId) {
        this.myListingId = listingId;
    }

    public void clearMyListingId() {
        this.myListingId = null;
    }
}
