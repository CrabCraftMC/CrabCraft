package crabcraft.net.crabUtilities.jade.protocol.provider

import crabcraft.net.crabUtilities.jade.protocol.accessor.Accessor
import crabcraft.net.crabUtilities.jade.protocol.util.ViewGroup

interface ServerExtensionProvider<T> : JadeProvider {
    fun getGroups(request: Accessor<*>): List<ViewGroup<T>>?
}
