package crabcraft.net.crabUtilities.velocity

import crabcraft.net.crabUtilities.nickname.NicknameParser
import net.kyori.adventure.text.Component

/** Velocity nickname parsing preserves empty components and untrimmed plain text. */
object NicknameComponentParser {
    @JvmStatic fun parse(raw: String?): Component = NicknameParser.parse(raw)

    @JvmStatic fun plain(raw: String?): String? = NicknameParser.plain(raw)
}
