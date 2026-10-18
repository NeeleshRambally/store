package com.example.store.service;

import com.example.store.exception.InvalidSortPropertyException;

import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SortPolicyTest {

    private final SortPolicy policy = SortPolicy.of("id", "description");

    @Test
    void acceptsListedPropertiesInAnyDirection() {
        Pageable pageable = PageRequest.of(1, 5, Sort.by(Sort.Order.desc("description"), Sort.Order.asc("id")));

        assertThat(policy.validate(pageable)).isSameAs(pageable);
    }

    @Test
    void acceptsAnUnsortedRequest() {
        Pageable pageable = PageRequest.of(0, 20);

        assertThat(policy.validate(pageable)).isSameAs(pageable);
    }

    @Test
    void rejectsTheFirstPropertyThatIsNotListed() {
        Pageable pageable = PageRequest.of(0, 20, Sort.by("id", "customer.name", "nope"));

        assertThatThrownBy(() -> policy.validate(pageable))
                .isInstanceOf(InvalidSortPropertyException.class)
                .hasMessage("Unknown sort property: customer.name");
    }

    @Test
    void comparesPropertyNamesCaseSensitively() {
        Pageable pageable = PageRequest.of(0, 20, Sort.by("ID"));

        assertThatThrownBy(() -> policy.validate(pageable)).hasMessage("Unknown sort property: ID");
    }
}
