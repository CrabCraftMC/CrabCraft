package crabcraft.net.crabUtilities.model

import com.nexomc.nexo.api.NexoItems

/** The only model-merging class that links directly against the optional Nexo API. */
object NexoItemBridge {
    @JvmStatic
    fun create(): NexoItemLookup = NexoItemLookup { item ->
        if (NexoItems.exists(item)) NexoItems.idFromItem(item) else null
    }
}
