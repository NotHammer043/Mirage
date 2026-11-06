package xyz.tofumc.mirage.command;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import xyz.tofumc.Mirage;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class MirageCommand implements CommandExecutor, TabCompleter {
    private static final String SYNC_LIMITATION =
        "Live world synchronization is disabled because replacing or saving active region files is not Folia-safe.";

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
            case "sync", "pull" -> sender.sendMessage(SYNC_LIMITATION);
            case "status" -> sender.sendMessage("Mirage mode=" + Mirage.getInstance().getMode()
                + ", liveSync=disabled");
            case "reload" -> {
                if (Mirage.getInstance().reloadMirageConfig()) {
                    sender.sendMessage("Mirage configuration reloaded.");
                } else {
                    sender.sendMessage("Mirage configuration reload failed. Check the server log.");
                }
            }
            default -> {
                sender.sendMessage("Unknown subcommand: " + args[0]);
                return false;
            }
        }
        return true;
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
}
