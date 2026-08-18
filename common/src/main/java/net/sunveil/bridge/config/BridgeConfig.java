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
    private String masterApiToken = "svl_secret_token_2026";
    private String serverKey = "svl_demo_realm";
    private String publicIp = "java.sunveil.net";
    private int publicPort = 25565;
    private String serverName = "Sunveil Modded Server";
    private int heartbeatIntervalSeconds = 30;

    public BridgeConfig() {
    }

    public static BridgeConfig load(Path configDir) {
        return load(configDir, DEFAULT_CONFIG_FILE);
    }

    public static BridgeConfig load(Path configDir, String fileName) {
        Path configPath = configDir.resolve(fileName != null ? fileName : DEFAULT_CONFIG_FILE);
        if (configPath == null || !Files.exists(configPath)) {
            BridgeConfig defaultConfig = new BridgeConfig();
            if (configPath != null) {
                defaultConfig.save(configDir, fileName);
            }
            return defaultConfig;
        }

        try (BufferedReader reader = Files.newBufferedReader(configPath, StandardCharsets.UTF_8)) {
            BridgeConfig config = GSON.fromJson(reader, BridgeConfig.class);
            if (config == null) {
                config = new BridgeConfig();
            }
            config.validateAndSetDefaults();
            return config;
        } catch (Exception e) {
            LOGGER.error("Failed to load config from {}. Using defaults.", configPath, e);
            BridgeConfig fallback = new BridgeConfig();
            fallback.save(configDir, fileName);
            return fallback;
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

    public void validateAndSetDefaults() {
        if (masterApiUrl == null || masterApiUrl.isBlank()) {
            masterApiUrl = "https://realms.sunveil.net/api/v1/heartbeat";
        }
        if (masterApiToken == null || masterApiToken.isBlank()) {
            masterApiToken = "svl_secret_token_2026";
        }
        if (serverKey == null || serverKey.isBlank()) {
            serverKey = "svl_demo_realm";
        }
        if (publicIp == null || publicIp.isBlank()) {
            publicIp = "java.sunveil.net";
        }
        if (publicPort <= 0 || publicPort > 65535) {
            publicPort = 25565;
        }
        if (serverName == null || serverName.isBlank()) {
            serverName = "Sunveil Modded Server";
        }
        if (heartbeatIntervalSeconds <= 0) {
            heartbeatIntervalSeconds = 30;
        }
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
}
