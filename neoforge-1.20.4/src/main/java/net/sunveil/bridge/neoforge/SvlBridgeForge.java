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

package net.sunveil.bridge.neoforge;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.sunveil.bridge.config.BridgeConfig;
import net.sunveil.bridge.model.HeartbeatPayload;
import net.sunveil.bridge.network.MasterApiClient;
import net.sunveil.bridge.scanner.ModScanner;
import net.sunveil.bridge.scanner.ModrinthService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Mod(SvlBridgeForge.MOD_ID)
public class SvlBridgeForge {
    public static final String MOD_ID = "svl_bridge";
    private static final Logger LOGGER = LoggerFactory.getLogger("svl-bridge-neoforge-1.20.4");

    private final BridgeConfig config;
    private final ModScanner modScanner;
    private final ModrinthService modrinthService;
    private final MasterApiClient masterApiClient;
    private ScheduledExecutorService heartbeatScheduler;

    public SvlBridgeForge(IEventBus modEventBus) {
        LOGGER.info("[SVL-Bridge] Initializing NeoForge Bridge Mod for Minecraft 1.20.4...");

        Path configDir = FMLPaths.CONFIGDIR.get();
        this.config = BridgeConfig.load(configDir);
        this.modScanner = new ModScanner();
        this.modrinthService = new ModrinthService();
        this.masterApiClient = new MasterApiClient();

        NeoForge.EVENT_BUS.addListener(this::onServerStarted);
        NeoForge.EVENT_BUS.addListener(this::onServerStopping);

        LOGGER.info("[SVL-Bridge] NeoForge 1.20.4 Bridge initialized. Server key: {}", config.getServerKey());
    }

    public void onServerStarted(ServerStartedEvent event) {
        Object server = extractServer(event);
        LOGGER.info("[SVL-Bridge] Server started. Initiating background mod scan and manifest sync...");

        Path gameDir = FMLPaths.GAMEDIR.get();

        CompletableFuture.runAsync(() -> {
            var scannedMods = modScanner.scanPlatform(gameDir, ModScanner.PlatformType.NEOFORGE);
            modrinthService.resolveModsWithFallbackAsync(scannedMods, masterApiClient, config.getMasterApiUrl(), config.getMasterApiToken())
                    .thenAccept(manifest -> {
                        LOGGER.info("[SVL-Bridge] Mod manifest resolved with {} entries.", manifest.size());
                        sendHeartbeat(server);
                    });
        }).exceptionally(throwable -> {
            LOGGER.error("[SVL-Bridge] Exception during initial mod scan.", throwable);
            return null;
        });

        int interval = config.getHeartbeatIntervalSeconds() > 0 ? config.getHeartbeatIntervalSeconds() : 30;
        this.heartbeatScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "SVL-Bridge-NeoForge-Heartbeat");
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

    private Object extractServer(ServerStartedEvent event) {
        try {
            Method m = event.getClass().getMethod("getServer");
            return m.invoke(event);
        } catch (Exception e) {
            LOGGER.debug("Could not extract server instance from event.", e);
            return null;
        }
    }

    private void sendHeartbeat(Object server) {
        try {
            String mcVersion = invokeString(server, "getServerVersion", "1.20.4");
            int players = invokeInt(server, "getPlayerCount", 0);
            int maxPlayers = invokeInt(server, "getMaxPlayers", 20);
            String motd = invokeString(server, "getMotd", "");

            String loaderVersion = "20.4.237";
            var neoforgeContainer = ModList.get().getModContainerById("neoforge");
            if (neoforgeContainer.isPresent()) {
                loaderVersion = neoforgeContainer.get().getModInfo().getVersion().toString();
            }

            HeartbeatPayload.VersionInfo versionInfo = new HeartbeatPayload.VersionInfo(
                    mcVersion,
                    "neoforge",
                    loaderVersion
            );

            HeartbeatPayload.StatusInfo statusInfo = new HeartbeatPayload.StatusInfo(
                    players,
                    maxPlayers,
                    motd
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

    private String invokeString(Object target, String methodName, String defaultValue) {
        if (target == null) return defaultValue;
        try {
            Method method = target.getClass().getMethod(methodName);
            Object res = method.invoke(target);
            return res != null ? res.toString() : defaultValue;
        } catch (Exception e) {
            return defaultValue;
        }
    }

    private int invokeInt(Object target, String methodName, int defaultValue) {
        if (target == null) return defaultValue;
        try {
            Method method = target.getClass().getMethod(methodName);
            Object res = method.invoke(target);
            if (res instanceof Number num) {
                return num.intValue();
            }
            return defaultValue;
        } catch (Exception e) {
            return defaultValue;
        }
    }

    public void onServerStopping(ServerStoppingEvent event) {
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
