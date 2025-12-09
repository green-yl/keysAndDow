package com.example.keys.model;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.LocalDateTime;

public class DownloadEvent {
    private Long id;
    private Long licenseId;
    private String token;
    private String fileId;
    private Boolean ok;
    private Boolean deducted;
    private Integer delta;
    private Long size;
    private String sha256;
    private String ip;
    private String ua;
    
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createdAt;

    // Constructors
    public DownloadEvent() {}

    public DownloadEvent(Long licenseId, String token, String fileId, Boolean ok, 
                        Boolean deducted, Integer delta, String ip, String ua) {
        this.licenseId = licenseId;
        this.token = token;
        this.fileId = fileId;
        this.ok = ok;
        this.deducted = deducted;
        this.delta = delta;
        this.ip = ip;
        this.ua = ua;
    }

    // Getters and Setters
    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getLicenseId() {
        return licenseId;
    }

    public void setLicenseId(Long licenseId) {
        this.licenseId = licenseId;
    }

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
    }

    public String getFileId() {
        return fileId;
    }

    public void setFileId(String fileId) {
        this.fileId = fileId;
    }

    public Boolean getOk() {
        return ok;
    }

    public void setOk(Boolean ok) {
        this.ok = ok;
    }

    public Boolean getDeducted() {
        return deducted;
    }

    public void setDeducted(Boolean deducted) {
        this.deducted = deducted;
    }

    public Integer getDelta() {
        return delta;
    }

    public void setDelta(Integer delta) {
        this.delta = delta;
    }

    public Long getSize() {
        return size;
    }

    public void setSize(Long size) {
        this.size = size;
    }

    public String getSha256() {
        return sha256;
    }

    public void setSha256(String sha256) {
        this.sha256 = sha256;
    }

    public String getIp() {
        return ip;
    }

    public void setIp(String ip) {
        this.ip = ip;
    }

    public String getUa() {
        return ua;
    }

    public void setUa(String ua) {
        this.ua = ua;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
