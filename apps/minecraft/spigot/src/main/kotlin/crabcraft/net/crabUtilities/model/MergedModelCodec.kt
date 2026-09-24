package crabcraft.net.crabUtilities.model

import io.papermc.paper.datacomponent.DataComponentType
import io.papermc.paper.datacomponent.DataComponentTypes
import java.util.Arrays
import org.bukkit.NamespacedKey
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.java.JavaPlugin

/** Writes and reads the reversible model-merge metadata carried by the helmet. */
@Suppress("UnstableApiUsage")
class MergedModelCodec(plugin: JavaPlugin) {
    data class StoredItems(val originalTarget: ItemStack, val cosmetic: ItemStack, val nexoItemId: String) {
        fun originalTarget() = originalTarget

        fun cosmetic() = cosmetic

        fun nexoItemId() = nexoItemId
    }

    class CorruptMergedItemException : Exception {
        constructor(message: String) : super(message)

        constructor(message: String, cause: Throwable) : super(message, cause)
    }

    private val versionKey = NamespacedKey(plugin, "model_merge_version")
    private val originalTargetKey = NamespacedKey(plugin, "model_merge_target")
    private val cosmeticKey = NamespacedKey(plugin, "model_merge_cosmetic")
    private val nexoItemIdKey = NamespacedKey(plugin, "model_merge_nexo_id")

    fun isMerged(item: ItemStack?): Boolean {
        if (item == null || item.isEmpty) return false
        val data = item.persistentDataContainer
        return data.has(versionKey) || data.has(originalTargetKey) || data.has(cosmeticKey) || data.has(nexoItemIdKey)
    }

    fun hasApplicableModel(cosmetic: ItemStack): Boolean =
        cosmetic.getData(DataComponentTypes.ITEM_MODEL) != null &&
            (cosmetic.isDataOverridden(DataComponentTypes.ITEM_MODEL) ||
                (cosmetic.isDataOverridden(DataComponentTypes.CUSTOM_MODEL_DATA) &&
                    cosmetic.hasData(DataComponentTypes.CUSTOM_MODEL_DATA)))

    fun preservesMerge(source: ItemStack?, result: ItemStack?): Boolean {
        if (!isMerged(source) || !isMerged(result) || source!!.amount != 1 || result!!.amount != 1) return false
        val sourceData = source.persistentDataContainer
        val resultData = result.persistentDataContainer
        return sourceData.get(versionKey, PersistentDataType.INTEGER) ==
            resultData.get(versionKey, PersistentDataType.INTEGER) &&
            Arrays.equals(
                sourceData.get(originalTargetKey, PersistentDataType.BYTE_ARRAY),
                resultData.get(originalTargetKey, PersistentDataType.BYTE_ARRAY),
            ) &&
            Arrays.equals(
                sourceData.get(cosmeticKey, PersistentDataType.BYTE_ARRAY),
                resultData.get(cosmeticKey, PersistentDataType.BYTE_ARRAY),
            ) &&
            sourceData.get(nexoItemIdKey, PersistentDataType.STRING) ==
                resultData.get(nexoItemIdKey, PersistentDataType.STRING) &&
            hasSamePatch(source, result, DataComponentTypes.ITEM_MODEL) &&
            hasSamePatch(source, result, DataComponentTypes.CUSTOM_MODEL_DATA) &&
            hasSamePatch(source, result, DataComponentTypes.DYED_COLOR) &&
            hasSamePatch(source, result, DataComponentTypes.EQUIPPABLE) &&
            hasSamePatch(source, result, DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE)
    }

    fun isHeadTarget(item: ItemStack) = item.getData(DataComponentTypes.EQUIPPABLE)?.slot() == EquipmentSlot.HEAD

    fun hasCompatibleEquipmentModel(cosmetic: ItemStack): Boolean {
        val equippable = cosmetic.getData(DataComponentTypes.EQUIPPABLE)
        return equippable == null || equippable.slot() == EquipmentSlot.HEAD
    }

    fun merge(target: ItemStack, cosmetic: ItemStack, nexoItemId: String): ItemStack {
        require(!isMerged(target) && !isMerged(cosmetic)) { "An item is already carrying model-merge data" }
        require(isHeadTarget(target)) { "The target is not head equipment" }
        require(hasApplicableModel(cosmetic) && hasCompatibleEquipmentModel(cosmetic)) {
            "The cosmetic does not provide a compatible model"
        }
        val originalTarget = target.asOne()
        val storedCosmetic = cosmetic.asOne()
        val merged = originalTarget.clone()
        merged.setData(DataComponentTypes.ITEM_MODEL, cosmetic.getData(DataComponentTypes.ITEM_MODEL)!!)
        copyPatch(cosmetic, merged, DataComponentTypes.CUSTOM_MODEL_DATA)
        copyPatch(cosmetic, merged, DataComponentTypes.DYED_COLOR)
        val targetEquippable = target.getData(DataComponentTypes.EQUIPPABLE)!!
        merged.setData(
            DataComponentTypes.EQUIPPABLE,
            targetEquippable.toBuilder().assetId(cosmetic.getData(DataComponentTypes.EQUIPPABLE)?.assetId()),
        )
        merged.setData(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE, false)
        val originalTargetBytes = originalTarget.serializeAsBytes()
        val cosmeticBytes = storedCosmetic.serializeAsBytes()
        merged.editPersistentDataContainer { data ->
            data.set(versionKey, PersistentDataType.INTEGER, FORMAT_VERSION)
            data.set(originalTargetKey, PersistentDataType.BYTE_ARRAY, originalTargetBytes)
            data.set(cosmeticKey, PersistentDataType.BYTE_ARRAY, cosmeticBytes)
            data.set(nexoItemIdKey, PersistentDataType.STRING, nexoItemId)
        }
        return merged
    }

    @Throws(CorruptMergedItemException::class)
    fun read(merged: ItemStack): StoredItems {
        val data = merged.persistentDataContainer
        val version =
            data.get(versionKey, PersistentDataType.INTEGER)
                ?: throw CorruptMergedItemException("missing format version")
        if (version != FORMAT_VERSION) throw CorruptMergedItemException("unsupported format version $version")
        val originalTargetBytes = data.get(originalTargetKey, PersistentDataType.BYTE_ARRAY)
        val cosmeticBytes = data.get(cosmeticKey, PersistentDataType.BYTE_ARRAY)
        val nexoItemId = data.get(nexoItemIdKey, PersistentDataType.STRING)
        if (
            originalTargetBytes == null ||
                originalTargetBytes.isEmpty() ||
                cosmeticBytes == null ||
                cosmeticBytes.isEmpty() ||
                nexoItemId.isNullOrBlank()
        ) {
            throw CorruptMergedItemException("missing stored item data")
        }
        try {
            val originalTarget = ItemStack.deserializeBytes(originalTargetBytes)
            val cosmetic = ItemStack.deserializeBytes(cosmeticBytes)
            if (originalTarget.isEmpty || cosmetic.isEmpty || originalTarget.amount != 1 || cosmetic.amount != 1) {
                throw CorruptMergedItemException("stored items are empty or have an invalid amount")
            }
            return StoredItems(originalTarget, cosmetic, nexoItemId)
        } catch (exception: CorruptMergedItemException) {
            throw exception
        } catch (exception: RuntimeException) {
            throw CorruptMergedItemException("stored items could not be decoded", exception)
        }
    }

    fun restoreTarget(merged: ItemStack, stored: StoredItems): ItemStack {
        val restored = merged.asOne()
        copyPatch(stored.originalTarget, restored, DataComponentTypes.ITEM_MODEL)
        copyPatch(stored.originalTarget, restored, DataComponentTypes.CUSTOM_MODEL_DATA)
        copyPatch(stored.originalTarget, restored, DataComponentTypes.DYED_COLOR)
        copyPatch(stored.originalTarget, restored, DataComponentTypes.EQUIPPABLE)
        copyPatch(stored.originalTarget, restored, DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE)
        restored.editPersistentDataContainer { data ->
            data.remove(versionKey)
            data.remove(originalTargetKey)
            data.remove(cosmeticKey)
            data.remove(nexoItemIdKey)
        }
        return restored
    }

    companion object {
        const val FORMAT_VERSION = 1

        private fun <T : Any> copyPatch(source: ItemStack, target: ItemStack, type: DataComponentType.Valued<T>) {
            if (!source.isDataOverridden(type)) target.resetData(type)
            else if (!source.hasData(type)) target.unsetData(type) else target.setData(type, source.getData(type)!!)
        }

        private fun <T : Any> hasSamePatch(first: ItemStack, second: ItemStack, type: DataComponentType.Valued<T>) =
            first.isDataOverridden(type) == second.isDataOverridden(type) &&
                first.hasData(type) == second.hasData(type) &&
                first.getData(type) == second.getData(type)
    }
}
