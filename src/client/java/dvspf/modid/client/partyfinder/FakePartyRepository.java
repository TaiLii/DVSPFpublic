package dvspf.modid.client.partyfinder;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * In-memory listing repository for testing. Seeded with a handful of sample
 * Wynncraft-flavoured listings so {@code /dpf list} returns something interesting
 * on first launch.
 *
 * <p>This class is the only place that knows the sample data lives in memory —
 * when the real backend lands, write a new {@link PartyRepository} impl
 * (e.g. {@code HttpPartyRepository}) and swap it in {@link PartyFinderManager}.
 */
public class FakePartyRepository implements PartyRepository {

    /** Use a LinkedHashMap so insertion order is preserved for predictable listings. */
    private final Map<String, Party> parties = new LinkedHashMap<>();

    public FakePartyRepository() {
        seed();
    }

    private void seed() {
        // Wynncraft activities: raids, dungeons, lootruns.
        // Categories use common shorthand so /dpf list <category> works naturally.
        addSeed(new Party("a1b2c3", "Steve",       "NotG",     4, "exp run, all welcome"));
        addSeed(new Party("d4e5f6", "Alex",        "TCC",      4, "speedrun, need archer"));
        addSeed(new Party("g7h8i9", "Notch",       "TNA",      4, "intermediate, no rush"));
        addSeed(new Party("j0k1l2", "Herobrine",   "NoL",      4, "carries, will tip"));
        addSeed(new Party("m3n4o5", "Wynnic",      "Lootrun",  3, "Silent Expanse, t6 keys"));
        addSeed(new Party("p6q7r8", "DungeonHero", "Dungeon",  4, "DS xp grind, lvl 60+"));
    }

    private void addSeed(Party p) {
        parties.put(p.getId(), p);
    }

    @Override
    public List<Party> list() {
        // newest first feels right for a "what's open right now" list
        List<Party> out = new ArrayList<>(parties.values());
        out.sort(Comparator.comparingLong(Party::getCreatedAtMillis).reversed());
        return out;
    }

    @Override
    public List<Party> listByCategory(String category) {
        if (category == null || category.isBlank()) return list();
        List<Party> out = new ArrayList<>();
        for (Party p : list()) {
            if (p.getCategory().equalsIgnoreCase(category)) {
                out.add(p);
            }
        }
        return out;
    }

    @Override
    public Optional<Party> findById(String id) {
        if (id == null) return Optional.empty();
        return Optional.ofNullable(parties.get(id));
    }

    @Override
    public Optional<Party> findByLeader(String leader) {
        if (leader == null) return Optional.empty();
        for (Party p : parties.values()) {
            if (p.getLeader().equalsIgnoreCase(leader)) return Optional.of(p);
        }
        return Optional.empty();
    }

    @Override
    public Party create(Party party) {
        parties.put(party.getId(), party);
        return party;
    }

    @Override
    public boolean delete(String id) {
        return parties.remove(id) != null;
    }

    @Override
    public boolean setFull(String id, boolean full) {
        Party p = parties.get(id);
        if (p == null) return false;
        p.setFull(full);
        return true;
    }
}
