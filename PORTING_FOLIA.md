# Folia Porting Notes

## Current status

The Fabric entry point, client code, mixins, and direct Minecraft server internals have been removed. Mirage is now a standard Paperweight/Folia plugin, and its remaining command and configuration paths use the Bukkit plugin API.

Live synchronization is intentionally disabled. The former implementation depended on operations that cannot be made safe by replacing a server-wide executor with a Folia scheduler:

- Saving or flushing every world from one task.
- Closing region-file caches and replacing `.mca` files while regions may still be ticking or loading chunks.
- Mutating chunk sections and player connections through server internals.
- Enumerating or modifying entities and chunks outside their owning region or entity scheduler.

Folia schedulers provide ownership for individual regions, entities, and global tasks; they do not provide a safe global lock for replacing a world's region files. Do not re-enable live sync by scheduling the old file operations on a global or arbitrary region scheduler.

## Safe surfaces

- Plugin enable/disable, configuration loading, command registration, and status reporting.
- Reloading the plugin's own configuration file.
- Reporting that sync and pull are unavailable.

## Requirements

- Folia server
- Java 21 or newer

## Build

```shell
./gradlew build
```
