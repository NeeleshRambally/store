package com.example.store.config;

import jakarta.servlet.ServletException;

import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RequestIdFilterMdcTest {

    private final RequestIdFilter filter = new RequestIdFilter();

    @Test
    void theRequestIdIsInTheMdcDuringTheChainAndGoneAfterIt() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Request-Id", "unit-1");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> seenInChain = new AtomicReference<>();

        filter.doFilter(request, response, (req, res) -> seenInChain.set(MDC.get("requestId")));

        assertThat(seenInChain.get()).isEqualTo("unit-1");
        assertThat(response.getHeader("X-Request-Id")).isEqualTo("unit-1");
        assertThat(MDC.get("requestId")).isNull();
    }

    @Test
    void theMdcIsClearedWhenTheChainThrows() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThatThrownBy(() -> filter.doFilter(request, response, (req, res) -> {
                    throw new ServletException("boom");
                }))
                .isInstanceOf(ServletException.class);

        assertThat(MDC.get("requestId")).isNull();
    }
}
