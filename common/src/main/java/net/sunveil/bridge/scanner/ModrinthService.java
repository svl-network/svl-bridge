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

package net.sunveil.bridge.scanner;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.sunveil.bridge.model.ModManifestEntry;
import net.sunveil.bridge.network.MasterApiClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.stream.Collectors;

public class ModrinthService {
    private static final Logger LOGGER = LoggerFactory.getLogger("svl-bridge-modrinth");
    private static final String MODRINTH_VERSION_FILES_URL = "https://api.modrinth.com/v2/version_files";
    private static final String USER_AGENT = "SunveilConnect/1.0.0 (admin@sunveil.net)";

    private final HttpClient httpClient;
    private final Gson gson;

    // In-memory cache for resolved mod manifest entries
    private volatile List<ModManifestEntry> cachedManifest = Collections.emptyList();

    public ModrinthService() {
        this.httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_2)
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        this.gson = new Gson();
    }

    public List<ModManifestEntry> getCachedManifest() {
        return cachedManifest;
    }

    /**
     * Resolves scanned mod files against Modrinth API v2 (Tier 1: Official).
     */
    public CompletableFuture<List<ModManifestEntry>> resolveModsAsync(List<ModScanner.ScannedMod> scannedMods) {
        if (scannedMods == null || scannedMods.isEmpty()) {
            LOGGER.info("No mod/plugin files to resolve on Modrinth.");
            this.cachedManifest = Collections.emptyList();
            return CompletableFuture.completedFuture(this.cachedManifest);
        }

        List<String> sha512Hashes = scannedMods.stream()
                .map(ModScanner.ScannedMod::sha512)
                .toList();

        JsonObject requestBodyJson = new JsonObject();
        JsonArray hashesArray = new JsonArray();
        sha512Hashes.forEach(hashesArray::add);
        requestBodyJson.add("hashes", hashesArray);
        requestBodyJson.addProperty("algorithm", "sha512");

        String bodyString = gson.toJson(requestBodyJson);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(MODRINTH_VERSION_FILES_URL))
                .header("Content-Type", "application/json")
                .header("User-Agent", USER_AGENT)
                .timeout(Duration.ofSeconds(15))
                .POST(HttpRequest.BodyPublishers.ofString(bodyString))
                .build();

        LOGGER.info("Querying Modrinth API for {} file hash(es)...", sha512Hashes.size());

        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    if (response.statusCode() != 200) {
                        LOGGER.error("Modrinth API returned error status {}: {}", response.statusCode(), response.body());
                        return buildFallbackManifest(scannedMods);
                    }

                    try {
                        List<ModManifestEntry> entries = parseModrinthResponse(response.body(), scannedMods);
                        this.cachedManifest = Collections.unmodifiableList(entries);
                        LOGGER.info("Successfully resolved {}/{} official item(s) from Modrinth API.",
                                entries.stream().filter(e -> "official".equals(e.getTier()) && e.getDownloadUrl() != null).count(),
                                scannedMods.size());
                        return this.cachedManifest;
                    } catch (Exception e) {
                        LOGGER.error("Failed to parse Modrinth API response.", e);
                        return buildFallbackManifest(scannedMods);
                    }
                })
                .exceptionally(throwable -> {
                    LOGGER.error("HTTP request to Modrinth API failed.", throwable);
                    return buildFallbackManifest(scannedMods);
                });
    }

    /**
     * Resolves mods against Modrinth (Tier 1) and uploads any unindexed mods to Master API storage (Tier 2).
     */
    public CompletableFuture<List<ModManifestEntry>> resolveModsWithFallbackAsync(
            List<ModScanner.ScannedMod> scannedMods,
            MasterApiClient masterClient,
            String masterApiUrl,
            String masterApiToken) {

        Map<String, ModScanner.ScannedMod> scannedMap = scannedMods.stream()
                .collect(Collectors.toMap(ModScanner.ScannedMod::sha256, Function.identity(), (a, b) -> a));

        return resolveModsAsync(scannedMods).thenCompose(initialEntries -> {
            return CompletableFuture.supplyAsync(() -> {
                for (ModManifestEntry entry : initialEntries) {
                    if (entry.getDownloadUrl() == null || entry.getDownloadUrl().isBlank()) {
                        ModScanner.ScannedMod scanned = scannedMap.get(entry.getSha256());
                        if (scanned != null && masterClient != null && masterApiUrl != null) {
                            LOGGER.info("Resolving Tier 2 community storage for unlisted mod: {}", scanned.fileName());
                            try {
                                String selfHostedUrl = masterClient.ensureModHostedAsync(masterApiUrl, masterApiToken, scanned).join();
                                if (selfHostedUrl != null && !selfHostedUrl.isBlank()) {
                                    entry.setDownloadUrl(selfHostedUrl);
                                    entry.setTier("community");
                                }
                                Thread.sleep(60); // Gentle 60ms pacing to avoid bursting Cloudflare/rate-limiters
                            } catch (Exception e) {
                                LOGGER.warn("Could not self-host community mod {}: {}", scanned.fileName(), e.getMessage());
                            }
                        }
                    }
                }
                this.cachedManifest = Collections.unmodifiableList(initialEntries);
                return this.cachedManifest;
            });
        });
    }

    private List<ModManifestEntry> parseModrinthResponse(String responseBody, List<ModScanner.ScannedMod> scannedMods) {
        JsonObject root = JsonParser.parseString(responseBody).getAsJsonObject();
        List<ModManifestEntry> resultList = new ArrayList<>();

        for (ModScanner.ScannedMod scanned : scannedMods) {
            String hash = scanned.sha512();

            if (root.has(hash) && !root.get(hash).isJsonNull()) {
                JsonObject versionObj = root.getAsJsonObject(hash);
                String projectId = versionObj.has("project_id") && !versionObj.get("project_id").isJsonNull()
                        ? versionObj.get("project_id").getAsString()
                        : scanned.fileName().replace(".jar", "");

                String fileName = scanned.fileName();
                String sha256 = scanned.sha256();
                String downloadUrl = null;

                if (versionObj.has("files") && versionObj.get("files").isJsonArray()) {
                    JsonArray files = versionObj.getAsJsonArray("files");
                    for (JsonElement fileElem : files) {
                        if (fileElem.isJsonObject()) {
                            JsonObject fileObj = fileElem.getAsJsonObject();
                            String candidateUrl = fileObj.has("url") && !fileObj.get("url").isJsonNull()
                                    ? fileObj.get("url").getAsString() : null;
                            String candidateFileName = fileObj.has("filename") && !fileObj.get("filename").isJsonNull()
                                    ? fileObj.get("filename").getAsString() : null;

                            String candidateSha256 = null;
                            if (fileObj.has("hashes") && fileObj.get("hashes").isJsonObject()) {
                                JsonObject h = fileObj.getAsJsonObject("hashes");
                                if (h.has("sha256") && !h.get("sha256").isJsonNull()) {
                                    candidateSha256 = h.get("sha256").getAsString();
                                }
                            }

                            if (candidateUrl != null) {
                                downloadUrl = candidateUrl;
                                if (candidateFileName != null) fileName = candidateFileName;
                                if (candidateSha256 != null) sha256 = candidateSha256;
                                break;
                            }
                        }
                    }
                }

                resultList.add(new ModManifestEntry(projectId, fileName, sha256, downloadUrl, "official"));
                LOGGER.debug("Resolved Modrinth item: project='{}', file='{}', url='{}'", projectId, fileName, downloadUrl);
            } else {
                LOGGER.warn("File '{}' (SHA-512: {}) not on Modrinth (marked for Tier 2 fallback).", scanned.fileName(), hash);
                String fallbackId = scanned.fileName().replaceAll("\\.jar$", "");
                resultList.add(new ModManifestEntry(fallbackId, scanned.fileName(), scanned.sha256(), null, "community"));
            }
        }

        return resultList;
    }

    private List<ModManifestEntry> buildFallbackManifest(List<ModScanner.ScannedMod> scannedMods) {
        List<ModManifestEntry> fallback = new ArrayList<>();
        for (ModScanner.ScannedMod mod : scannedMods) {
            String fallbackId = mod.fileName().replaceAll("\\.jar$", "");
            fallback.add(new ModManifestEntry(fallbackId, mod.fileName(), mod.sha256(), null, "community"));
        }
        this.cachedManifest = Collections.unmodifiableList(fallback);
        return this.cachedManifest;
    }
}
