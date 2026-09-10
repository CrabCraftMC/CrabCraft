package crabcraft.net.crabUtilities.bingo;

import com.google.gson.Gson;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/** Checks that Redis order, completion marks and clickable coordinates stay aligned. */
public final class BingoChatViewRegressionTest {
    public static void main(String[] args) {
        Random random = new Random(824019L);
        List<String> taskIds = new ArrayList<>(BingoTask.allDeployed().stream().map(BingoTask::id).toList());
        Collections.shuffle(taskIds, random);
        taskIds = List.copyOf(taskIds.subList(0, 16));
        BingoActiveCard card = BingoActiveCard.fromJson(new Gson().toJson(Map.of(
                "id", random.nextInt(10_000), "number", random.nextInt(10_000),
                "startsAt", 0, "endsAt", Long.MAX_VALUE, "taskIds", taskIds)));
        check(card.taskIds().equals(taskIds), "Redis task order must survive parsing");

        Set<String> completed = new HashSet<>(Set.of(taskIds.get(0), taskIds.get(9), taskIds.get(15)));
        completed.add("synthetic-retired-" + random.nextInt());
        Component grid = BingoChatView.grid(card, completed);
        check(plain(grid).lines().count() == 6, "The grid must stay within six lines");
        check(plain(grid).contains("3/16 complete"), "Only tasks on this card count");
        List<Component> squares = grid.children().stream().filter(child -> child.clickEvent() != null).toList();
        check(squares.size() == 16, "Every square must be clickable");
        for (int index = 0; index < 16; index++) {
            Component square = squares.get(index);
            String coordinate = "ABCD".charAt(index % 4) + Integer.toString(index / 4 + 1);
            check(square.clickEvent().equals(ClickEvent.runCommand("/bingo " + coordinate)),
                    "Click target must match the displayed position");
            boolean done = completed.contains(taskIds.get(index));
            check(plain(square).equals("[" + coordinate + (done ? " +]" : " -]")),
                    "Completion mark must belong to the task at this position");
            check(square.color().equals(done ? NamedTextColor.GREEN : NamedTextColor.GRAY),
                    "Completion colour must agree with the mark");
            Component detail = BingoChatView.detail(card, completed, index);
            check(square.hoverEvent() != null && square.hoverEvent().value().equals(detail),
                    "Hover and click details must agree");
            BingoTask task = BingoTask.fromId(taskIds.get(index)).orElseThrow();
            check(plain(detail).replace('\n', ' ').contains(task.description()),
                    "Details must describe the task at this position");
        }
        check(plain(BingoChatView.grid(card, Set.of())).contains("0/16 complete"), "Empty progress");
        check(plain(BingoChatView.grid(card, Set.copyOf(taskIds))).contains("16/16 complete"), "Full progress");
    }

    private static String plain(Component component) {
        StringBuilder text = new StringBuilder(component instanceof TextComponent literal ? literal.content() : "");
        component.children().forEach(child -> text.append(plain(child)));
        return text.toString();
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
