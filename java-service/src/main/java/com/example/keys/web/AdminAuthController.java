package com.example.keys.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import com.example.keys.util.IpUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
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
    private static final int MAX_LOGIN_FAILURES = 5;
    private static final long LOGIN_LOCK_MILLIS = 15 * 60 * 1000L;
    private final ConcurrentHashMap<String, LoginFailure> loginFailures = new ConcurrentHashMap<>();

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody Map<String, String> credentials, HttpServletRequest request) {
        String username = credentials.get("username");
        String password = credentials.get("password");
        String failureKey = loginFailureKey(request, username);

        if (loginFailures.size() > 1000) {
            cleanupExpiredFailures();
        }

        LoginFailure failure = loginFailures.get(failureKey);
        if (failure != null && failure.isLocked()) {
            return ResponseEntity.status(429).body(Map.of("ok", false, "error", "登录失败次数过多，请稍后再试"));
        }

        if (safeEquals(adminUsername, username) && safeEquals(adminPassword, password)) {
            loginFailures.remove(failureKey);
            HttpSession session = request.getSession(true);
            request.changeSessionId();
            session.setAttribute(SESSION_KEY, true);
            session.setMaxInactiveInterval(7200); // 2 hours
            return ResponseEntity.ok(Map.of("ok", true));
        }

        recordLoginFailure(failureKey);
        return ResponseEntity.status(401).body(Map.of("ok", false, "error", "账号或密码错误"));
    }

    private String loginFailureKey(HttpServletRequest request, String username) {
        String normalizedUser = username == null ? "" : username.trim().toLowerCase(Locale.ROOT);
        return IpUtils.getClientIpAddress(request) + ":" + normalizedUser;
    }

    private void recordLoginFailure(String failureKey) {
        loginFailures.compute(failureKey, (key, old) -> {
            long now = System.currentTimeMillis();
            LoginFailure failure = old == null || old.isExpired(now) ? new LoginFailure() : old;
            failure.count++;
            failure.lastFailureAt = now;
            if (failure.count >= MAX_LOGIN_FAILURES) {
                failure.lockedUntil = now + LOGIN_LOCK_MILLIS;
            }
            return failure;
        });
    }

    private void cleanupExpiredFailures() {
        long now = System.currentTimeMillis();
        loginFailures.entrySet().removeIf(e -> e.getValue().isExpired(now));
    }

    private boolean safeEquals(String expected, String actual) {
        if (actual == null) {
            return false;
        }
        byte[] left = String.valueOf(expected).getBytes(StandardCharsets.UTF_8);
        byte[] right = actual.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(left, right);
    }

    private static class LoginFailure {
        int count;
        long lastFailureAt;
        long lockedUntil;

        boolean isLocked() {
            return lockedUntil > System.currentTimeMillis();
        }

        boolean isExpired(long now) {
            return lockedUntil <= now && now - lastFailureAt > LOGIN_LOCK_MILLIS;
        }
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
