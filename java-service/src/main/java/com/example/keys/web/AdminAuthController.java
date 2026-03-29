package com.example.keys.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/auth/admin")
@CrossOrigin(origins = "*", maxAge = 3600)
public class AdminAuthController {

    @Value("${app.admin.username:suolongshinidie}")
    private String adminUsername;

    @Value("${app.admin.password:suolongshinidie}")
    private String adminPassword;

    public static final String SESSION_KEY = "ADMIN_AUTHENTICATED";

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody Map<String, String> credentials, HttpServletRequest request) {
        String username = credentials.get("username");
        String password = credentials.get("password");

        if (adminUsername.equals(username) && adminPassword.equals(password)) {
            HttpSession session = request.getSession(true);
            session.setAttribute(SESSION_KEY, true);
            session.setMaxInactiveInterval(7200); // 2 hours
            return ResponseEntity.ok(Map.of("ok", true));
        }

        return ResponseEntity.status(401).body(Map.of("ok", false, "error", "账号或密码错误"));
    }

    @PostMapping("/logout")
    public ResponseEntity<?> logout(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        return ResponseEntity.ok(Map.of("ok", true));
    }

    @GetMapping("/check")
    public ResponseEntity<?> check(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        boolean authenticated = session != null && Boolean.TRUE.equals(session.getAttribute(SESSION_KEY));
        return ResponseEntity.ok(Map.of("authenticated", authenticated));
    }
}
