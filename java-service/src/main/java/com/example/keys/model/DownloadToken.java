package com.example.keys.model;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.LocalDateTime;

public class DownloadToken {
    private Long id;
    private String token;
    private Long licenseId;
    private String fileId;
    private String licenseCode;
    private String hwid;
    private String sourceId;
    
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime expireAt;
    
    private Boolean used;
    
    // 是否为更新请求（更新请求不扣除配额）
    private Boolean isUpdate;
    
    // 更新时的源版本号
    private String fromVersion;
    
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createdAt;

    // Constructors
    public DownloadToken() {}

    public DownloadToken(String token, Long licenseId, String fileId, LocalDateTime expireAt) {
        this.token = token;
        this.licenseId = licenseId;
        this.fileId = fileId;
        this.expireAt = expireAt;
        this.used = false;
        this.isUpdate = false;
    }
    
    public DownloadToken(String token, Long licenseId, String fileId, LocalDateTime expireAt, boolean isUpdate, String fromVersion) {
        this.token = token;
        this.licenseId = licenseId;
        this.fileId = fileId;
        this.expireAt = expireAt;
        this.used = false;
        this.isUpdate = isUpdate;
        this.fromVersion = fromVersion;
    }

    public DownloadToken(String token, Long licenseId, String fileId, LocalDateTime expireAt,
                         boolean isUpdate, String fromVersion, String licenseCode, String hwid, String sourceId) {
        this(token, licenseId, fileId, expireAt, isUpdate, fromVersion);
        this.licenseCode = licenseCode;
        this.hwid = hwid;
        this.sourceId = sourceId;
    }

    // Getters and Setters
    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
    }

    public Long getLicenseId() {
        return licenseId;
    }

    public void setLicenseId(Long licenseId) {
        this.licenseId = licenseId;
    }

    public String getFileId() {
        return fileId;
    }

    public void setFileId(String fileId) {
        this.fileId = fileId;
    }

    public String getLicenseCode() { return licenseCode; }

    public void setLicenseCode(String licenseCode) { this.licenseCode = licenseCode; }

    public String getHwid() { return hwid; }

    public void setHwid(String hwid) { this.hwid = hwid; }

    public String getSourceId() { return sourceId; }

    public void setSourceId(String sourceId) { this.sourceId = sourceId; }

    public LocalDateTime getExpireAt() {
        return expireAt;
    }

    public void setExpireAt(LocalDateTime expireAt) {
        this.expireAt = expireAt;
    }

    public Boolean getUsed() {
        return used;
    }

    public void setUsed(Boolean used) {
        this.used = used;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
    
    public Boolean getIsUpdate() {
        return isUpdate;
    }

    public void setIsUpdate(Boolean isUpdate) {
        this.isUpdate = isUpdate;
    }

    public String getFromVersion() {
        return fromVersion;
    }

    public void setFromVersion(String fromVersion) {
        this.fromVersion = fromVersion;
    }
}
