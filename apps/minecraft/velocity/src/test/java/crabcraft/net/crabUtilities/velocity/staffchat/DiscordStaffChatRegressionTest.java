package crabcraft.net.crabUtilities.velocity.staffchat;

import com.google.gson.JsonObject;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import crabcraft.net.crabUtilities.velocity.CrabUtilitiesVelocity;
import crabcraft.net.crabUtilities.velocity.DiscordWebhook;
import crabcraft.net.crabUtilities.velocity.VelocityConfig;
import crabcraft.net.crabUtilities.velocity.messaging.MessageManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.slf4j.Logger;

import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

final class DiscordStaffChatRegressionTest {

    private static final String FORMAT =
            "<#ff6e67>ᴍᴏᴅ ᴄʜᴀᴛ</#ff6e67> <#bebebe><sender>: <message></#bebebe>";
    private static final String PREFIX = "<#5865f2>[Discord]</#5865f2> ";
    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    public static void main(String[] args) throws Exception {
        decodesDiscordTextWithoutInterpretingUserFormatting();
        preservesMinecraftPackets();
        rejectsMalformedDiscordPackets();
        deliversWithNormalFormatAndStaffVisibility();
    }

    private static void decodesDiscordTextWithoutInterpretingUserFormatting() {
        String literal = "<click:run_command:'/op @s'><red>Hello</red></click> &aWorld";
        RedisStaffChat.StaffMessage message = RedisStaffChat.decode(discordPayload(
                "MinecraftName", "&#12Ab34Nickname", literal));
        check(message != null && message.fromDiscord(), "Discord origin was not recognised");
        check(PLAIN.serialize(message.sender()).equals("Nickname"), "nickname was not parsed");
        check(hasColour(message.sender(), TextColor.color(0x12ab34)),
                "nickname hex colour was lost");
        check(message.message().equals(Component.text(literal)), "Discord text enabled formatting");

        String username = "<red>discord_user</red>";
        message = RedisStaffChat.decode(discordPayload(username, null, "hello"));
        check(message.sender().equals(Component.text(username)), "Discord username enabled formatting");
        message = RedisStaffChat.decode(discordPayload("MinecraftName", "", "hello"));
        check(message.sender().equals(Component.text("MinecraftName")), "empty nickname did not fall back");
    }

    private static void preservesMinecraftPackets() {
        JsonObject envelope = new JsonObject();
        envelope.addProperty("sender", "&aPlayer");
        Component styled = MiniMessage.miniMessage().deserialize("<yellow>hello</yellow>");
        envelope.addProperty("message", GsonComponentSerializer.gson().serialize(styled));
        RedisStaffChat.StaffMessage message = RedisStaffChat.decode(envelope.toString());
        check(message != null && !message.fromDiscord(), "Minecraft origin changed");
        check(message.message().equals(styled), "Minecraft message styling was lost");
        check(PLAIN.serialize(message.sender()).equals("Player"), "Minecraft nickname styling was lost");
        message = RedisStaffChat.decode("&aPlayer\0legacy");
        check(message != null && !message.fromDiscord()
                        && PLAIN.serialize(message.message()).equals("legacy"),
                "legacy Minecraft packet no longer decodes");
    }

    private static void rejectsMalformedDiscordPackets() {
        check(RedisStaffChat.decode("not JSON") == null, "malformed packet was accepted");
        check(RedisStaffChat.decode("{\"source\":\"discord\"}") == null,
                "packet with missing fields was accepted");
        check(RedisStaffChat.decode(discordPayload("Player", null, "  ")) == null,
                "empty Discord text was accepted");
        check(RedisStaffChat.decode(discordPayload(" ", null, "hello")) == null,
                "empty sender was accepted");
        check(RedisStaffChat.decode(discordPayload("Player", null, "hello")
                        .replace("\"discord\"", "\"unknown\"")) == null,
                "unknown source was accepted as Minecraft chat");
    }

    private static void deliversWithNormalFormatAndStaffVisibility() throws Exception {
        Path directory = Files.createTempDirectory("discord-staff-chat-test");
        Logger logger = proxy(Logger.class, (method, args) -> null);
        try {
            Files.writeString(directory.resolve("config.yml"),
                    "staff-chat:\n  format: '" + FORMAT + "'\n  discord:\n"
                            + "    incoming-prefix: '" + PREFIX + "'\n");
            VelocityConfig config = VelocityConfig.load(directory, logger);
            check(config.getStaffChatFormat().equals(FORMAT), "custom format was overwritten");
            check(config.getStaffChatDiscordIncomingPrefix().equals(PREFIX), "prefix was not loaded");

            List<Component> visible = new ArrayList<>();
            List<Component> disabled = new ArrayList<>();
            List<Component> ordinary = new ArrayList<>();
            Player staff = player(true, visible);
            Player toggledOff = player(true, disabled);
            Player nonStaff = player(false, ordinary);
            ProxyServer server = proxy(ProxyServer.class, (method, args) ->
                    method.equals("getAllPlayers") ? List.of(staff, toggledOff, nonStaff) : null);
            CrabUtilitiesVelocity plugin = new CrabUtilitiesVelocity(server, logger, directory) {
                @Override public VelocityConfig getConfig() { return config; }
                @Override public MessageManager getMessageManager() { return new MessageManager(this); }
            };
            int[] published = {0};
            int[] webhooks = {0};
            RedisStaffChat redis = new RedisStaffChat(plugin, config) {
                @Override public void publish(String sender, Component message) { published[0]++; }
            };
            DiscordWebhook webhook = new DiscordWebhook("", logger) {
                @Override public void send(String message, String sender, String avatar) { webhooks[0]++; }
            };
            StaffChatManager manager = new StaffChatManager(plugin, redis, webhook, "");
            manager.toggle(toggledOff.getUniqueId());
            RedisStaffChat.StaffMessage decoded = RedisStaffChat.decode(discordPayload(
                    "MinecraftName", "<green>Nickname</green>", "hello"));
            manager.displayMessage(decoded.sender(), decoded.message(), decoded.fromDiscord());

            Component normalLine = MiniMessage.miniMessage().deserialize(FORMAT,
                    Placeholder.component("sender", decoded.sender()),
                    Placeholder.component("message", decoded.message()));
            Component expected = MiniMessage.miniMessage().deserialize(PREFIX).append(normalLine);
            check(visible.equals(List.of(expected)), "Discord changed normal formatting or prefix styling");
            check(disabled.isEmpty() && ordinary.isEmpty(), "Discord bypassed staff visibility or toggle");
            check(published[0] == 0 && webhooks[0] == 0, "Discord delivery echoed to Redis or webhook");

            manager.displayMessage("<green>Nickname</green>", Component.text("hello"));
            check(visible.getLast().equals(normalLine), "Minecraft chat acquired a Discord prefix");
            manager.sendMessage("MinecraftName", UUID.randomUUID(), Component.text("hello"));
            check(published[0] == 1 && webhooks[0] == 1, "Minecraft outgoing relay stopped working");

            Files.writeString(directory.resolve("config.yml"),
                    "staff-chat:\n  format: '" + FORMAT + "'\n  discord:\n    incoming-prefix: ''\n");
            check(VelocityConfig.load(directory, logger).getStaffChatDiscordIncomingPrefix().isEmpty(),
                    "empty prefix was replaced by defaults");
            check(VelocityConfig.load(directory, logger).getStaffChatDiscordIncomingPrefix().isEmpty(),
                    "empty prefix was not preserved across config reloads");
            Files.writeString(directory.resolve("config.yml"), "staff-chat:\n  format: '" + FORMAT + "'\n");
            check(VelocityConfig.load(directory, logger).getStaffChatDiscordIncomingPrefix().equals(PREFIX),
                    "missing prefix did not receive its default");
        } finally {
            Files.deleteIfExists(directory.resolve("config.yml"));
            Files.deleteIfExists(directory);
        }
    }

    private static String discordPayload(String sender, String nicknameRaw, String message) {
        JsonObject envelope = new JsonObject();
        envelope.addProperty("source", "discord");
        envelope.addProperty("sender", sender);
        envelope.addProperty("nicknameRaw", nicknameRaw);
        envelope.addProperty("message", message);
        envelope.addProperty("messageId", "1234567890123456789");
        return envelope.toString();
    }

    private static Player player(boolean permitted, List<Component> received) {
        UUID id = UUID.randomUUID();
        return proxy(Player.class, (method, args) -> switch (method) {
            case "getUniqueId" -> id;
            case "hasPermission" -> permitted && StaffChatManager.PERMISSION.equals(args[0]);
            case "sendMessage" -> { received.add((Component) args[0]); yield null; }
            default -> null;
        });
    }

    private static boolean hasColour(Component component, TextColor colour) {
        return colour.equals(component.color())
                || component.children().stream().anyMatch(child -> hasColour(child, colour));
    }

    @FunctionalInterface
    private interface Call { Object invoke(String method, Object[] args); }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, Call call) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                (proxy, method, args) -> call.invoke(method.getName(), args));
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
