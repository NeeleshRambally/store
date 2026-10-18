package com.example.store.config;

import com.example.store.exception.RequestBodyTooLargeException;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

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
    void aReaderWithinTheBoundReadsTheBodyAsUtf8WhenNoEncodingIsDeclared() throws Exception {
        MockHttpServletRequest request = chunked("caf\u00e9");
        AtomicReference<String> read = new AtomicReference<>();

        filter.doFilter(
                request,
                new MockHttpServletResponse(),
                (req, res) -> read.set(req.getReader().readLine()));

        assertThat(read.get()).isEqualTo("caf\u00e9");
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
        RecordingInputStream delegate = new RecordingInputStream("e");
        ServletInputStream stream = new RequestBodyLimitFilter.BoundedInputStream(delegate, LIMIT);
        ReadListener listener = mock(ReadListener.class);

        stream.setReadListener(listener);
        assertThat(delegate.listener).isSameAs(listener);
        assertThat(stream.isReady()).isTrue();
        assertThat(stream.isFinished()).isFalse();
        assertThat(stream.read()).isEqualTo('e');
        assertThat(stream.read()).isEqualTo(-1);
        assertThat(stream.isFinished()).isTrue();
        stream.close();
        assertThat(delegate.closed).isTrue();
    }

    private static final class RecordingInputStream extends ServletInputStream {

        private final InputStream source;
        private ReadListener listener;
        private boolean closed;
        private boolean finished;

        private RecordingInputStream(String content) {
            source = new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
        }

        @Override
        public int read() throws IOException {
            int value = source.read();
            finished = value == -1;
            return value;
        }

        @Override
        public void close() {
            closed = true;
        }

        @Override
        public boolean isFinished() {
            return finished;
        }

        @Override
        public boolean isReady() {
            return true;
        }

        @Override
        public void setReadListener(ReadListener readListener) {
            listener = readListener;
        }
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
