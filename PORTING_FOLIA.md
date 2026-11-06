# Folia Synchronization Design

Mirage no longer accesses Minecraft server internals or live `.mca` files.

## Chunk transfer

1. The mirror requests one named world and chunk coordinate over an authenticated TCP connection.
2. The main server resolves the world on the global region scheduler and loads the already-generated chunk asynchronously.
3. The source chunk's owning region creates a Bukkit `ChunkSnapshot`. Snapshot encoding runs off-thread; `ChunkSnapshot` is documented as immutable and thread-safe.
4. The mirror resolves its world on the global region scheduler and loads or generates the destination chunk asynchronously.
5. The destination chunk's owning region validates the snapshot and applies block states and biomes.

No region task waits for another region, socket, or disk operation. Network workers wait only on the source snapshot future, never on a server tick thread. Requests and worker queues are bounded.

## Intentional limitations

- Transfers are one chunk at a time; whole-world region-file replacement is not available through a safe Folia API.
- Entities, block entities, scheduled ticks, and other world metadata are not copied.
- Source chunks with block entities are rejected. Existing destination chunks with block entities are left unchanged and rejected.
- Both servers must have matching world names, height ranges, Minecraft versions, and compatible block/biome registries.

Do not bypass these checks or reintroduce raw region-file replacement. Extending the copied data requires a separate region-owned, public-API transfer design for each additional data type.
