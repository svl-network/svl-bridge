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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class ModScanner {
    private static final Logger LOGGER = LoggerFactory.getLogger("svl-bridge-scanner");
    private static final String SELF_MOD_ID = "svl-bridge";

    public enum PlatformType {
        FABRIC,
        NEOFORGE,
        FORGE,
        PAPER,
        SPIGOT,
        BUKKIT,
        HYBRID,
        AUTO
    }

    public record ScannedMod(Path path, String fileName, String sha512, String sha256, String targetFolder) {
        public ScannedMod(Path path, String fileName, String sha512, String sha256) {
            this(path, fileName, sha512, sha256, "mods");
        }
    }

    /**
     * Scans for mods, plugins, shaderpacks and resourcepacks based on the platform type and server root directory.
     */
    public List<ScannedMod> scanPlatform(Path serverRoot, PlatformType platform) {
        if (serverRoot == null) {
            return Collections.emptyList();
        }

        List<Path> targetDirs = new ArrayList<>();
        Path modsDir = serverRoot.resolve("mods");
        Path pluginsDir = serverRoot.resolve("plugins");
        Path shaderpacksDir = serverRoot.resolve("shaderpacks");
        Path resourcepacksDir = serverRoot.resolve("resourcepacks");

        switch (platform) {
            case FABRIC, NEOFORGE, FORGE -> {
                if (Files.exists(modsDir)) targetDirs.add(modsDir);
                if (Files.exists(pluginsDir)) {
                    LOGGER.info("Hybrid environment detected: adding plugins/ directory to scan.");
                    targetDirs.add(pluginsDir);
                }
            }
            case PAPER, SPIGOT, BUKKIT -> {
                if (Files.exists(modsDir)) {
                    LOGGER.info("Hybrid environment detected: adding mods/ directory to scan.");
                    targetDirs.add(modsDir);
                } else {
                    LOGGER.info("Paper/Spigot/Bukkit server detected: server plugins in plugins/ excluded from client sync.");
                }
            }
            case HYBRID, AUTO -> {
                if (Files.exists(modsDir)) targetDirs.add(modsDir);
                if (Files.exists(pluginsDir)) targetDirs.add(pluginsDir);
            }
        }

        // Always scan shaderpacks and resourcepacks if present on the server
        if (Files.exists(shaderpacksDir)) {
            LOGGER.info("Detected shaderpacks directory: syncing server shaderpacks to client.");
            targetDirs.add(shaderpacksDir);
        }
        if (Files.exists(resourcepacksDir)) {
            LOGGER.info("Detected resourcepacks directory: syncing server resourcepacks to client.");
            targetDirs.add(resourcepacksDir);
        }

        return scanDirectories(targetDirs.toArray(new Path[0]));
    }

    /**
     * Scans the specified directories for jar and zip files and computes SHA-512 and SHA-256 hashes.
     * Enforces path canonicalization and sandbox containment to defend against directory traversal.
     */
    public List<ScannedMod> scanDirectories(Path... dirs) {
        List<ScannedMod> scannedMods = new ArrayList<>();
        Set<String> seenSha256 = new HashSet<>();

        if (dirs == null || dirs.length == 0) {
            return scannedMods;
        }

        for (Path dir : dirs) {
            if (dir == null || !Files.exists(dir) || !Files.isDirectory(dir)) {
                LOGGER.debug("Directory '{}' does not exist or is not a directory. Skipping.", dir);
                continue;
            }

            Path canonicalDir;
            try {
                canonicalDir = dir.toRealPath();
            } catch (IOException e) {
                canonicalDir = dir.toAbsolutePath().normalize();
            }

            String folderName = canonicalDir.getFileName() != null ? canonicalDir.getFileName().toString().toLowerCase() : "mods";
            String targetFolder = switch (folderName) {
                case "shaderpacks" -> "shaderpacks";
                case "resourcepacks" -> "resourcepacks";
                case "config" -> "config";
                default -> "mods";
            };

            LOGGER.info("Scanning directory: {} (targetFolder: {})", canonicalDir, targetFolder);

            try (DirectoryStream<Path> stream = Files.newDirectoryStream(canonicalDir, p -> {
                String name = p.getFileName().toString().toLowerCase();
                return name.endsWith(".jar") || name.endsWith(".zip");
            })) {
                for (Path filePath : stream) {
                    if (Files.isRegularFile(filePath)) {
                        Path realFilePath;
                        try {
                            realFilePath = filePath.toRealPath();
                        } catch (IOException e) {
                            realFilePath = filePath.toAbsolutePath().normalize();
                        }

                        // Sandbox check: Ensure file resides strictly within target directory
                        if (!realFilePath.startsWith(canonicalDir)) {
                            LOGGER.warn("SECURITY WARNING: File '{}' points outside designated folder '{}'. Skipping.",
                                    filePath, canonicalDir);
                            continue;
                        }

                        String fileName = realFilePath.getFileName().toString();

                        if (isSelfMod(fileName)) {
                            LOGGER.debug("Skipping bridge self jar: {}", fileName);
                            continue;
                        }

                        try {
                            Hashes hashes = computeHashes(realFilePath);
                            if (seenSha256.add(hashes.sha256())) {
                                scannedMods.add(new ScannedMod(realFilePath, fileName, hashes.sha512(), hashes.sha256(), targetFolder));
                                LOGGER.debug("Scanned file: {} in {} (SHA-256: {}, SHA-512: {}...)",
                                        fileName, targetFolder, hashes.sha256(), hashes.sha512().substring(0, 16));
                            }
                        } catch (IOException | NoSuchAlgorithmException e) {
                            LOGGER.error("Failed to compute hashes for file '{}'.", realFilePath, e);
                        }
                    }
                }
            } catch (IOException e) {
                LOGGER.error("Error reading directory '{}'.", canonicalDir, e);
            }
        }

        LOGGER.info("Completed scan across {} directory/directories. Found {} unique file(s).",
                dirs.length, scannedMods.size());
        return scannedMods;
    }

    private boolean isSelfMod(String fileName) {
        String lower = fileName.toLowerCase();
        return lower.startsWith(SELF_MOD_ID) || lower.startsWith("svlbridge") || lower.contains("svl-bridge");
    }

    private record Hashes(String sha512, String sha256) {}

    private Hashes computeHashes(Path file) throws IOException, NoSuchAlgorithmException {
        MessageDigest digest512 = MessageDigest.getInstance("SHA-512");
        MessageDigest digest256 = MessageDigest.getInstance("SHA-256");

        try (InputStream is = Files.newInputStream(file)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = is.read(buffer)) != -1) {
                digest512.update(buffer, 0, read);
                digest256.update(buffer, 0, read);
            }
        }

        String sha512Hex = HexFormat.of().formatHex(digest512.digest());
        String sha256Hex = HexFormat.of().formatHex(digest256.digest());

        return new Hashes(sha512Hex, sha256Hex);
    }
}
