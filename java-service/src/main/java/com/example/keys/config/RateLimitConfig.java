package com.example.keys.config;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * API 请求限流配置
 * 防止单个 IP 发送过多请求导致服务器过载
 */
@Slf4j
@Configuration
public class RateLimitConfig {
    
    // 限流配置
    private static final int MAX_REQUESTS_PER_MINUTE = 120; // 每分钟最大请求数
    private static final int MAX_REQUESTS_PER_SECOND = 10;  // 每秒最大请求数
    
    // IP 请求计数器
    private final Map<String, RateLimitEntry> ipRateLimits = new ConcurrentHashMap<>();
    
    @Bean
    public FilterRegistrationBean<RateLimitFilter> rateLimitFilter() {
        FilterRegistrationBean<RateLimitFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new RateLimitFilter());
        registration.addUrlPatterns("/api/*");
        registration.setOrder(1);
        return registration;
    }
    
    /**
     * 限流过滤器
     */
    public class RateLimitFilter implements Filter {
        
        @Override
        public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
                throws IOException, ServletException {
            
            HttpServletRequest httpRequest = (HttpServletRequest) request;
            HttpServletResponse httpResponse = (HttpServletResponse) response;
            
            String clientIp = getClientIp(httpRequest);
            String path = httpRequest.getRequestURI();
            
            // 清理过期的限流记录（每100个请求清理一次）
            if (ipRateLimits.size() > 100) {
                cleanupExpiredEntries();
            }
            
            // 检查限流
            RateLimitEntry entry = ipRateLimits.computeIfAbsent(clientIp, k -> new RateLimitEntry());
            
            if (!entry.allowRequest()) {
                log.warn("IP {} 被限流，请求路径: {}", clientIp, path);
                httpResponse.setStatus(429);
                httpResponse.setContentType("application/json;charset=UTF-8");
                httpResponse.getWriter().write("{\"success\":false,\"error\":\"请求过于频繁，请稍后再试\",\"code\":429}");
                return;
            }
            
            // 继续处理请求
            chain.doFilter(request, response);
        }
        
        private String getClientIp(HttpServletRequest request) {
            String ip = request.getHeader("X-Forwarded-For");
            if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
                ip = request.getHeader("X-Real-IP");
            }
            if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
                ip = request.getHeader("Proxy-Client-IP");
            }
            if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
                ip = request.getRemoteAddr();
            }
            // 如果有多个代理，取第一个
            if (ip != null && ip.contains(",")) {
                ip = ip.split(",")[0].trim();
            }
            return ip != null ? ip : "unknown";
        }
        
        private void cleanupExpiredEntries() {
            Instant now = Instant.now();
            ipRateLimits.entrySet().removeIf(e -> e.getValue().isExpired(now));
        }
    }
    
    /**
     * 限流记录条目
     */
    private static class RateLimitEntry {
        private final AtomicInteger secondCount = new AtomicInteger(0);
        private final AtomicInteger minuteCount = new AtomicInteger(0);
        private volatile long currentSecond;
        private volatile long currentMinute;
        private volatile long lastAccess;
        
        public RateLimitEntry() {
            long now = System.currentTimeMillis();
            this.currentSecond = now / 1000;
            this.currentMinute = now / 60000;
            this.lastAccess = now;
        }
        
        public synchronized boolean allowRequest() {
            long now = System.currentTimeMillis();
            long thisSecond = now / 1000;
            long thisMinute = now / 60000;
            lastAccess = now;
            
            // 重置秒计数器
            if (thisSecond != currentSecond) {
                currentSecond = thisSecond;
                secondCount.set(0);
            }
            
            // 重置分钟计数器
            if (thisMinute != currentMinute) {
                currentMinute = thisMinute;
                minuteCount.set(0);
            }
            
            // 检查限制
            if (secondCount.get() >= MAX_REQUESTS_PER_SECOND) {
                return false;
            }
            if (minuteCount.get() >= MAX_REQUESTS_PER_MINUTE) {
                return false;
            }
            
            // 增加计数
            secondCount.incrementAndGet();
            minuteCount.incrementAndGet();
            return true;
        }
        
        public boolean isExpired(Instant now) {
            // 5分钟没有访问则过期
            return now.toEpochMilli() - lastAccess > 300000;
        }
    }
}

