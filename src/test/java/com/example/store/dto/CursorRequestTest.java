package com.example.store.dto;

import com.example.store.exception.InvalidCursorRequestException;

import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CursorRequestTest {

    @Test
    void takesTheSizeOfTheResolvedPageable() {
        CursorRequest request = CursorRequest.of(5L, PageRequest.of(0, 20, Sort.by("id")));

        assertThat(request).isEqualTo(new CursorRequest(5L, 20));
    }

    @Test
    void acceptsAnExplicitAscendingIdSort() {
        CursorRequest request = CursorRequest.of(0L, PageRequest.of(0, 7, Sort.by(Sort.Direction.ASC, "id")));

        assertThat(request).isEqualTo(new CursorRequest(0L, 7));
    }

    @Test
    void acceptsAnUnsortedPageable() {
        CursorRequest request = CursorRequest.of(0L, Pageable.ofSize(3));

        assertThat(request).isEqualTo(new CursorRequest(0L, 3));
    }

    @Test
    void rejectsAPageNumberOtherThanZero() {
        Pageable pageable = PageRequest.of(1, 20, Sort.by("id"));

        assertThatThrownBy(() -> CursorRequest.of(0L, pageable))
                .isInstanceOf(InvalidCursorRequestException.class)
                .hasMessage("after cannot be combined with page or sort");
    }

    @Test
    void rejectsADescendingIdSort() {
        Pageable pageable = PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, "id"));

        assertThatThrownBy(() -> CursorRequest.of(0L, pageable))
                .isInstanceOf(InvalidCursorRequestException.class)
                .hasMessage("after cannot be combined with page or sort");
    }

    @Test
    void rejectsASortWhoseFirstPropertyIsNotId() {
        Pageable pageable = PageRequest.of(0, 20, Sort.by("description", "id"));

        assertThatThrownBy(() -> CursorRequest.of(0L, pageable))
                .isInstanceOf(InvalidCursorRequestException.class)
                .hasMessage("after cannot be combined with page or sort");
    }
}
