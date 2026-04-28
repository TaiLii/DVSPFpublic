package dvspf.modid.client.chat;

import dvspf.modid.client.commands.PartyFinderCommands;

import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Watches incoming chat for the {@code [DPF] <name> wants to join} pattern that
 * {@link PartyFinderCommands#sendJoinRequest} emits, and posts a clickable
 * "▸ Click to invite &lt;name&gt;" hint into the leader's local chat.
 *
 * <p>Clicking the hint runs {@code /party invite <name>} via Minecraft's
 * standard chat-component click event — no mixins, no packet rewriting, just a
 * second line in chat next to the original whisper.
 *
 * <p>The {@code <name>} marker is parsed out of the message body itself rather
 * than the surrounding chat formatting, so this works regardless of how
 * Wynncraft (or any other server) wraps incoming whispers — as long as the
 * body comes through unmodified, the regex finds the requester's name.
 */
public final class PartyFinderChatListener {

    /** Mirrors the format produced by {@link PartyFinderCommands#sendJoinRequest}. */
    private static final Pattern JOIN_REQUEST = Pattern.compile(
        "\\[DPF] <([A-Za-z0-9_]{1,16})> wants to join");

    private PartyFinderChatListener() {}

    public static void register() {
        // Wynncraft (like most Mojang-vanilla-ish servers) ships whispers as
        // system/game messages. We hook the GAME variant. If a future server
        // sends them as signed player chat we'd also need ClientReceiveMessageEvents.CHAT.
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (overlay) return;            // overlay = above the hotbar; never a whisper
            handle(message.getString());
        });
    }

    private static void handle(String text) {
        if (text == null) return;
        Matcher m = JOIN_REQUEST.matcher(text);
        if (!m.find()) return;

        String requester = m.group(1);

        // Skip if WE are the requester — the message we just sent has likely
        // been echoed back to our own chat, and we don't want to offer to
        // /party invite ourselves.
        String me = PartyFinderCommands.localPlayerName();
        if (me.equalsIgnoreCase(requester)) return;

        // Build a clickable line and drop it straight into the chat HUD.
        Component hint = Component.literal("[DPF] ")
            .withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD)
            .append(Component.literal("▸ Click to invite ").withStyle(ChatFormatting.GREEN))
            .append(Component.literal(requester).withStyle(ChatFormatting.YELLOW, ChatFormatting.BOLD))
            .append(Component.literal(" to your party").withStyle(ChatFormatting.GREEN))
            .withStyle(s -> s.withClickEvent(new ClickEvent.RunCommand("/party invite " + requester)));

        Minecraft mc = Minecraft.getInstance();
        if (mc.gui != null && mc.gui.getChat() != null) {
            mc.gui.getChat().addMessage(hint);
        }
    }
}
