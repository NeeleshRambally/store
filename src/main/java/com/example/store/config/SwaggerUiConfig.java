package com.example.store.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

// Serves Swagger UI and the checked-in OpenAPI.yaml on the API port unless SWAGGER_UI_ENABLED is false (ADR 0011).
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "store.swagger-ui.enabled", havingValue = "true", matchIfMissing = true)
public class SwaggerUiConfig implements WebMvcConfigurer {

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/swagger-ui.html", "/swagger-initializer.js", "/openapi.yaml")
                .addResourceLocations("classpath:/swagger-ui/");
    }
}
