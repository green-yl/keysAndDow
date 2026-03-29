package com.example.keys.config;

import com.example.keys.security.AdminAuthInterceptor;
import com.example.keys.security.ApiKeyInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class InterceptorConfig implements WebMvcConfigurer {
    private final ApiKeyInterceptor apiKeyInterceptor;
    private final AdminAuthInterceptor adminAuthInterceptor;

    public InterceptorConfig(ApiKeyInterceptor apiKeyInterceptor, AdminAuthInterceptor adminAuthInterceptor) {
        this.apiKeyInterceptor = apiKeyInterceptor;
        this.adminAuthInterceptor = adminAuthInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(adminAuthInterceptor)
                .addPathPatterns("/admin.html", "/api/admin/**")
                .excludePathPatterns("/api/auth/admin/**");

        registry.addInterceptor(apiKeyInterceptor)
                .addPathPatterns("/api/**")
                .excludePathPatterns("/api/auth/admin/**", "/api/admin/**");
    }
}







