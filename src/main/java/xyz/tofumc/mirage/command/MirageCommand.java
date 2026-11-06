package xyz.tofumc.mirage.command;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import xyz.tofumc.Mirage;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletionException;

public final class MirageCommand implements CommandExecutor, TabCompleter {
    public static void register(Mirage plugin) {
        PluginCommand command = plugin.getCommand("mirage");
        if (command == null) {
            plugin.getLogger().severe("The mirage command is missing from plugin.yml.");
            return;
        }

        MirageCommand executor = new MirageCommand();
        command.setExecutor(executor);
        command.setTabCompleter(executor);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage("Usage: /mirage <sync|pull|status|reload>");
            return true;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "sync" -> handleSync(sender, args);
            case "pull" -> handlePull(sender, args);
            case "status" -> handleStatus(sender);
            case "reload" -> handleReload(sender);
            default -> {
                sender.sendMessage("Unknown subcommand: " + args[0]);
                return false;
            }
        }
        return true;
    }

    private void handleSync(CommandSender sender, String[] args) {
        Mirage mirage = Mirage.getInstance();
        if (!mirage.isMirrorMode()) {
            int port = mirage.getMirageConfig().getMainServer().getPort();
            sender.sendMessage("This server serves chunk snapshots on port " + port
                + ". Run /mirage pull <world> <chunkX> <chunkZ> on the mirror server.");
            return;
        }
        handlePull(sender, args);
    }

    private void handlePull(CommandSender sender, String[] args) {
        Mirage mirage = Mirage.getInstance();
        if (!mirage.isMirrorMode()) {
            sender.sendMessage("Chunk pulls must be started on a server configured with mode: mirror.");
            return;
        }
        if (args.length < 4) {
            sender.sendMessage("Usage: /mirage pull <world> <chunkX> <chunkZ> (coordinates are chunk coordinates)");
            return;
        }

        int chunkX;
        int chunkZ;
        try {
            chunkX = Integer.parseInt(args[args.length - 2]);
            chunkZ = Integer.parseInt(args[args.length - 1]);
        } catch (NumberFormatException e) {
            sender.sendMessage("Chunk coordinates must be integers.");
            return;
        }

        String worldName = String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length - 2));
        if (worldName.isBlank()) {
            sender.sendMessage("World name cannot be empty.");
            return;
        }

        sender.sendMessage("Requesting chunk (" + chunkX + ", " + chunkZ + ") from world " + worldName + "...");
        mirage.getSyncService().pullChunk(
            mirage.getMirageConfig().getMirrorServer(), worldName, chunkX, chunkZ
        ).whenComplete((message, error) -> {
            if (error != null) {
                Throwable cause = unwrap(error);
                Mirage.LOGGER.warn("Chunk pull failed: {}", cause.getMessage());
                sendSafely(mirage, sender, "Chunk pull failed: " + cause.getMessage());
            } else {
                sendSafely(mirage, sender, message);
            }
        });
    }

    private void handleStatus(CommandSender sender) {
        Mirage mirage = Mirage.getInstance();
        String state = mirage.isMainMode()
            ? (mirage.getSyncService() != null && mirage.getSyncService().isListening()
                ? "serving on port " + mirage.getMirageConfig().getMainServer().getPort()
                : "listener unavailable")
            : "mirror mode; remote pulls enabled";
        sender.sendMessage("Mirage mode=" + mirage.getMode() + ", status=" + state);
    }

    private void handleReload(CommandSender sender) {
        Mirage mirage = Mirage.getInstance();
        if (mirage.reloadMirageConfig()) {
            sender.sendMessage(
                "Mirage configuration reloaded. Restart to apply mode or main listener port/authentication changes."
            );
        } else {
            sender.sendMessage("Mirage configuration reload failed. Check the server log.");
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length != 1) {
            return List.of();
        }

        List<String> completions = new ArrayList<>(List.of("sync", "pull", "status", "reload"));
        if (args[0].isBlank()) {
            return completions;
        }
        return completions.stream()
            .filter(value -> value.regionMatches(true, 0, args[0], 0, args[0].length()))
            .toList();
    }

    private static void sendSafely(Mirage plugin, CommandSender sender, String message) {
        if (!plugin.isEnabled()) {
            Mirage.LOGGER.info("Chunk operation completed after Mirage was disabled: {}", message);
            return;
        }
        if (sender instanceof Player player) {
            if (player.getScheduler().run(plugin, task -> player.sendMessage(message), null) == null) {
                Mirage.LOGGER.info("Could not notify a player because they disconnected: {}", message);
            }
        } else {
            plugin.getServer().getGlobalRegionScheduler().execute(plugin, () -> sender.sendMessage(message));
        }
    }

    private static Throwable unwrap(Throwable error) {
        Throwable current = error;
        while ((current instanceof CompletionException || current instanceof java.util.concurrent.ExecutionException)
            && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }
}
