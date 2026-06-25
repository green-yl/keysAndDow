package com.example.keys.util;

import org.springframework.http.ResponseEntity;

import java.util.Map;

public final class ResponseHelper {
    private ResponseHelper() {}

    public static ResponseEntity<?> fromServiceResult(Map<String, Object> result) {
        Integer code = (Integer) result.get("code");
        if (code != null && code != 200) {
            return ResponseEntity.status(code).body(result);
        }
        return ResponseEntity.ok(result);
    }
}
