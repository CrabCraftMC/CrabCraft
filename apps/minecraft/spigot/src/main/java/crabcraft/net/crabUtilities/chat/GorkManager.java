package crabcraft.net.crabUtilities.chat;

import crabcraft.net.crabUtilities.CrabMessages;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class GorkManager {

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
        responses.add(new GorkResponse("bold move for someone with no plan", 1));
        responses.add(new GorkResponse("spiritually im saying yes", 1));
        responses.add(new GorkResponse("source: it came to me in a vision", 2));
        responses.add(new GorkResponse("let him cook but keep the fire department nearby", 2));
        responses.add(new GorkResponse("do not let bro cook again", 1));
        responses.add(new GorkResponse("this could either work or be really funny", 2));
        responses.add(new GorkResponse("terrible idea. proceed immediately", 2));
        responses.add(new GorkResponse("im not qualified for this but neither are you", 2));
        responses.add(new GorkResponse("huge day for making questionable decisions", 1));
        responses.add(new GorkResponse("locked in on absolutely nothing", 1));
        responses.add(new GorkResponse("bro unlocked the wrong thought", 1));
        responses.add(new GorkResponse("aura gained somehow", 1));
        responses.add(new GorkResponse("negative aura with interest", 1));
        responses.add(new GorkResponse("this is a canon event i cannot interfere", 2));
        responses.add(new GorkResponse("sounds like a side quest with no rewards", 1));
        responses.add(new GorkResponse("wait for the patch notes", 1));
        responses.add(new GorkResponse("bro skipped the tutorial", 1));
        responses.add(new GorkResponse("skill issue respectfully", 1));
        responses.add(new GorkResponse("cooked beyond manufacturer recommendations", 2));
        responses.add(new GorkResponse("put bro back in the microwave", 1));
        responses.add(new GorkResponse("the plot has officially been misplaced", 2));
        responses.add(new GorkResponse("even the narrator is confused", 2));
        responses.add(new GorkResponse("actions have consequences big dawg", 1));
        responses.add(new GorkResponse("legally i have to say maybe", 2));
        responses.add(new GorkResponse("allegedly thats a good idea", 1));
        responses.add(new GorkResponse("be serious for like four seconds", 1));
        responses.add(new GorkResponse("stand up bro this is embarrassing", 1));
        responses.add(new GorkResponse("sit back down actually", 1));
        responses.add(new GorkResponse("perhaps. perhaps not. hope this helps", 2));
        responses.add(new GorkResponse("absolutely not but i respect the confidence", 1));
        responses.add(new GorkResponse("unfortunately yeah", 1));
        responses.add(new GorkResponse("ask again when my brain finishes updating", 2));
        responses.add(new GorkResponse("the council has approved your nonsense", 2));
        responses.add(new GorkResponse("the council said no and laughed", 2));
        responses.add(new GorkResponse("vibe check passed somehow", 1));
        responses.add(new GorkResponse("the vibes are medically rancid", 2));
        responses.add(new GorkResponse("brain loading please wait", 1));
        responses.add(new GorkResponse("that thought is still buffering", 1));
        responses.add(new GorkResponse("that sentence had paid dlc", 2));
        responses.add(new GorkResponse("punctuation could not have saved you", 1));
        responses.add(new GorkResponse("the facts left the chat", 1));
        responses.add(new GorkResponse("confidence doing all the heavy lifting rn", 2));
        responses.add(new GorkResponse("ive heard worse from smarter people", 2));
        responses.add(new GorkResponse("ive heard better from a smoke alarm", 2));
        responses.add(new GorkResponse("no notes because i stopped reading", 1));
        responses.add(new GorkResponse("several notes. none of them are helpful", 1));
        responses.add(new GorkResponse("big if true. microscopic if false", 2));
        responses.add(new GorkResponse("small if false", 1));
        responses.add(new GorkResponse("concerning amount of confidence here", 1));
        responses.add(new GorkResponse("inspiring amount of delusion", 1));
        responses.add(new GorkResponse("delete this before the historians find it", 2));
        responses.add(new GorkResponse("send it before common sense arrives", 1));
        responses.add(new GorkResponse("screenshotting this for the investigation", 2));
        responses.add(new GorkResponse("keep this one inside the group chat", 1));
        responses.add(new GorkResponse("future you is already mad", 1));
        responses.add(new GorkResponse("past you tried to warn us", 1));
        responses.add(new GorkResponse("present you needs supervision", 1));
        responses.add(new GorkResponse("sleep on it and forget by morning", 1));
        responses.add(new GorkResponse("dont sleep on it bro thats how it escapes", 2));
        responses.add(new GorkResponse("have you tried drinking water about it", 1));
        responses.add(new GorkResponse("touch grass before making this decision", 1));
        responses.add(new GorkResponse("the grass declined your request", 2));
        responses.add(new GorkResponse("outside is free btw", 1));
        responses.add(new GorkResponse("stay indoors actually the public isnt ready", 2));
        responses.add(new GorkResponse("the algorithm did not prepare me for this", 2));
        responses.add(new GorkResponse("run that by the group chat first", 1));
        responses.add(new GorkResponse("ask your mom she seems reasonable", 2));
        responses.add(new GorkResponse("put the phone down with both hands", 1));
        responses.add(new GorkResponse("pick the phone back up i need updates", 1));
        responses.add(new GorkResponse("im literally just pixels bro", 2));
        responses.add(new GorkResponse("dont quote me unless it works", 1));
        responses.add(new GorkResponse("quote me when this becomes legendary", 2));
        responses.add(new GorkResponse("gork certified moment", 1));
        responses.add(new GorkResponse("gork has denied your application", 2));
        responses.add(new GorkResponse("take those potatoes of doubt, season them with delusion, and fry them in the boiling oil of consequences", 7));
        responses.add(new GorkResponse("bro keeps waiting for a sign like the universe has push notifications enabled just do the thing and let tomorrow file the complaint", 10));
    }

    /**
     * Checks a chat message for "@gork" (case-insensitive) and returns
     * a randomly chosen response if it's present, or null otherwise.
     */
    public @Nullable String processMessage(String message) {
        if (message == null || !message.toLowerCase().contains("@gork")) {
            return null;
        }
        return pickResponse();
    }

    public static Component decorateMessage(@NotNull String message) {
        return Component.text("gork").color(CrabMessages.ACCENT)
                .append(Component.text(": ").color(NamedTextColor.GRAY))
                .append(Component.text(message).color(CrabMessages.TEXT));
    }

    private String pickResponse() {
        if (responses.isEmpty()) {
            return null;
        }

        double totalWeight = 0;
        for (GorkResponse r : responses) {
            totalWeight += r.getWeight();
        }

        double roll = random.nextDouble() * totalWeight;
        double cumulative = 0;

        for (GorkResponse r : responses) {
            cumulative += r.getWeight();
            if (roll <= cumulative) {
                return r.message();
            }
        }

        // fallback in case of floating point rounding
        return responses.getLast().message();
    }

    private record GorkResponse(String message, int rarity) {
        double getWeight() {
            return 1.0 / rarity;
        }
    }
}