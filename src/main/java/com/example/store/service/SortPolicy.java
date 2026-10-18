package com.example.store.service;

import com.example.store.exception.InvalidSortPropertyException;

import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.util.Set;

public record SortPolicy(Set<String> allowed) {

    public static SortPolicy of(String... properties) {
        return new SortPolicy(Set.of(properties));
    }

    public Pageable validate(Pageable pageable) {
        for (Sort.Order order : pageable.getSort()) {
            if (!allowed.contains(order.getProperty())) {
                throw new InvalidSortPropertyException(order.getProperty());
            }
        }
        return pageable;
    }
}
