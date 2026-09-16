package crabcraft.net.crabUtilities.bingo;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;

import java.util.Set;

/** A six-line card with task text kept in hover tooltips. */
final class BingoChatView {
    private BingoChatView() {}

    static Component grid(BingoActiveCard card, Set<String> completed) {
        long count = card.taskIds().stream().filter(completed::contains).count();
        Component message = Component.text("Bingo #" + card.number(), NamedTextColor.GOLD)
                .append(Component.text(" | " + count + "/16 complete", NamedTextColor.GRAY));
        for (int row = 0; row < 4; row++) {
            message = message.append(Component.newline());
            for (int column = 0; column < 4; column++) {
                int square = row * 4 + column;
                boolean done = completed.contains(card.taskIds().get(square));
                if (column > 0) message = message.append(Component.text(" "));
                message = message.append(Component.text(
                                "[" + coordinate(square) + (done ? " +]" : " -]"),
                                done ? NamedTextColor.GREEN : NamedTextColor.GRAY)
                        .hoverEvent(detail(card, completed, square))
                        .clickEvent(ClickEvent.runCommand("/bingo " + coordinate(square))));
            }
        }
        return message.append(Component.newline()).append(Component.text(
                "+ done, - to do | Hover or click a square", NamedTextColor.DARK_GRAY));
    }

    static Component detail(BingoActiveCard card, Set<String> completed, int square) {
        String taskId = card.taskIds().get(square);
        BingoTask task = BingoTask.fromId(taskId).orElseThrow();
        boolean done = completed.contains(taskId);
        return Component.text(coordinate(square) + (done ? " | Complete" : " | To do"),
                        done ? NamedTextColor.GREEN : NamedTextColor.GOLD)
                .append(Component.newline())
                .append(Component.text(wrap(task.description()), NamedTextColor.WHITE))
                .append(Component.newline())
                .append(Component.text(wrap(task.detail()), NamedTextColor.GRAY));
    }

    static String coordinate(int square) {
        return "" + (char) ('A' + square % 4) + (square / 4 + 1);
    }

    private static String wrap(String text) {
        StringBuilder wrapped = new StringBuilder();
        int width = 0;
        for (String word : text.split("\\s+")) {
            if (width > 0) {
                if (width + 1 + word.length() > 48) {
                    wrapped.append('\n');
                    width = 0;
                } else {
                    wrapped.append(' ');
                    width++;
                }
            }
            wrapped.append(word);
            width += word.length();
        }
        return wrapped.toString();
    }
}
