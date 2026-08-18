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

package net.sunveil.bridge.model;

import com.google.gson.annotations.SerializedName;

import java.util.ArrayList;
import java.util.List;

public class HeartbeatPayload {
    @SerializedName("serverKey")
    private String serverKey;

    @SerializedName("name")
    private String name;

    @SerializedName("ip")
    private String ip;

    @SerializedName("port")
    private int port;

    @SerializedName("version")
    private VersionInfo version;

    @SerializedName("status")
    private StatusInfo status;

    @SerializedName("mods")
    private List<ModManifestEntry> mods = new ArrayList<>();

    public HeartbeatPayload() {
    }

    public HeartbeatPayload(String serverKey, String name, String ip, int port,
                            VersionInfo version, StatusInfo status, List<ModManifestEntry> mods) {
        this.serverKey = serverKey;
        this.name = name;
        this.ip = ip;
        this.port = port;
        this.version = version;
        this.status = status;
        this.mods = mods != null ? mods : new ArrayList<>();
    }

    public String getServerKey() {
        return serverKey;
    }

    public void setServerKey(String serverKey) {
        this.serverKey = serverKey;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getIp() {
        return ip;
    }

    public void setIp(String ip) {
        this.ip = ip;
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public VersionInfo getVersion() {
        return version;
    }

    public void setVersion(VersionInfo version) {
        this.version = version;
    }

    public StatusInfo getStatus() {
        return status;
    }

    public void setStatus(StatusInfo status) {
        this.status = status;
    }

    public List<ModManifestEntry> getMods() {
        return mods;
    }

    public void setMods(List<ModManifestEntry> mods) {
        this.mods = mods != null ? mods : new ArrayList<>();
    }

    public static class VersionInfo {
        @SerializedName("minecraft")
        private String minecraft;

        @SerializedName("loader")
        private String loader;

        @SerializedName("loaderVersion")
        private String loaderVersion;

        public VersionInfo() {
        }

        public VersionInfo(String minecraft, String loader, String loaderVersion) {
            this.minecraft = minecraft;
            this.loader = loader;
            this.loaderVersion = loaderVersion;
        }

        public String getMinecraft() {
            return minecraft;
        }

        public void setMinecraft(String minecraft) {
            this.minecraft = minecraft;
        }

        public String getLoader() {
            return loader;
        }

        public void setLoader(String loader) {
            this.loader = loader;
        }

        public String getLoaderVersion() {
            return loaderVersion;
        }

        public void setLoaderVersion(String loaderVersion) {
            this.loaderVersion = loaderVersion;
        }
    }

    public static class StatusInfo {
        @SerializedName("players")
        private int players;

        @SerializedName("maxPlayers")
        private int maxPlayers;

        @SerializedName("motd")
        private String motd;

        public StatusInfo() {
        }

        public StatusInfo(int players, int maxPlayers, String motd) {
            this.players = players;
            this.maxPlayers = maxPlayers;
            this.motd = motd;
        }

        public int getPlayers() {
            return players;
        }

        public void setPlayers(int players) {
            this.players = players;
        }

        public int getMaxPlayers() {
            return maxPlayers;
        }

        public void setMaxPlayers(int maxPlayers) {
            this.maxPlayers = maxPlayers;
        }

        public String getMotd() {
            return motd;
        }

        public void setMotd(String motd) {
            this.motd = motd;
        }
    }
}
