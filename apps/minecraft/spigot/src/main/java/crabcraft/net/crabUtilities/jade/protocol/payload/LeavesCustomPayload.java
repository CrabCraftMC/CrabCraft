package crabcraft.net.crabUtilities.jade.protocol.payload;

import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import org.jetbrains.annotations.NotNull;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Re-implementation of Leaf's LeavesCustomPayload marker for plugin use.
 * Keeps {@code @ID}/{@code @Codec} annotations so existing payload classes
 * (ported from Leaf) work unmodified aside from the import swap.
 */
public interface LeavesCustomPayload extends CustomPacketPayload {

    Type<? extends CustomPacketPayload> LEAVES_TYPE = new Type<>(Identifier.fromNamespaceAndPath("leaves", "custom_payload"));

    @Override
    default @NotNull Type<? extends CustomPacketPayload> type() {
        return LEAVES_TYPE;
    }

    @Target(ElementType.FIELD)
    @Retention(RetentionPolicy.RUNTIME)
    @interface ID {
    }

    @Target(ElementType.FIELD)
    @Retention(RetentionPolicy.RUNTIME)
    @interface Codec {
    }
}
