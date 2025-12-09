package com.example.keys.model;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.LocalDateTime;

public class License {
    private Long id;
    private String code;
    private String sub; // 客户标识
    private String hwid;
    private String serverIp;
    private LocalDateTime lastServerSwitchAt;
    private Long planId;
    
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime validFrom;
    
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime validTo;
    
    private String kid; // Key ID for signature
    private String licensePayload;
    private String licenseSig;
    private String status; // ok, revoked
    private Integer downloadQuotaTotal;
    private Integer downloadQuotaRemaining;
    
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createdAt;
    
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime updatedAt;

    // Plan info (for joined queries)
    private String planName;
    private Integer planDurationHours;
    private Boolean planAllowGrace;

    // Constructors
    public License() {}

    public License(String code, String sub, String hwid, Long planId, 
                  LocalDateTime validFrom, LocalDateTime validTo, String kid,
                  String licensePayload, String licenseSig, Integer quotaTotal) {
        this.code = code;
        this.sub = sub;
        this.hwid = hwid;
        this.planId = planId;
        this.validFrom = validFrom;
        this.validTo = validTo;
        this.kid = kid;
        this.licensePayload = licensePayload;
        this.licenseSig = licenseSig;
        this.status = "ok";
        this.downloadQuotaTotal = quotaTotal;
        this.downloadQuotaRemaining = quotaTotal;
    }

    // Getters and Setters
    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getSub() {
        return sub;
    }

    public void setSub(String sub) {
        this.sub = sub;
    }

    public String getHwid() {
        return hwid;
    }

    public void setHwid(String hwid) {
        this.hwid = hwid;
    }

    public String getServerIp() {
        return serverIp;
    }

    public void setServerIp(String serverIp) {
        this.serverIp = serverIp;
    }

    public LocalDateTime getLastServerSwitchAt() {
        return lastServerSwitchAt;
    }

    public void setLastServerSwitchAt(LocalDateTime lastServerSwitchAt) {
        this.lastServerSwitchAt = lastServerSwitchAt;
    }

    public Long getPlanId() {
        return planId;
    }

    public void setPlanId(Long planId) {
        this.planId = planId;
    }

    public LocalDateTime getValidFrom() {
        return validFrom;
    }

    public void setValidFrom(LocalDateTime validFrom) {
        this.validFrom = validFrom;
    }

    public LocalDateTime getValidTo() {
        return validTo;
    }

    public void setValidTo(LocalDateTime validTo) {
        this.validTo = validTo;
    }

    public String getKid() {
        return kid;
    }

    public void setKid(String kid) {
        this.kid = kid;
    }

    public String getLicensePayload() {
        return licensePayload;
    }

    public void setLicensePayload(String licensePayload) {
        this.licensePayload = licensePayload;
    }

    public String getLicenseSig() {
        return licenseSig;
    }

    public void setLicenseSig(String licenseSig) {
        this.licenseSig = licenseSig;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Integer getDownloadQuotaTotal() {
        return downloadQuotaTotal;
    }

    public void setDownloadQuotaTotal(Integer downloadQuotaTotal) {
        this.downloadQuotaTotal = downloadQuotaTotal;
    }

    public Integer getDownloadQuotaRemaining() {
        return downloadQuotaRemaining;
    }

    public void setDownloadQuotaRemaining(Integer downloadQuotaRemaining) {
        this.downloadQuotaRemaining = downloadQuotaRemaining;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

    public String getPlanName() {
        return planName;
    }

    public void setPlanName(String planName) {
        this.planName = planName;
    }

    public Integer getPlanDurationHours() {
        return planDurationHours;
    }

    public void setPlanDurationHours(Integer planDurationHours) {
        this.planDurationHours = planDurationHours;
    }

    public Boolean getPlanAllowGrace() {
        return planAllowGrace;
    }

    public void setPlanAllowGrace(Boolean planAllowGrace) {
        this.planAllowGrace = planAllowGrace;
    }
}
