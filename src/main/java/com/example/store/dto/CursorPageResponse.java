package com.example.store.dto;

import java.util.List;
import java.util.function.Function;

public record CursorPageResponse<T>(List<T> content, int size, Long nextCursor) implements ListResponse<T> {

    public static <R, T> CursorPageResponse<T> from(
            List<R> fetched, int size, Function<R, Long> id, Function<List<R>, List<T>> mapper) {
        boolean hasMore = fetched.size() > size;
        List<R> rows = hasMore ? fetched.subList(0, size) : fetched;
        return new CursorPageResponse<>(mapper.apply(rows), size, hasMore ? id.apply(rows.get(size - 1)) : null);
    }
}
