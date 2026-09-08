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

package net.sunveil.bridge;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import net.sunveil.bridge.config.BridgeConfig;
import net.sunveil.bridge.model.HeartbeatPayload;
import net.sunveil.bridge.model.ModManifestEntry;
import net.sunveil.bridge.network.MasterApiClient;
import net.sunveil.bridge.scanner.ModScanner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

public class CommonBridgeTest {

    @Test
    void testBridgeConfigDefaultAndSaveLoad(@TempDir Path tempDir) {
        BridgeConfig config = BridgeConfig.load(tempDir);
        assertNotNull(config);
        assertEquals("https://realms.sunveil.net/api/v1/heartbeat", config.getMasterApiUrl());
        assertEquals("", config.getMasterApiToken());
        assertEquals("svl_demo_realm", config.getServerKey());
        assertEquals("auto", config.getPublicIp());
        assertEquals(25565, config.getPublicPort());
        assertEquals("Sunveil Modded Server", config.getServerName());
        assertEquals(30, config.getHeartbeatIntervalSeconds());

        BridgeConfig bukkitConfig = BridgeConfig.load(tempDir, "config.json");
        assertNotNull(bukkitConfig);
        bukkitConfig.setServerName("Custom Bukkit Realm");
        bukkitConfig.setMasterApiToken("custom_secret_999");
        bukkitConfig.setPublicPort(25570);
        bukkitConfig.save(tempDir, "config.json");

        BridgeConfig reloaded = BridgeConfig.load(tempDir, "config.json");
        assertEquals("Custom Bukkit Realm", reloaded.getServerName());
        assertEquals("custom_secret_999", reloaded.getMasterApiToken());
        assertEquals(25570, reloaded.getPublicPort());
    }

    @Test
    void testHeartbeatPayloadJsonStructure() {
        HeartbeatPayload.VersionInfo versionInfo = new HeartbeatPayload.VersionInfo("1.21.1", "paper", "1.21.1-R0.1-SNAPSHOT");
        HeartbeatPayload.StatusInfo statusInfo = new HeartbeatPayload.StatusInfo(12, 100, "Sunveil Network Realm");
        ModManifestEntry officialMod = new ModManifestEntry(
                "viaversion",
                "ViaVersion-5.0.0.jar",
                "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                "https://cdn.modrinth.com/data/viaversion/versions/v1/ViaVersion-5.0.0.jar",
                "official"
        );
        ModManifestEntry communityMod = new ModManifestEntry(
                "antigriefzones",
                "AntiGriefZones.jar",
                "112233445566778899aabbccddeeff00112233445566778899aabbccddeeff00",
                "http://localhost:3001/static/mods/112233445566778899aabbccddeeff00.jar",
                "community"
        );

        HeartbeatPayload payload = new HeartbeatPayload(
                "svl_demo_realm",
                "Sunveil Modded Server",
                "127.0.0.1",
                25565,
                versionInfo,
                statusInfo,
                List.of(officialMod, communityMod)
        );

        Gson gson = new Gson();
        String json = gson.toJson(payload);
        JsonObject parsed = JsonParser.parseString(json).getAsJsonObject();

        assertEquals("svl_demo_realm", parsed.get("serverKey").getAsString());
        assertEquals("Sunveil Modded Server", parsed.get("name").getAsString());
        assertEquals("127.0.0.1", parsed.get("ip").getAsString());
        assertEquals(25565, parsed.get("port").getAsInt());

        JsonObject versionObj = parsed.getAsJsonObject("version");
        assertEquals("1.21.1", versionObj.get("minecraft").getAsString());
        assertEquals("paper", versionObj.get("loader").getAsString());
        assertEquals("1.21.1-R0.1-SNAPSHOT", versionObj.get("loaderVersion").getAsString());

        JsonObject statusObj = parsed.getAsJsonObject("status");
        assertEquals(12, statusObj.get("players").getAsInt());
        assertEquals(100, statusObj.get("maxPlayers").getAsInt());
        assertEquals("Sunveil Network Realm", statusObj.get("motd").getAsString());

        assertTrue(parsed.has("mods"));
        assertEquals(2, parsed.getAsJsonArray("mods").size());

        JsonObject mod1 = parsed.getAsJsonArray("mods").get(0).getAsJsonObject();
        assertEquals("viaversion", mod1.get("projectId").getAsString());
        assertEquals("official", mod1.get("tier").getAsString());

        JsonObject mod2 = parsed.getAsJsonArray("mods").get(1).getAsJsonObject();
        assertEquals("antigriefzones", mod2.get("projectId").getAsString());
        assertEquals("community", mod2.get("tier").getAsString());
    }

    @Test
    void testModScannerPlatformAndHybrid(@TempDir Path tempDir) throws IOException {
        Path modsDir = tempDir.resolve("mods");
        Path pluginsDir = tempDir.resolve("plugins");
        Files.createDirectories(modsDir);
        Files.createDirectories(pluginsDir);

        Files.writeString(modsDir.resolve("fabric-api.jar"), "dummy fabric api");
        Files.writeString(modsDir.resolve("svl-bridge-fabric-1.0.0.jar"), "self bridge jar");
        Files.writeString(pluginsDir.resolve("EssentialsX.jar"), "dummy essentials");
        Files.writeString(pluginsDir.resolve("SvlBridge-1.0.0.jar"), "self paper jar");

        ModScanner scanner = new ModScanner();

        List<ModScanner.ScannedMod> hybridScanned = scanner.scanPlatform(tempDir, ModScanner.PlatformType.HYBRID);
        assertEquals(2, hybridScanned.size());

        List<ModScanner.ScannedMod> autoScanned = scanner.scanPlatform(tempDir, ModScanner.PlatformType.AUTO);
        assertEquals(2, autoScanned.size());

        for (ModScanner.ScannedMod mod : autoScanned) {
            assertEquals(64, mod.sha256().length());
            assertEquals(128, mod.sha512().length());
        }
    }

    @Test
    void testMasterApiClientBearerAuth() throws Exception {
        AtomicReference<String> receivedAuthHeader = new AtomicReference<>();

        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/v1/heartbeat", exchange -> {
            receivedAuthHeader.set(exchange.getRequestHeaders().getFirst("Authorization"));

            String auth = exchange.getRequestHeaders().getFirst("Authorization");
            if ("Bearer secret_test_token".equals(auth)) {
                byte[] response = "{\"status\":\"ok\",\"verified\":true}".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, response.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(response);
                }
            } else {
                byte[] response = "{\"error\":\"Unauthorized\"}".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(401, response.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(response);
                }
            }
        });
        server.start();

        int port = server.getAddress().getPort();
        String url = "http://localhost:" + port + "/api/v1/heartbeat";

        try {
            MasterApiClient client = new MasterApiClient();
            HeartbeatPayload payload = new HeartbeatPayload(
                    "test_key", "Test Server", "127.0.0.1", 25565,
                    new HeartbeatPayload.VersionInfo("1.21.1", "paper", "1.21.1-R0.1-SNAPSHOT"),
                    new HeartbeatPayload.StatusInfo(0, 20, "MOTD"),
                    List.of(new ModManifestEntry("test-plugin", "test.jar", "abc", "https://cdn.modrinth.com/test.jar", "official"))
            );

            // With valid token
            boolean success = client.sendHeartbeatAsync(url, "secret_test_token", payload).get();
            assertTrue(success);
            assertEquals("Bearer secret_test_token", receivedAuthHeader.get());

            // With invalid token
            boolean failed = client.sendHeartbeatAsync(url, "wrong_token", payload).get();
            assertFalse(failed);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void testMasterApiClientStorageCheckAndUpload(@TempDir Path tempDir) throws Exception {
        Path dummyJar = tempDir.resolve("AntiGriefZones.jar");
        Files.writeString(dummyJar, "custom jar content");

        AtomicBoolean uploadCalled = new AtomicBoolean(false);
        AtomicReference<String> uploadAuth = new AtomicReference<>();

        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/v1/storage/check/dummyhash", exchange -> {
            byte[] response = "{\"exists\":false}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(response);
            }
        });

        server.createContext("/api/v1/storage/upload", exchange -> {
            uploadAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            uploadCalled.set(true);
            byte[] response = "{\"status\":\"ok\",\"sha256\":\"dummyhash\",\"url\":\"http://localhost/static/mods/dummyhash.jar\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(201, response.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(response);
            }
        });
        server.start();

        int port = server.getAddress().getPort();
        String masterUrl = "http://localhost:" + port + "/api/v1/heartbeat";

        try {
            MasterApiClient client = new MasterApiClient();
            ModScanner.ScannedMod scannedMod = new ModScanner.ScannedMod(dummyJar, "AntiGriefZones.jar", "sha512", "dummyhash");

            String downloadUrl = client.ensureModHostedAsync(masterUrl, "auth_token_xyz", scannedMod).get();
            assertNotNull(downloadUrl);
            assertEquals("http://localhost/static/mods/dummyhash.jar", downloadUrl);
            assertTrue(uploadCalled.get());
            assertEquals("Bearer auth_token_xyz", uploadAuth.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void testHardenedIpDerivationDeterminismAndSafety() {
        String ip1 = net.sunveil.bridge.tunnel.TunnelClient.deriveHardenedIp("user_hwid_987654321");
        String ip2 = net.sunveil.bridge.tunnel.TunnelClient.deriveHardenedIp("user_hwid_987654321");
        String ip3 = net.sunveil.bridge.tunnel.TunnelClient.deriveHardenedIp("user_hwid_123456789");

        assertNotNull(ip1);
        assertNotNull(ip2);
        assertNotNull(ip3);

        // Deterministic: Same HWID/identity yields the exact same hardened IP
        assertEquals(ip1, ip2);

        // Different HWID yields a distinct hardened IP
        assertNotEquals(ip1, ip3);

        // Must start with 127. loopback prefix
        assertTrue(ip1.startsWith("127."));
        assertTrue(ip3.startsWith("127."));

        // Must NEVER be 127.0.0.1 or 127.0.0.0 to prevent server-wide collateral bans
        assertNotEquals("127.0.0.1", ip1);
        assertNotEquals("127.0.0.0", ip1);
        assertNotEquals("127.0.0.1", ip3);
        assertNotEquals("127.0.0.0", ip3);

        // Fallbacks for null or local IPs must also be valid hardened loopbacks and not 127.0.0.1
        String fallback1 = net.sunveil.bridge.tunnel.TunnelClient.deriveHardenedIp("127.0.0.1");
        String fallback2 = net.sunveil.bridge.tunnel.TunnelClient.deriveHardenedIp(null);
        assertTrue(fallback1.startsWith("127."));
        assertNotEquals("127.0.0.1", fallback1);
        assertTrue(fallback2.startsWith("127."));
        assertNotEquals("127.0.0.1", fallback2);
    }

    @Test
    void testHardenedIpSocketBindingIsolation() throws Exception {
        java.net.ServerSocket server = new java.net.ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1"));
        int port = server.getLocalPort();

        AtomicReference<String> remoteAddress = new AtomicReference<>();
        Thread serverThread = new Thread(() -> {
            try {
                java.net.Socket accepted = server.accept();
                remoteAddress.set(accepted.getRemoteSocketAddress().toString());
                accepted.close();
            } catch (Exception ignored) {}
        });
        serverThread.start();

        String hardenedIp = net.sunveil.bridge.tunnel.TunnelClient.deriveHardenedIp("test_player_identity_abc");
        java.net.Socket client = new java.net.Socket();
        client.bind(new InetSocketAddress(java.net.InetAddress.getByName(hardenedIp), 0));
        client.connect(new InetSocketAddress("127.0.0.1", port), 2000);
        client.close();
        serverThread.join(2000);
        server.close();

        assertNotNull(remoteAddress.get());
        assertTrue(remoteAddress.get().contains(hardenedIp), "Server must observe connection directly from hardened IP " + hardenedIp);
        assertFalse(remoteAddress.get().contains("127.0.0.1:"), "Server must NOT observe connection from shared 127.0.0.1");
    }
}
