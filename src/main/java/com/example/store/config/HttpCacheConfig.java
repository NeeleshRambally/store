package com.example.store.config;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.CacheControl;
import org.springframework.web.filter.ShallowEtagHeaderFilter;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.mvc.WebContentInterceptor;

// Weak ETags so Tomcat still compresses the body; no-cache so caches revalidate with If-None-Match.
@Configuration
public class HttpCacheConfig implements WebMvcConfigurer {

    private static final String[] API_PATTERNS = {
        "/order", "/order/*", "/customer", "/customer/*", "/products", "/products/*"
    };

    @Bean
    FilterRegistrationBean<ShallowEtagHeaderFilter> etagFilter() {
        ShallowEtagHeaderFilter filter = new ShallowEtagHeaderFilter();
        filter.setWriteWeakETag(true);
        FilterRegistrationBean<ShallowEtagHeaderFilter> registration = new FilterRegistrationBean<>(filter);
        registration.addUrlPatterns(API_PATTERNS);
        return registration;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        WebContentInterceptor interceptor = new WebContentInterceptor();
        interceptor.addCacheMapping(CacheControl.noCache(), API_PATTERNS);
        registry.addInterceptor(interceptor).addPathPatterns(API_PATTERNS);
    }
}
