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

import java.util.Objects;

public class ModManifestEntry {
    @SerializedName("projectId")
    private String projectId;

    @SerializedName("fileName")
    private String fileName;

    @SerializedName("sha256")
    private String sha256;

    @SerializedName("downloadUrl")
    private String downloadUrl;

    @SerializedName("tier")
    private String tier = "official";

    public ModManifestEntry() {
    }

    public ModManifestEntry(String projectId, String fileName, String sha256, String downloadUrl) {
        this(projectId, fileName, sha256, downloadUrl, "official");
    }

    public ModManifestEntry(String projectId, String fileName, String sha256, String downloadUrl, String tier) {
        this.projectId = projectId;
        this.fileName = fileName;
        this.sha256 = sha256;
        this.downloadUrl = downloadUrl;
        this.tier = tier != null ? tier : "official";
    }

    public String getProjectId() {
        return projectId;
    }

    public void setProjectId(String projectId) {
        this.projectId = projectId;
    }

    public String getFileName() {
        return fileName;
    }

    public void setFileName(String fileName) {
        this.fileName = fileName;
    }

    public String getSha256() {
        return sha256;
    }

    public void setSha256(String sha256) {
        this.sha256 = sha256;
    }

    public String getDownloadUrl() {
        return downloadUrl;
    }

    public void setDownloadUrl(String downloadUrl) {
        this.downloadUrl = downloadUrl;
    }

    public String getTier() {
        return tier;
    }

    public void setTier(String tier) {
        this.tier = tier;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ModManifestEntry that = (ModManifestEntry) o;
        return Objects.equals(projectId, that.projectId) &&
                Objects.equals(fileName, that.fileName) &&
                Objects.equals(sha256, that.sha256) &&
                Objects.equals(downloadUrl, that.downloadUrl) &&
                Objects.equals(tier, that.tier);
    }

    @Override
    public int hashCode() {
        return Objects.hash(projectId, fileName, sha256, downloadUrl, tier);
    }

    @Override
    public String toString() {
        return "ModManifestEntry{" +
                "projectId='" + projectId + '\'' +
                ", fileName='" + fileName + '\'' +
                ", sha256='" + sha256 + '\'' +
                ", downloadUrl='" + downloadUrl + '\'' +
                ", tier='" + tier + '\'' +
                '}';
    }
}
