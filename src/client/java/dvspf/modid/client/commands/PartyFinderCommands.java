package dvspf.modid.client.commands;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;

import dvspf.modid.client.gui.PartyFinderScreen;
import dvspf.modid.client.partyfinder.Party;
import dvspf.modid.client.partyfinder.PartyFinderManager;
import dvspf.modid.client.partyfinder.PartyRepository;

import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.util.List;
import java.util.Optional;

/**
 * Registers the {@code /dpf} client command and its subcommands.
 *
 * <p>A "party" in this mod is a <em>listing</em> — an ad on a bulletin board.
 * The actual Wynncraft party lives on the server via {@code /party}. So:
 * <ul>
 *   <li>{@code /dpf create} just posts an ad describing what you're running</li>
 *   <li>{@code /dpf join} sends a {@code /msg} to the leader asking for an invite</li>
 *   <li>{@code /dpf close} pulls down your own ad</li>
 * </ul>
 *
 * <p>None of these touch Wynncraft party state. The leader still has to manually
 * run {@code /party invite <player>} after they get the message.
 *
 * <p>Subcommands:
 * <ul>
 *   <li>{@code /dpf} or {@code /dpf gui} — open the GUI</li>
 *   <li>{@code /dpf help} — list usage</li>
 *   <li>{@code /dpf list [category]} — show open listings (optionally filtered)</li>
 *   <li>{@code /dpf create <category> <size> <note...>} — post a listing</li>
 *   <li>{@code /dpf join <id>} — DM the leader of a listing</li>
 *   <li>{@code /dpf close} — take down your own listing</li>
 *   <li>{@code /dpf my} — show your listing (if you have one)</li>
 *   <li>{@code /dpf refresh} — re-fetch the listings (no-op against the fake repo)</li>
 * </ul>
 */
public final class PartyFinderCommands {

    /** Prefix prepended to the DM sent on Join, so the leader can spot it. */
    private static final String MSG_PREFIX = "[DPF]";

    private PartyFinderCommands() {}

    public static void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            LiteralArgumentBuilder<FabricClientCommandSource> root = ClientCommandManager.literal("dpf")
                // Bare /dpf opens the GUI. Schedule on the client thread so the
                // chat screen has time to close before we show ours.
                .executes(ctx -> {
                    Minecraft.getInstance().execute(() ->
                        Minecraft.getInstance().setScreen(new PartyFinderScreen()));
                    return 1;
                })

                .then(ClientCommandManager.literal("gui")
                    .executes(ctx -> {
                        Minecraft.getInstance().execute(() ->
                            Minecraft.getInstance().setScreen(new PartyFinderScreen()));
                        return 1;
                    }))

                .then(ClientCommandManager.literal("help")
                    .executes(ctx -> { sendHelp(ctx.getSource()); return 1; }))

                .then(ClientCommandManager.literal("list")
                    .executes(ctx -> doList(ctx.getSource(), null))
                    .then(ClientCommandManager.argument("category", StringArgumentType.word())
                        .executes(ctx -> doList(ctx.getSource(), StringArgumentType.getString(ctx, "category")))))

                .then(ClientCommandManager.literal("my")
                    .executes(ctx -> doMy(ctx.getSource())))

                .then(ClientCommandManager.literal("refresh")
                    .executes(ctx -> doRefresh(ctx.getSource())))

                .then(ClientCommandManager.literal("close")
                    .executes(ctx -> doClose(ctx.getSource())))

                .then(ClientCommandManager.literal("full")
                    .executes(ctx -> doSetFull(ctx.getSource(), true)))
                .then(ClientCommandManager.literal("open")
                    .executes(ctx -> doSetFull(ctx.getSource(), false)))

                .then(ClientCommandManager.literal("join")
                    .then(ClientCommandManager.argument("id", StringArgumentType.word())
                        .executes(ctx -> doJoin(ctx.getSource(), StringArgumentType.getString(ctx, "id")))))

                .then(ClientCommandManager.literal("create")
                    .then(ClientCommandManager.argument("category", StringArgumentType.word())
                        .then(ClientCommandManager.argument("size", IntegerArgumentType.integer(2, 10))
                            // Note is optional — bare "/dpf create NotG 4" is fine and
                            // posts with an empty note. Trailing greedy-string branch
                            // still picks up "/dpf create NotG 4 some longer note".
                            .executes(ctx -> doCreate(
                                ctx.getSource(),
                                StringArgumentType.getString(ctx, "category"),
                                IntegerArgumentType.getInteger(ctx, "size"),
                                ""))
                            .then(ClientCommandManager.argument("note", StringArgumentType.greedyString())
                                .executes(ctx -> doCreate(
                                    ctx.getSource(),
                                    StringArgumentType.getString(ctx, "category"),
                                    IntegerArgumentType.getInteger(ctx, "size"),
                                    StringArgumentType.getString(ctx, "note")))))));

            dispatcher.register(root);
        });
    }

    // --- subcommand implementations ----------------------------------------

    private static int doList(FabricClientCommandSource src, String category) {
        PartyRepository repo = PartyFinderManager.get().getRepository();
        List<Party> parties = (category == null) ? repo.list() : repo.listByCategory(category);

        if (parties.isEmpty()) {
            src.sendFeedback(prefix().append(Component.literal(
                category == null ? "No listings posted." : "No listings in category '" + category + "'.")
                .withStyle(ChatFormatting.GRAY)));
            return 1;
        }

        String header = (category == null)
            ? "Open listings (" + parties.size() + ")"
            : "Open listings in '" + category + "' (" + parties.size() + ")";

        src.sendFeedback(prefix().append(Component.literal(header).withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD)));

        for (Party p : parties) {
            // line: [id] leader  category  size  note  (Xm ago)
            MutableComponent line = Component.literal("  ")
                .append(Component.literal("[" + p.getId() + "] ").withStyle(ChatFormatting.DARK_GRAY))
                .append(Component.literal(p.getLeader()).withStyle(ChatFormatting.YELLOW))
                .append(Component.literal("  "))
                .append(Component.literal(p.getCategory()).withStyle(ChatFormatting.AQUA))
                .append(Component.literal("  "))
                .append(Component.literal(p.getSize() + "p").withStyle(ChatFormatting.GREEN))
                .append(Component.literal("  "))
                .append(Component.literal("\"" + p.getNote() + "\"").withStyle(ChatFormatting.WHITE))
                .append(Component.literal("  (" + p.getAgeMinutes() + "m ago)").withStyle(ChatFormatting.DARK_GRAY));
            src.sendFeedback(line);
        }

        src.sendFeedback(Component.literal("  Tip: ").withStyle(ChatFormatting.DARK_GRAY)
            .append(Component.literal("/dpf join <id>").withStyle(ChatFormatting.GRAY))
            .append(Component.literal(" — DMs the leader for an invite").withStyle(ChatFormatting.DARK_GRAY)));
        return 1;
    }

    private static int doMy(FabricClientCommandSource src) {
        String me = localName(src);
        Optional<Party> mine = PartyFinderManager.get().getMyListing(me);
        if (mine.isEmpty()) {
            src.sendFeedback(prefix().append(Component.literal("You haven't posted a listing. Use ").withStyle(ChatFormatting.GRAY))
                .append(Component.literal("/dpf create").withStyle(ChatFormatting.YELLOW)));
            return 1;
        }
        Party p = mine.get();
        src.sendFeedback(prefix().append(Component.literal("Your listing:").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD)));
        src.sendFeedback(Component.literal("  ID: ").withStyle(ChatFormatting.GRAY)
            .append(Component.literal(p.getId()).withStyle(ChatFormatting.WHITE)));
        src.sendFeedback(Component.literal("  Category: ").withStyle(ChatFormatting.GRAY)
            .append(Component.literal(p.getCategory()).withStyle(ChatFormatting.AQUA)));
        src.sendFeedback(Component.literal("  Size: ").withStyle(ChatFormatting.GRAY)
            .append(Component.literal(p.getSize() + " players").withStyle(ChatFormatting.GREEN)));
        src.sendFeedback(Component.literal("  Note: ").withStyle(ChatFormatting.GRAY)
            .append(Component.literal(p.getNote()).withStyle(ChatFormatting.WHITE)));
        src.sendFeedback(Component.literal("  Take it down with ").withStyle(ChatFormatting.DARK_GRAY)
            .append(Component.literal("/dpf close").withStyle(ChatFormatting.GRAY)));
        return 1;
    }

    private static int doJoin(FabricClientCommandSource src, String id) {
        Optional<Party> existing = PartyFinderManager.get().getRepository().findById(id);
        if (existing.isEmpty()) {
            src.sendError(Component.literal("No listing found with id '" + id + "'."));
            return 0;
        }
        Party party = existing.get();

        String me = localName(src);
        if (party.getLeader().equalsIgnoreCase(me)) {
            src.sendError(Component.literal("That's your own listing."));
            return 0;
        }
        if (party.isFull()) {
            src.sendError(Component.literal("That listing is marked full — try again later."));
            return 0;
        }

        sendJoinRequest(party);

        src.sendFeedback(prefix().append(Component.literal("Sent join request to ").withStyle(ChatFormatting.GREEN))
            .append(Component.literal(party.getLeader()).withStyle(ChatFormatting.YELLOW))
            .append(Component.literal(" — wait for them to /party invite you.").withStyle(ChatFormatting.GRAY)));
        return 1;
    }

    private static int doSetFull(FabricClientCommandSource src, boolean full) {
        String me = localName(src);
        Optional<Party> updated = setMyListingFull(me, full);
        if (updated.isEmpty()) {
            src.sendError(Component.literal("You don't have a listing posted."));
            return 0;
        }
        if (full) {
            src.sendFeedback(prefix().append(Component.literal("Marked your listing ").withStyle(ChatFormatting.GRAY))
                .append(Component.literal("FULL").withStyle(ChatFormatting.RED, ChatFormatting.BOLD))
                .append(Component.literal(" — incoming joins blocked.").withStyle(ChatFormatting.GRAY)));
        } else {
            src.sendFeedback(prefix().append(Component.literal("Marked your listing ").withStyle(ChatFormatting.GRAY))
                .append(Component.literal("OPEN").withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD))
                .append(Component.literal(" — accepting joins again.").withStyle(ChatFormatting.GRAY)));
        }
        return 1;
    }

    private static int doClose(FabricClientCommandSource src) {
        String me = localName(src);
        Optional<Party> closed = closeMyListing(me);
        if (closed.isEmpty()) {
            src.sendError(Component.literal("You don't have a listing posted."));
            return 0;
        }
        src.sendFeedback(prefix().append(Component.literal("Closed your listing ").withStyle(ChatFormatting.GREEN))
            .append(Component.literal("[" + closed.get().getId() + "]").withStyle(ChatFormatting.DARK_GRAY)));
        return 1;
    }

    /**
     * Shared close-listing implementation used by both {@code /dpf close} and
     * the GUI's "Close Listing" button. Deletes the listing from the repo,
     * clears local "my listing" state, and disbands the leader's Wynncraft
     * party so the two stay in sync.
     *
     * @return the listing that was closed, or empty if there was nothing to close
     */
    public static Optional<Party> closeMyListing(String localPlayerName) {
        PartyFinderManager mgr = PartyFinderManager.get();
        Optional<Party> mine = mgr.getMyListing(localPlayerName);
        if (mine.isEmpty()) return Optional.empty();

        mgr.getRepository().delete(mine.get().getId());
        mgr.clearMyListingId();
        sendServerCommand("party disband");
        return mine;
    }

    private static int doCreate(FabricClientCommandSource src, String category, int size, String note) {
        String me = localName(src);
        PartyFinderManager mgr = PartyFinderManager.get();

        // If you already have a listing posted, replace it — one ad per leader.
        mgr.getMyListing(me).ifPresent(old -> mgr.getRepository().delete(old.getId()));

        Party created = mgr.getRepository().create(Party.newListing(me, category, size, note));
        mgr.setMyListingId(created.getId());

        // Also spin up an actual Wynncraft party so the leader doesn't have to run
        // a second command. Harmless if they're already in one — Wynncraft will
        // reply with an error message which is just informational.
        sendServerCommand("party create");

        src.sendFeedback(prefix().append(Component.literal("Posted listing ").withStyle(ChatFormatting.GREEN))
            .append(Component.literal("[" + created.getId() + "] ").withStyle(ChatFormatting.DARK_GRAY))
            .append(Component.literal(category).withStyle(ChatFormatting.AQUA))
            .append(Component.literal("  " + size + "p").withStyle(ChatFormatting.GREEN)));
        src.sendFeedback(Component.literal("  ")
            .append(Component.literal("/party create").withStyle(ChatFormatting.GRAY))
            .append(Component.literal(" was sent automatically — players who DM you will get a clickable invite hint.").withStyle(ChatFormatting.DARK_GRAY)));
        return 1;
    }

    private static int doRefresh(FabricClientCommandSource src) {
        PartyFinderManager.get().getRepository().refresh();
        src.sendFeedback(prefix().append(Component.literal("Refreshed.").withStyle(ChatFormatting.GRAY)));
        return 1;
    }

    private static void sendHelp(FabricClientCommandSource src) {
        src.sendFeedback(prefix().append(Component.literal("Party Finder commands:").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD)));
        helpLine(src, "/dpf",                                       "open the GUI");
        helpLine(src, "/dpf list [category]",                       "show open listings");
        helpLine(src, "/dpf my",                                    "show your listing");
        helpLine(src, "/dpf join <id>",                             "DM the leader for an invite");
        helpLine(src, "/dpf close",                                 "take down your listing");
        helpLine(src, "/dpf full",                                  "mark your listing as full (blocks joins)");
        helpLine(src, "/dpf open",                                  "mark your listing as open again");
        helpLine(src, "/dpf create <category> <size> [note]",       "post a listing (note is optional)");
        helpLine(src, "/dpf refresh",                               "re-fetch listings");
    }

    private static void helpLine(FabricClientCommandSource src, String cmd, String desc) {
        src.sendFeedback(Component.literal("  ")
            .append(Component.literal(cmd).withStyle(ChatFormatting.YELLOW))
            .append(Component.literal(" - " + desc).withStyle(ChatFormatting.GRAY)));
    }

    // --- shared helpers (also used by the GUI) -----------------------------

    /**
     * Send the actual {@code /msg <leader> ...} so the leader gets pinged. This is
     * the single side-effect of "joining" a listing — the listing data does NOT
     * change, because the dpf side has no way of knowing whether the leader
     * actually invites them on the Wynncraft side.
     *
     * <p>The {@code <senderName>} marker in the body is what
     * {@code PartyFinderChatListener} uses to detect incoming join requests on
     * the leader's side and offer a clickable {@code /party invite} affordance.
     * Don't change that format without updating the listener too.
     */
    public static void sendJoinRequest(Party party) {
        // Safety net — the GUI and chat command both pre-check, but if a caller
        // forgets, silently noop on full listings rather than spamming the leader.
        if (party.isFull()) return;
        String me = localPlayerName();
        String text = MSG_PREFIX + " <" + me + "> wants to join your " + party.getCategory()
            + " party (listing " + party.getId() + ") — press the message above to invite" + me;
        sendServerCommand("msg " + party.getLeader() + " " + text);
    }

    /**
     * Flip the full flag on the local player's listing. Used by both the chat
     * command and the GUI button. Returns the updated listing on success.
     */
    public static Optional<Party> setMyListingFull(String localPlayerName, boolean full) {
        PartyFinderManager mgr = PartyFinderManager.get();
        Optional<Party> mine = mgr.getMyListing(localPlayerName);
        if (mine.isEmpty()) return Optional.empty();
        if (!mgr.getRepository().setFull(mine.get().getId(), full)) return Optional.empty();
        // Update the local copy too so callers don't have to re-fetch — refresh()
        // on the HTTP repo will overwrite it with server state on the next poll.
        mine.get().setFull(full);
        return mine;
    }

    public static String localPlayerName() {
        LocalPlayer p = Minecraft.getInstance().player;
        return p != null ? p.getGameProfile().name() : "You";
    }

    private static MutableComponent prefix() {
        return Component.literal("[DPF] ").withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD);
    }

    private static String localName(FabricClientCommandSource src) {
        LocalPlayer p = src.getPlayer();
        if (p != null) return p.getGameProfile().name();
        return localPlayerName();
    }

    /**
     * Sends a chat command to the connected server (e.g. {@code msg Steve hello}).
     * No-op when not connected. The leading slash is stripped because the network
     * helper expects the bare command.
     */
    private static void sendServerCommand(String command) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null || player.connection == null) return;
        String stripped = command.startsWith("/") ? command.substring(1) : command;
        player.connection.sendCommand(stripped);
    }
}
