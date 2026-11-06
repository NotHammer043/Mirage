![Mirage](icon.png)

# Mirage

Mirage copies individual chunks from a main Folia server to a mirror server using Folia's region scheduler and Bukkit's immutable `ChunkSnapshot` API. It does not read, replace, or close live region files.

## Requirements

- Folia 1.21.4
- Java 21 or newer
- Main and mirror must use the same Minecraft version, world names, world heights, and compatible block/biome registries.
- The main server's sync port must be reachable from the mirror. Use a private network or VPN; the connection is authenticated but not encrypted.

## Install and configure

Install the plugin jar in the `plugins/` directory on both servers and start each server once. Edit `plugins/Mirage/mirage.json`.

On the main server:

```json
{
  "mode": "main",
  "mainServer": {
    "port": 25566,
    "authToken": "replace-with-a-long-random-secret"
  }
}
```

On the mirror server:

```json
{
  "mode": "mirror",
  "mirrorServer": {
    "mainIp": "main-server-address",
    "mainPort": 25566,
    "authToken": "the-same-long-random-secret"
  }
}
```

Set the same strong secret on both servers. Do not use `change-me`. Restart after changing the mode or the main server's listener port or authentication token. Mirror endpoint and authentication changes can be applied with `/mirage reload`.

## Copy a chunk

Run this command on the mirror:

```text
/mirage pull <world-name> <chunkX> <chunkZ>
```

For example:

```text
/mirage pull world 10 -2
```

Coordinates are chunk coordinates, not block coordinates. Block coordinates `(160, -32)` are chunk `(10, -2)`. The source and mirror worlds must have the same name. `/mirage sync <world-name> <chunkX> <chunkZ>` is also accepted on a mirror. On the main server, `/mirage sync` displays the corresponding pull instructions.

Use `/mirage status` to check the configured role and `/mirage reload` to reload the configuration. Restart after changing the role or main-server listener settings.

## What is copied

The plugin transfers block states and biomes for one requested chunk. It does not transfer entities, block entities (such as chests, signs, or spawners), scheduled ticks, or other world metadata. To avoid silently losing block-entity data, the transfer is rejected if the source chunk contains block entities or if an existing mirror chunk contains them. An absent mirror chunk may be generated before the snapshot is applied.

Each chunk is captured on its owning source region and applied on its owning mirror region. Bulk whole-dimension replacement is intentionally not supported: Folia does not provide a safe global lock for replacing active world region files.

## Build

```shell
./gradlew build
```

The plugin jar is written to `build/libs/mirage-<version>.jar`.
