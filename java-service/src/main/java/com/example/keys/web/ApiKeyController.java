package com.example.keys.web;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/keys")
public class ApiKeyController {
    private final JdbcTemplate jdbc;
    public ApiKeyController(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @GetMapping
    public List<Map<String,Object>> list() {
        return jdbc.queryForList("SELECT id,name,api_key,created_time,is_active FROM api_keys");
    }

    @PostMapping
    public Map<String,Object> create(@RequestParam String name) {
        String id = UUID.randomUUID().toString();
        String key = UUID.randomUUID().toString().replace("-", "");
        jdbc.update("INSERT INTO api_keys (id,name,api_key,is_active) VALUES (?,?,?,1)", id, name, key);
        return Map.of("success", true, "id", id, "apiKey", key);
    }

    @PostMapping("/{id}/disable")
    public Map<String,Object> disable(@PathVariable String id) {
        jdbc.update("UPDATE api_keys SET is_active=0 WHERE id=?", id);
        return Map.of("success", true);
    }
}







