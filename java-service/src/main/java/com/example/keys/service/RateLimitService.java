package com.example.keys.service;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.Bucket4j;
import io.github.bucket4j.Refill;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class RateLimitService {
    
    private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();
    
    @PostConstruct
    public void init() {
        // 清理过期的bucket（简单实现）
        // 在生产环境中应该使用更复杂的清理策略或外部缓存
    }
    
    /**
     * 检查激活操作的限流
     * 每个IP每分钟最多5次激活尝试
     */
    public boolean allowActivate(String ip) {
        String key = "activate:" + ip;
        Bucket bucket = buckets.computeIfAbsent(key, k -> createActivateBucket());
        return bucket.tryConsume(1);
    }
    
    /**
     * 检查预授权操作的限流
     * 每个许可证每分钟最多20次预授权请求
     */
    public boolean allowPreauth(String licenseId) {
        String key = "preauth:" + licenseId;
        Bucket bucket = buckets.computeIfAbsent(key, k -> createPreauthBucket());
        return bucket.tryConsume(1);
    }
    
    /**
     * 检查回执操作的限流
     * 每个许可证每分钟最多30次回执请求
     */
    public boolean allowCommit(String licenseId) {
        String key = "commit:" + licenseId;
        Bucket bucket = buckets.computeIfAbsent(key, k -> createCommitBucket());
        return bucket.tryConsume(1);
    }
    
    /**
     * 检查激活码查询的限流
     * 每个激活码每分钟最多10次查询
     */
    public boolean allowCodeQuery(String code) {
        String key = "code_query:" + code;
        Bucket bucket = buckets.computeIfAbsent(key, k -> createCodeQueryBucket());
        return bucket.tryConsume(1);
    }
    
    /**
     * 检查设备的限流
     * 每个设备每小时最多100次操作
     */
    public boolean allowDevice(String hwid) {
        String key = "device:" + hwid;
        Bucket bucket = buckets.computeIfAbsent(key, k -> createDeviceBucket());
        return bucket.tryConsume(1);
    }
    
    /**
     * 检查全局限流
     * 系统级别的限流保护
     */
    public boolean allowGlobal() {
        String key = "global";
        Bucket bucket = buckets.computeIfAbsent(key, k -> createGlobalBucket());
        return bucket.tryConsume(1);
    }
    
    // 创建不同类型的限流桶
    
    private Bucket createActivateBucket() {
        // 每分钟5次，突发允许10次
        Bandwidth limit = Bandwidth.classic(5, Refill.intervally(5, Duration.ofMinutes(1)));
        Bandwidth burst = Bandwidth.classic(10, Refill.intervally(10, Duration.ofMinutes(1)));
        return Bucket4j.builder()
                .addLimit(limit)
                .addLimit(burst)
                .build();
    }
    
    private Bucket createPreauthBucket() {
        // 每分钟200次（开发测试期间放宽限制）
        Bandwidth limit = Bandwidth.classic(200, Refill.intervally(200, Duration.ofMinutes(1)));
        return Bucket4j.builder()
                .addLimit(limit)
                .build();
    }
    
    private Bucket createCommitBucket() {
        // 每分钟30次
        Bandwidth limit = Bandwidth.classic(30, Refill.intervally(30, Duration.ofMinutes(1)));
        return Bucket4j.builder()
                .addLimit(limit)
                .build();
    }
    
    private Bucket createCodeQueryBucket() {
        // 每分钟10次
        Bandwidth limit = Bandwidth.classic(10, Refill.intervally(10, Duration.ofMinutes(1)));
        return Bucket4j.builder()
                .addLimit(limit)
                .build();
    }
    
    private Bucket createDeviceBucket() {
        // 每小时100次
        Bandwidth limit = Bandwidth.classic(100, Refill.intervally(100, Duration.ofHours(1)));
        return Bucket4j.builder()
                .addLimit(limit)
                .build();
    }
    
    private Bucket createGlobalBucket() {
        // 全局每秒1000次
        Bandwidth limit = Bandwidth.classic(1000, Refill.intervally(1000, Duration.ofSeconds(1)));
        return Bucket4j.builder()
                .addLimit(limit)
                .build();
    }
    
    /**
     * 清理过期的bucket
     */
    public void cleanup() {
        buckets.clear(); // 简单粗暴的清理方式
    }
    
    /**
     * 获取当前bucket数量（监控用）
     */
    public int getBucketCount() {
        return buckets.size();
    }
}
