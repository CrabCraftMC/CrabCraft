package crabcraft.net.crabUtilities.jade.protocol

import net.minecraft.core.BlockPos
import net.minecraft.core.Vec3i
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.DoubleTag
import net.minecraft.nbt.ListTag
import net.minecraft.world.phys.Vec3

open class JadeNbtUtils {
    companion object {
        @JvmStatic
        fun writeBlockPosToTag(pos: Vec3i, tag: CompoundTag) {
            tag.putInt("x", pos.x)
            tag.putInt("y", pos.y)
            tag.putInt("z", pos.z)
        }

        @JvmStatic
        fun readBlockPos(tag: CompoundTag?): BlockPos? {
            if (tag != null && tag.contains("x") && tag.contains("y") && tag.contains("z")) {
                return BlockPos(tag.getIntOr("x", 0), tag.getIntOr("y", 0), tag.getIntOr("z", 0))
            }
            return null
        }

        @JvmStatic
        fun writeEntityPositionToTag(pos: Vec3, tag: CompoundTag) {
            val posList = ListTag()
            posList.add(DoubleTag.valueOf(pos.x))
            posList.add(DoubleTag.valueOf(pos.y))
            posList.add(DoubleTag.valueOf(pos.z))
            tag.put("Pos", posList)
        }

        @JvmStatic
        fun readVec3(tag: CompoundTag?): Vec3? {
            if (tag != null && tag.contains("dx") && tag.contains("dy") && tag.contains("dz")) {
                return Vec3(tag.getDoubleOr("dx", 0.0), tag.getDoubleOr("dy", 0.0), tag.getDoubleOr("dz", 0.0))
            }
            return null
        }

        @JvmStatic
        fun readEntityPositionFromTag(tag: CompoundTag?): Vec3? {
            if (tag == null || !tag.contains("Pos")) return null
            val tagList = tag.getListOrEmpty("Pos")
            if (tagList.size != 3) return null
            return Vec3(tagList.getDoubleOr(0, 0.0), tagList.getDoubleOr(1, 0.0), tagList.getDoubleOr(2, 0.0))
        }

        @JvmStatic
        fun readVec3iFromTag(tag: CompoundTag?): Vec3i? {
            if (tag != null && tag.contains("x") && tag.contains("y") && tag.contains("z")) {
                return Vec3i(tag.getIntOr("x", 0), tag.getIntOr("y", 0), tag.getIntOr("z", 0))
            }
            return null
        }

        @JvmStatic
        fun readBlockPosFromArrayTag(tag: CompoundTag, tagName: String): BlockPos? {
            if (tag.contains(tagName)) {
                val pos = tag.getIntArray(tagName).orElse(IntArray(0))
                if (pos.size == 3) return BlockPos(pos[0], pos[1], pos[2])
            }
            return null
        }
    }
}
