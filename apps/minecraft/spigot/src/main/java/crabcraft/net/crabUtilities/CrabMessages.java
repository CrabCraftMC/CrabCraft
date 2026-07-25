package crabcraft.net.crabUtilities;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.jetbrains.annotations.NotNull;

/** Shared player-facing colours and message components for CrabUtilities. */
public final class CrabMessages {

    public static final String ACCENT_TAG = "<#FC835C>";
    public static final String HIGHLIGHT_TAG = "<#FCD05C>";
    public static final String SUCCESS_TAG = "<#77dd77>";
    public static final String ERROR_TAG = "<#f77069>";
    public static final String TEXT_TAG = "<#F4F1EA>";
    public static final String MUTED_TAG = "<#b0b0b0>";

    public static final TextColor ACCENT = TextColor.color(0xFC835C);
    public static final TextColor HIGHLIGHT = TextColor.color(0xFCD05C);
    public static final TextColor SUCCESS = TextColor.color(0x77DD77);
    public static final TextColor ERROR = TextColor.color(0xF77069);
    public static final TextColor TEXT = TextColor.color(0xF4F1EA);
    public static final TextColor MUTED = TextColor.color(0xB0B0B0);

    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();

    private CrabMessages() {
    }

    public static @NotNull Component text(@NotNull String message) {
        return component(message, TEXT);
    }

    public static @NotNull Component muted(@NotNull String message) {
        return component(message, MUTED);
    }

    public static @NotNull Component accent(@NotNull String message) {
        return component(message, ACCENT);
    }

    public static @NotNull Component highlight(@NotNull String message) {
        return component(message, HIGHLIGHT);
    }

    public static @NotNull Component success(@NotNull String message) {
        return component(message, SUCCESS);
    }

    public static @NotNull Component error(@NotNull String message) {
        return component(message, ERROR);
    }

    public static @NotNull Component warning(@NotNull String message) {
        return component(message, HIGHLIGHT);
    }

    public static @NotNull Component label(@NotNull String label, @NotNull Object value) {
        return component(label + ": ", ACCENT)
                .append(component(String.valueOf(value), TEXT));
    }

    public static @NotNull Component label(@NotNull String label, @NotNull Component value) {
        return component(label + ": ", ACCENT).append(value);
    }

    public static @NotNull Component mini(@NotNull String value) {
        return MINI_MESSAGE.deserialize(value).decoration(TextDecoration.ITALIC, false);
    }

    private static @NotNull Component component(@NotNull String message,
                                                @NotNull TextColor colour) {
        return Component.text(message, colour).decoration(TextDecoration.ITALIC, false);
    }
}
