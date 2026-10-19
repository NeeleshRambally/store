package com.example.store.dto;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CursorPageResponseTest {

    @Test
    void keepsItsContentWhenTheListItWasBuiltFromChanges() {
        List<String> content = new ArrayList<>(List.of("first"));
        CursorPageResponse<String> page = new CursorPageResponse<>(content, 20, null);

        content.add("second");

        assertThat(page.content()).containsExactly("first");
    }

    @Test
    void rejectsChangesToItsContent() {
        CursorPageResponse<String> page = new CursorPageResponse<>(new ArrayList<>(List.of("first")), 20, null);

        assertThatThrownBy(() -> page.content().add("second")).isInstanceOf(UnsupportedOperationException.class);
    }
}
