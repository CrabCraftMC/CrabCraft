package crabcraft.net.crabUtilities.model;

import io.papermc.paper.datacomponent.DataComponentType;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.Equippable;
import net.kyori.adventure.key.Key;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Arrays;
import java.util.Objects;

/** Writes and reads the reversible model-merge metadata carried by the helmet. */
@SuppressWarnings("UnstableApiUsage")
final class MergedModelCodec {

    static final int FORMAT_VERSION = 1;

    record StoredItems(ItemStack originalTarget, ItemStack cosmetic, String nexoItemId) {
    }

    static final class CorruptMergedItemException extends Exception {
        CorruptMergedItemException(String message) {
            super(message);
        }

        CorruptMergedItemException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private final NamespacedKey versionKey;
    private final NamespacedKey originalTargetKey;
    private final NamespacedKey cosmeticKey;
    private final NamespacedKey nexoItemIdKey;

    MergedModelCodec(JavaPlugin plugin) {
        this.versionKey = new NamespacedKey(plugin, "model_merge_version");
        this.originalTargetKey = new NamespacedKey(plugin, "model_merge_target");
        this.cosmeticKey = new NamespacedKey(plugin, "model_merge_cosmetic");
        this.nexoItemIdKey = new NamespacedKey(plugin, "model_merge_nexo_id");
    }

    boolean isMerged(ItemStack item) {
        if (item == null || item.isEmpty()) {
            return false;
        }
        var data = item.getPersistentDataContainer();
        return data.has(versionKey)
                || data.has(originalTargetKey)
                || data.has(cosmeticKey)
                || data.has(nexoItemIdKey);
    }

    boolean hasApplicableModel(ItemStack cosmetic) {
        Key itemModel = cosmetic.getData(DataComponentTypes.ITEM_MODEL);
        return itemModel != null
                && (cosmetic.isDataOverridden(DataComponentTypes.ITEM_MODEL)
                || (cosmetic.isDataOverridden(DataComponentTypes.CUSTOM_MODEL_DATA)
                        && cosmetic.hasData(DataComponentTypes.CUSTOM_MODEL_DATA)));
    }

    boolean preservesMerge(ItemStack source, ItemStack result) {
        if (!isMerged(source) || !isMerged(result)
                || source.getAmount() != 1 || result.getAmount() != 1) {
            return false;
        }
        var sourceData = source.getPersistentDataContainer();
        var resultData = result.getPersistentDataContainer();
        return Objects.equals(
                sourceData.get(versionKey, PersistentDataType.INTEGER),
                resultData.get(versionKey, PersistentDataType.INTEGER))
                && Arrays.equals(
                        sourceData.get(originalTargetKey, PersistentDataType.BYTE_ARRAY),
                        resultData.get(originalTargetKey, PersistentDataType.BYTE_ARRAY))
                && Arrays.equals(
                        sourceData.get(cosmeticKey, PersistentDataType.BYTE_ARRAY),
                        resultData.get(cosmeticKey, PersistentDataType.BYTE_ARRAY))
                && Objects.equals(
                        sourceData.get(nexoItemIdKey, PersistentDataType.STRING),
                        resultData.get(nexoItemIdKey, PersistentDataType.STRING))
                && hasSamePatch(source, result, DataComponentTypes.ITEM_MODEL)
                && hasSamePatch(source, result, DataComponentTypes.CUSTOM_MODEL_DATA)
                && hasSamePatch(source, result, DataComponentTypes.EQUIPPABLE)
                && hasSamePatch(source, result, DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE);
    }

    boolean isHeadTarget(ItemStack item) {
        Equippable equippable = item.getData(DataComponentTypes.EQUIPPABLE);
        return equippable != null && equippable.slot() == EquipmentSlot.HEAD;
    }

    boolean hasCompatibleEquipmentModel(ItemStack cosmetic) {
        Equippable equippable = cosmetic.getData(DataComponentTypes.EQUIPPABLE);
        return equippable == null || equippable.slot() == EquipmentSlot.HEAD;
    }

    ItemStack merge(ItemStack target, ItemStack cosmetic, String nexoItemId) {
        if (isMerged(target) || isMerged(cosmetic)) {
            throw new IllegalArgumentException("An item is already carrying model-merge data");
        }
        if (!isHeadTarget(target)) {
            throw new IllegalArgumentException("The target is not head equipment");
        }
        if (!hasApplicableModel(cosmetic) || !hasCompatibleEquipmentModel(cosmetic)) {
            throw new IllegalArgumentException("The cosmetic does not provide a compatible model");
        }

        ItemStack originalTarget = target.asOne();
        ItemStack storedCosmetic = cosmetic.asOne();
        ItemStack merged = originalTarget.clone();

        Key itemModel = Objects.requireNonNull(cosmetic.getData(DataComponentTypes.ITEM_MODEL));
        merged.setData(DataComponentTypes.ITEM_MODEL, itemModel);
        copyPatch(cosmetic, merged, DataComponentTypes.CUSTOM_MODEL_DATA);

        Equippable targetEquippable = Objects.requireNonNull(
                target.getData(DataComponentTypes.EQUIPPABLE));
        Equippable cosmeticEquippable = cosmetic.getData(DataComponentTypes.EQUIPPABLE);
        Key cosmeticAsset = cosmeticEquippable == null ? null : cosmeticEquippable.assetId();
        merged.setData(
                DataComponentTypes.EQUIPPABLE,
                targetEquippable.toBuilder().assetId(cosmeticAsset));
        merged.setData(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE, false);

        byte[] originalTargetBytes = originalTarget.serializeAsBytes();
        byte[] cosmeticBytes = storedCosmetic.serializeAsBytes();
        merged.editPersistentDataContainer(data -> {
            data.set(versionKey, PersistentDataType.INTEGER, FORMAT_VERSION);
            data.set(originalTargetKey, PersistentDataType.BYTE_ARRAY, originalTargetBytes);
            data.set(cosmeticKey, PersistentDataType.BYTE_ARRAY, cosmeticBytes);
            data.set(nexoItemIdKey, PersistentDataType.STRING, nexoItemId);
        });
        return merged;
    }

    StoredItems read(ItemStack merged) throws CorruptMergedItemException {
        var data = merged.getPersistentDataContainer();
        Integer version = data.get(versionKey, PersistentDataType.INTEGER);
        if (version == null) {
            throw new CorruptMergedItemException("missing format version");
        }
        if (version != FORMAT_VERSION) {
            throw new CorruptMergedItemException("unsupported format version " + version);
        }

        byte[] originalTargetBytes = data.get(originalTargetKey, PersistentDataType.BYTE_ARRAY);
        byte[] cosmeticBytes = data.get(cosmeticKey, PersistentDataType.BYTE_ARRAY);
        String nexoItemId = data.get(nexoItemIdKey, PersistentDataType.STRING);
        if (originalTargetBytes == null || originalTargetBytes.length == 0
                || cosmeticBytes == null || cosmeticBytes.length == 0
                || nexoItemId == null || nexoItemId.isBlank()) {
            throw new CorruptMergedItemException("missing stored item data");
        }

        try {
            ItemStack originalTarget = ItemStack.deserializeBytes(originalTargetBytes);
            ItemStack cosmetic = ItemStack.deserializeBytes(cosmeticBytes);
            if (originalTarget.isEmpty() || cosmetic.isEmpty()
                    || originalTarget.getAmount() != 1 || cosmetic.getAmount() != 1) {
                throw new CorruptMergedItemException("stored items are empty or have an invalid amount");
            }
            return new StoredItems(originalTarget, cosmetic, nexoItemId);
        } catch (CorruptMergedItemException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new CorruptMergedItemException("stored items could not be decoded", exception);
        }
    }

    ItemStack restoreTarget(ItemStack merged, StoredItems stored) {
        ItemStack restored = merged.asOne();
        restorePatch(stored.originalTarget(), restored, DataComponentTypes.ITEM_MODEL);
        restorePatch(stored.originalTarget(), restored, DataComponentTypes.CUSTOM_MODEL_DATA);
        restorePatch(stored.originalTarget(), restored, DataComponentTypes.EQUIPPABLE);
        restorePatch(
                stored.originalTarget(), restored, DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE);
        restored.editPersistentDataContainer(data -> {
            data.remove(versionKey);
            data.remove(originalTargetKey);
            data.remove(cosmeticKey);
            data.remove(nexoItemIdKey);
        });
        return restored;
    }

    private static <T> void copyPatch(ItemStack source,
                                      ItemStack target,
                                      DataComponentType.Valued<T> type) {
        if (!source.isDataOverridden(type)) {
            target.resetData(type);
            return;
        }
        if (!source.hasData(type)) {
            target.unsetData(type);
            return;
        }
        target.setData(type, Objects.requireNonNull(source.getData(type)));
    }

    private static <T> boolean hasSamePatch(ItemStack first,
                                            ItemStack second,
                                            DataComponentType.Valued<T> type) {
        return first.isDataOverridden(type) == second.isDataOverridden(type)
                && first.hasData(type) == second.hasData(type)
                && Objects.equals(first.getData(type), second.getData(type));
    }

    private static <T> void restorePatch(ItemStack original,
                                         ItemStack target,
                                         DataComponentType.Valued<T> type) {
        copyPatch(original, target, type);
    }
}
