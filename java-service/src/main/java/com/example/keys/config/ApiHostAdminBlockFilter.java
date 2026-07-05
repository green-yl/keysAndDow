package com.example.keys.config;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Locale;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 5)
public class ApiHostAdminBlockFilter implements Filter {

    @Value("${app.admin.api-host:auth-api.coolpay.top}")
    private String apiHost;

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest req = (HttpServletRequest) request;
        HttpServletResponse res = (HttpServletResponse) response;

        if (isApiHost(req.getHeader("Host")) && isAdminPath(req.getRequestURI())) {
            res.setStatus(HttpServletResponse.SC_NOT_FOUND);
            return;
        }

        chain.doFilter(request, response);
    }

    private boolean isApiHost(String hostHeader) {
        if (hostHeader == null || apiHost == null || apiHost.isBlank()) {
            return false;
        }
        String host = hostHeader.toLowerCase(Locale.ROOT);
        int colon = host.indexOf(':');
        if (colon > 0) {
            host = host.substring(0, colon);
        }
        return host.equals(apiHost.toLowerCase(Locale.ROOT));
    }

    private boolean isAdminPath(String path) {
        if (path == null) {
            return false;
        }
        String p = path.toLowerCase(Locale.ROOT);
        return p.equals("/admin-login.html")
                || p.equals("/admin.html")
                || p.startsWith("/admin/")
                || p.startsWith("/assets/admin");
    }
}
