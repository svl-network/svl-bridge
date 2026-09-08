/*
 * Copyright (c) 2026 Sunveil Network. All rights reserved.
 *
 * PROPRIETARY & CONFIDENTIAL
 *
 * This file is part of Sunveil Connect and the Sunveil Bridge.
 * Unauthorized copying of this file, via any medium, is strictly prohibited.
 *
 * You are permitted to view and compile this source code for personal,
 * private use with your own server infrastructure only. Redistribution,
 * public hosting, or creating derivative works is a direct violation of copyright.
 */

package net.sunveil.bridge.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public class BridgeConfig {
    private static final Logger LOGGER = LoggerFactory.getLogger("svl-bridge-config");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    public static final String DEFAULT_CONFIG_FILE = "svl-bridge.json";

    private String masterApiUrl = "https://realms.sunveil.net/api/v1/heartbeat";
    private String masterApiToken = "";
    private String serverKey = "svl_demo_realm";
    private String publicIp = "auto";
    private int publicPort = 25565;
    private String serverName = "Sunveil Modded Server";
    private int heartbeatIntervalSeconds = 30;
    private boolean tunnelEnabled = true;
    private int localServerPort = 25565;
    private boolean autoUpdateEnabled = true;

    private static final String[] ADJECTIVES = {
        "swift", "shadow", "mystic", "cosmic", "solar", "lunar", "crystal", "frost",
        "ember", "golden", "silent", "ancient", "storm", "valiant", "blazing",
        "emerald", "iron", "radiant", "stellar", "noble", "vortex", "astral",
        "hidden", "prime", "wild", "epic", "azure", "crimson", "phantom"
    };

    private static final String[] NOUNS = {
        "realm", "haven", "creeper", "dragon", "citadel", "valley", "sanctum", "outpost",
        "summit", "dominion", "stronghold", "bastion", "frontier", "peak", "oasis",
        "shelter", "temple", "refuge", "keep", "island", "forest", "cavern",
        "nexus", "canyon", "spire", "grove", "garrison"
    };

    public BridgeConfig() {
    }

    /**
     * Generates a unique, friendly Minecraft-themed random server key (e.g. "swift-dragon-482")
     */
    public static String generateRandomServerKey() {
        int adjIdx = java.util.concurrent.ThreadLocalRandom.current().nextInt(ADJECTIVES.length);
        int nounIdx = java.util.concurrent.ThreadLocalRandom.current().nextInt(NOUNS.length);
        int number = 100 + java.util.concurrent.ThreadLocalRandom.current().nextInt(900);
        return ADJECTIVES[adjIdx] + "-" + NOUNS[nounIdx] + "-" + number;
    }

    /**
     * Checks whether a string resembles an API token, license key, or JWT secret rather than a friendly server key
     */
    public static boolean isTokenLike(String str) {
        if (str == null || str.isBlank()) {
            return false;
        }
        String s = str.trim();
        if (s.startsWith("Bearer ") || s.startsWith("SVL-") || s.startsWith("svl_") ||
            s.startsWith("eyJ") || s.startsWith("sk_") || s.startsWith("token_") || s.startsWith("secret_")) {
            return true;
        }
        // Long random hex/uuid/base64 strings (>= 30 chars without hyphens between words or containing multiple dots/underscores)
        if (s.length() >= 30 && (s.matches("^[a-fA-F0-9]{32,}$") || s.contains("."))) {
            return true;
        }
        return false;
    }

    public static BridgeConfig load(Path configDir) {
        return load(configDir, DEFAULT_CONFIG_FILE);
    }

    public static BridgeConfig load(Path configDir, String fileName) {
        String targetName = fileName != null ? fileName : DEFAULT_CONFIG_FILE;
        Path configPath = configDir.resolve(targetName);

        // Also check fallback filenames if target doesn't exist
        if (!Files.exists(configPath)) {
            if ("config.json".equals(targetName) && Files.exists(configDir.resolve("svl-bridge.json"))) {
                configPath = configDir.resolve("svl-bridge.json");
            } else if ("svl-bridge.json".equals(targetName) && Files.exists(configDir.resolve("config.json"))) {
                configPath = configDir.resolve("config.json");
            }
        }

        final String actualFileName = configPath.getFileName().toString();

        if (configPath == null || !Files.exists(configPath)) {
            BridgeConfig defaultConfig = new BridgeConfig();
            defaultConfig.serverKey = generateRandomServerKey();
            defaultConfig.serverName = "Sunveil Realm (" + defaultConfig.serverKey + ")";
            defaultConfig.applyEnvironmentOverrides();
            defaultConfig.validateAndSetDefaults();
            defaultConfig.save(configDir, actualFileName);
            return defaultConfig;
        }

        try (BufferedReader reader = Files.newBufferedReader(configPath, StandardCharsets.UTF_8)) {
            BridgeConfig config = GSON.fromJson(reader, BridgeConfig.class);
            if (config == null) {
                config = new BridgeConfig();
            }
            config.applyEnvironmentOverrides();
            boolean modified = config.validateAndSetDefaults();
            if (modified) {
                config.save(configDir, actualFileName);
            }
            return config;
        } catch (Exception e) {
            LOGGER.error("Failed to load config from {}. Generating new valid configuration.", configPath, e);
            BridgeConfig fallback = new BridgeConfig();
            fallback.serverKey = generateRandomServerKey();
            fallback.serverName = "Sunveil Realm (" + fallback.serverKey + ")";
            fallback.applyEnvironmentOverrides();
            fallback.validateAndSetDefaults();
            fallback.save(configDir, actualFileName);
            return fallback;
        }
    }

    public void applyEnvironmentOverrides() {
        String envUrl = System.getenv("SVL_MASTER_API_URL");
        if (envUrl != null && !envUrl.isBlank()) {
            this.masterApiUrl = envUrl.trim();
        }
        String envToken = System.getenv("SVL_MASTER_API_TOKEN");
        if (envToken != null && !envToken.isBlank()) {
            this.masterApiToken = envToken.trim();
        }
        String envKey = System.getenv("SVL_SERVER_KEY");
        if (envKey != null && !envKey.isBlank()) {
            this.serverKey = envKey.trim();
        }
        String envIp = System.getenv("SVL_PUBLIC_IP");
        if (envIp != null && !envIp.isBlank()) {
            this.publicIp = envIp.trim();
        }
        String envName = System.getenv("SVL_SERVER_NAME");
        if (envName != null && !envName.isBlank()) {
            this.serverName = envName.trim();
        }
        String envTunnel = System.getenv("SVL_TUNNEL_ENABLED");
        if (envTunnel != null && !envTunnel.isBlank()) {
            this.tunnelEnabled = Boolean.parseBoolean(envTunnel.trim());
        }
    }

    public void save(Path configDir) {
        save(configDir, DEFAULT_CONFIG_FILE);
    }

    public void save(Path configDir, String fileName) {
        Path configPath = configDir.resolve(fileName != null ? fileName : DEFAULT_CONFIG_FILE);
        if (configPath == null) {
            return;
        }
        try {
            if (configPath.getParent() != null) {
                Files.createDirectories(configPath.getParent());
            }
            try (BufferedWriter writer = Files.newBufferedWriter(configPath, StandardCharsets.UTF_8)) {
                GSON.toJson(this, writer);
                LOGGER.info("Saved configuration to {}", configPath);
            }
        } catch (IOException e) {
            LOGGER.error("Failed to save configuration to {}", configPath, e);
        }
    }

    /**
     * Validates and normalizes all fields. Returns true if any field was corrected or regenerated.
     */
    public boolean validateAndSetDefaults() {
        boolean modified = false;

        if (masterApiUrl == null || masterApiUrl.isBlank()) {
            masterApiUrl = "https://realms.sunveil.net/api/v1/heartbeat";
            modified = true;
        }
        if (masterApiToken == null) {
            masterApiToken = "";
            modified = true;
        }

        // Safety Guard: Prevent user from accidentally configuring their secret masterApiToken as serverKey
        if (isTokenLike(serverKey)) {
            if (masterApiToken.isBlank()) {
                masterApiToken = serverKey.trim();
                LOGGER.info("[SVL-Config] Migrated secret token from serverKey field to masterApiToken.");
            }
            serverKey = generateRandomServerKey();
            modified = true;
            LOGGER.warn("[SVL-Config] Protected master key from being exposed as serverKey. Assigned safe random key: {}", serverKey);
        } else if (serverKey != null && !serverKey.isBlank() && serverKey.trim().equalsIgnoreCase(masterApiToken.trim()) && !masterApiToken.isBlank()) {
            serverKey = generateRandomServerKey();
            modified = true;
            LOGGER.warn("[SVL-Config] serverKey cannot match masterApiToken. Assigned new random key: {}", serverKey);
        } else if (serverKey == null || serverKey.isBlank() || "svl_demo_realm".equalsIgnoreCase(serverKey.trim()) || "default".equalsIgnoreCase(serverKey.trim())) {
            serverKey = generateRandomServerKey();
            modified = true;
            LOGGER.info("[SVL-Config] Generated initial unique random serverKey: {}", serverKey);
        }

        if (publicIp == null || publicIp.isBlank() || "java.sunveil.net".equalsIgnoreCase(publicIp)) {
            publicIp = "auto";
            modified = true;
        }
        if (publicPort <= 0 || publicPort > 65535) {
            publicPort = 25565;
            modified = true;
        }
        if (serverName == null || serverName.isBlank() || "Sunveil Modded Server".equals(serverName)) {
            serverName = "Sunveil Realm (" + serverKey + ")";
            modified = true;
        }
        if (heartbeatIntervalSeconds <= 0) {
            heartbeatIntervalSeconds = 30;
            modified = true;
        }

        return modified;
    }

    public String getMasterApiUrl() {
        return masterApiUrl;
    }

    public void setMasterApiUrl(String masterApiUrl) {
        this.masterApiUrl = masterApiUrl;
    }

    public String getMasterApiToken() {
        return masterApiToken;
    }

    public void setMasterApiToken(String masterApiToken) {
        this.masterApiToken = masterApiToken;
    }

    public String getServerKey() {
        return serverKey;
    }

    public void setServerKey(String serverKey) {
        this.serverKey = serverKey;
    }

    public String getPublicIp() {
        return publicIp;
    }

    public void setPublicIp(String publicIp) {
        this.publicIp = publicIp;
    }

    public int getPublicPort() {
        return publicPort;
    }

    public void setPublicPort(int publicPort) {
        this.publicPort = publicPort;
    }

    public String getServerName() {
        return serverName;
    }

    public void setServerName(String serverName) {
        this.serverName = serverName;
    }

    public int getHeartbeatIntervalSeconds() {
        return heartbeatIntervalSeconds;
    }

    public void setHeartbeatIntervalSeconds(int heartbeatIntervalSeconds) {
        this.heartbeatIntervalSeconds = heartbeatIntervalSeconds;
    }

    public boolean isTunnelEnabled() {
        return tunnelEnabled;
    }

    public void setTunnelEnabled(boolean tunnelEnabled) {
        this.tunnelEnabled = tunnelEnabled;
    }

    public int getLocalServerPort() {
        return localServerPort;
    }

    public void setLocalServerPort(int localServerPort) {
        this.localServerPort = localServerPort;
    }

    public boolean isAutoUpdateEnabled() {
        return autoUpdateEnabled;
    }

    public void setAutoUpdateEnabled(boolean autoUpdateEnabled) {
        this.autoUpdateEnabled = autoUpdateEnabled;
    }
}
