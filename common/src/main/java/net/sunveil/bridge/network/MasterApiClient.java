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

package net.sunveil.bridge.network;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.sunveil.bridge.model.HeartbeatPayload;
import net.sunveil.bridge.scanner.ModScanner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

public class MasterApiClient {
    private static final Logger LOGGER = LoggerFactory.getLogger("svl-bridge-network");
    private static final String USER_AGENT = "SunveilBridge/1.0.0 (admin@sunveil.net)";

    private final HttpClient httpClient;
    private final Gson gson;

    public MasterApiClient() {
        this.httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_2)
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        this.gson = new GsonBuilder().create();
    }

    public String extractBaseUrl(String apiUrl) {
        if (apiUrl == null || apiUrl.isBlank()) return "http://localhost:3001";
        try {
            URI uri = URI.create(apiUrl);
            String scheme = uri.getScheme() != null ? uri.getScheme() : "http";
            String host = uri.getHost() != null ? uri.getHost() : "localhost";
            int port = uri.getPort();
            return port != -1 ? scheme + "://" + host + ":" + port : scheme + "://" + host;
        } catch (Exception e) {
            return "http://localhost:3001";
        }
    }

    /**
     * Checks if a mod file with the given SHA-256 already exists in Master API storage.
     */
    public CompletableFuture<String> checkStorageAsync(String masterApiUrl, String sha256) {
        String baseUrl = extractBaseUrl(masterApiUrl);
        String checkUrl = baseUrl + "/api/v1/storage/check/" + sha256.toLowerCase();

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(checkUrl))
                .header("User-Agent", USER_AGENT)
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();

        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    if (response.statusCode() == 200) {
                        JsonObject obj = JsonParser.parseString(response.body()).getAsJsonObject();
                        if (obj.has("exists") && obj.get("exists").getAsBoolean()) {
                            return obj.has("url") && !obj.get("url").isJsonNull()
                                    ? obj.get("url").getAsString() : null;
                        }
                    }
                    return null;
                })
                .exceptionally(throwable -> {
                    LOGGER.debug("Storage check request failed for hash {}: {}", sha256, throwable.getMessage());
                    return null;
                });
    }

    /**
     * Uploads a local mod/plugin jar to the Master API storage endpoint with Bearer authentication.
     */
    public CompletableFuture<String> uploadModAsync(String masterApiUrl, String apiToken, Path jarPath) {
        if (jarPath == null || !Files.exists(jarPath)) {
            return CompletableFuture.completedFuture(null);
        }

        String baseUrl = extractBaseUrl(masterApiUrl);
        String uploadUrl = baseUrl + "/api/v1/storage/upload";

        try {
            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(uploadUrl))
                    .header("Content-Type", "application/octet-stream")
                    .header("User-Agent", USER_AGENT)
                    .timeout(Duration.ofSeconds(30))
                    .POST(HttpRequest.BodyPublishers.ofFile(jarPath));

            if (apiToken != null && !apiToken.isBlank()) {
                requestBuilder.header("Authorization", "Bearer " + apiToken.trim());
            }

            HttpRequest request = requestBuilder.build();
            LOGGER.info("Uploading unindexed mod '{}' to Master API storage...", jarPath.getFileName());

            return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                    .thenApply(response -> {
                        if (response.statusCode() == 200 || response.statusCode() == 201) {
                            JsonObject obj = JsonParser.parseString(response.body()).getAsJsonObject();
                            if (obj.has("url") && !obj.get("url").isJsonNull()) {
                                String downloadUrl = obj.get("url").getAsString();
                                LOGGER.info("Successfully uploaded '{}'. Hosted at: {}", jarPath.getFileName(), downloadUrl);
                                return downloadUrl;
                            }
                        }
                        LOGGER.warn("Upload failed for '{}': HTTP {} - {}", jarPath.getFileName(), response.statusCode(), response.body());
                        return null;
                    })
                    .exceptionally(throwable -> {
                        LOGGER.error("Failed to upload '{}': {}", jarPath.getFileName(), throwable.getMessage());
                        return null;
                    });
        } catch (Exception e) {
            LOGGER.error("Error creating upload request for '{}'.", jarPath, e);
            return CompletableFuture.completedFuture(null);
        }
    }

    /**
     * Ensures an unindexed mod is hosted on the Master API by checking existence and uploading if needed.
     */
    public CompletableFuture<String> ensureModHostedAsync(String masterApiUrl, String apiToken, ModScanner.ScannedMod scannedMod) {
        return checkStorageAsync(masterApiUrl, scannedMod.sha256()).thenCompose(existingUrl -> {
            if (existingUrl != null && !existingUrl.isBlank()) {
                LOGGER.debug("Mod '{}' already cached on Master API storage: {}", scannedMod.fileName(), existingUrl);
                return CompletableFuture.completedFuture(existingUrl);
            }
            return uploadModAsync(masterApiUrl, apiToken, scannedMod.path());
        });
    }

    /**
     * Sends a heartbeat payload asynchronously to the Sunveil Master API with Bearer authentication.
     */
    public CompletableFuture<Boolean> sendHeartbeatAsync(String apiUrl, String apiToken, HeartbeatPayload payload) {
        if (apiUrl == null || apiUrl.isBlank()) {
            LOGGER.warn("Master API URL is not configured. Skipping heartbeat.");
            return CompletableFuture.completedFuture(false);
        }

        try {
            String jsonBody = gson.toJson(payload);

            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(apiUrl))
                    .header("Content-Type", "application/json")
                    .header("User-Agent", USER_AGENT)
                    .timeout(Duration.ofSeconds(15))
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody));

            if (apiToken != null && !apiToken.isBlank()) {
                requestBuilder.header("Authorization", "Bearer " + apiToken.trim());
            }

            HttpRequest request = requestBuilder.build();
            LOGGER.info("[SVL-Bridge] Sending heartbeat to '{}' ({} mods registered)...", apiUrl, payload.getMods().size());

            return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                    .thenApply(response -> {
                        int code = response.statusCode();
                        if (code >= 200 && code < 300) {
                            LOGGER.info("[SVL-Bridge] Heartbeat successfully accepted by Master API (HTTP {}). Server is ONLINE!", code);
                            return true;
                        } else if (code == 401 || code == 403) {
                            LOGGER.error("[SVL-Bridge] Master API rejected heartbeat (HTTP {}). Check masterApiToken in config: {}", code, response.body());
                            return false;
                        } else {
                            LOGGER.warn("[SVL-Bridge] Master API returned non-OK status: HTTP {} - {}", code, response.body());
                            return false;
                        }
                    })
                    .exceptionally(throwable -> {
                        LOGGER.warn("Could not connect to Master API at '{}': {}", apiUrl, throwable.getMessage());
                        return false;
                    });
        } catch (IllegalArgumentException e) {
            LOGGER.error("Invalid Master API URL provided: '{}'", apiUrl, e);
            return CompletableFuture.completedFuture(false);
        } catch (Exception e) {
            LOGGER.error("Unexpected error preparing heartbeat request.", e);
            return CompletableFuture.completedFuture(false);
        }
    }
}
