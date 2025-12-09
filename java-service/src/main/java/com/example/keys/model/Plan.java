package com.example.keys.model;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.LocalDateTime;

public class Plan {
    private Long id;
    private String name;
    private Integer durationHours;
    private Integer initQuota;
    private Boolean allowGrace;
    private String features;
    
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createdAt;

    // Constructors
    public Plan() {}

    public Plan(String name, Integer durationHours, Integer initQuota) {
        this.name = name;
        this.durationHours = durationHours;
        this.initQuota = initQuota;
        this.allowGrace = false;
    }

    // Getters and Setters
    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Integer getDurationHours() {
        return durationHours;
    }

    public void setDurationHours(Integer durationHours) {
        this.durationHours = durationHours;
    }

    public Integer getInitQuota() {
        return initQuota;
    }

    public void setInitQuota(Integer initQuota) {
        this.initQuota = initQuota;
    }

    public Boolean getAllowGrace() {
        return allowGrace;
    }

    public void setAllowGrace(Boolean allowGrace) {
        this.allowGrace = allowGrace;
    }

    public String getFeatures() {
        return features;
    }

    public void setFeatures(String features) {
        this.features = features;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
