package com.example.store.config;

import com.example.store.exception.RequestBodyTooLargeException;

import jakarta.servlet.ServletInputStream;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.DelegatingServletInputStream;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RequestBodyLimitFilterTest {

    private static final long LIMIT = 1024;

    private final RequestBodyLimitFilter filter = new RequestBodyLimitFilter(new RequestBodyLimitProperties(LIMIT));

    @Test
    void aChunkedBodyPastTheBoundFailsWhileItIsRead() {
        MockHttpServletRequest request = chunked("a".repeat((int) LIMIT + 1));

        assertThatThrownBy(
                        () -> filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> req.getInputStream()
                                .readAllBytes()))
                .isInstanceOf(RequestBodyTooLargeException.class)
                .hasMessage("Request body exceeds 1024 bytes");
    }

    @Test
    void aChunkedBodyWithinTheBoundIsReadInFull() throws Exception {
        MockHttpServletRequest request = chunked("b".repeat((int) LIMIT));
        AtomicReference<byte[]> read = new AtomicReference<>();

        filter.doFilter(
                request,
                new MockHttpServletResponse(),
                (req, res) -> read.set(req.getInputStream().readAllBytes()));

        assertThat(read.get()).hasSize((int) LIMIT);
    }

    @Test
    void aDeclaredLengthPastTheBoundFailsBeforeAnyByteIsRead() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setContent("c".repeat((int) LIMIT + 1).getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> req.getReader()))
                .isInstanceOf(RequestBodyTooLargeException.class);
    }

    @Test
    void theReaderCountsThroughTheSameStream() throws Exception {
        MockHttpServletRequest request = chunked("d".repeat((int) LIMIT + 1));
        request.setCharacterEncoding("UTF-8");

        assertThatThrownBy(() -> filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> req.getReader()
                        .readLine()))
                .isInstanceOf(RequestBodyTooLargeException.class);
    }

    @Test
    void theStreamDelegatesTheServletMethods() throws Exception {
        AtomicBoolean closed = new AtomicBoolean();
        InputStream source = new ByteArrayInputStream("e".getBytes(StandardCharsets.UTF_8)) {
            @Override
            public void close() throws IOException {
                closed.set(true);
                super.close();
            }
        };
        ServletInputStream stream =
                new RequestBodyLimitFilter.BoundedInputStream(new DelegatingServletInputStream(source), LIMIT);

        assertThat(stream.isReady()).isTrue();
        assertThat(stream.isFinished()).isFalse();
        assertThat(stream.read()).isEqualTo('e');
        assertThat(stream.read()).isEqualTo(-1);
        assertThat(stream.isFinished()).isTrue();
        assertThatThrownBy(() -> stream.setReadListener(null)).isInstanceOf(UnsupportedOperationException.class);
        stream.close();
        assertThat(closed).isTrue();
    }

    private static MockHttpServletRequest chunked(String body) {
        MockHttpServletRequest request = new MockHttpServletRequest() {
            @Override
            public long getContentLengthLong() {
                return -1;
            }
        };
        request.setContent(body.getBytes(StandardCharsets.UTF_8));
        return request;
    }
}
