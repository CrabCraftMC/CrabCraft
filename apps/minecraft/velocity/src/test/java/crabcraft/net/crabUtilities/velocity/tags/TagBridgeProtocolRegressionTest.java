package crabcraft.net.crabUtilities.velocity.tags;

import crabcraft.net.crabUtilities.tags.TagBridgeProtocol;
import crabcraft.net.crabUtilities.tags.TagDefinition;
import crabcraft.net.crabUtilities.tags.TagSnapshot;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

final class TagBridgeProtocolRegressionTest {
    public static void main(String[] args) {
        UUID request = UUID.randomUUID();
        UUID player = UUID.randomUUID();
        var pumpkin = new TagDefinition("halloween-2026-pumpkin", "Halloween 2026", "Earned pumpkin", "pumpkin", 10, true);
        var snapshot = new TagSnapshot(player, Long.MAX_VALUE, pumpkin.id(), List.of(pumpkin));
        check(TagBridgeProtocol.decode(TagBridgeProtocol.query(request)).requestId().equals(request), "query correlation lost");
        var selected = TagBridgeProtocol.decode(TagBridgeProtocol.select(request, pumpkin.id(), Long.MAX_VALUE));
        check(selected.expectedRevision() == Long.MAX_VALUE && selected.selectedTagId().equals(pumpkin.id()), "selection lost precision");
        check(TagBridgeProtocol.decode(TagBridgeProtocol.select(request, null, 0)).selectedTagId() == null, "None was not null");
        var result = TagBridgeProtocol.decode(TagBridgeProtocol.result(request, TagBridgeProtocol.Status.OK, snapshot));
        check(result.snapshot().equals(snapshot) && result.playerId().equals(player), "committed snapshot did not round-trip");
        check(TagBridgeProtocol.decode(TagBridgeProtocol.state(snapshot)).snapshot().equals(snapshot), "state did not round-trip");
        var unavailable = TagBridgeProtocol.decode(TagBridgeProtocol.result(request, player, TagBridgeProtocol.Status.UNAVAILABLE, null));
        check(unavailable.snapshot() == null && unavailable.playerId().equals(player), "unavailable response lost actor");
        var disabled = new TagDefinition(pumpkin.id(), pumpkin.name(), pumpkin.description(), pumpkin.glyphId(), 10, false);
        check(TagBridgeProtocol.decode(TagBridgeProtocol.state(new TagSnapshot(player, 3, pumpkin.id(), List.of(disabled))))
                .snapshot().selectedTag().enabled() == false, "disabled saved choice was not retained");
        byte[] query = TagBridgeProtocol.query(request);
        for (int i = 0; i < query.length; i++) {
            byte[] truncated = Arrays.copyOf(query, i);
            rejects(() -> TagBridgeProtocol.decode(truncated));
        }
        byte[] validResult = TagBridgeProtocol.result(request, TagBridgeProtocol.Status.OK, snapshot);
        for (int i = 0; i < validResult.length; i++) {
            byte[] truncated = Arrays.copyOf(validResult, i);
            rejects(() -> TagBridgeProtocol.decode(truncated));
        }
        rejects(() -> TagBridgeProtocol.decode(new byte[30_001]));
        byte[] version = query.clone();
        version[0] = 2;
        rejects(() -> TagBridgeProtocol.decode(version));
        byte[] type = query.clone();
        type[1] = 127;
        rejects(() -> TagBridgeProtocol.decode(type));
        rejects(() -> TagBridgeProtocol.decode(Arrays.copyOf(query, query.length + 1)));
        rejects(() -> TagBridgeProtocol.select(request, null, -1));
        rejects(() -> TagBridgeProtocol.select(request, "unsafe<tag>", 0));
        rejects(() -> TagBridgeProtocol.select(request, "", 0));
        rejects(() -> TagBridgeProtocol.select(request, "a".repeat(97), 0));
        byte[] selection = TagBridgeProtocol.select(request, "test", 0);
        byte[] malformedBoolean = selection.clone();
        malformedBoolean[18] = 2;
        rejects(() -> TagBridgeProtocol.decode(malformedBoolean));
        byte[] malformedLength = selection.clone();
        ByteBuffer.wrap(malformedLength).putInt(19, -1);
        rejects(() -> TagBridgeProtocol.decode(malformedLength));
        byte[] oversizedLength = selection.clone();
        ByteBuffer.wrap(oversizedLength).putInt(19, 4097);
        rejects(() -> TagBridgeProtocol.decode(oversizedLength));
        byte[] malformedUtf8 = selection.clone();
        malformedUtf8[23] = (byte) 0xC0;
        rejects(() -> TagBridgeProtocol.decode(malformedUtf8));
        byte[] unsafeSelectedId = selection.clone();
        unsafeSelectedId[23] = '<';
        rejects(() -> TagBridgeProtocol.decode(unsafeSelectedId));
        byte[] negativeExpectedRevision = selection.clone();
        ByteBuffer.wrap(negativeExpectedRevision).putLong(27, -1);
        rejects(() -> TagBridgeProtocol.decode(negativeExpectedRevision));
        byte[] emptyState = TagBridgeProtocol.state(new TagSnapshot(player, 0, null, List.of()));
        byte[] negativeCount = emptyState.clone();
        ByteBuffer.wrap(negativeCount).putInt(27, -1);
        rejects(() -> TagBridgeProtocol.decode(negativeCount));
        byte[] oversizedCount = emptyState.clone();
        ByteBuffer.wrap(oversizedCount).putInt(27, 129);
        rejects(() -> TagBridgeProtocol.decode(oversizedCount));
        byte[] negativeSnapshotRevision = emptyState.clone();
        ByteBuffer.wrap(negativeSnapshotRevision).putLong(18, -1);
        rejects(() -> TagBridgeProtocol.decode(negativeSnapshotRevision));
        byte[] invalidStatus = validResult.clone();
        invalidStatus[34] = 127;
        rejects(() -> TagBridgeProtocol.decode(invalidStatus));
        byte[] invalidSnapshotBoolean = validResult.clone();
        invalidSnapshotBoolean[35] = 2;
        rejects(() -> TagBridgeProtocol.decode(invalidSnapshotBoolean));
        byte[] mismatchedSnapshotOwner = validResult.clone();
        mismatchedSnapshotOwner[36] ^= 1;
        rejects(() -> TagBridgeProtocol.decode(mismatchedSnapshotOwner));
        byte[] invalidDefinitionBoolean = TagBridgeProtocol.state(snapshot);
        invalidDefinitionBoolean[invalidDefinitionBoolean.length - 1] = 2;
        rejects(() -> TagBridgeProtocol.decode(invalidDefinitionBoolean));
        rejects(() -> new TagSnapshot(player, 0, "unowned", List.of(pumpkin)));
        rejects(() -> new TagSnapshot(player, 0, null, List.of(pumpkin, pumpkin)));
        rejects(() -> new TagDefinition("unsafe<tag>", "Test", "", "pumpkin", 0, true));
        rejects(() -> new TagDefinition("test", "Test", "", "pumpkin><click:run_command:/op>", 0, true));
        rejects(() -> TagBridgeProtocol.result(request, UUID.randomUUID(), TagBridgeProtocol.Status.OK, snapshot));
        rejects(() -> TagBridgeProtocol.result(request, player, TagBridgeProtocol.Status.OK, null));
    }

    private static void rejects(Runnable action) {
        try {
            action.run();
            throw new AssertionError("Malformed tag data was accepted");
        } catch (IllegalArgumentException expected) {
            // Expected.
        }
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
