package xyz.tofumc;

import org.bukkit.plugin.java.JavaPlugin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import xyz.tofumc.mirage.command.MirageCommand;
import xyz.tofumc.mirage.config.ConfigManager;
import xyz.tofumc.mirage.config.MirageConfig;

import java.io.IOException;

public final class Mirage extends JavaPlugin {
    public static final String MOD_ID = "mirage";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    private static Mirage instance;

    private ConfigManager configManager;
    private MirageConfig config;

    public static Mirage getInstance() {
        return instance;
    }

    @Override
    public void onEnable() {
        instance = this;
        configManager = new ConfigManager(getDataFolder().toPath());
        reloadMirageConfig();
        MirageCommand.register(this);

        LOGGER.warn("Live world synchronization is disabled: safe Folia APIs do not support replacing active region files.");
        LOGGER.info("Mirage initialized in {} mode", getMode());
    }

    @Override
    public void onDisable() {
        instance = null;
    }

    public boolean reloadMirageConfig() {
        try {
            config = configManager.load();
            return true;
        } catch (IOException e) {
            LOGGER.error("Failed to load Mirage configuration", e);
            if (config == null) {
                config = new MirageConfig();
            }
            return false;
        }
    }

    public String getMode() {
        return config != null ? config.getMode() : "unknown";
    }

    public MirageConfig getMirageConfig() {
        return config;
    }
}
