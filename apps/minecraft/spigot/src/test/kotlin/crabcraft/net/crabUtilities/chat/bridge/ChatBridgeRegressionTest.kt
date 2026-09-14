package crabcraft.net.crabUtilities.chat.bridge

import crabcraft.net.crabUtilities.chatbridge.ChatBridgeProtocol
import net.kyori.adventure.key.Key
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.ComponentIteratorType
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import java.util.UUID

object ChatBridgeRegressionTest {
    @JvmStatic
    fun main(args: Array<String>) {
        checkCommandParsing()
        checkProtocolRoundTrips()
        checkStaffPrefixPreservesComponents()
    }

    private fun checkCommandParsing() {
        val nickname = "Fict ${UUID.randomUUID().toString().take(8)}"
        val message = ChatCommandParser.parse("/msg \"$nickname\" hello [i]")
        check(message.type() == ChatCommandParser.Type.PRIVATE,
            "/msg was not recognised")
        check(nickname == message.target(), "quoted nickname was not preserved")
        check("hello [i]" == message.message(), "private-message body was changed")

        val marker = "<cmd=${UUID.randomUUID()}:[inv]:>"
        val reply = ChatCommandParser.parse("/r $marker")
        check(reply.type() == ChatCommandParser.Type.REPLY && marker == reply.message(),
            "InteractiveChat command marker was not preserved")

        val staff = ChatCommandParser.parse("/crabutilities:staffchat hello")
        check(staff.type() == ChatCommandParser.Type.STAFF && "hello" == staff.message(),
            "namespaced staff-chat command was not parsed")

        val username = "Fict${UUID.randomUUID().toString().take(8)}"
        check(!ChatCommandParser.parse("/essentials:msg $username hello").recognised(),
            "another plugin's namespaced command was intercepted")
        check(!ChatCommandParser.parse("/msg $username").valid(),
            "message without a body was accepted")
        check(!ChatCommandParser.parse("/msg \"Unclosed $username hello").valid(),
            "unclosed quoted nickname was accepted")
    }

    private fun checkProtocolRoundTrips() {
        val nickname = "Fict ${UUID.randomUUID().toString().take(8)}"
        val request = ChatBridgeProtocol.decode(
            ChatBridgeProtocol.privateRequest(nickname, "hello 🦀 [i]")
        )
        check(request.type() == ChatBridgeProtocol.Type.PRIVATE_REQUEST,
            "private request type changed")
        check(nickname == request.target() && "hello 🦀 [i]" == request.content(),
            "private request payload changed")

        val playerId = UUID.randomUUID()
        val componentJson = "{\"text\":\"hello\",\"font\":\"crabcraft:emoji\"}"
        val delivery = ChatBridgeProtocol.decode(ChatBridgeProtocol.delivery(playerId, componentJson))
        check(delivery.type() == ChatBridgeProtocol.Type.DELIVERY &&
            playerId == delivery.playerId() && componentJson == delivery.content(),
            "component delivery did not round-trip")

        val state = ChatBridgeProtocol.decode(ChatBridgeProtocol.staffState(playerId, false))
        check(state.type() == ChatBridgeProtocol.Type.STAFF_STATE && !state.enabled(),
            "staff-chat state did not round-trip")
    }

    private fun checkStaffPrefixPreservesComponents() {
        val emojiFont = Key.key("crabcraft", "emoji")
        val input = Component.text("# hello", NamedTextColor.AQUA)
            .append(Component.text(" 🦀").font(emojiFont))
        val result = StaffChatComponents.removePrefix(input)

        check("hello 🦀" == PlainTextComponentSerializer.plainText().serialize(result),
            "staff-chat prefix was not removed cleanly")
        check(result.color() == NamedTextColor.AQUA,
            "staff-chat prefix removal lost the original colour")
        var fontPreserved = false
        for (child in result.iterable(ComponentIteratorType.DEPTH_FIRST)) {
            if (emojiFont == child.font()) {
                fontPreserved = true
                break
            }
        }
        check(fontPreserved, "staff-chat prefix removal lost the emoji font")
    }

    private fun check(condition: Boolean, message: String) {
        if (!condition) throw AssertionError(message)
    }
}
