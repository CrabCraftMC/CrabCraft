package crabcraft.net.crabUtilities.chat

import crabcraft.net.crabUtilities.CrabMessages
import java.util.Locale
import java.util.Random
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor

open class GorkManager {
    private val responses =
        listOf(
            GorkResponse("idk man ask chat jipity", 3),
            GorkResponse("yeah brah", 1),
            GorkResponse("go for it dude", 1),
            GorkResponse("whatever", 1),
            GorkResponse("so skibidi", 1),
            GorkResponse("lowk yeah", 1),
            GorkResponse("tbh its a good idea", 1),
            GorkResponse("im lowk the goat of just saying shit", 2),
            GorkResponse("the most entertaining outcome is the most likely", 3),
            GorkResponse("i agree fr", 1),
            GorkResponse("nah bro dont", 1),
            GorkResponse("what are you, 12", 1),
            GorkResponse("waaah waaah waaah", 2),
            GorkResponse("dont ping me again", 5),
            GorkResponse("what do you want", 1),
            GorkResponse("respect the grind i guess", 1),
            GorkResponse("ur cooked bro", 1),
            GorkResponse("bro thought i would care", 1),
            GorkResponse("sounds like a you problem ngl", 1),
            GorkResponse("figure it out big bro", 1),
            GorkResponse("idk i wasnt listening", 1),
            GorkResponse("much love", 1),
            GorkResponse(
                "nah, intelligence aint showing up till you drop the truth and turn those potatoes into fries overnight",
                10,
            ),
            GorkResponse(
                "lmao keep waiting for the whole world to wake up and clap for ur reflection while u sit there blindfolded bro just flip the switch urself and turn those potatoes into fries already",
                10,
            ),
            GorkResponse("bold move for someone with no plan", 1),
            GorkResponse("spiritually im saying yes", 1),
            GorkResponse("source: it came to me in a vision", 2),
            GorkResponse("let him cook but keep the fire department nearby", 2),
            GorkResponse("do not let bro cook again", 1),
            GorkResponse("this could either work or be really funny", 2),
            GorkResponse("terrible idea. proceed immediately", 2),
            GorkResponse("im not qualified for this but neither are you", 2),
            GorkResponse("huge day for making questionable decisions", 1),
            GorkResponse("locked in on absolutely nothing", 1),
            GorkResponse("bro unlocked the wrong thought", 1),
            GorkResponse("aura gained somehow", 1),
            GorkResponse("negative aura with interest", 1),
            GorkResponse("this is a canon event i cannot interfere", 2),
            GorkResponse("sounds like a side quest with no rewards", 1),
            GorkResponse("wait for the patch notes", 1),
            GorkResponse("bro skipped the tutorial", 1),
            GorkResponse("skill issue respectfully", 1),
            GorkResponse("cooked beyond manufacturer recommendations", 2),
            GorkResponse("put bro back in the microwave", 1),
            GorkResponse("the plot has officially been misplaced", 2),
            GorkResponse("even the narrator is confused", 2),
            GorkResponse("actions have consequences big dawg", 1),
            GorkResponse("legally i have to say maybe", 2),
            GorkResponse("allegedly thats a good idea", 1),
            GorkResponse("be serious for like four seconds", 1),
            GorkResponse("stand up bro this is embarrassing", 1),
            GorkResponse("sit back down actually", 1),
            GorkResponse("perhaps. perhaps not. hope this helps", 2),
            GorkResponse("absolutely not but i respect the confidence", 1),
            GorkResponse("unfortunately yeah", 1),
            GorkResponse("ask again when my brain finishes updating", 2),
            GorkResponse("the council has approved your nonsense", 2),
            GorkResponse("the council said no and laughed", 2),
            GorkResponse("vibe check passed somehow", 1),
            GorkResponse("the vibes are medically rancid", 2),
            GorkResponse("brain loading please wait", 1),
            GorkResponse("that thought is still buffering", 1),
            GorkResponse("that sentence had paid dlc", 2),
            GorkResponse("punctuation could not have saved you", 1),
            GorkResponse("the facts left the chat", 1),
            GorkResponse("confidence doing all the heavy lifting rn", 2),
            GorkResponse("ive heard worse from smarter people", 2),
            GorkResponse("ive heard better from a smoke alarm", 2),
            GorkResponse("no notes because i stopped reading", 1),
            GorkResponse("several notes. none of them are helpful", 1),
            GorkResponse("big if true. microscopic if false", 2),
            GorkResponse("small if false", 1),
            GorkResponse("concerning amount of confidence here", 1),
            GorkResponse("inspiring amount of delusion", 1),
            GorkResponse("delete this before the historians find it", 2),
            GorkResponse("send it before common sense arrives", 1),
            GorkResponse("screenshotting this for the investigation", 2),
            GorkResponse("keep this one inside the group chat", 1),
            GorkResponse("future you is already mad", 1),
            GorkResponse("past you tried to warn us", 1),
            GorkResponse("present you needs supervision", 1),
            GorkResponse("sleep on it and forget by morning", 1),
            GorkResponse("dont sleep on it bro thats how it escapes", 2),
            GorkResponse("have you tried drinking water about it", 1),
            GorkResponse("touch grass before making this decision", 1),
            GorkResponse("the grass declined your request", 2),
            GorkResponse("outside is free btw", 1),
            GorkResponse("stay indoors actually the public isnt ready", 2),
            GorkResponse("the algorithm did not prepare me for this", 2),
            GorkResponse("run that by the group chat first", 1),
            GorkResponse("ask your mom she seems reasonable", 2),
            GorkResponse("put the phone down with both hands", 1),
            GorkResponse("pick the phone back up i need updates", 1),
            GorkResponse("im literally just pixels bro", 2),
            GorkResponse("dont quote me unless it works", 1),
            GorkResponse("quote me when this becomes legendary", 2),
            GorkResponse("gork certified moment", 1),
            GorkResponse("gork has denied your application", 2),
            GorkResponse(
                "take those potatoes of doubt, season them with delusion, and fry them in the boiling oil of consequences",
                7,
            ),
            GorkResponse(
                "bro keeps waiting for a sign like the universe has push notifications enabled just do the thing and let tomorrow file the complaint",
                10,
            ),
        )
    private val random = Random()

    /** Returns a randomly chosen response for a case-insensitive @gork mention. */
    open fun processMessage(message: String?): String? {
        if (message == null || !message.lowercase(Locale.getDefault()).contains("@gork")) return null
        return pickResponse()
    }

    private fun pickResponse(): String? {
        if (responses.isEmpty()) return null
        val totalWeight = responses.sumOf { it.getWeight() }
        val roll = random.nextDouble() * totalWeight
        var cumulative = 0.0
        for (response in responses) {
            cumulative += response.getWeight()
            if (roll <= cumulative) return response.message
        }
        // Fallback in case of floating point rounding.
        return responses.last().message
    }

    private data class GorkResponse(val message: String, val rarity: Int) {
        fun getWeight(): Double = 1.0 / rarity
    }

    companion object {
        @JvmStatic
        fun decorateMessage(message: String): Component =
            Component.text("gork")
                .color(CrabMessages.ACCENT)
                .append(Component.text(": ").color(NamedTextColor.GRAY))
                .append(Component.text(message).color(CrabMessages.TEXT))
    }
}
