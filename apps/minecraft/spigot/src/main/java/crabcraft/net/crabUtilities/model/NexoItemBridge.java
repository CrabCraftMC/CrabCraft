package crabcraft.net.crabUtilities.model;

import com.nexomc.nexo.api.NexoItems;

/** The only model-merging class that links directly against the optional Nexo API. */
final class NexoItemBridge {

    private NexoItemBridge() {
    }

    static NexoItemLookup create() {
        return item -> NexoItems.exists(item) ? NexoItems.idFromItem(item) : null;
    }
}
