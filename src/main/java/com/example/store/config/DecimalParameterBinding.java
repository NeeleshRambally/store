package com.example.store.config;

import org.springframework.util.StringUtils;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.InitBinder;

import java.beans.PropertyEditorSupport;

// Parses page and size as Spring Data's Pageable resolver does (Integer.valueOf), so the value validated is the
// value served: Spring's own number parsing strips whitespace and reads 0x and # as hexadecimal.
@ControllerAdvice
public class DecimalParameterBinding {

    @InitBinder({"page", "size"})
    void parseAsThePageableResolverDoes(WebDataBinder binder) {
        binder.registerCustomEditor(Integer.class, new PropertyEditorSupport() {
            @Override
            public void setAsText(String text) {
                setValue(StringUtils.hasText(text) ? Integer.valueOf(text) : null);
            }
        });
    }
}
