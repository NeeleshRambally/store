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
    void appendsAnIdTieBreakInTheDirectionOfTheLastProperty() {
        Pageable pageable = PageRequest.of(2, 50, Sort.by(Sort.Order.desc("description")));

        Pageable validated = policy.validate(pageable);

        assertThat(validated.getPageNumber()).isEqualTo(2);
        assertThat(validated.getPageSize()).isEqualTo(50);
        assertThat(validated.getSort()).containsExactly(Sort.Order.desc("description"), Sort.Order.desc("id"));
    }

    @Test
    void appendsAnAscendingIdTieBreakAfterAnAscendingProperty() {
        Pageable pageable = PageRequest.of(0, 20, Sort.by(Sort.Order.asc("description")));

        assertThat(policy.validate(pageable).getSort())
                .containsExactly(Sort.Order.asc("description"), Sort.Order.asc("id"));
    }

    @Test
    void leavesAnUnpagedSortedRequestUntouched() {
        Pageable pageable = Pageable.unpaged(Sort.by(Sort.Order.desc("description")));

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
