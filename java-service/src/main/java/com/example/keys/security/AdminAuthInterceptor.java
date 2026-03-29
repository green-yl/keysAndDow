package com.example.keys.security;

import com.example.keys.web.AdminAuthController;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class AdminAuthInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }

        HttpSession session = request.getSession(false);
        boolean authenticated = session != null
                && Boolean.TRUE.equals(session.getAttribute(AdminAuthController.SESSION_KEY));

        if (authenticated) {
            return true;
        }

        String uri = request.getRequestURI();

        if (uri.endsWith("/admin.html")) {
            response.sendRedirect("/admin-login.html");
            return false;
        }

        response.setStatus(401);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"ok\":false,\"error\":\"未登录\"}");
        return false;
    }
}
