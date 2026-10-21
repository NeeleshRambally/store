package com.example.store.dto;

import com.example.store.exception.InvalidIdempotencyKeyException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IdempotencyKeyTest {

    private static final String MESSAGE = "Idempotency-Key must be 1 to 255 visible ASCII characters";

    @Test
    void acceptsVisibleAsciiFromOneToTwoHundredAndFiftyFiveCharacters() {
        String uuid = UUID.randomUUID().toString();

        assertThat(new IdempotencyKey("!").value()).isEqualTo("!");
        assertThat(new IdempotencyKey("~").value()).isEqualTo("~");
        assertThat(new IdempotencyKey(uuid).value()).isEqualTo(uuid);
        assertThat(new IdempotencyKey("k".repeat(255)).value()).hasSize(255);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "has space", "tab\there", "nul\u0000", "café", "line\nbreak", "\u007f"})
    void rejectsAKeyThatIsNotVisibleAscii(String value) {
        assertThatThrownBy(() -> new IdempotencyKey(value))
                .isInstanceOf(InvalidIdempotencyKeyException.class)
                .hasMessage(MESSAGE);
    }

    @Test
    void rejectsAKeyLongerThanTwoHundredAndFiftyFiveCharacters() {
        assertThatThrownBy(() -> new IdempotencyKey("k".repeat(256)))
                .isInstanceOf(InvalidIdempotencyKeyException.class)
                .hasMessage(MESSAGE);
    }

    @Test
    void rejectsANullKey() {
        assertThatThrownBy(() -> new IdempotencyKey(null))
                .isInstanceOf(InvalidIdempotencyKeyException.class)
                .hasMessage(MESSAGE);
    }

    @Test
    void comparesKeysExactly() {
        assertThat(new IdempotencyKey("Key")).isEqualTo(new IdempotencyKey("Key"));
        assertThat(new IdempotencyKey("Key")).isNotEqualTo(new IdempotencyKey("key"));
    }

    @Test
    void anAbsentHeaderIsNoKey() {
        assertThat(IdempotencyKey.fromHeader(null)).isNull();
    }

    @Test
    void aPresentHeaderIsParsed() {
        assertThat(IdempotencyKey.fromHeader("order-7f3c")).isEqualTo(new IdempotencyKey("order-7f3c"));
    }

    @Test
    void anEmptyHeaderIsRejected() {
        assertThatThrownBy(() -> IdempotencyKey.fromHeader(""))
                .isInstanceOf(InvalidIdempotencyKeyException.class)
                .hasMessage(MESSAGE);
    }

    @Test
    void namesTheHeaderItIsReadFrom() {
        assertThat(IdempotencyKey.HEADER).isEqualTo("Idempotency-Key");
    }
}
