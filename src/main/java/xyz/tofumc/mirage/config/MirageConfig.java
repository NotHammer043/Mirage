package xyz.tofumc.mirage.config;

import java.util.UUID;

public class MirageConfig {
    private String mode = "main";
    private MainServerConfig mainServer = new MainServerConfig();
    private MirrorServerConfig mirrorServer = new MirrorServerConfig();

    public String getMode() {
        return mode;
    }

    public MainServerConfig getMainServer() {
        return mainServer;
    }

    public MirrorServerConfig getMirrorServer() {
        return mirrorServer;
    }

    public static class MainServerConfig {
        private int port = 25566;
        private String authToken = UUID.randomUUID().toString();

        public int getPort() {
            return port;
        }

        public String getAuthToken() {
            return authToken;
        }
    }

    public static class MirrorServerConfig {
        private String mainIp = "127.0.0.1";
        private int mainPort = 25566;
        private String authToken = "";

        public String getMainIp() {
            return mainIp;
        }

        public int getMainPort() {
            return mainPort;
        }

        public String getAuthToken() {
            return authToken;
        }
    }
}
