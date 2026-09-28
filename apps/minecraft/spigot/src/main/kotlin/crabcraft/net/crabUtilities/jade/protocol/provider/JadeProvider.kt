package crabcraft.net.crabUtilities.jade.protocol.provider

import net.minecraft.resources.Identifier

interface JadeProvider {
    fun getUid(): Identifier

    fun getDefaultPriority(): Int = 0
}
