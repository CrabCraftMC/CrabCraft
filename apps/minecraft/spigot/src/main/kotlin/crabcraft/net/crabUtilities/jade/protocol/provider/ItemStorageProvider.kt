package crabcraft.net.crabUtilities.jade.protocol.provider

import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.Tag
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.resources.Identifier
import net.minecraft.world.LockCode
import net.minecraft.world.RandomizableContainer
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity
import crabcraft.net.crabUtilities.jade.JadeBootstrap
import crabcraft.net.crabUtilities.jade.protocol.JadeProtocol
import crabcraft.net.crabUtilities.jade.protocol.accessor.Accessor
import crabcraft.net.crabUtilities.jade.protocol.accessor.BlockAccessor
import crabcraft.net.crabUtilities.jade.protocol.accessor.EntityAccessor
import crabcraft.net.crabUtilities.jade.protocol.util.CommonUtil
import crabcraft.net.crabUtilities.jade.protocol.util.ItemCollector
import crabcraft.net.crabUtilities.jade.protocol.util.ViewGroup
import java.util.concurrent.atomic.AtomicBoolean

abstract class ItemStorageProvider<T : Accessor<*>> : ServerDataProvider<T> {
    override fun getUid(): Identifier = UNIVERSAL_ITEM_STORAGE

    override fun appendServerData(data: CompoundTag, accessor: T) {
        if (accessor.getTarget() is AbstractFurnaceBlockEntity) {
            return
        }
        putData(data, accessor)
    }

    override fun getDefaultPriority(): Int = 1000

    open class ForBlock : ItemStorageProvider<BlockAccessor>() {
        companion object {
            internal val INSTANCE = ForBlock()
        }
    }

    open class ForEntity : ItemStorageProvider<EntityAccessor>() {
        companion object {
            internal val INSTANCE = ForEntity()
        }
    }

    enum class Encoding {
        NATIVE,
        VERSIONED,
        UNAVAILABLE,
    }

    companion object {
        private val NATIVE_STREAM_CODEC: StreamCodec<RegistryFriendlyByteBuf, Map.Entry<Identifier, List<ViewGroup<ItemStack>>>> =
            ViewGroup.listCodec(ItemStack.OPTIONAL_STREAM_CODEC)
        private val UNIVERSAL_ITEM_STORAGE = JadeProtocol.mc_id("item_storage")
        private val VERSIONED_CODEC_AVAILABLE = AtomicBoolean(true)
        private val VERSIONED_CODEC_CAPABILITY_WARNING_LOGGED = AtomicBoolean()
        private val VERSIONED_CODEC_RESPONSE_WARNING_LOGGED = AtomicBoolean()

        @JvmStatic
        fun getBlock(): ForBlock = ForBlock.INSTANCE

        @JvmStatic
        fun getEntity(): ForEntity = ForEntity.INSTANCE

        @JvmStatic
        fun putData(tag: CompoundTag, accessor: Accessor<*>) {
            val target = accessor.getTarget()
            val player = accessor.getPlayer()
            val clientProtocol = JadeProtocol.getClientProtocol(player)
            val serverProtocol = JadeProtocol.getServerProtocol()
            val encoding = selectEncoding(
                clientProtocol,
                serverProtocol,
                JadeProtocol.isPacketEventsAvailable() && VERSIONED_CODEC_AVAILABLE.get(),
            )
            if (encoding == Encoding.UNAVAILABLE && clientProtocol == serverProtocol + 1) {
                warnVersionedCodecUnavailable(clientProtocol, null)
            }
            if (encoding != Encoding.UNAVAILABLE) {
                val entry = CommonUtil.getServerExtensionData(accessor, JadeProtocol.itemStorageProviders)
                if (entry != null) {
                    for (group in entry.value) {
                        if (group.views.size > ItemCollector.MAX_SIZE) {
                            group.views = group.views.subList(0, ItemCollector.MAX_SIZE)
                        }
                    }
                    val encoded = encode(accessor, entry, encoding, clientProtocol)
                    if (encoded != null) {
                        tag.put(UNIVERSAL_ITEM_STORAGE.toString(), encoded)
                        return
                    }
                }
            }
            if (target is RandomizableContainer && target.lootTable != null) {
                tag.putBoolean("Loot", true)
            } else if (!player.isCreative && !player.isSpectator && target is BaseContainerBlockEntity) {
                if (target.lockKey !== LockCode.NO_LOCK) {
                    tag.putBoolean("Locked", true)
                }
            }
        }

        private fun encode(
            accessor: Accessor<*>,
            entry: Map.Entry<Identifier, List<ViewGroup<ItemStack>>>,
            encoding: Encoding,
            clientProtocol: Int,
        ): Tag? {
            if (encoding == Encoding.NATIVE) {
                return accessor.encodeAsNbt(NATIVE_STREAM_CODEC, entry)
            }
            return try {
                PacketEventsItemStackEncoder.encode(accessor, entry, clientProtocol)
            } catch (e: PacketEventsItemStackEncoder.UnsupportedClientProtocolException) {
                VERSIONED_CODEC_AVAILABLE.set(false)
                warnVersionedCodecUnavailable(clientProtocol, e)
                null
            } catch (e: LinkageError) {
                VERSIONED_CODEC_AVAILABLE.set(false)
                warnVersionedCodecUnavailable(clientProtocol, e)
                null
            } catch (e: RuntimeException) {
                if (VERSIONED_CODEC_RESPONSE_WARNING_LOGGED.compareAndSet(false, true)) {
                    JadeBootstrap.LOGGER.warn(
                        "Could not encode a Jade item storage response for client protocol {}; " +
                            "this inventory response will be omitted.",
                        clientProtocol, e,
                    )
                }
                null
            }
        }

        private fun warnVersionedCodecUnavailable(clientProtocol: Int, cause: Throwable?) {
            if (!VERSIONED_CODEC_CAPABILITY_WARNING_LOGGED.compareAndSet(false, true)) {
                return
            }
            val message = "Jade item storage is unavailable for client protocol $clientProtocol" +
                ". PacketEvents 2.13.0 or newer is required; inventory data will be omitted."
            if (cause == null) {
                JadeBootstrap.LOGGER.warn(message)
            } else {
                JadeBootstrap.LOGGER.warn(message, cause)
            }
        }

        @JvmStatic
        fun selectEncoding(clientProtocol: Int, serverProtocol: Int, packetEventsAvailable: Boolean): Encoding {
            if (clientProtocol == serverProtocol) {
                return Encoding.NATIVE
            }
            return if (packetEventsAvailable && clientProtocol == serverProtocol + 1) {
                Encoding.VERSIONED
            } else {
                Encoding.UNAVAILABLE
            }
        }
    }
}
