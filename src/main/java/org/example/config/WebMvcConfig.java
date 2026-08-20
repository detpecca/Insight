package org.example.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web MVC 配置
 * 配置跨域（来源可配置，默认仅限本机开发）和静态资源
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    /**
     * 允许的来源列表（逗号分隔）。
     * 默认仅本机开发地址；生产部署请通过 cors.allowed-origins 配置真实前端域名，
     * 不要使用 "*"。
     */
    @Value("${cors.allowed-origins:http://localhost:9900,http://127.0.0.1:9900}")
    private String[] allowedOrigins;

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins(allowedOrigins)
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .maxAge(3600);
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // 配置静态资源映射
        registry.addResourceHandler("/**")
                .addResourceLocations("classpath:/static/");
    }
}
