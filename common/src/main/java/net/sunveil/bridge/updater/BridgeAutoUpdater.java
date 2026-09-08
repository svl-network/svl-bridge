/*
 * Copyright (c) 2026 Sunveil Network. All rights reserved.
 *
 * PROPRIETARY & CONFIDENTIAL
 *
 * This file is part of Sunveil Connect and the Sunveil Bridge.
 * Unauthorized copying of this file, via any medium, is strictly prohibited.
 */

package net.sunveil.bridge.updater;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

public class BridgeAutoUpdater {
    private static final Logger LOGGER = LoggerFactory.getLogger("svl-bridge-updater");
    private static final Gson GSON = new Gson();
    public static final String CURRENT_VERSION = "2.3.0";

    private final String masterApiUrl;
    private final String platform;
    private final Path rootDir; // e.g., server root or plugins/mods dir
    private final HttpClient httpClient;

    public BridgeAutoUpdater(String masterApiUrl, String platform, Path rootDir) {
        this.masterApiUrl = (masterApiUrl != null && !masterApiUrl.isBlank()) 
                ? masterApiUrl.trim().replaceAll("/+$", "") 
                : "https://realms.sunveil.net";
        this.platform = platform != null ? platform.toLowerCase().trim() : "paper";
        this.rootDir = rootDir;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.ALWAYS)
                .build();
    }

    /**
     * Checks for updates and automatically downloads and installs new .jar if available.
     */
    public CompletableFuture<Boolean> checkAndApplyUpdateAsync() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String updateApiUrl = masterApiUrl.contains("/api/v1") 
                        ? masterApiUrl.substring(0, masterApiUrl.indexOf("/api/v1")) + "/api/v1/updates/latest"
                        : masterApiUrl + "/api/v1/updates/latest";

                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(updateApiUrl))
                        .timeout(Duration.ofSeconds(15))
                        .header("User-Agent", "SVL-Bridge-AutoUpdater/" + CURRENT_VERSION)
                        .GET()
                        .build();

                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() != 200) {
                    LOGGER.debug("Update server responded with status: {}", response.statusCode());
                    return false;
                }

                JsonObject root = GSON.fromJson(response.body(), JsonObject.class);
                if (root == null || !root.has("bridge")) {
                    return false;
                }

                JsonObject bridge = root.getAsJsonObject("bridge");
                String remoteVersion = bridge.has("version") ? bridge.get("version").getAsString() : "";
                if (remoteVersion.isBlank() || !isVersionNewer(remoteVersion, CURRENT_VERSION)) {
                    LOGGER.info("[SVL-Updater] SVL-Bridge is up-to-date (v{}).", CURRENT_VERSION);
                    return false;
                }

                LOGGER.info("[SVL-Updater] 🚀 New SVL-Bridge version found: v{} (Current: v{}). Starting automatic download...", 
                        remoteVersion, CURRENT_VERSION);

                String downloadUrl = "";
                if (bridge.has("downloads") && bridge.getAsJsonObject("downloads").has(platform)) {
                    downloadUrl = bridge.getAsJsonObject("downloads").get(platform).getAsString();
                } else if (bridge.has("downloadUrl")) {
                    downloadUrl = bridge.get("downloadUrl").getAsString();
                } else {
                    downloadUrl = "https://realms.sunveil.net/downloads/svl-bridge-" + platform + ".jar";
                }

                return downloadAndInstall(downloadUrl, remoteVersion);
            } catch (Exception e) {
                LOGGER.warn("[SVL-Updater] Auto-update check skipped: {}", e.getMessage());
                return false;
            }
        });
    }

    private boolean downloadAndInstall(String downloadUrl, String remoteVersion) {
        try {
            LOGGER.info("[SVL-Updater] Downloading update from: {}", downloadUrl);

            HttpRequest downloadReq = HttpRequest.newBuilder()
                    .uri(URI.create(downloadUrl))
                    .timeout(Duration.ofMinutes(2))
                    .header("User-Agent", "SVL-Bridge-AutoUpdater/" + CURRENT_VERSION)
                    .GET()
                    .build();

            HttpResponse<InputStream> downloadRes = httpClient.send(downloadReq, HttpResponse.BodyHandlers.ofInputStream());
            if (downloadRes.statusCode() != 200) {
                LOGGER.error("[SVL-Updater] Download failed with HTTP status: {}", downloadRes.statusCode());
                return false;
            }

            Path targetJarPath = resolveUpdateTargetFile();
            if (targetJarPath == null) {
                LOGGER.error("[SVL-Updater] Could not resolve target directory for update installation.");
                return false;
            }

            if (targetJarPath.getParent() != null) {
                Files.createDirectories(targetJarPath.getParent());
            }

            try (InputStream in = downloadRes.body()) {
                Files.copy(in, targetJarPath, StandardCopyOption.REPLACE_EXISTING);
            }

            LOGGER.info("[SVL-Updater] =========================================================");
            LOGGER.info("[SVL-Updater] ✅ SVL-Bridge v{} downloaded & installed successfully!", remoteVersion);
            LOGGER.info("[SVL-Updater] 📁 Target: {}", targetJarPath);
            LOGGER.info("[SVL-Updater] 🔄 The update will automatically activate on next server restart.");
            LOGGER.info("[SVL-Updater] =========================================================");
            return true;
        } catch (Exception e) {
            LOGGER.error("[SVL-Updater] Failed to download or install update: {}", e.getMessage(), e);
            return false;
        }
    }

    private Path resolveUpdateTargetFile() {
        if ("paper".equals(platform) || "spigot".equals(platform) || "purpur".equals(platform) || "bukkit".equals(platform)) {
            // Paper/Spigot update folder
            Path pluginsDir = rootDir.resolve("plugins");
            if (!Files.exists(pluginsDir)) {
                pluginsDir = rootDir;
            }
            Path updateDir = pluginsDir.resolve("update");
            return updateDir.resolve("svl-bridge-paper.jar");
        } else {
            // Fabric / Forge / NeoForge mods folder
            Path modsDir = rootDir.resolve("mods");
            if (!Files.exists(modsDir)) {
                modsDir = rootDir;
            }
            return modsDir.resolve("svl-bridge-" + platform + ".jar");
        }
    }

    /**
     * Compares two semantic version strings (e.g. "2.3.0" vs "2.2.0")
     */
    public static boolean isVersionNewer(String remote, String current) {
        if (remote == null || remote.isBlank()) return false;
        if (current == null || current.isBlank()) return true;

        String[] rParts = remote.replace("v", "").replace("V", "").split("-")[0].split("\\.");
        String[] cParts = current.replace("v", "").replace("V", "").split("-")[0].split("\\.");

        int length = Math.max(rParts.length, cParts.length);
        for (int i = 0; i < length; i++) {
            int rNum = 0;
            int cNum = 0;
            if (i < rParts.length) {
                try { rNum = Integer.parseInt(rParts[i]); } catch (Exception ignored) {}
            }
            if (i < cParts.length) {
                try { cNum = Integer.parseInt(cParts[i]); } catch (Exception ignored) {}
            }
            if (rNum > cNum) return true;
            if (rNum < cNum) return false;
        }
        return false;
    }
}
