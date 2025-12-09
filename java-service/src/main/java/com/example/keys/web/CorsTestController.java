package com.example.keys.web;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/test")
@CrossOrigin(origins = "*", maxAge = 3600)
public class CorsTestController {
    
    /**
     * CORS测试端点
     */
    @GetMapping("/cors")
    public ResponseEntity<?> testCors() {
        return ResponseEntity.ok(Map.of(
            "ok", true,
            "message", "CORS配置正常",
            "timestamp", System.currentTimeMillis(),
            "server", "KeysAndDwd Java Service"
        ));
    }
    
    /**
     * POST方法的CORS测试
     */
    @PostMapping("/cors")
    public ResponseEntity<?> testCorsPost(@RequestBody(required = false) Map<String, Object> request) {
        return ResponseEntity.ok(Map.of(
            "ok", true,
            "message", "POST CORS配置正常",
            "received", request != null ? request : Map.of(),
            "timestamp", System.currentTimeMillis()
        ));
    }
    
    /**
     * 预检请求测试
     */
    @RequestMapping(value = "/cors", method = RequestMethod.OPTIONS)
    public ResponseEntity<?> handleCorsOptions() {
        return ResponseEntity.ok().build();
    }
}
