package com.example.keys.web;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class HealthController {

    @Autowired
    private JdbcTemplate jdbc;

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        Map<String, Object> result = new HashMap<>();
        result.put("service", "keysAndDwd");
        result.put("timestamp", System.currentTimeMillis());

        try {
            jdbc.queryForObject("SELECT 1", Integer.class);
            result.put("status", "UP");
            result.put("database", "OK");
        } catch (Exception e) {
            result.put("status", "DEGRADED");
            result.put("database", "UNREACHABLE");
            return ResponseEntity.status(503).body(result);
        }

        Runtime rt = Runtime.getRuntime();
        result.put("memory", Map.of(
                "total_mb", rt.totalMemory() / (1024 * 1024),
                "free_mb", rt.freeMemory() / (1024 * 1024),
                "used_mb", (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024)
        ));

        return ResponseEntity.ok(result);
    }
}

