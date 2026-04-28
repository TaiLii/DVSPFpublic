package dvspf.modid.client.reminder;

import dvspf.modid.client.commands.PartyFinderCommands;
import dvspf.modid.client.partyfinder.Party;
import dvspf.modid.client.partyfinder.PartyFinderManager;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;

import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Periodic nag for the local player when they leave a listing posted too long.
 *
 * <p>Once started, a daemon thread wakes up every minute, looks at whether the
 * local player has a listing, and — if it's been more than {@code intervalMinutes}
 * since either the listing was posted or the last reminder went out — drops a
 * clickable "click to /dpf close" line into chat.
 *
 * <p>The click event runs {@code /dpf close} via Minecraft's standard chat-component
 * click action, same mechanism the join-request listener uses.
 *
 * <p>Set {@code reminderIntervalMinutes = 0} in the config to disable entirely.
 */
public final class InactivityReminder {

    private static final long TICK_PERIOD_SECONDS = 60; // check every minute

    private final long intervalMs;
    private final ScheduledExecutorService scheduler;

    /**
     * Last time we fired a reminder, or 0 if none yet for the current listing.
     * Reset to 0 when the player's listing changes (closed/recreated) so a fresh
     * listing starts its own clock instead of inheriting the previous one's.
     */
    private long lastReminderMillis = 0L;

    /** Tracks the listing id we last saw, so we can reset the clock on a new one. */
    private String lastSeenListingId = null;

    private InactivityReminder(int intervalMinutes) {
        this.intervalMs = TimeUnit.MINUTES.toMillis(intervalMinutes);
        this.scheduler = Executors.newSingleThreadScheduledExecutor(daemonFactory("dvspf-reminder"));
        this.scheduler.scheduleWithFixedDelay(this::tickSafe,
            TICK_PERIOD_SECONDS, TICK_PERIOD_SECONDS, TimeUnit.SECONDS);
    }

    /**
     * Start the reminder loop. {@code intervalMinutes <= 0} disables — no thread
     * is created so there's zero overhead when turned off.
     */
    public static void start(int intervalMinutes) {
        if (intervalMinutes <= 0) return;
        // Reference is intentionally discarded — daemon thread keeps itself alive
        // for the JVM lifetime, which is what we want.
        new InactivityReminder(intervalMinutes);
    }

    private void tickSafe() {
        try {
            tick();
        } catch (Throwable t) {
            // Never let an exception kill the scheduler thread.
        }
    }

    private void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.gui == null) {
            // Player not in a world (main menu, loading screen, etc.) — skip.
            return;
        }

        String me = PartyFinderCommands.localPlayerName();
        Optional<Party> mine = PartyFinderManager.get().getMyListing(me);

        if (mine.isEmpty()) {
            // No listing — reset state so the next one starts fresh.
            lastSeenListingId = null;
            lastReminderMillis = 0L;
            return;
        }

        Party p = mine.get();

        // Reset clock when the listing identity changes (new post or replaced).
        if (!p.getId().equals(lastSeenListingId)) {
            lastSeenListingId = p.getId();
            lastReminderMillis = p.getCreatedAtMillis();
        }

        long now = System.currentTimeMillis();
        if (now - lastReminderMillis < intervalMs) return;

        // Fire on the main client thread — chat HUD isn't safe to touch off-thread.
        mc.execute(() -> sendReminder(p));
        lastReminderMillis = now;
    }

    private void sendReminder(Party p) {
        Component line = Component.literal("[DPF] ")
            .withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD)
            .append(Component.literal("Your ").withStyle(ChatFormatting.GRAY))
            .append(Component.literal(p.getCategory()).withStyle(ChatFormatting.AQUA))
            .append(Component.literal(" listing has been up ").withStyle(ChatFormatting.GRAY))
            .append(Component.literal(p.getAgeMinutes() + "m").withStyle(ChatFormatting.YELLOW))
            .append(Component.literal(" — ").withStyle(ChatFormatting.GRAY))
            .append(Component.literal("[click to /dpf close]")
                .withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD, ChatFormatting.UNDERLINE))
            .withStyle(s -> s.withClickEvent(new ClickEvent.RunCommand("/dpf close")));

        Minecraft mc = Minecraft.getInstance();
        if (mc.gui != null && mc.gui.getChat() != null) {
            mc.gui.getChat().addMessage(line);
        }
    }

    /** Daemon-thread factory so the scheduler doesn't block JVM shutdown. */
    private static ThreadFactory daemonFactory(String namePrefix) {
        AtomicInteger n = new AtomicInteger(0);
        return r -> {
            Thread t = new Thread(r, namePrefix + "-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
    }
}
