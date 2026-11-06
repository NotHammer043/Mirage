package xyz.tofumc.mirage.sync;

import org.bukkit.ChunkSnapshot;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.io.InputStream;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

public final class ChunkSnapshotCodec {
    private static final int MAGIC = 0x4D524147;
    private static final int VERSION = 1;
    private static final int MAX_HEIGHT = 1024;
    private static final int MAX_UNCOMPRESSED_BYTES = 8 * 1024 * 1024;
    public static final int MAX_PAYLOAD_BYTES = 16 * 1024 * 1024;

    private ChunkSnapshotCodec() {
    }

    public static byte[] encode(ChunkSnapshot snapshot, int minHeight, int maxHeight) throws IOException {
        return encode(snapshot, minHeight, maxHeight,
            (chunk, x, y, z) -> chunk.getBiome(x, y, z).getKey().toString());
    }

    static byte[] encode(
        ChunkSnapshot snapshot,
        int minHeight,
        int maxHeight,
        BiomeKeyResolver biomeKeyResolver
    ) throws IOException {
        int height = maxHeight - minHeight;
        if (height <= 0 || height > MAX_HEIGHT || height % 4 != 0) {
            throw new IOException("Unsupported world height: " + minHeight + ".." + maxHeight);
        }

        Map<String, Integer> blockPalette = new LinkedHashMap<>();
        short[] blockIndices = new short[16 * 16 * height];
        int blockIndex = 0;
        for (int y = minHeight; y < maxHeight; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    String blockData = snapshot.getBlockData(x, y, z).getAsString();
                    blockIndices[blockIndex++] = paletteIndex(blockPalette, blockData);
                }
            }
        }

        Map<String, Integer> biomePalette = new LinkedHashMap<>();
        short[] biomeIndices = new short[16 * (height / 4)];
        int biomeIndex = 0;
        for (int y = minHeight; y < maxHeight; y += 4) {
            for (int z = 0; z < 16; z += 4) {
                for (int x = 0; x < 16; x += 4) {
                    biomeIndices[biomeIndex++] = paletteIndex(
                        biomePalette, biomeKeyResolver.getKey(snapshot, x, y, z)
                    );
                }
            }
        }

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(new DeflaterOutputStream(bytes))) {
            output.writeInt(MAGIC);
            output.writeShort(VERSION);
            output.writeInt(snapshot.getX());
            output.writeInt(snapshot.getZ());
            output.writeInt(minHeight);
            output.writeInt(maxHeight);
            writePalette(output, blockPalette);
            writeIndices(output, blockIndices);
            writePalette(output, biomePalette);
            writeIndices(output, biomeIndices);
        }

        if (bytes.size() > MAX_PAYLOAD_BYTES) {
            throw new IOException("Compressed chunk snapshot exceeds the transfer limit");
        }
        return bytes.toByteArray();
    }

    public static Snapshot decode(byte[] payload) throws IOException {
        if (payload.length == 0 || payload.length > MAX_PAYLOAD_BYTES) {
            throw new IOException("Invalid chunk snapshot payload size: " + payload.length);
        }

        try (DataInputStream input = new DataInputStream(
            new LimitedInputStream(new InflaterInputStream(new ByteArrayInputStream(payload)),
                MAX_UNCOMPRESSED_BYTES))) {
            if (input.readInt() != MAGIC || input.readUnsignedShort() != VERSION) {
                throw new IOException("Unsupported chunk snapshot format");
            }

            int chunkX = input.readInt();
            int chunkZ = input.readInt();
            int minHeight = input.readInt();
            int maxHeight = input.readInt();
            int height = maxHeight - minHeight;
            if (height <= 0 || height > MAX_HEIGHT || height % 4 != 0) {
                throw new IOException("Invalid chunk snapshot height: " + minHeight + ".." + maxHeight);
            }

            String[] blockPalette = readPalette(input);
            short[] blockIndices = readIndices(input, 16 * 16 * height, blockPalette.length);
            String[] biomePalette = readPalette(input);
            short[] biomeIndices = readIndices(input, 16 * (height / 4), biomePalette.length);
            if (input.read() != -1) {
                throw new IOException("Unexpected trailing chunk snapshot data");
            }

            return new Snapshot(
                chunkX, chunkZ, minHeight, maxHeight,
                blockPalette, blockIndices, biomePalette, biomeIndices
            );
        } catch (IllegalArgumentException e) {
            throw new IOException("Malformed chunk snapshot payload", e);
        }
    }

    private static short paletteIndex(Map<String, Integer> palette, String value) throws IOException {
        Integer existing = palette.get(value);
        if (existing != null) {
            return existing.shortValue();
        }
        if (palette.size() >= Short.MAX_VALUE) {
            throw new IOException("Chunk snapshot has too many unique block or biome states");
        }
        int index = palette.size();
        palette.put(value, index);
        return (short) index;
    }

    private static void writePalette(DataOutputStream output, Map<String, Integer> palette) throws IOException {
        output.writeInt(palette.size());
        for (String value : palette.keySet()) {
            output.writeUTF(value);
        }
    }

    private static String[] readPalette(DataInputStream input) throws IOException {
        int size = input.readInt();
        if (size <= 0 || size > Short.MAX_VALUE) {
            throw new IOException("Invalid chunk palette size: " + size);
        }
        String[] palette = new String[size];
        for (int i = 0; i < size; i++) {
            palette[i] = input.readUTF();
        }
        return palette;
    }

    private static void writeIndices(DataOutputStream output, short[] indices) throws IOException {
        output.writeInt(indices.length);
        for (short index : indices) {
            output.writeShort(index);
        }
    }

    private static short[] readIndices(DataInputStream input, int expectedSize, int paletteSize) throws IOException {
        int size = input.readInt();
        if (size != expectedSize) {
            throw new IOException("Invalid chunk data length: " + size);
        }
        short[] indices = new short[size];
        for (int i = 0; i < size; i++) {
            int index = input.readUnsignedShort();
            if (index >= paletteSize) {
                throw new IOException("Chunk palette index is out of range: " + index);
            }
            indices[i] = (short) index;
        }
        return indices;
    }

    private static final class LimitedInputStream extends FilterInputStream {
        private final long limit;
        private long bytesRead;

        private LimitedInputStream(InputStream input, long limit) {
            super(input);
            this.limit = limit;
        }

        @Override
        public int read() throws IOException {
            int value = super.read();
            if (value >= 0) {
                count(1);
            }
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            if (bytesRead >= limit) {
                throw new IOException("Decompressed chunk snapshot exceeds the transfer limit");
            }
            int allowed = (int) Math.min(length, limit - bytesRead);
            int count = super.read(buffer, offset, allowed);
            if (count > 0) {
                count(count);
            }
            return count;
        }

        private void count(int count) throws IOException {
            bytesRead += count;
            if (bytesRead > limit) {
                throw new IOException("Decompressed chunk snapshot exceeds the transfer limit");
            }
        }
    }

    public record Snapshot(
        int chunkX,
        int chunkZ,
        int minHeight,
        int maxHeight,
        String[] blockPalette,
        short[] blockIndices,
        String[] biomePalette,
        short[] biomeIndices
    ) {
        public Snapshot {
            blockPalette = blockPalette.clone();
            blockIndices = blockIndices.clone();
            biomePalette = biomePalette.clone();
            biomeIndices = biomeIndices.clone();
        }

        @Override
        public String[] blockPalette() {
            return blockPalette.clone();
        }

        @Override
        public short[] blockIndices() {
            return blockIndices.clone();
        }

        @Override
        public String[] biomePalette() {
            return biomePalette.clone();
        }

        @Override
        public short[] biomeIndices() {
            return biomeIndices.clone();
        }
    }

    @FunctionalInterface
    interface BiomeKeyResolver {
        String getKey(ChunkSnapshot snapshot, int x, int y, int z);
    }
}
