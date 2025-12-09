package com.example.keys.model;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.LocalDateTime;

public class LicenseCode {
    private Long id;
    private String code;
    private Long planId;
    private Integer issueLimit;
    private Integer issueCount;
    
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime expAt;
    
    private String status; // active, frozen, revoked
    private String note;
    
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createdAt;
    
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime updatedAt;

    // Plan info (for joined queries)
    private String planName;
    private Integer planDurationHours;
    private Integer planInitQuota;

    // Constructors
    public LicenseCode() {}

    public LicenseCode(String code, Long planId, Integer issueLimit, LocalDateTime expAt) {
        this.code = code;
        this.planId = planId;
        this.issueLimit = issueLimit;
        this.issueCount = 0;
        this.expAt = expAt;
        this.status = "active";
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

    public Long getPlanId() {
        return planId;
    }

    public void setPlanId(Long planId) {
        this.planId = planId;
    }

    public Integer getIssueLimit() {
        return issueLimit;
    }

    public void setIssueLimit(Integer issueLimit) {
        this.issueLimit = issueLimit;
    }

    public Integer getIssueCount() {
        return issueCount;
    }

    public void setIssueCount(Integer issueCount) {
        this.issueCount = issueCount;
    }

    public LocalDateTime getExpAt() {
        return expAt;
    }

    public void setExpAt(LocalDateTime expAt) {
        this.expAt = expAt;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getNote() {
        return note;
    }

    public void setNote(String note) {
        this.note = note;
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

    public Integer getPlanInitQuota() {
        return planInitQuota;
    }

    public void setPlanInitQuota(Integer planInitQuota) {
        this.planInitQuota = planInitQuota;
    }
}
