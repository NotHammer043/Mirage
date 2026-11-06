![Mirage](icon.png)

# Mirage

Mirage is a Folia-compatible server plugin scaffold for dimension synchronization.

> **Important:** Live world synchronization is currently disabled. The previous implementation saved and replaced global `.mca` region files, unloaded chunks, and modified internal chunk state. Those operations are not safe on Folia, where world regions tick independently. Mirage now fails closed instead of risking world corruption. A Folia-safe synchronization design is still required before sync and pull can be enabled.

## Requirements

- Folia
- Java 21 or newer

## Build

```shell
./gradlew build
```

The plugin jar is written to `build/libs/mirage-<version>.jar`.

## Install

1. Copy the jar into the server's `plugins/` directory.
2. Start the server once to create `plugins/Mirage/mirage.json`.
3. Optionally edit the informational `mode` setting in `plugins/Mirage/mirage.json`.

## Commands

- `/mirage status` — show the configured mode and synchronization availability.
- `/mirage reload` — reload `plugins/Mirage/mirage.json`.
- `/mirage sync` and `/mirage pull` — report that live synchronization is disabled.

All commands require operator permission.
