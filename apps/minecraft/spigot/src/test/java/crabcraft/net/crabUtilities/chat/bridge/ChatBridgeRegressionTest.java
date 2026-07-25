package crabcraft.net.crabUtilities.chat.bridge;

import crabcraft.net.crabUtilities.chatbridge.ChatBridgeProtocol;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.ComponentIteratorType;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import java.util.UUID;

final class ChatBridgeRegressionTest {

    public static void main(String[] args) {
        checkCommandParsing();
        checkProtocolRoundTrips();
        checkStaffPrefixPreservesComponents();
    }

    private static void checkCommandParsing() {
        ChatCommandParser.Parsed message =
                ChatCommandParser.parse("/msg \"Crab Lord\" hello [i]");
        check(message.type() == ChatCommandParser.Type.PRIVATE,
                "/msg was not recognised");
        check("Crab Lord".equals(message.target()),
                "quoted nickname was not preserved");
        check("hello [i]".equals(message.message()),
                "private-message body was changed");

        String marker = "<cmd=123e4567-e89b-12d3-a456-426614174000:[inv]:>";
        ChatCommandParser.Parsed reply = ChatCommandParser.parse("/r " + marker);
        check(reply.type() == ChatCommandParser.Type.REPLY
                        && marker.equals(reply.message()),
                "InteractiveChat command marker was not preserved");

        ChatCommandParser.Parsed staff =
                ChatCommandParser.parse("/crabutilities:staffchat hello");
        check(staff.type() == ChatCommandParser.Type.STAFF
                        && "hello".equals(staff.message()),
                "namespaced staff-chat command was not parsed");

        check(!ChatCommandParser.parse("/essentials:msg Alex hello").recognised(),
                "another plugin's namespaced command was intercepted");
        check(!ChatCommandParser.parse("/msg Alex").valid(),
                "message without a body was accepted");
        check(!ChatCommandParser.parse("/msg \"Unclosed Alex hello").valid(),
                "unclosed quoted nickname was accepted");
    }

    private static void checkProtocolRoundTrips() {
        ChatBridgeProtocol.Packet request = ChatBridgeProtocol.decode(
                ChatBridgeProtocol.privateRequest("Crab Lord", "hello 🦀 [i]"));
        check(request.type() == ChatBridgeProtocol.Type.PRIVATE_REQUEST,
                "private request type changed");
        check("Crab Lord".equals(request.target())
                        && "hello 🦀 [i]".equals(request.content()),
                "private request payload changed");

        UUID playerId = UUID.randomUUID();
        String componentJson = "{\"text\":\"hello\",\"font\":\"crabcraft:emoji\"}";
        ChatBridgeProtocol.Packet delivery = ChatBridgeProtocol.decode(
                ChatBridgeProtocol.delivery(playerId, componentJson));
        check(delivery.type() == ChatBridgeProtocol.Type.DELIVERY
                        && playerId.equals(delivery.playerId())
                        && componentJson.equals(delivery.content()),
                "component delivery did not round-trip");

        ChatBridgeProtocol.Packet state = ChatBridgeProtocol.decode(
                ChatBridgeProtocol.staffState(playerId, false));
        check(state.type() == ChatBridgeProtocol.Type.STAFF_STATE
                        && !state.enabled(),
                "staff-chat state did not round-trip");
    }

    private static void checkStaffPrefixPreservesComponents() {
        Key emojiFont = Key.key("crabcraft", "emoji");
        Component input = Component.text("# hello", NamedTextColor.AQUA)
                .append(Component.text(" 🦀").font(emojiFont));
        Component result = StaffChatComponents.removePrefix(input);

        check("hello 🦀".equals(
                        PlainTextComponentSerializer.plainText().serialize(result)),
                "staff-chat prefix was not removed cleanly");
        check(result.color() == NamedTextColor.AQUA,
                "staff-chat prefix removal lost the original colour");
        boolean fontPreserved = false;
        for (Component child : result.iterable(ComponentIteratorType.DEPTH_FIRST)) {
            if (emojiFont.equals(child.font())) {
                fontPreserved = true;
                break;
            }
        }
        check(fontPreserved, "staff-chat prefix removal lost the emoji font");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
