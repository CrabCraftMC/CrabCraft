package crabcraft.net.crabUtilities.chat

import crabcraft.net.crabUtilities.CrabMessages
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import java.util.Locale
import java.util.Random

open class GorkManager {
    private val responses = ArrayList<GorkResponse>()
    private val random = Random()

    init {
        responses.add(GorkResponse("idk man ask chat jipity", 3))
        responses.add(GorkResponse("yeah brah", 1))
        responses.add(GorkResponse("go for it dude", 1))
        responses.add(GorkResponse("whatever", 1))
        responses.add(GorkResponse("so skibidi", 1))
        responses.add(GorkResponse("lowk yeah", 1))
        responses.add(GorkResponse("tbh its a good idea", 1))
        responses.add(GorkResponse("im lowk the goat of just saying shit", 2))
        responses.add(GorkResponse("the most entertaining outcome is the most likely", 3))
        responses.add(GorkResponse("i agree fr", 1))
        responses.add(GorkResponse("nah bro dont", 1))
        responses.add(GorkResponse("what are you, 12", 1))
        responses.add(GorkResponse("waaah waaah waaah", 2))
        responses.add(GorkResponse("dont ping me again", 5))
        responses.add(GorkResponse("what do you want", 1))
        responses.add(GorkResponse("respect the grind i guess", 1))
        responses.add(GorkResponse("ur cooked bro", 1))
        responses.add(GorkResponse("bro thought i would care", 1))
        responses.add(GorkResponse("sounds like a you problem ngl", 1))
        responses.add(GorkResponse("figure it out big bro", 1))
        responses.add(GorkResponse("idk i wasnt listening", 1))
        responses.add(GorkResponse("much love", 1))
        responses.add(GorkResponse("nah, intelligence aint showing up till you drop the truth and turn those potatoes into fries overnight", 10))
        responses.add(GorkResponse("lmao keep waiting for the whole world to wake up and clap for ur reflection while u sit there blindfolded bro just flip the switch urself and turn those potatoes into fries already", 10))
        responses.add(GorkResponse("bold move for someone with no plan", 1))
        responses.add(GorkResponse("spiritually im saying yes", 1))
        responses.add(GorkResponse("source: it came to me in a vision", 2))
        responses.add(GorkResponse("let him cook but keep the fire department nearby", 2))
        responses.add(GorkResponse("do not let bro cook again", 1))
        responses.add(GorkResponse("this could either work or be really funny", 2))
        responses.add(GorkResponse("terrible idea. proceed immediately", 2))
        responses.add(GorkResponse("im not qualified for this but neither are you", 2))
        responses.add(GorkResponse("huge day for making questionable decisions", 1))
        responses.add(GorkResponse("locked in on absolutely nothing", 1))
        responses.add(GorkResponse("bro unlocked the wrong thought", 1))
        responses.add(GorkResponse("aura gained somehow", 1))
        responses.add(GorkResponse("negative aura with interest", 1))
        responses.add(GorkResponse("this is a canon event i cannot interfere", 2))
        responses.add(GorkResponse("sounds like a side quest with no rewards", 1))
        responses.add(GorkResponse("wait for the patch notes", 1))
        responses.add(GorkResponse("bro skipped the tutorial", 1))
        responses.add(GorkResponse("skill issue respectfully", 1))
        responses.add(GorkResponse("cooked beyond manufacturer recommendations", 2))
        responses.add(GorkResponse("put bro back in the microwave", 1))
        responses.add(GorkResponse("the plot has officially been misplaced", 2))
        responses.add(GorkResponse("even the narrator is confused", 2))
        responses.add(GorkResponse("actions have consequences big dawg", 1))
        responses.add(GorkResponse("legally i have to say maybe", 2))
        responses.add(GorkResponse("allegedly thats a good idea", 1))
        responses.add(GorkResponse("be serious for like four seconds", 1))
        responses.add(GorkResponse("stand up bro this is embarrassing", 1))
        responses.add(GorkResponse("sit back down actually", 1))
        responses.add(GorkResponse("perhaps. perhaps not. hope this helps", 2))
        responses.add(GorkResponse("absolutely not but i respect the confidence", 1))
        responses.add(GorkResponse("unfortunately yeah", 1))
        responses.add(GorkResponse("ask again when my brain finishes updating", 2))
        responses.add(GorkResponse("the council has approved your nonsense", 2))
        responses.add(GorkResponse("the council said no and laughed", 2))
        responses.add(GorkResponse("vibe check passed somehow", 1))
        responses.add(GorkResponse("the vibes are medically rancid", 2))
        responses.add(GorkResponse("brain loading please wait", 1))
        responses.add(GorkResponse("that thought is still buffering", 1))
        responses.add(GorkResponse("that sentence had paid dlc", 2))
        responses.add(GorkResponse("punctuation could not have saved you", 1))
        responses.add(GorkResponse("the facts left the chat", 1))
        responses.add(GorkResponse("confidence doing all the heavy lifting rn", 2))
        responses.add(GorkResponse("ive heard worse from smarter people", 2))
        responses.add(GorkResponse("ive heard better from a smoke alarm", 2))
        responses.add(GorkResponse("no notes because i stopped reading", 1))
        responses.add(GorkResponse("several notes. none of them are helpful", 1))
        responses.add(GorkResponse("big if true. microscopic if false", 2))
        responses.add(GorkResponse("small if false", 1))
        responses.add(GorkResponse("concerning amount of confidence here", 1))
        responses.add(GorkResponse("inspiring amount of delusion", 1))
        responses.add(GorkResponse("delete this before the historians find it", 2))
        responses.add(GorkResponse("send it before common sense arrives", 1))
        responses.add(GorkResponse("screenshotting this for the investigation", 2))
        responses.add(GorkResponse("keep this one inside the group chat", 1))
        responses.add(GorkResponse("future you is already mad", 1))
        responses.add(GorkResponse("past you tried to warn us", 1))
        responses.add(GorkResponse("present you needs supervision", 1))
        responses.add(GorkResponse("sleep on it and forget by morning", 1))
        responses.add(GorkResponse("dont sleep on it bro thats how it escapes", 2))
        responses.add(GorkResponse("have you tried drinking water about it", 1))
        responses.add(GorkResponse("touch grass before making this decision", 1))
        responses.add(GorkResponse("the grass declined your request", 2))
        responses.add(GorkResponse("outside is free btw", 1))
        responses.add(GorkResponse("stay indoors actually the public isnt ready", 2))
        responses.add(GorkResponse("the algorithm did not prepare me for this", 2))
        responses.add(GorkResponse("run that by the group chat first", 1))
        responses.add(GorkResponse("ask your mom she seems reasonable", 2))
        responses.add(GorkResponse("put the phone down with both hands", 1))
        responses.add(GorkResponse("pick the phone back up i need updates", 1))
        responses.add(GorkResponse("im literally just pixels bro", 2))
        responses.add(GorkResponse("dont quote me unless it works", 1))
        responses.add(GorkResponse("quote me when this becomes legendary", 2))
        responses.add(GorkResponse("gork certified moment", 1))
        responses.add(GorkResponse("gork has denied your application", 2))
        responses.add(GorkResponse("take those potatoes of doubt, season them with delusion, and fry them in the boiling oil of consequences", 7))
        responses.add(GorkResponse("bro keeps waiting for a sign like the universe has push notifications enabled just do the thing and let tomorrow file the complaint", 10))
    }

    /** Returns a randomly chosen response when the message mentions "@gork". */
    open fun processMessage(message: String?): String? {
        if (message == null || !message.lowercase(Locale.getDefault()).contains("@gork")) return null
        return pickResponse()
    }

    private fun pickResponse(): String? {
        if (responses.isEmpty()) return null
        var totalWeight = 0.0
        for (response in responses) totalWeight += response.getWeight()
        val roll = random.nextDouble() * totalWeight
        var cumulative = 0.0
        for (response in responses) {
            cumulative += response.getWeight()
            if (roll <= cumulative) return response.message()
        }
        // Fallback in case of floating point rounding.
        return responses.last().message()
    }

    private data class GorkResponse(private val message: String, private val rarity: Int) {
        fun message(): String = message
        fun rarity(): Int = rarity
        fun getWeight(): Double = 1.0 / rarity
    }

    companion object {
        @JvmStatic
        fun decorateMessage(message: String): Component = Component.text("gork").color(CrabMessages.ACCENT)
            .append(Component.text(": ").color(NamedTextColor.GRAY))
            .append(Component.text(message).color(CrabMessages.TEXT))
    }
}
