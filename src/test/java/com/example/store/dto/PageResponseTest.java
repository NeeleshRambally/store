package com.example.store.dto;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PageResponseTest {

    @Test
    void keepsItsContentWhenTheListItWasBuiltFromChanges() {
        List<String> content = new ArrayList<>(List.of("first"));
        PageResponse<String> page = new PageResponse<>(content, 0, 20, 1, 1);

        content.add("second");

        assertThat(page.content()).containsExactly("first");
    }

    @Test
    void rejectsChangesToItsContent() {
        PageResponse<String> page = new PageResponse<>(new ArrayList<>(List.of("first")), 0, 20, 1, 1);

        assertThatThrownBy(() -> page.content().add("second")).isInstanceOf(UnsupportedOperationException.class);
    }
}
