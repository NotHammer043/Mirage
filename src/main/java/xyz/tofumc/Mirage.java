package xyz.tofumc;

import org.bukkit.plugin.java.JavaPlugin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import xyz.tofumc.mirage.command.MirageCommand;
import xyz.tofumc.mirage.config.ConfigManager;
import xyz.tofumc.mirage.config.MirageConfig;
import xyz.tofumc.mirage.sync.FoliaSyncService;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

public final class Mirage extends JavaPlugin {
    public static final String MOD_ID = "mirage";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    private static Mirage instance;

    private ConfigManager configManager;
    private MirageConfig config;
    private FoliaSyncService syncService;

    public static Mirage getInstance() {
        return instance;
    }

    @Override
    public void onEnable() {
        instance = this;
        configManager = new ConfigManager(getDataFolder().toPath());
        if (!reloadMirageConfig()) {
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        MirageCommand.register(this);

        if (config.getMainServer() == null || config.getMirrorServer() == null
            || config.getMode() == null
            || (!"main".equalsIgnoreCase(config.getMode()) && !"mirror".equalsIgnoreCase(config.getMode()))) {
            LOGGER.error("Invalid Mirage mode '{}'; expected 'main' or 'mirror'", config.getMode());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        syncService = new FoliaSyncService(this);
        if ("main".equalsIgnoreCase(config.getMode())) {
            try {
                syncService.startMainServer(config.getMainServer());
            } catch (IOException e) {
                LOGGER.error("Failed to start Mirage's chunk sync listener", e);
                getServer().getPluginManager().disablePlugin(this);
                return;
            }
        } else {
            LOGGER.info("Mirage mirror mode is ready to pull chunks from the configured main server");
        }

        LOGGER.info("Mirage initialized in {} mode", getMode());
    }

    @Override
    public void onDisable() {
        if (syncService != null) {
            syncService.close();
            syncService = null;
        }
        instance = null;
    }

    public boolean reloadMirageConfig() {
        try {
            MirageConfig loaded = configManager.load();
            validateConfig(loaded);
            if (config != null && (
                !config.getMode().equalsIgnoreCase(loaded.getMode())
                    || config.getMainServer().getPort() != loaded.getMainServer().getPort()
                    || !config.getMainServer().getAuthToken().equals(loaded.getMainServer().getAuthToken())
            )) {
                throw new IOException("Mode, listener port, and main authentication changes require a server restart");
            }
            config = loaded;
            return true;
        } catch (IOException e) {
            LOGGER.error("Failed to load Mirage configuration", e);
            return false;
        }
    }

    private static void validateConfig(MirageConfig config) throws IOException {
        if (config.getMode() == null
            || (!"main".equalsIgnoreCase(config.getMode()) && !"mirror".equalsIgnoreCase(config.getMode()))) {
            throw new IOException("Mode must be either 'main' or 'mirror'");
        }
        if (config.getMainServer() == null || config.getMirrorServer() == null) {
            throw new IOException("Both mainServer and mirrorServer configuration sections are required");
        }

        if ("main".equalsIgnoreCase(config.getMode())) {
            String token = config.getMainServer().getAuthToken();
            if (config.getMainServer().getPort() < 1 || config.getMainServer().getPort() > 65535
                || !hasStrongToken(token)) {
                throw new IOException("Main mode requires a valid port and a 16-to-1024-byte auth token");
            }
        } else {
            String token = config.getMirrorServer().getAuthToken();
            if (config.getMirrorServer().getMainIp() == null || config.getMirrorServer().getMainIp().isBlank()
                || config.getMirrorServer().getMainPort() < 1 || config.getMirrorServer().getMainPort() > 65535
                || !hasStrongToken(token)) {
                throw new IOException("Mirror mode requires mainIp, a valid mainPort, and a 16-to-1024-byte shared auth token");
            }
        }
    }

    private static boolean hasStrongToken(String token) {
        if (token == null) {
            return false;
        }
        int length = token.getBytes(StandardCharsets.UTF_8).length;
        return length >= 16 && length <= 1024;
    }

    public String getMode() {
        return config != null ? config.getMode() : "unknown";
    }

    public boolean isMainMode() {
        return "main".equalsIgnoreCase(getMode());
    }

    public boolean isMirrorMode() {
        return "mirror".equalsIgnoreCase(getMode());
    }

    public MirageConfig getMirageConfig() {
        return config;
    }

    public FoliaSyncService getSyncService() {
        return syncService;
    }
}
