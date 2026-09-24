package crabcraft.net.crabUtilities.chat.bridge

import crabcraft.net.crabUtilities.chatbridge.ChatBridgeProtocol
import java.util.UUID
import net.kyori.adventure.key.Key
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.ComponentIteratorType
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer

object ChatBridgeRegressionTest {
    @JvmStatic
    fun main(args: Array<String>) {
        checkCommandParsing()
        checkProtocolRoundTrips()
        checkStaffPrefixPreservesComponents()
    }

    private fun checkCommandParsing() {
        val message = ChatCommandParser.parse("/msg \"Crab Lord\" hello [i]")
        check(message.type() == ChatCommandParser.Type.PRIVATE, "/msg was not recognised")
        check(message.target() == "Crab Lord", "quoted nickname was not preserved")
        check(message.message() == "hello [i]", "private-message body was changed")
        val marker = "<cmd=123e4567-e89b-12d3-a456-426614174000:[inv]:>"
        val reply = ChatCommandParser.parse("/r $marker")
        check(
            reply.type() == ChatCommandParser.Type.REPLY && reply.message() == marker,
            "InteractiveChat command marker was not preserved",
        )
        val staff = ChatCommandParser.parse("/crabutilities:staffchat hello")
        check(
            staff.type() == ChatCommandParser.Type.STAFF && staff.message() == "hello",
            "namespaced staff-chat command was not parsed",
        )
        check(
            !ChatCommandParser.parse("/essentials:msg Alex hello").recognised(),
            "another plugin's namespaced command was intercepted",
        )
        check(!ChatCommandParser.parse("/msg Alex").valid(), "message without a body was accepted")
        check(!ChatCommandParser.parse("/msg \"Unclosed Alex hello").valid(), "unclosed quoted nickname was accepted")
    }

    private fun checkProtocolRoundTrips() {
        val request = ChatBridgeProtocol.decode(ChatBridgeProtocol.privateRequest("Crab Lord", "hello 🦀 [i]"))
        check(request.type() == ChatBridgeProtocol.Type.PRIVATE_REQUEST, "private request type changed")
        check(request.target() == "Crab Lord" && request.content() == "hello 🦀 [i]", "private request payload changed")
        val playerId = UUID.randomUUID()
        val componentJson = "{\"text\":\"hello\",\"font\":\"crabcraft:emoji\"}"
        val delivery = ChatBridgeProtocol.decode(ChatBridgeProtocol.delivery(playerId, componentJson))
        check(
            delivery.type() == ChatBridgeProtocol.Type.DELIVERY &&
                delivery.playerId() == playerId &&
                delivery.content() == componentJson,
            "component delivery did not round-trip",
        )
        val state = ChatBridgeProtocol.decode(ChatBridgeProtocol.staffState(playerId, false))
        check(
            state.type() == ChatBridgeProtocol.Type.STAFF_STATE && !state.enabled(),
            "staff-chat state did not round-trip",
        )
    }

    private fun checkStaffPrefixPreservesComponents() {
        val emojiFont = Key.key("crabcraft", "emoji")
        val input = Component.text("# hello", NamedTextColor.AQUA).append(Component.text(" 🦀").font(emojiFont))
        val result = StaffChatComponents.removePrefix(input)
        check(
            PlainTextComponentSerializer.plainText().serialize(result) == "hello 🦀",
            "staff-chat prefix was not removed cleanly",
        )
        check(result.color() == NamedTextColor.AQUA, "staff-chat prefix removal lost the original colour")
        check(
            result.iterable(ComponentIteratorType.DEPTH_FIRST).any { it.font() == emojiFont },
            "staff-chat prefix removal lost the emoji font",
        )
    }

    private fun check(condition: Boolean, message: String) {
        if (!condition) throw AssertionError(message)
    }
}
