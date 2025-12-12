package com.example.keys.model;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.LocalDateTime;

public class DownloadToken {
    private Long id;
    private String token;
    private Long licenseId;
    private String fileId;
    
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
