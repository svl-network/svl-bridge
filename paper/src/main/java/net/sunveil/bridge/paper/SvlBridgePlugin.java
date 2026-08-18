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

package net.sunveil.bridge.paper;

import net.sunveil.bridge.config.BridgeConfig;
import net.sunveil.bridge.model.HeartbeatPayload;
import net.sunveil.bridge.network.MasterApiClient;
import net.sunveil.bridge.scanner.ModScanner;
import net.sunveil.bridge.scanner.ModrinthService;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.nio.file.Path;
import java.util.logging.Level;

public class SvlBridgePlugin extends JavaPlugin {
    private BridgeConfig config;
    private ModScanner modScanner;
    private ModrinthService modrinthService;
    private MasterApiClient masterApiClient;
    private BukkitTask heartbeatTask;

    @Override
    public void onEnable() {
        getLogger().info("Initializing SVL Bridge for Paper/Spigot/Bukkit...");

        Path pluginDir = getDataFolder().toPath();
        this.config = BridgeConfig.load(pluginDir, "config.json");
        this.modScanner = new ModScanner();
        this.modrinthService = new ModrinthService();
        this.masterApiClient = new MasterApiClient();

        // Asynchronous initial scan and manifest resolution
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            Path serverRoot = Path.of(".").toAbsolutePath().normalize();
            getLogger().info("Starting background scan from root: " + serverRoot);

            var scannedMods = modScanner.scanPlatform(serverRoot, ModScanner.PlatformType.PAPER);
            modrinthService.resolveModsWithFallbackAsync(scannedMods, masterApiClient, config.getMasterApiUrl(), config.getMasterApiToken())
                    .thenAccept(manifest -> {
                        getLogger().info("Mod/Plugin manifest resolved with " + manifest.size() + " items.");
                        sendHeartbeat();
                    });
        });

        // Repeating async heartbeat
        int intervalSeconds = config.getHeartbeatIntervalSeconds() > 0 ? config.getHeartbeatIntervalSeconds() : 30;
        long intervalTicks = intervalSeconds * 20L;

        this.heartbeatTask = Bukkit.getScheduler().runTaskTimerAsynchronously(
                this,
                this::sendHeartbeat,
                intervalTicks,
                intervalTicks
        );

        getLogger().info("SVL Bridge enabled successfully. Heartbeat interval: " + intervalSeconds + "s.");
    }

    private void sendHeartbeat() {
        try {
            String mcVersion = "1.21.1";
            try {
                mcVersion = Bukkit.getServer().getMinecraftVersion();
            } catch (Throwable ignored) {
                try {
                    mcVersion = Bukkit.getBukkitVersion().split("-")[0];
                } catch (Throwable fallback) {
                    mcVersion = "1.21.1";
                }
            }
            if (mcVersion == null || mcVersion.isBlank()) {
                mcVersion = "1.21.1";
            }

            String serverName = Bukkit.getName().toLowerCase();
            String loader = serverName.contains("paper") ? "paper"
                    : serverName.contains("purpur") ? "purpur"
                    : serverName.contains("spigot") ? "spigot"
                    : "bukkit";

            String loaderVersion = Bukkit.getVersion();

            HeartbeatPayload.VersionInfo versionInfo = new HeartbeatPayload.VersionInfo(
                    mcVersion,
                    loader,
                    loaderVersion
            );

            HeartbeatPayload.StatusInfo statusInfo = new HeartbeatPayload.StatusInfo(
                    Bukkit.getOnlinePlayers().size(),
                    Bukkit.getMaxPlayers(),
                    Bukkit.getMotd() != null ? Bukkit.getMotd() : ""
            );

            HeartbeatPayload payload = new HeartbeatPayload(
                    config.getServerKey(),
                    config.getServerName(),
                    config.getPublicIp(),
                    config.getPublicPort(),
                    versionInfo,
                    statusInfo,
                    modrinthService.getCachedManifest()
            );

            masterApiClient.sendHeartbeatAsync(config.getMasterApiUrl(), config.getMasterApiToken(), payload);
        } catch (Exception e) {
            getLogger().log(Level.WARNING, "Error dispatching heartbeat payload", e);
        }
    }

    @Override
    public void onDisable() {
        getLogger().info("Disabling SVL Bridge...");
        if (heartbeatTask != null && !heartbeatTask.isCancelled()) {
            heartbeatTask.cancel();
        }
        Bukkit.getScheduler().cancelTasks(this);
        getLogger().info("SVL Bridge disabled.");
    }
}
