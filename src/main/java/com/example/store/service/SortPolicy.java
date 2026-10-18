package com.example.store.service;

import com.example.store.exception.InvalidSortPropertyException;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.util.Set;

public record SortPolicy(Set<String> allowed) {

    private static final String TIE_BREAK = "id";

    public static SortPolicy of(String... properties) {
        return new SortPolicy(Set.of(properties));
    }

    public Pageable validate(Pageable pageable) {
        Sort.Order last = null;
        for (Sort.Order order : pageable.getSort()) {
            if (!allowed.contains(order.getProperty())) {
                throw new InvalidSortPropertyException(order.getProperty());
            }
            last = order;
        }
        if (last == null || pageable.isUnpaged() || pageable.getSort().getOrderFor(TIE_BREAK) != null) {
            return pageable;
        }
        Sort tieBroken = pageable.getSort().and(Sort.by(last.getDirection(), TIE_BREAK));
        return PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), tieBroken);
    }
}
