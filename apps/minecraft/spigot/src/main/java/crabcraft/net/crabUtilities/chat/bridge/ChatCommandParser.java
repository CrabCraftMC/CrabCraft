package crabcraft.net.crabUtilities.chat.bridge;

import java.util.Locale;
import java.util.Set;

final class ChatCommandParser {

    private static final Set<String> PRIVATE_ALIASES =
            Set.of("msg", "message", "tell", "whisper", "w", "dm");
    private static final Set<String> REPLY_ALIASES = Set.of("r", "reply");
    private static final Set<String> STAFF_ALIASES = Set.of("sc", "staffchat");

    private ChatCommandParser() {}

    enum Type {
        NONE,
        PRIVATE,
        REPLY,
        STAFF
    }

    record Parsed(Type type, String target, String message) {
        boolean recognised() {
            return type != Type.NONE;
        }

        boolean valid() {
            return message != null && !message.isBlank()
                    && (type != Type.PRIVATE || target != null);
        }
    }

    static Parsed parse(String input) {
        if (input == null || input.length() < 2 || input.charAt(0) != '/') {
            return new Parsed(Type.NONE, null, null);
        }

        int separator = firstWhitespace(input, 1);
        String rawLabel = input.substring(1, separator < 0 ? input.length() : separator);
        String label = normaliseLabel(rawLabel);
        if (label == null) {
            return new Parsed(Type.NONE, null, null);
        }

        Type type;
        if (PRIVATE_ALIASES.contains(label)) {
            type = Type.PRIVATE;
        } else if (REPLY_ALIASES.contains(label)) {
            type = Type.REPLY;
        } else if (STAFF_ALIASES.contains(label)) {
            type = Type.STAFF;
        } else {
            return new Parsed(Type.NONE, null, null);
        }

        if (separator < 0) {
            return new Parsed(type, null, null);
        }
        int argumentsStart = skipWhitespace(input, separator);
        if (argumentsStart >= input.length()) {
            return new Parsed(type, null, null);
        }

        if (type != Type.PRIVATE) {
            return new Parsed(type, null, input.substring(argumentsStart));
        }

        ParsedTarget parsedTarget = parseTarget(input, argumentsStart);
        if (parsedTarget == null) {
            return new Parsed(type, null, null);
        }
        int messageStart = skipWhitespace(input, parsedTarget.end());
        if (messageStart >= input.length()) {
            return new Parsed(type, parsedTarget.value(), null);
        }
        return new Parsed(type, parsedTarget.value(), input.substring(messageStart));
    }

    private static String normaliseLabel(String rawLabel) {
        String lower = rawLabel.toLowerCase(Locale.ROOT);
        int namespace = lower.indexOf(':');
        if (namespace < 0) return lower;
        if (!lower.substring(0, namespace).equals("crabutilities")) return null;
        return lower.substring(namespace + 1);
    }

    private static ParsedTarget parseTarget(String input, int start) {
        if (input.charAt(start) != '"') {
            int end = firstWhitespace(input, start);
            if (end < 0) end = input.length();
            String target = input.substring(start, end);
            return target.isEmpty() ? null : new ParsedTarget(target, end);
        }

        StringBuilder target = new StringBuilder();
        boolean escaped = false;
        for (int i = start + 1; i < input.length(); i++) {
            char character = input.charAt(i);
            if (escaped) {
                if (character != '"' && character != '\\') return null;
                target.append(character);
                escaped = false;
            } else if (character == '\\') {
                escaped = true;
            } else if (character == '"') {
                int end = i + 1;
                if (end < input.length() && !Character.isWhitespace(input.charAt(end))) {
                    return null;
                }
                return target.isEmpty() ? null : new ParsedTarget(target.toString(), end);
            } else {
                target.append(character);
            }
        }
        return null;
    }

    private static int firstWhitespace(String input, int start) {
        for (int i = start; i < input.length(); i++) {
            if (Character.isWhitespace(input.charAt(i))) return i;
        }
        return -1;
    }

    private static int skipWhitespace(String input, int start) {
        int index = start;
        while (index < input.length() && Character.isWhitespace(input.charAt(index))) {
            index++;
        }
        return index;
    }

    private record ParsedTarget(String value, int end) {}
}
