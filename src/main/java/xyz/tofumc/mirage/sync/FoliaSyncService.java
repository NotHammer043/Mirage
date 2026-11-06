package xyz.tofumc.mirage.sync;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.ChunkSnapshot;
import org.bukkit.World;
import org.bukkit.block.Biome;
import org.bukkit.block.data.BlockData;
import xyz.tofumc.Mirage;
import xyz.tofumc.mirage.config.MirageConfig;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class FoliaSyncService implements AutoCloseable {
    private static final int REQUEST_MAGIC = 0x4D525131;
    private static final int PROTOCOL_VERSION = 1;
    private static final int MAX_CHUNK_COORDINATE = 1_875_000;
    private static final int SOCKET_TIMEOUT_MILLIS = 90_000;
    private static final int MIN_TOKEN_BYTES = 16;
    private static final int MAX_TOKEN_BYTES = 1024;

    private final Mirage plugin;
    private final ExecutorService ioExecutor = Executors.newFixedThreadPool(3, task -> {
        Thread thread = new Thread(task, "Mirage-IO");
        thread.setDaemon(true);
        return thread;
    });
    private final ThreadPoolExecutor clientExecutor = new ThreadPoolExecutor(
        2, 2, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(8), task -> {
            Thread thread = new Thread(task, "Mirage-Client");
            thread.setDaemon(true);
            return thread;
        }, new ThreadPoolExecutor.AbortPolicy()
    );
    private final AtomicBoolean running = new AtomicBoolean();
    private final Set<Socket> openSockets = ConcurrentHashMap.newKeySet();
    private volatile ServerSocket listener;

    public FoliaSyncService(Mirage plugin) {
        this.plugin = plugin;
    }

    public void startMainServer(MirageConfig.MainServerConfig config) throws IOException {
        if (config == null) {
            throw new IOException("Missing mainServer configuration");
        }
        if (config.getPort() < 1 || config.getPort() > 65535) {
            throw new IOException("Main server port must be between 1 and 65535");
        }
        if (!isStrongToken(config.getAuthToken())) {
            throw new IOException("Set mainServer.authToken to a secret between 16 and 1024 UTF-8 bytes");
        }

        ServerSocket serverSocket = new ServerSocket();
        serverSocket.setReuseAddress(true);
        serverSocket.bind(new InetSocketAddress(config.getPort()));
        listener = serverSocket;
        running.set(true);

        Thread acceptThread = new Thread(() -> acceptConnections(serverSocket, config.getAuthToken()), "Mirage-Acceptor");
        acceptThread.setDaemon(true);
        acceptThread.start();
        Mirage.LOGGER.info("Folia-safe chunk sync listener started on port {}", config.getPort());
    }

    public boolean isListening() {
        return running.get();
    }

    public CompletableFuture<String> pullChunk(
        MirageConfig.MirrorServerConfig config,
        String worldName,
        int chunkX,
        int chunkZ
    ) {
        if (config == null) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Mirror configuration is missing"));
        }
        if (worldName == null || worldName.isBlank() || worldName.length() > 128) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("World name must contain 1 to 128 characters"));
        }
        if (chunkX < -MAX_CHUNK_COORDINATE || chunkX > MAX_CHUNK_COORDINATE
            || chunkZ < -MAX_CHUNK_COORDINATE || chunkZ > MAX_CHUNK_COORDINATE) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Chunk coordinates are outside the supported world border"));
        }
        if (config.getMainIp() == null || config.getMainIp().isBlank()
            || config.getMainPort() < 1 || config.getMainPort() > 65535
            || !isStrongToken(config.getAuthToken())) {
            return CompletableFuture.failedFuture(new IllegalArgumentException(
                "Configure mirrorServer.mainIp, a valid mainPort, and the shared authToken (16 to 1024 UTF-8 bytes)"
            ));
        }

        CompletableFuture<String> result = new CompletableFuture<>();
        try {
            clientExecutor.execute(() -> {
                try {
                    byte[] payload = requestChunk(config, worldName, chunkX, chunkZ);
                    ChunkSnapshotCodec.Snapshot snapshot = ChunkSnapshotCodec.decode(payload);
                    if (snapshot.chunkX() != chunkX || snapshot.chunkZ() != chunkZ) {
                        throw new IOException("The main server returned a different chunk than requested");
                    }
                    applySnapshot(worldName, snapshot).whenComplete((message, error) -> {
                        if (error != null) {
                            result.completeExceptionally(unwrap(error));
                        } else {
                            result.complete(message);
                        }
                    });
                } catch (Exception e) {
                    result.completeExceptionally(e);
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException e) {
            result.completeExceptionally(new IOException("Too many synchronization requests are already running", e));
        }
        return result;
    }

    private void acceptConnections(ServerSocket serverSocket, String authToken) {
        while (running.get()) {
            try {
                Socket socket = serverSocket.accept();
                try {
                    socket.setSoTimeout(SOCKET_TIMEOUT_MILLIS);
                    openSockets.add(socket);
                    clientExecutor.execute(() -> serveRequest(socket, authToken));
                } catch (IOException | java.util.concurrent.RejectedExecutionException e) {
                    closeSocket(socket);
                    openSockets.remove(socket);
                    Mirage.LOGGER.warn("Could not accept a chunk sync connection: {}", e.getMessage());
                }
            } catch (IOException e) {
                if (running.get()) {
                    Mirage.LOGGER.error("Chunk sync listener failed", e);
                    running.set(false);
                }
                return;
            }
        }
    }

    private void serveRequest(Socket socket, String authToken) {
        try (socket) {
            DataInputStream input = new DataInputStream(socket.getInputStream());
            DataOutputStream output = new DataOutputStream(socket.getOutputStream());
            int magic = input.readInt();
            int version = input.readUnsignedShort();
            String suppliedToken = input.readUTF();
            String worldName = input.readUTF();
            int chunkX = input.readInt();
            int chunkZ = input.readInt();

            if (magic != REQUEST_MAGIC || version != PROTOCOL_VERSION
                || !MessageDigest.isEqual(authToken.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    suppliedToken.getBytes(java.nio.charset.StandardCharsets.UTF_8))) {
                writeError(output, "Authentication failed or protocol version is unsupported");
                return;
            }
            if (worldName.isBlank() || worldName.length() > 128
                || chunkX < -MAX_CHUNK_COORDINATE || chunkX > MAX_CHUNK_COORDINATE
                || chunkZ < -MAX_CHUNK_COORDINATE || chunkZ > MAX_CHUNK_COORDINATE) {
                writeError(output, "Invalid world name or chunk coordinates");
                return;
            }

            byte[] payload;
            try {
                payload = captureChunk(worldName, chunkX, chunkZ).get(SOCKET_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
            } catch (Exception e) {
                Throwable cause = unwrap(e);
                writeError(output, cause.getMessage() == null ? "Chunk snapshot failed" : cause.getMessage());
                return;
            }
            output.writeBoolean(true);
            output.writeInt(payload.length);
            output.write(payload);
            output.flush();
            Mirage.LOGGER.info("Sent chunk ({}, {}) from world {} to {}",
                chunkX, chunkZ, worldName, socket.getRemoteSocketAddress());
        } catch (Exception e) {
            if (running.get()) {
                Mirage.LOGGER.warn("Chunk sync request failed: {}", e.getMessage());
            }
        } finally {
            openSockets.remove(socket);
        }
    }

    private CompletableFuture<byte[]> captureChunk(String worldName, int chunkX, int chunkZ) {
        CompletableFuture<byte[]> result = new CompletableFuture<>();
        try {
            plugin.getServer().getGlobalRegionScheduler().execute(plugin, () -> {
                try {
                    World world = Bukkit.getWorld(worldName);
                    if (world == null) {
                        result.completeExceptionally(new IllegalArgumentException("Main server has no loaded world named " + worldName));
                        return;
                    }

                    world.getChunkAtAsync(chunkX, chunkZ, false).whenComplete((loadedChunk, loadError) -> {
                        if (loadError != null) {
                            result.completeExceptionally(unwrap(loadError));
                            return;
                        }
                        if (loadedChunk == null) {
                            result.completeExceptionally(new IllegalArgumentException("The requested source chunk is not generated"));
                            return;
                        }

                        try {
                            plugin.getServer().getRegionScheduler().execute(plugin, world, chunkX, chunkZ, () -> {
                                try {
                                    boolean ticketAdded = world.addPluginChunkTicket(chunkX, chunkZ, plugin);
                                    try {
                                        Chunk chunk = world.getChunkAt(chunkX, chunkZ);
                                        if (chunk.getTileEntities().length != 0) {
                                            throw new IllegalStateException(
                                                "This chunk contains block entities; transferring it would lose their data"
                                            );
                                        }
                                        ChunkSnapshot snapshot = chunk.getChunkSnapshot(true, true, false, false);
                                        int minHeight = world.getMinHeight();
                                        int maxHeight = world.getMaxHeight();
                                        try {
                                            ioExecutor.execute(() -> {
                                                try {
                                                    result.complete(ChunkSnapshotCodec.encode(snapshot, minHeight, maxHeight));
                                                } catch (Exception e) {
                                                    result.completeExceptionally(e);
                                                }
                                            });
                                        } catch (java.util.concurrent.RejectedExecutionException e) {
                                            result.completeExceptionally(
                                                new IOException("Snapshot processing is shutting down", e)
                                            );
                                        }
                                    } finally {
                                        if (ticketAdded) {
                                            world.removePluginChunkTicket(chunkX, chunkZ, plugin);
                                        }
                                    }
                                } catch (Exception e) {
                                    result.completeExceptionally(e);
                                }
                            });
                        } catch (Exception e) {
                            result.completeExceptionally(e);
                        }
                    });
                } catch (Exception e) {
                    result.completeExceptionally(e);
                }
            });
        } catch (Exception e) {
            result.completeExceptionally(e);
        }
        return result;
    }

    private CompletableFuture<String> applySnapshot(String worldName, ChunkSnapshotCodec.Snapshot snapshot) {
        CompletableFuture<String> result = new CompletableFuture<>();
        try {
            plugin.getServer().getGlobalRegionScheduler().execute(plugin, () -> {
                try {
                    World world = Bukkit.getWorld(worldName);
                    if (world == null) {
                        result.completeExceptionally(new IllegalArgumentException("Mirror server has no loaded world named " + worldName));
                        return;
                    }

                    world.getChunkAtAsync(snapshot.chunkX(), snapshot.chunkZ(), false).whenComplete((chunk, loadError) -> {
                        if (loadError != null) {
                            result.completeExceptionally(unwrap(loadError));
                            return;
                        }
                        if (chunk != null) {
                            scheduleApply(world, worldName, snapshot, result);
                            return;
                        }

                        world.getChunkAtAsync(snapshot.chunkX(), snapshot.chunkZ(), true).whenComplete((generated, generationError) -> {
                            if (generationError != null) {
                                result.completeExceptionally(unwrap(generationError));
                            } else if (generated == null) {
                                result.completeExceptionally(new IllegalStateException("The mirror chunk could not be loaded or generated"));
                            } else {
                                scheduleApply(world, worldName, snapshot, result);
                            }
                        });
                    });
                } catch (Exception e) {
                    result.completeExceptionally(e);
                }
            });
        } catch (Exception e) {
            result.completeExceptionally(e);
        }
        return result;
    }

    private void scheduleApply(
        World world,
        String worldName,
        ChunkSnapshotCodec.Snapshot snapshot,
        CompletableFuture<String> result
    ) {
        try {
            plugin.getServer().getRegionScheduler().execute(plugin, world, snapshot.chunkX(), snapshot.chunkZ(), () -> {
                boolean ticketAdded = false;
                String successMessage = null;
                Throwable failure = null;
                try {
                    ticketAdded = world.addPluginChunkTicket(snapshot.chunkX(), snapshot.chunkZ(), plugin);
                    if (world.getMinHeight() != snapshot.minHeight() || world.getMaxHeight() != snapshot.maxHeight()) {
                        throw new IllegalArgumentException("Source and mirror worlds have different height ranges");
                    }

                    Chunk chunk = world.getChunkAt(snapshot.chunkX(), snapshot.chunkZ());
                    if (chunk.getTileEntities().length != 0) {
                        throw new IllegalStateException("The mirror chunk contains block entities; it was left unchanged");
                    }

                    String[] blockPalette = snapshot.blockPalette();
                    BlockData[] blockData = new BlockData[blockPalette.length];
                    for (int i = 0; i < blockPalette.length; i++) {
                        blockData[i] = Bukkit.createBlockData(blockPalette[i]);
                    }
                    String[] biomePalette = snapshot.biomePalette();
                    Biome[] biomes = new Biome[biomePalette.length];
                    for (int i = 0; i < biomePalette.length; i++) {
                        org.bukkit.NamespacedKey key = org.bukkit.NamespacedKey.fromString(biomePalette[i]);
                        biomes[i] = key == null ? null : org.bukkit.Registry.BIOME.get(key);
                        if (biomes[i] == null) {
                            throw new IllegalArgumentException("Mirror server does not have biome " + biomePalette[i]);
                        }
                    }

                    short[] blockIndices = snapshot.blockIndices();
                    int index = 0;
                    for (int y = snapshot.minHeight(); y < snapshot.maxHeight(); y++) {
                        for (int z = 0; z < 16; z++) {
                            for (int x = 0; x < 16; x++) {
                                chunk.getBlock(x, y, z).setBlockData(
                                    blockData[Short.toUnsignedInt(blockIndices[index++])], false
                                );
                            }
                        }
                    }

                    short[] biomeIndices = snapshot.biomeIndices();
                    int biomeIndex = 0;
                    for (int y = snapshot.minHeight(); y < snapshot.maxHeight(); y += 4) {
                        for (int z = 0; z < 16; z += 4) {
                            for (int x = 0; x < 16; x += 4) {
                                world.setBiome((snapshot.chunkX() << 4) + x, y, (snapshot.chunkZ() << 4) + z,
                                    biomes[Short.toUnsignedInt(biomeIndices[biomeIndex++])]);
                            }
                        }
                    }

                    successMessage = "Copied blocks and biomes for chunk (" + snapshot.chunkX() + ", "
                        + snapshot.chunkZ() + ") in world " + worldName + ".";
                    Mirage.LOGGER.info("Applied chunk ({}, {}) in world {}",
                        snapshot.chunkX(), snapshot.chunkZ(), worldName);
                } catch (Exception e) {
                    failure = e;
                } finally {
                    if (ticketAdded) {
                        try {
                            world.removePluginChunkTicket(snapshot.chunkX(), snapshot.chunkZ(), plugin);
                        } catch (Exception e) {
                            if (failure == null) {
                                failure = e;
                            } else {
                                failure.addSuppressed(e);
                            }
                        }
                    }
                }

                if (failure != null) {
                    result.completeExceptionally(failure);
                } else {
                    result.complete(successMessage);
                }
            });
        } catch (Exception e) {
            result.completeExceptionally(e);
        }
    }

    private byte[] requestChunk(
        MirageConfig.MirrorServerConfig config,
        String worldName,
        int chunkX,
        int chunkZ
    ) throws IOException {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(config.getMainIp(), config.getMainPort()), 5_000);
            socket.setSoTimeout(SOCKET_TIMEOUT_MILLIS);
            DataOutputStream output = new DataOutputStream(socket.getOutputStream());
            DataInputStream input = new DataInputStream(socket.getInputStream());

            output.writeInt(REQUEST_MAGIC);
            output.writeShort(PROTOCOL_VERSION);
            output.writeUTF(config.getAuthToken());
            output.writeUTF(worldName);
            output.writeInt(chunkX);
            output.writeInt(chunkZ);
            output.flush();

            if (!input.readBoolean()) {
                throw new IOException(input.readUTF());
            }
            int size = input.readInt();
            if (size <= 0 || size > ChunkSnapshotCodec.MAX_PAYLOAD_BYTES) {
                throw new IOException("Main server returned an invalid chunk payload size: " + size);
            }
            byte[] payload = new byte[size];
            input.readFully(payload);
            return payload;
        }
    }

    private static void writeError(DataOutputStream output, String message) throws IOException {
        output.writeBoolean(false);
        output.writeUTF(message.length() > 2000 ? message.substring(0, 2000) : message);
        output.flush();
    }

    private static boolean isStrongToken(String token) {
        if (token == null) {
            return false;
        }
        int byteLength = token.getBytes(StandardCharsets.UTF_8).length;
        return byteLength >= MIN_TOKEN_BYTES && byteLength <= MAX_TOKEN_BYTES;
    }

    private static Throwable unwrap(Throwable error) {
        if ((error instanceof java.util.concurrent.CompletionException
            || error instanceof java.util.concurrent.ExecutionException) && error.getCause() != null) {
            return error.getCause();
        }
        return error;
    }

    private static void closeSocket(Socket socket) {
        try {
            socket.close();
        } catch (IOException e) {
            Mirage.LOGGER.debug("Failed to close rejected connection", e);
        }
    }

    @Override
    public void close() {
        running.set(false);
        ServerSocket serverSocket = listener;
        listener = null;
        if (serverSocket != null) {
            try {
                serverSocket.close();
            } catch (IOException e) {
                Mirage.LOGGER.warn("Failed to close chunk sync listener", e);
            }
        }
        for (Socket socket : openSockets) {
            closeSocket(socket);
        }
        openSockets.clear();
        clientExecutor.shutdownNow();
        ioExecutor.shutdownNow();
    }
}
