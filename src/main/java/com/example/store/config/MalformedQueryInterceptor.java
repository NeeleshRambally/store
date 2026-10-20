package com.example.store.config;

import com.example.store.exception.MalformedQueryException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.apache.catalina.Globals;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Arrays;

// Rejects a query string Tomcat could not parse in full where the endpoint binds query parameters, since Tomcat
// drops each parameter it cannot decode and the endpoint would otherwise run without it.
class MalformedQueryInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (handler instanceof HandlerMethod handlerMethod && bindsQueryParameters(handlerMethod)) {
            request.getParameterMap();
            if (request.getAttribute(Globals.PARAMETER_PARSE_FAILED_ATTR) != null) {
                throw new MalformedQueryException();
            }
        }
        return true;
    }

    private static boolean bindsQueryParameters(HandlerMethod handlerMethod) {
        return Arrays.stream(handlerMethod.getMethodParameters())
                .anyMatch(parameter -> parameter.hasParameterAnnotation(RequestParam.class));
    }
}
