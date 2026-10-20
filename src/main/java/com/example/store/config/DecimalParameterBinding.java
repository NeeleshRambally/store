package com.example.store.config;

import org.springframework.util.StringUtils;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.InitBinder;

import java.beans.PropertyEditor;
import java.beans.PropertyEditorSupport;
import java.util.function.Function;
import java.util.regex.Pattern;

// Accepts a numeric parameter only in canonical decimal form, ASCII digits with no plus sign or leading zeros, so each
// number has one spelling; Spring's own number conversion strips whitespace and reads 0x and # as hexadecimal.
@ControllerAdvice
public class DecimalParameterBinding {

    private static final Pattern CANONICAL_DECIMAL = Pattern.compile("0|-?[1-9][0-9]*");

    @InitBinder({"id", "after", "customerId", "productId", "page", "size"})
    void parseAsDecimalIntegers(WebDataBinder binder) {
        binder.registerCustomEditor(Long.class, decimal(Long::valueOf));
        binder.registerCustomEditor(Integer.class, decimal(Integer::valueOf));
    }

    private static PropertyEditor decimal(Function<String, ? extends Number> parser) {
        return new PropertyEditorSupport() {
            @Override
            public void setAsText(String text) {
                if (!StringUtils.hasText(text)) {
                    setValue(null);
                } else if (CANONICAL_DECIMAL.matcher(text).matches()) {
                    setValue(parser.apply(text));
                } else {
                    throw new NumberFormatException("Not in canonical decimal form: \"" + text + "\"");
                }
            }
        };
    }
}
