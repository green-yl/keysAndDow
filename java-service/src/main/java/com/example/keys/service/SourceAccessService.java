package com.example.keys.service;

import com.example.keys.model.SourcePackage;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Map;

@Service
public class SourceAccessService {

    public String extractInstallCode(HttpServletRequest request, Map<String, Object> body) {
        if (request != null) {
            String header = request.getHeader("X-Install-Code");
            if (StringUtils.hasText(header)) return header.trim();

            String querySnake = request.getParameter("install_code");
            if (StringUtils.hasText(querySnake)) return querySnake.trim();

            String queryCamel = request.getParameter("installCode");
            if (StringUtils.hasText(queryCamel)) return queryCamel.trim();
        }

        if (body != null) {
            Object snake = body.get("install_code");
            if (snake instanceof String value && StringUtils.hasText(value)) return value.trim();

            Object camel = body.get("installCode");
            if (camel instanceof String value && StringUtils.hasText(value)) return value.trim();
        }

        return null;
    }

    public Map<String, Object> validateInstallCode(SourcePackage sourcePackage, String installCode) {
        if (sourcePackage == null || !sourcePackage.isHiddenEnabled()) {
            return Map.of("ok", true);
        }

        String expected = sourcePackage.getInstallCode();
        if (!StringUtils.hasText(expected) || !expected.trim().equals(installCode)) {
            return Map.of(
                    "ok", false,
                    "error", "安装码错误或缺失",
                    "code", 403
            );
        }

        return Map.of("ok", true);
    }

    public boolean isAdminRequest(HttpServletRequest request) {
        if (request == null || request.getSession(false) == null) {
            return false;
        }
        Object authenticated = request.getSession(false).getAttribute("ADMIN_AUTHENTICATED");
        return Boolean.TRUE.equals(authenticated);
    }
}
