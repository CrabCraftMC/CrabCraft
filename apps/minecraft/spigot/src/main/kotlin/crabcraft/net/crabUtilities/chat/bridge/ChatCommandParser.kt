package crabcraft.net.crabUtilities.chat.bridge

import java.util.Locale

object ChatCommandParser {
    private val PRIVATE_ALIASES = setOf("msg", "message", "tell", "whisper", "w", "dm")
    private val REPLY_ALIASES = setOf("r", "reply")
    private val STAFF_ALIASES = setOf("sc", "staffchat")

    enum class Type { NONE, PRIVATE, REPLY, STAFF }

    data class Parsed(private val type: Type, private val target: String?, private val message: String?) {
        fun type(): Type = type
        fun target(): String? = target
        fun message(): String? = message
        fun recognised(): Boolean = type != Type.NONE
        fun valid(): Boolean = message != null && message.any { !Character.isWhitespace(it) } && (type != Type.PRIVATE || target != null)
    }

    @JvmStatic
    fun parse(input: String?): Parsed {
        if (input == null || input.length < 2 || input[0] != '/') return Parsed(Type.NONE, null, null)
        val separator = firstWhitespace(input, 1)
        val rawLabel = input.substring(1, if (separator < 0) input.length else separator)
        val label = normaliseLabel(rawLabel) ?: return Parsed(Type.NONE, null, null)
        val type = when (label) {
            in PRIVATE_ALIASES -> Type.PRIVATE
            in REPLY_ALIASES -> Type.REPLY
            in STAFF_ALIASES -> Type.STAFF
            else -> return Parsed(Type.NONE, null, null)
        }
        if (separator < 0) return Parsed(type, null, null)
        val argumentsStart = skipWhitespace(input, separator)
        if (argumentsStart >= input.length) return Parsed(type, null, null)
        if (type != Type.PRIVATE) return Parsed(type, null, input.substring(argumentsStart))
        val parsedTarget = parseTarget(input, argumentsStart) ?: return Parsed(type, null, null)
        val messageStart = skipWhitespace(input, parsedTarget.end())
        if (messageStart >= input.length) return Parsed(type, parsedTarget.value(), null)
        return Parsed(type, parsedTarget.value(), input.substring(messageStart))
    }

    private fun normaliseLabel(rawLabel: String): String? {
        val lower = rawLabel.lowercase(Locale.ROOT)
        val namespace = lower.indexOf(':')
        if (namespace < 0) return lower
        if (lower.substring(0, namespace) != "crabutilities") return null
        return lower.substring(namespace + 1)
    }

    private fun parseTarget(input: String, start: Int): ParsedTarget? {
        if (input[start] != '"') {
            var end = firstWhitespace(input, start)
            if (end < 0) end = input.length
            val target = input.substring(start, end)
            return if (target.isEmpty()) null else ParsedTarget(target, end)
        }
        val target = StringBuilder()
        var escaped = false
        for (i in start + 1 until input.length) {
            val character = input[i]
            if (escaped) {
                if (character != '"' && character != '\\') return null
                target.append(character)
                escaped = false
            } else if (character == '\\') {
                escaped = true
            } else if (character == '"') {
                val end = i + 1
                if (end < input.length && !Character.isWhitespace(input[end])) return null
                return if (target.isEmpty()) null else ParsedTarget(target.toString(), end)
            } else {
                target.append(character)
            }
        }
        return null
    }

    private fun firstWhitespace(input: String, start: Int): Int {
        for (i in start until input.length) {
            if (Character.isWhitespace(input[i])) return i
        }
        return -1
    }

    private fun skipWhitespace(input: String, start: Int): Int {
        var index = start
        while (index < input.length && Character.isWhitespace(input[index])) index++
        return index
    }

    private data class ParsedTarget(private val value: String, private val end: Int) {
        fun value(): String = value
        fun end(): Int = end
    }
}
