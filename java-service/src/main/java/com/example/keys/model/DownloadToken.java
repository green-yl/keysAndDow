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
}
