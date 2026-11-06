package xyz.tofumc.mirage.sync;

import org.bukkit.ChunkSnapshot;
import org.bukkit.block.data.BlockData;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ChunkSnapshotCodecTest {
    @Test
    void roundTripsBlockAndBiomePalettes() throws Exception {
        ChunkSnapshot source = snapshot();

        ChunkSnapshotCodec.Snapshot decoded = ChunkSnapshotCodec.decode(
            ChunkSnapshotCodec.encode(source, -64, 320,
                (chunk, x, y, z) -> x < 8 ? "minecraft:plains" : "minecraft:desert")
        );

        assertEquals(12, decoded.chunkX());
        assertEquals(-8, decoded.chunkZ());
        assertEquals(-64, decoded.minHeight());
        assertEquals(320, decoded.maxHeight());
        assertEquals(16 * 16 * 384, decoded.blockIndices().length);
        assertEquals(16 * 96, decoded.biomeIndices().length);

        String[] blocks = decoded.blockPalette();
        short[] blockIndices = decoded.blockIndices();
        int blockOffset = ((0 + 64) * 16 + 0) * 16 + 0;
        assertEquals("minecraft:stone", blocks[Short.toUnsignedInt(blockIndices[blockOffset])]);
        blockOffset = ((0 + 64) * 16 + 0) * 16 + 1;
        assertEquals("minecraft:dirt", blocks[Short.toUnsignedInt(blockIndices[blockOffset])]);

        String[] biomes = decoded.biomePalette();
        short[] biomeIndices = decoded.biomeIndices();
        int biomeOffset = ((0 + 64) / 4 * 4) * 4;
        assertEquals("minecraft:plains", biomes[Short.toUnsignedInt(biomeIndices[biomeOffset])]);
        assertEquals("minecraft:desert", biomes[Short.toUnsignedInt(biomeIndices[biomeOffset + 2])]);
    }

    @Test
    void rejectsTruncatedAndOversizedPayloads() {
        assertThrows(Exception.class, () -> ChunkSnapshotCodec.decode(new byte[] {1, 2, 3}));
        assertThrows(Exception.class, () -> ChunkSnapshotCodec.decode(
            new byte[ChunkSnapshotCodec.MAX_PAYLOAD_BYTES + 1]
        ));
    }

    private static ChunkSnapshot snapshot() {
        return proxy(ChunkSnapshot.class, (method, args) -> switch (method) {
            case "getX" -> 12;
            case "getZ" -> -8;
            case "getBlockData" -> blockData((int) args[0]);
            default -> throw new UnsupportedOperationException(method);
        });
    }

    private static BlockData blockData(int x) {
        String value = (x & 1) == 0 ? "minecraft:stone" : "minecraft:dirt";
        return proxy(BlockData.class, (method, args) -> {
            if ("getAsString".equals(method)) {
                return value;
            }
            throw new UnsupportedOperationException(method);
        });
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, Invocation invocation) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type},
            (proxy, method, args) -> invocation.invoke(method.getName(), args));
    }

    @FunctionalInterface
    private interface Invocation {
        Object invoke(String method, Object[] args);
    }
}
