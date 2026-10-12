package crabcraft.net.crabUtilities.jade;

import crabcraft.net.crabUtilities.jade.protocol.accessor.BlockAccessor;
import io.netty.buffer.Unpooled;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

final class JadeClientProtocolPayloadRegressionTest {

    public static void main(String[] args) {
        check(JadeBootstrap.decodeClientProtocol(new byte[]{0, 0, 3, 8}) == 776,
                "the proxy's 26.2 protocol payload was decoded incorrectly");
        check(JadeBootstrap.decodeClientProtocol(new byte[]{0, 0, 3, 9}) == 777,
                "the proxy's 26.3 protocol payload was decoded incorrectly");
        check(JadeBootstrap.decodeClientProtocol(new byte[]{0, 0, 3}) == -1,
                "a truncated client protocol payload was accepted");
        check(JadeBootstrap.decodeClientProtocol(new byte[]{0, 0, 0, 0}) == -1,
                "an invalid client protocol was accepted");
        blockRequestRoundTripsOn26_3();
    }

    private static void blockRequestRoundTripsOn26_3() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        BlockPos position = new BlockPos(-12, 64, 30);
        Vec3 location = new Vec3(-11.75, 64.5, 30.25);
        BlockHitResult hit = new BlockHitResult(location, Direction.WEST, position, true, true);
        CompoundTag extra = new CompoundTag();
        extra.putBoolean("probe", true);
        var request = new BlockAccessor.SyncData(true, hit, ItemStack.EMPTY, extra);
        var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        try {
            BlockAccessor.SyncData.STREAM_CODEC.encode(buffer, request);
            var decoded = BlockAccessor.SyncData.STREAM_CODEC.decode(buffer);
            check(decoded.showDetails(), "the block request lost its detail flag");
            check(decoded.hit().getBlockPos().equals(position), "the block request lost its position");
            check(decoded.hit().getLocation().equals(location), "the block request lost its hit location");
            check(decoded.hit().getDirection() == Direction.WEST, "the block request lost its hit face");
            check(decoded.hit().isInside() && decoded.hit().isWorldBorderHit(),
                    "the block request lost its hit flags");
            check(decoded.serversideRep().isEmpty() && decoded.data().equals(extra),
                    "the block request misread data following the hit result");
            check(!buffer.isReadable(), "the block request left trailing bytes");
        } finally {
            buffer.release();
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
