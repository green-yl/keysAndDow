package com.example.keys.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.Arrays;

@Configuration
public class WebConfig implements WebMvcConfigurer {
    @Value("${app.cors.allowed-origins:*}")
    private String allowedOrigins;
    
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                .allowedOriginPatterns("*")
                .allowedMethods("GET", "POST", "PUT", "DELETE", "HEAD", "OPTIONS", "PATCH")
                .allowedHeaders("*")
                .allowCredentials(false)
                .maxAge(3600);
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        
        // 配置允许的域名
        if ("*".equals(allowedOrigins)) {
            // 允许所有域名，但不能同时设置allowCredentials=true
            config.addAllowedOriginPattern("*");
            config.setAllowCredentials(false);
        } else {
            Arrays.stream(allowedOrigins.split(","))
                    .forEach(o -> config.addAllowedOriginPattern(o.trim()));
            config.setAllowCredentials(true);
        }
        
        // 允许所有HTTP头
        config.addAllowedHeader("*");
        
        // 允许所有HTTP方法
        config.addAllowedMethod("*");
        
        // 设置预检请求的缓存时间
        config.setMaxAge(3600L);
        
        // 暴露一些头给客户端
        config.addExposedHeader("Content-Disposition");
        config.addExposedHeader("Content-Length");
        config.addExposedHeader("Content-Type");
        
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}







