package com.example.store.config;

import org.springframework.util.StringUtils;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.InitBinder;

import java.beans.PropertyEditor;
import java.beans.PropertyEditorSupport;
import java.util.function.Function;

// Parses numeric parameters as decimal integers, as Spring Data's Pageable resolver parses page and size; Spring's own
// number conversion strips whitespace and reads 0x and # as hexadecimal, so /order/1%200 would read order 10.
@ControllerAdvice
public class DecimalParameterBinding {

    @InitBinder({"id", "after", "customerId", "productId", "page", "size"})
    void parseAsDecimalIntegers(WebDataBinder binder) {
        binder.registerCustomEditor(Long.class, decimal(Long::valueOf));
        binder.registerCustomEditor(Integer.class, decimal(Integer::valueOf));
    }

    private static PropertyEditor decimal(Function<String, ? extends Number> parser) {
        return new PropertyEditorSupport() {
            @Override
            public void setAsText(String text) {
                setValue(StringUtils.hasText(text) ? parser.apply(text) : null);
            }
        };
    }
}
