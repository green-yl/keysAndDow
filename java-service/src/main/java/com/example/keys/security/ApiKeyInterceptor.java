package com.example.keys.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.List;

@Component
public class ApiKeyInterceptor implements HandlerInterceptor {
    private final JdbcTemplate jdbc;
    @Value("${app.security.require-api-key:true}")
    private boolean requireApiKey;

    public ApiKeyInterceptor(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if (!requireApiKey) return true;

        // 初次创建密钥放行：当没有任何激活密钥时，允许 POST /api/keys 无需鉴权
        String path = request.getRequestURI();
        String method = request.getMethod();
        if (path.startsWith("/api/keys") && "POST".equalsIgnoreCase(method)) {
            Integer cnt = jdbc.queryForObject("SELECT COUNT(*) FROM api_keys WHERE is_active=1", Integer.class);
            if (cnt == null || cnt == 0) {
                return true;
            }
        }

        // 允许GET列表/下载匿名访问？按需调整。此处要求下载和写操作带密钥；sources列表与statistics可匿名
        boolean needsKey = true;
        if ("GET".equalsIgnoreCase(method) && (path.startsWith("/api/sources") || path.equals("/api/statistics"))) {
            // 只限制下载接口
            if (path.contains("/download")) needsKey = true; else needsKey = false;
        }
        if (!needsKey) return true;

        String apiKey = request.getHeader("X-API-Key");
        if (apiKey == null || apiKey.isBlank()) {
            response.setStatus(401); return false;
        }
        List<Integer> list = jdbc.query("SELECT 1 FROM api_keys WHERE api_key=? AND is_active=1",
                rs -> rs.next() ? List.of(1) : List.of(), apiKey);
        if (list.isEmpty()) { response.setStatus(403); return false; }
        return true;
    }
}


