package crabcraft.net.crabUtilities.jade.protocol.util

import io.netty.buffer.ByteBuf
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.resources.Identifier
import java.util.Optional

open class ViewGroup<T>(
    @JvmField var views: List<T>,
    id: Optional<String>,
    extraData: Optional<CompoundTag>
) {
    @JvmField var id: String? = id.orElse(null)
    @JvmField protected var extraData: CompoundTag? = extraData.orElse(null)

    constructor(views: List<T>) : this(views, Optional.empty(), Optional.empty())

    open fun getExtraData(): CompoundTag {
        var data = extraData
        if (data == null) {
            data = CompoundTag()
            extraData = data
        }
        return data
    }

    companion object {
        @JvmStatic
        fun <B : ByteBuf, T : Any> codec(viewCodec: StreamCodec<B, T>): StreamCodec<B, ViewGroup<T>> =
            StreamCodec.composite(
                ByteBufCodecs.list<B, T>().apply(viewCodec),
                { group: ViewGroup<T> -> group.views },
                ByteBufCodecs.optional(ByteBufCodecs.STRING_UTF8),
                { group: ViewGroup<T> -> Optional.ofNullable(group.id) },
                ByteBufCodecs.optional(ByteBufCodecs.COMPOUND_TAG),
                { group: ViewGroup<T> -> Optional.ofNullable(group.extraData) },
                { views, id, extraData -> ViewGroup(views, id, extraData) }
            )

        @JvmStatic
        fun <B : ByteBuf, T : Any> listCodec(viewCodec: StreamCodec<B, T>): StreamCodec<B, Map.Entry<Identifier, List<ViewGroup<T>>>> =
            StreamCodec.composite(
                Identifier.STREAM_CODEC,
                { entry: Map.Entry<Identifier, List<ViewGroup<T>>> -> entry.key },
                ByteBufCodecs.list<B, ViewGroup<T>>().apply(codec(viewCodec)),
                { entry: Map.Entry<Identifier, List<ViewGroup<T>>> -> entry.value },
                { id, groups -> java.util.Map.entry(id, groups) }
            )
    }
}
