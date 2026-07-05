package com.example.keys.service;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.Bucket4j;
import io.github.bucket4j.Refill;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class RateLimitService {

    private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();

    @Value("${app.ratelimit.activate.ip-per-minute:5}")
    private int activateIpPerMinute;

    @Value("${app.ratelimit.activate.code-per-hour:8}")
    private int activateCodePerHour;

    @Value("${app.ratelimit.device.per-hour:120}")
    private int devicePerHour;

    @Value("${app.ratelimit.status.hwid-per-minute:60}")
    private int statusHwidPerMinute;

    @Value("${app.ratelimit.status.ip-per-minute:120}")
    private int statusIpPerMinute;

    @Value("${app.ratelimit.sources.hwid-per-minute:80}")
    private int sourcesHwidPerMinute;

    @Value("${app.ratelimit.sources.ip-per-minute:160}")
    private int sourcesIpPerMinute;

    @Value("${app.ratelimit.preauth.license-per-minute:60}")
    private int preauthLicensePerMinute;

    @Value("${app.ratelimit.preauth.ip-per-minute:120}")
    private int preauthIpPerMinute;

    @Value("${app.ratelimit.commit.license-per-minute:60}")
    private int commitLicensePerMinute;

    @Value("${app.ratelimit.commit.token-per-minute:12}")
    private int commitTokenPerMinute;

    @Value("${app.ratelimit.download.token-per-minute:20}")
    private int downloadTokenPerMinute;

    @Value("${app.ratelimit.global.per-second:1000}")
    private int globalPerSecond;

    public boolean allowActivate(String ip) {
        return allow("activate:ip", ip, activateIpPerMinute, Duration.ofMinutes(1));
    }

    public boolean allowPreauth(String licenseId) {
        return allow("preauth:license", licenseId, preauthLicensePerMinute, Duration.ofMinutes(1));
    }

    public boolean allowPreauthRequest(String ip, String hwid) {
        return allow("preauth:ip", ip, preauthIpPerMinute, Duration.ofMinutes(1))
                && allowDevice(hwid);
    }

    public boolean allowCommit(String licenseId) {
        return allow("commit:license", licenseId, commitLicensePerMinute, Duration.ofMinutes(1));
    }

    public boolean allowCommitRequest(String ip, String token) {
        return allow("commit:ip", ip, preauthIpPerMinute, Duration.ofMinutes(1))
                && allow("commit:token", token, commitTokenPerMinute, Duration.ofMinutes(1));
    }

    public boolean allowCodeQuery(String code) {
        return allow("activate:code", code, activateCodePerHour, Duration.ofHours(1));
    }

    public boolean allowDevice(String hwid) {
        return allow("device", hwid, devicePerHour, Duration.ofHours(1));
    }

    public boolean allowStatus(String ip, String hwid) {
        return allow("status:ip", ip, statusIpPerMinute, Duration.ofMinutes(1))
                && allow("status:hwid", hwid, statusHwidPerMinute, Duration.ofMinutes(1));
    }

    public boolean allowSourceList(String ip, String hwid) {
        return allow("sources:ip", ip, sourcesIpPerMinute, Duration.ofMinutes(1))
                && allow("sources:hwid", hwid, sourcesHwidPerMinute, Duration.ofMinutes(1));
    }

    public boolean allowDownloadToken(String ip, String token) {
        return allow("download:ip", ip, preauthIpPerMinute, Duration.ofMinutes(1))
                && allow("download:token", token, downloadTokenPerMinute, Duration.ofMinutes(1));
    }

    public boolean allowGlobal() {
        return allow("global", "all", globalPerSecond, Duration.ofSeconds(1));
    }

    private boolean allow(String scope, String rawKey, int capacity, Duration refillPeriod) {
        String key = StringUtils.hasText(rawKey) ? rawKey.trim() : "unknown";
        int safeCapacity = Math.max(1, capacity);
        String bucketKey = scope + ':' + key;
        Bucket bucket = buckets.computeIfAbsent(bucketKey, k -> createBucket(safeCapacity, refillPeriod));
        return bucket.tryConsume(1);
    }

    private Bucket createBucket(int capacity, Duration refillPeriod) {
        Bandwidth limit = Bandwidth.classic(capacity, Refill.intervally(capacity, refillPeriod));
        return Bucket4j.builder().addLimit(limit).build();
    }

    public void cleanup() {
        buckets.clear();
    }

    public int getBucketCount() {
        return buckets.size();
    }
}
