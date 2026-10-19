package com.example.store.config;

import com.example.store.exception.RepeatedParameterException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.MethodParameter;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

// Rejects a repeated @RequestParam query parameter, of which Spring would bind the first number or the joined strings.
class SingleValuedParameterInterceptor implements HandlerInterceptor {

    private static final ParameterNameDiscoverer PARAMETER_NAMES = new DefaultParameterNameDiscoverer();

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (handler instanceof HandlerMethod handlerMethod) {
            for (MethodParameter parameter : handlerMethod.getMethodParameters()) {
                RequestParam requestParam = parameter.getParameterAnnotation(RequestParam.class);
                if (requestParam != null) {
                    String name = name(parameter, requestParam);
                    String[] values = request.getParameterValues(name);
                    if (values != null && values.length > 1) {
                        throw new RepeatedParameterException(name);
                    }
                }
            }
        }
        return true;
    }

    private static String name(MethodParameter parameter, RequestParam requestParam) {
        if (!requestParam.name().isEmpty()) {
            return requestParam.name();
        }
        parameter.initParameterNameDiscovery(PARAMETER_NAMES);
        return parameter.getParameterName();
    }
}
