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

package net.sunveil.bridge.fabric;

import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.server.MinecraftServer;
import net.sunveil.bridge.config.BridgeConfig;
import net.sunveil.bridge.model.HeartbeatPayload;
import net.sunveil.bridge.network.MasterApiClient;
import net.sunveil.bridge.scanner.ModScanner;
import net.sunveil.bridge.scanner.ModrinthService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class SvlBridgeFabric implements DedicatedServerModInitializer {
    public static final String MOD_ID = "svl-bridge";
    private static final Logger LOGGER = LoggerFactory.getLogger("svl-bridge-fabric");

    private BridgeConfig config;
    private ModScanner modScanner;
    private ModrinthService modrinthService;
    private MasterApiClient masterApiClient;
    private ScheduledExecutorService heartbeatScheduler;

    @Override
    public void onInitializeServer() {
        LOGGER.info("[SVL-Bridge] Initializing Fabric Server Bridge for Minecraft 1.21.1...");

        Path configDir = FabricLoader.getInstance().getConfigDir();
        this.config = BridgeConfig.load(configDir);
        this.modScanner = new ModScanner();
        this.modrinthService = new ModrinthService();
        this.masterApiClient = new MasterApiClient();

        ServerLifecycleEvents.SERVER_STARTED.register(this::onServerStarted);
        ServerLifecycleEvents.SERVER_STOPPING.register(this::onServerStopping);

        LOGGER.info("[SVL-Bridge] Fabric Bridge initialized. Server key: {}", config.getServerKey());
    }

    private void onServerStarted(MinecraftServer server) {
        LOGGER.info("[SVL-Bridge] Server started. Initiating background mod scan and manifest sync...");

        Path gameDir = FabricLoader.getInstance().getGameDir();

        // Asynchronous scan and Modrinth hash resolution
        CompletableFuture.runAsync(() -> {
            var scannedMods = modScanner.scanPlatform(gameDir, ModScanner.PlatformType.FABRIC);
            modrinthService.resolveModsWithFallbackAsync(scannedMods, masterApiClient, config.getMasterApiUrl(), config.getMasterApiToken())
                    .thenAccept(manifest -> {
                        LOGGER.info("[SVL-Bridge] Mod manifest resolved with {} entries.", manifest.size());
                        sendHeartbeat(server);
                    });
        }).exceptionally(throwable -> {
            LOGGER.error("[SVL-Bridge] Exception during initial mod scan.", throwable);
            return null;
        });

        // Periodic heartbeat task
        int interval = config.getHeartbeatIntervalSeconds() > 0 ? config.getHeartbeatIntervalSeconds() : 30;
        this.heartbeatScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "SVL-Bridge-Fabric-Heartbeat");
            t.setDaemon(true);
            return t;
        });

        heartbeatScheduler.scheduleAtFixedRate(
                () -> {
                    try {
                        sendHeartbeat(server);
                    } catch (Exception e) {
                        LOGGER.error("[SVL-Bridge] Unexpected error while sending heartbeat.", e);
                    }
                },
                interval,
                interval,
                TimeUnit.SECONDS
        );

        LOGGER.info("[SVL-Bridge] Heartbeat scheduler started (interval: {}s, endpoint: {}).",
                interval, config.getMasterApiUrl());
    }

    private void sendHeartbeat(MinecraftServer server) {
        if (server == null) {
            return;
        }

        try {
            String mcVersion = server.getVersion();
            if (mcVersion == null || mcVersion.isBlank()) {
                mcVersion = "1.21.1";
            }

            String loaderVersion = FabricLoader.getInstance()
                    .getModContainer("fabricloader")
                    .map(ModContainer::getMetadata)
                    .map(metadata -> metadata.getVersion().getFriendlyString())
                    .orElse("unknown");

            HeartbeatPayload.VersionInfo versionInfo = new HeartbeatPayload.VersionInfo(
                    mcVersion,
                    "fabric",
                    loaderVersion
            );

            HeartbeatPayload.StatusInfo statusInfo = new HeartbeatPayload.StatusInfo(
                    server.getCurrentPlayerCount(),
                    server.getMaxPlayerCount(),
                    server.getServerMotd() != null ? server.getServerMotd() : ""
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
            LOGGER.error("[SVL-Bridge] Error creating heartbeat payload.", e);
        }
    }

    private void onServerStopping(MinecraftServer server) {
        LOGGER.info("[SVL-Bridge] Server stopping. Shutting down heartbeat scheduler...");

        if (heartbeatScheduler != null && !heartbeatScheduler.isShutdown()) {
            heartbeatScheduler.shutdown();
            try {
                if (!heartbeatScheduler.awaitTermination(3, TimeUnit.SECONDS)) {
                    heartbeatScheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                heartbeatScheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }

        LOGGER.info("[SVL-Bridge] Heartbeat scheduler terminated.");
    }
}
