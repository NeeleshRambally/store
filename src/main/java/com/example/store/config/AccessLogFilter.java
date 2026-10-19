package com.example.store.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;

import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

// Logs the matched route template, never the raw path or query string; outside the ETag filter so a 304 logs as 304.
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 2)
public class AccessLogFilter extends OncePerRequestFilter {

    private static final Logger ACCESS = LoggerFactory.getLogger("com.example.store.access");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long start = System.nanoTime();
        try {
            chain.doFilter(request, response);
        } finally {
            String route =
                    Objects.toString(request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE), "UNKNOWN");
            int status = response.getStatus();
            long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            ACCESS.atInfo()
                    .addKeyValue("route", route)
                    .addKeyValue("status", status)
                    .addKeyValue("durationMs", durationMs)
                    .log("{} {} {} {} ms", request.getMethod(), route, status, durationMs);
        }
    }
}
