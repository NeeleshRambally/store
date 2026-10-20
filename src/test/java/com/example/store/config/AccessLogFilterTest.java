package com.example.store.config;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletResponse;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AccessLogFilterTest {

    private final AccessLogFilter filter = new AccessLogFilter();
    private final Logger access = (Logger) LoggerFactory.getLogger("com.example.store.access");
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    @BeforeEach
    void attachAppender() {
        appender.start();
        access.addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        access.detachAppender(appender);
    }

    @Test
    void anExceptionThatEscapesTheChainIsLoggedWithTheServerErrorTomcatSends() {
        MockHttpServletRequest request = new MockHttpServletRequest("PUT", "/customer");

        assertThatThrownBy(() -> filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> {
                    throw new ServletException("escaped");
                }))
                .isInstanceOf(ServletException.class);

        assertLogged("PUT UNKNOWN 500 \\d+ ms", 500);
    }

    @Test
    void anErrorStatusSetBeforeTheExceptionIsReplacedLikeTomcatReplacesIt() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/order");

        assertThatThrownBy(() -> filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> {
                    ((HttpServletResponse) res).setStatus(503);
                    throw new IllegalStateException("escaped");
                }))
                .isInstanceOf(IllegalStateException.class);

        assertLogged("GET UNKNOWN 500 \\d+ ms", 500);
    }

    @Test
    void aResponseCommittedBeforeTheExceptionIsLoggedWithTheStatusItSent() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/order");

        assertThatThrownBy(() -> filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> {
                    res.flushBuffer();
                    throw new IOException("client gone");
                }))
                .isInstanceOf(IOException.class);

        assertLogged("GET UNKNOWN 200 \\d+ ms", 200);
    }

    private void assertLogged(String message, int status) {
        assertThat(appender.list).singleElement().satisfies(line -> {
            assertThat(line.getFormattedMessage()).matches(message);
            assertThat(line.getKeyValuePairs())
                    .filteredOn(pair -> pair.key.equals("status"))
                    .singleElement()
                    .satisfies(pair -> assertThat(pair.value).isEqualTo(status));
        });
    }
}
