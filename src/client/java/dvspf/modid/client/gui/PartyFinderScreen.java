package dvspf.modid.client.gui;

import dvspf.modid.client.commands.PartyFinderCommands;
import dvspf.modid.client.partyfinder.Party;
import dvspf.modid.client.partyfinder.PartyFinderManager;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Chest-style Party Finder screen — opens when the player runs {@code /dpf}.
 *
 * <p>This is a normal {@link Screen} (not a real container), but it's drawn to
 * <em>look</em> like a 3x9 chest GUI. Each listing fills one slot with an
 * {@link ItemStack}; hovering pops a vanilla-style tooltip with leader, category,
 * size, note and a "click to join" hint.
 *
 * <p>Listings are <em>ads</em>, not actual parties. The Wynncraft {@code /party}
 * system holds the real party state. Clicking a slot fires a {@code /msg} to the
 * leader via {@link PartyFinderCommands#sendJoinRequest(Party)} — the leader still
 * has to {@code /party invite} the requester manually.
 *
 * <p>Layout (3x9 chest-style grid):
 * <pre>
 *   Row 0:  L L L L L L L L L     <- listings (cont'd)
 *   Row 1:  L L L L L L L L L     <- listings (up to 18 total)
 *   Row 2:  R . . . X . . . .     <- R=Refresh (clock), X=Create/Close (book/barrier)
 * </pre>
 *
 * <p>If you ever post more than 18 listings, only the freshest 18 will fit. Add
 * scrolling later via {@code ContainerObjectSelectionList}.
 */
public class PartyFinderScreen extends Screen {

    // --- Layout constants (mirrors vanilla single-chest dimensions) -------

    private static final int COLS = 9;
    private static final int ROWS = 3;
    private static final int SLOT_SIZE = 18;            // 16x16 item + 2px frame
    private static final int PANEL_PADDING_X = 7;       // gap between panel border and first slot
    private static final int PANEL_PADDING_TOP = 28;    // title bar height (room for title + subtitle)
    private static final int PANEL_PADDING_BOTTOM = 7;
    private static final int PANEL_WIDTH = PANEL_PADDING_X * 2 + COLS * SLOT_SIZE;       // 176
    private static final int PANEL_HEIGHT = PANEL_PADDING_TOP + ROWS * SLOT_SIZE + PANEL_PADDING_BOTTOM; // 79

    private static final int LISTING_SLOTS = COLS * 2;   // first two rows = listings
    private static final int ACTION_ROW = 2;             // bottom row = actions

    // colours
    private static final int COLOUR_PANEL_FILL    = 0xFFC6C6C6; // vanilla light grey
    private static final int COLOUR_PANEL_BORDER  = 0xFF373737;
    private static final int COLOUR_SLOT_FILL     = 0xFF8B8B8B; // vanilla slot grey
    private static final int COLOUR_SLOT_BORDER   = 0xFF373737;
    private static final int COLOUR_HOVER         = 0x80FFFFFF;

    // --- runtime state ------------------------------------------------------

    private int panelX, panelY;
    private final List<SlotEntry> slots = new ArrayList<>();

    public PartyFinderScreen() {
        super(Component.literal(" DVS Party Finder"));
    }

    @Override
    protected void init() {
        panelX = (this.width - PANEL_WIDTH) / 2;
        panelY = (this.height - PANEL_HEIGHT) / 2;
        rebuildSlots();
    }

    /** Recompute the slot list from the current repo state. */
    private void rebuildSlots() {
        slots.clear();
        String me = PartyFinderCommands.localPlayerName();

        // --- listing slots (rows 0..1) ------------------------------------
        List<Party> parties = PartyFinderManager.get().getRepository().list();
        for (int i = 0; i < parties.size() && i < LISTING_SLOTS; i++) {
            Party p = parties.get(i);
            int row = i / COLS;
            int col = i % COLS;
            int x = panelX + PANEL_PADDING_X + col * SLOT_SIZE + 1; // +1 to inset item from border
            int y = panelY + PANEL_PADDING_TOP + row * SLOT_SIZE + 1;

            // Full listings render as a barrier so they're visually distinct in
            // the grid — easier to see at a glance than only in the tooltip.
            ItemStack stack = new ItemStack(p.isFull() ? Items.BARRIER : itemForCategory(p.getCategory()));
            List<Component> tooltip = buildListingTooltip(p, p.getLeader().equalsIgnoreCase(me));
            boolean isMine = p.getLeader().equalsIgnoreCase(me);
            boolean isFull = p.isFull();

            slots.add(new SlotEntry(x, y, stack, tooltip, slot -> {
                if (isMine) return;  // can't DM yourself
                if (isFull) return;  // joins blocked when leader marked it full
                PartyFinderCommands.sendJoinRequest(p);
                // visually mark the slot as "sent" by swapping the icon and tooltip
                slot.stack = new ItemStack(Items.EMERALD);
                slot.tooltip = List.of(
                    Component.literal("Sent!").withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD),
                    Component.literal("Wait for " + p.getLeader() + " to ").withStyle(ChatFormatting.GRAY)
                        .append(Component.literal("/party invite").withStyle(ChatFormatting.WHITE))
                        .append(Component.literal(" you.").withStyle(ChatFormatting.GRAY))
                );
                slot.onClick = s -> {}; // disarm
            }));
        }

        // --- action slots (row 2) -----------------------------------------
        int actionY = panelY + PANEL_PADDING_TOP + ACTION_ROW * SLOT_SIZE + 1;

        // [0] Refresh
        slots.add(new SlotEntry(
            panelX + PANEL_PADDING_X + 0 * SLOT_SIZE + 1, actionY,
            new ItemStack(Items.CLOCK),
            List.of(
                Component.literal("Refresh").withStyle(ChatFormatting.YELLOW, ChatFormatting.BOLD),
                Component.literal("Re-fetch the listings.").withStyle(ChatFormatting.GRAY)
            ),
            slot -> {
                PartyFinderManager.get().getRepository().refresh();
                rebuildSlots();
            }
        ));

        // [4] Create Party / Close Listing (depending on state)
        boolean hasListing = PartyFinderManager.get().hasListing(me);
        slots.add(new SlotEntry(
            panelX + PANEL_PADDING_X + 4 * SLOT_SIZE + 1, actionY,
            new ItemStack(hasListing ? Items.BARRIER : Items.WRITABLE_BOOK),
            hasListing
                ? List.of(
                    Component.literal("Close Listing").withStyle(ChatFormatting.RED, ChatFormatting.BOLD),
                    Component.literal("Take down your posted listing.").withStyle(ChatFormatting.GRAY))
                : List.of(
                    Component.literal("Create Party").withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD),
                    Component.literal("Posts a listing so others can").withStyle(ChatFormatting.GRAY),
                    Component.literal("DM you for invites.").withStyle(ChatFormatting.GRAY),
                    Component.empty(),
                    Component.literal("This automatically sets up a Wynncraft").withStyle(ChatFormatting.DARK_GRAY),
                    Component.literal("/party").withStyle(ChatFormatting.WHITE)
                        .append(Component.literal(".").withStyle(ChatFormatting.DARK_GRAY))),
            slot -> {
                if (hasListing) {
                    closeMyListing(me);
                    rebuildSlots();
                } else {
                    Minecraft.getInstance().setScreen(new ChatScreen("/dpf create ", false));
                }
            }
        ));

        // [6] Toggle Open / Full — only when you have a listing posted.
        Optional<Party> myListing = PartyFinderManager.get().getMyListing(me);
        if (myListing.isPresent()) {
            boolean currentlyFull = myListing.get().isFull();
            slots.add(new SlotEntry(
                panelX + PANEL_PADDING_X + 6 * SLOT_SIZE + 1, actionY,
                new ItemStack(currentlyFull ? Items.RED_DYE : Items.LIME_DYE),
                currentlyFull
                    ? List.of(
                        Component.literal("Mark Open").withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD),
                        Component.literal("Currently ").withStyle(ChatFormatting.GRAY)
                            .append(Component.literal("FULL").withStyle(ChatFormatting.RED, ChatFormatting.BOLD))
                            .append(Component.literal(" — joins blocked.").withStyle(ChatFormatting.GRAY)),
                        Component.empty(),
                        Component.literal("Click to start accepting joins again.").withStyle(ChatFormatting.GRAY))
                    : List.of(
                        Component.literal("Mark Full").withStyle(ChatFormatting.RED, ChatFormatting.BOLD),
                        Component.literal("Currently ").withStyle(ChatFormatting.GRAY)
                            .append(Component.literal("OPEN").withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD))
                            .append(Component.literal(" — accepting joins.").withStyle(ChatFormatting.GRAY)),
                        Component.empty(),
                        Component.literal("Click to block further /dpf joins").withStyle(ChatFormatting.GRAY),
                        Component.literal("until you mark it open again.").withStyle(ChatFormatting.GRAY)),
                slot -> {
                    PartyFinderCommands.setMyListingFull(me, !currentlyFull);
                    rebuildSlots();
                }
            ));
        }

        // [8] My Listing (info only — shows your current ad if any)
        Optional<Party> mine = PartyFinderManager.get().getMyListing(me);
        if (mine.isPresent()) {
            Party p = mine.get();
            slots.add(new SlotEntry(
                panelX + PANEL_PADDING_X + 8 * SLOT_SIZE + 1, actionY,
                new ItemStack(Items.NAME_TAG),
                List.of(
                    Component.literal("Your Listing").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD),
                    Component.literal("Status: ").withStyle(ChatFormatting.GRAY)
                        .append(p.isFull()
                            ? Component.literal("FULL").withStyle(ChatFormatting.RED, ChatFormatting.BOLD)
                            : Component.literal("OPEN").withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD)),
                    Component.literal("ID: ").withStyle(ChatFormatting.GRAY)
                        .append(Component.literal(p.getId()).withStyle(ChatFormatting.WHITE)),
                    Component.literal("Category: ").withStyle(ChatFormatting.GRAY)
                        .append(Component.literal(p.getCategory()).withStyle(ChatFormatting.AQUA)),
                    Component.literal("Size: ").withStyle(ChatFormatting.GRAY)
                        .append(Component.literal(p.getSize() + " players").withStyle(ChatFormatting.GREEN)),
                    Component.literal("Note: ").withStyle(ChatFormatting.GRAY)
                        .append(Component.literal(p.getNote()).withStyle(ChatFormatting.WHITE))
                ),
                slot -> {} // info-only
            ));
        }
    }

    // --- rendering ---------------------------------------------------------

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick); // dim background

        // Panel
        g.fill(panelX - 1, panelY - 1, panelX + PANEL_WIDTH + 1, panelY + PANEL_HEIGHT + 1, COLOUR_PANEL_BORDER);
        g.fill(panelX, panelY, panelX + PANEL_WIDTH, panelY + PANEL_HEIGHT, COLOUR_PANEL_FILL);

        // Title on the first line, subtitle (listing count) on the second.
        // Two lines so a longer title doesn't collide with the count.
        g.drawString(this.font, this.title, panelX + 8, panelY + 6, 0xFF404040, false);
        Component subtitle = Component.literal(
                PartyFinderManager.get().getRepository().list().size() + " open listings")
            .withStyle(ChatFormatting.DARK_GRAY);
        g.drawString(this.font, subtitle, panelX + 8, panelY + 17, 0xFF606060, false);

        // Slots
        SlotEntry hovered = null;
        for (SlotEntry s : slots) {
            // slot frame: dark border + lighter inner fill
            g.fill(s.x - 1, s.y - 1, s.x + 17, s.y + 17, COLOUR_SLOT_BORDER);
            g.fill(s.x, s.y, s.x + 16, s.y + 16, COLOUR_SLOT_FILL);

            // item
            g.renderItem(s.stack, s.x, s.y);
            g.renderItemDecorations(this.font, s.stack, s.x, s.y);

            if (isMouseOver(s, mouseX, mouseY)) {
                hovered = s;
                g.fillGradient(s.x, s.y, s.x + 16, s.y + 16, COLOUR_HOVER, COLOUR_HOVER);
            }
        }

        // Empty state text drawn over the empty grid (only if we have NO listings)
        if (PartyFinderManager.get().getRepository().list().isEmpty()) {
            Component empty = Component.literal("No listings posted yet")
                .withStyle(ChatFormatting.DARK_GRAY);
            g.drawCenteredString(this.font, empty,
                panelX + PANEL_WIDTH / 2,
                panelY + PANEL_PADDING_TOP + SLOT_SIZE / 2 + 4,
                0xFF404040);
        }

        // Tooltip rendered LAST so it sits on top of everything. In 1.21.11 the
        // direct-render helper for List<Component> was renamed to a "queue for end
        // of frame" call — same effect, slightly different name.
        if (hovered != null && !hovered.tooltip.isEmpty()) {
            g.setComponentTooltipForNextFrame(this.font, hovered.tooltip, mouseX, mouseY);
        }
    }

    // --- input -------------------------------------------------------------

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClicked) {
        if (event.button() == 0) { // left-click only
            for (SlotEntry s : slots) {
                if (isMouseOver(s, (int) event.x(), (int) event.y())) {
                    s.onClick.accept(s);
                    return true;
                }
            }
        }
        return super.mouseClicked(event, doubleClicked);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // --- helpers -----------------------------------------------------------

    private static boolean isMouseOver(SlotEntry s, int mouseX, int mouseY) {
        return mouseX >= s.x && mouseX < s.x + 16 && mouseY >= s.y && mouseY < s.y + 16;
    }

    /** Pick a thematic Wynncraft icon for the listing's category. */
    private static Item itemForCategory(String category) {
        if (category == null) return Items.PAPER;
        String c = category.toLowerCase();
        // raids
        if (c.equals("notg") || c.equals("nol") || c.equals("tcc") || c.equals("tna")
            || c.contains("raid")) return Items.NETHER_STAR;
        if (c.contains("dungeon") || c.equals("ds") || c.equals("ib") || c.equals("ts")) return Items.IRON_SWORD;
        if (c.contains("lootrun")) return Items.COMPASS;
        if (c.contains("boss")) return Items.DIAMOND_SWORD;
        return Items.PAPER;
    }

    private static List<Component> buildListingTooltip(Party p, boolean isMine) {
        List<Component> lines = new ArrayList<>();
        // top line: leader (yellow, bold) + [FULL] tag if applicable
        if (p.isFull()) {
            lines.add(Component.literal(p.getLeader())
                .withStyle(ChatFormatting.YELLOW, ChatFormatting.BOLD)
                .append(Component.literal("  [FULL]").withStyle(ChatFormatting.RED, ChatFormatting.BOLD)));
        } else {
            lines.add(Component.literal(p.getLeader())
                .withStyle(ChatFormatting.YELLOW, ChatFormatting.BOLD));
        }
        // category line
        lines.add(Component.literal("Category: ").withStyle(ChatFormatting.GRAY)
            .append(Component.literal(p.getCategory()).withStyle(ChatFormatting.AQUA)));
        // size line
        lines.add(Component.literal("Size: ").withStyle(ChatFormatting.GRAY)
            .append(Component.literal(p.getSize() + " players").withStyle(ChatFormatting.GREEN)));
        // note line
        lines.add(Component.literal("\"" + p.getNote() + "\"").withStyle(ChatFormatting.WHITE));
        // age
        lines.add(Component.literal("Posted " + p.getAgeMinutes() + "m ago")
            .withStyle(ChatFormatting.DARK_GRAY));
        // id
        lines.add(Component.literal("ID: " + p.getId()).withStyle(ChatFormatting.DARK_GRAY));

        // bottom action hint
        lines.add(Component.empty());
        if (isMine) {
            lines.add(Component.literal("Your own listing").withStyle(ChatFormatting.DARK_PURPLE, ChatFormatting.ITALIC));
        } else if (p.isFull()) {
            lines.add(Component.literal("Marked full — joins blocked").withStyle(ChatFormatting.RED, ChatFormatting.ITALIC));
        } else {
            lines.add(Component.literal("Click to ").withStyle(ChatFormatting.GRAY)
                .append(Component.literal("DM the leader").withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD))
                .append(Component.literal(" for an invite").withStyle(ChatFormatting.GRAY)));
        }
        return lines;
    }

    private static void closeMyListing(String me) {
        // Delegate to the shared helper so the GUI button does exactly what
        // /dpf close does — including firing /party disband on the server.
        PartyFinderCommands.closeMyListing(me);
    }

    /**
     * Mutable holder for one slot's render state. Mutable so a clicked listing slot
     * can flip its own icon to "Sent!" without rebuilding the whole list.
     */
    private static final class SlotEntry {
        final int x, y;
        ItemStack stack;
        List<Component> tooltip;
        Consumer<SlotEntry> onClick;

        SlotEntry(int x, int y, ItemStack stack, List<Component> tooltip, Consumer<SlotEntry> onClick) {
            this.x = x;
            this.y = y;
            this.stack = stack;
            this.tooltip = tooltip;
            this.onClick = onClick;
        }
    }
}
