package com.example.store.dto;

import com.example.store.exception.InvalidCursorRequestException;

import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

public record CursorRequest(long after, int size) {

    public static CursorRequest of(long after, Pageable pageable) {
        if (pageable.getPageNumber() != 0 || !startsWithAscendingId(pageable.getSort())) {
            throw new InvalidCursorRequestException();
        }
        return new CursorRequest(after, pageable.getPageSize());
    }

    private static boolean startsWithAscendingId(Sort sort) {
        return sort.stream()
                .findFirst()
                .map(order -> order.getProperty().equals("id") && order.isAscending())
                .orElse(true);
    }
}
