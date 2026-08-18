package crabcraft.net.crabUtilities.chat;

import crabcraft.net.crabUtilities.CrabMessages;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** Chooses and formats responses to {@code @gork} messages. */
public final class GorkManager {

    private final List<GorkResponse> responses = new ArrayList<>();
    private final Random random = new Random();

    public GorkManager() {
        responses.add(new GorkResponse("idk man ask chat jipity", 3));
        responses.add(new GorkResponse("yeah brah", 1));
        responses.add(new GorkResponse("go for it dude", 1));
        responses.add(new GorkResponse("whatever", 1));
        responses.add(new GorkResponse("so skibidi", 1));
        responses.add(new GorkResponse("lowk yeah", 1));
        responses.add(new GorkResponse("tbh its a good idea", 1));
        responses.add(new GorkResponse("im lowk the goat of just saying shit", 2));
        responses.add(new GorkResponse("the most entertaining outcome is the most likely", 3));
        responses.add(new GorkResponse("i agree fr", 1));
        responses.add(new GorkResponse("nah bro dont", 1));
        responses.add(new GorkResponse("what are you, 12", 1));
        responses.add(new GorkResponse("waaah waaah waaah", 2));
        responses.add(new GorkResponse("dont ping me again", 5));
        responses.add(new GorkResponse("what do you want", 1));
        responses.add(new GorkResponse("respect the grind i guess", 1));
        responses.add(new GorkResponse("ur cooked bro", 1));
        responses.add(new GorkResponse("bro thought i would care", 1));
        responses.add(new GorkResponse("sounds like a you problem ngl", 1));
        responses.add(new GorkResponse("figure it out big bro", 1));
        responses.add(new GorkResponse("idk i wasnt listening", 1));
        responses.add(new GorkResponse("much love", 1));
        responses.add(new GorkResponse("nah, intelligence aint showing up till you drop the truth and turn those potatoes into fries overnight", 10));
        responses.add(new GorkResponse("lmao keep waiting for the whole world to wake up and clap for ur reflection while u sit there blindfolded bro just flip the switch urself and turn those potatoes into fries already", 10));
    }

    public @Nullable String processMessage(String message) {
        if (message == null || !message.toLowerCase().contains("@gork")) {
            return null;
        }
        double totalWeight = responses.stream().mapToDouble(GorkResponse::weight).sum();
        double roll = random.nextDouble() * totalWeight;
        for (GorkResponse response : responses) {
            roll -= response.weight();
            if (roll <= 0) return response.message();
        }
        return responses.getLast().message();
    }

    public static Component decorateMessage(@NotNull String message) {
        return Component.text("gork").color(CrabMessages.ACCENT)
                .append(Component.text(": ").color(NamedTextColor.GRAY))
                .append(Component.text(message).color(CrabMessages.TEXT));
    }

    private record GorkResponse(String message, int rarity) {
        double weight() { return 1.0 / rarity; }
    }
}
