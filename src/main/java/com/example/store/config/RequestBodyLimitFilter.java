package com.example.store.config;

import com.example.store.exception.RequestBodyTooLargeException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;

import lombok.RequiredArgsConstructor;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

// Bounds the request body by declared length or by counted bytes; the exception surfaces as a 413 problem detail.
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
@EnableConfigurationProperties(RequestBodyLimitProperties.class)
@RequiredArgsConstructor
public class RequestBodyLimitFilter extends OncePerRequestFilter {

    private final RequestBodyLimitProperties properties;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        chain.doFilter(new BoundedRequest(request, properties.maxBodyBytes()), response);
    }

    static final class BoundedRequest extends HttpServletRequestWrapper {

        private final long maxBodyBytes;

        BoundedRequest(HttpServletRequest request, long maxBodyBytes) {
            super(request);
            this.maxBodyBytes = maxBodyBytes;
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            if (getContentLengthLong() > maxBodyBytes) {
                throw new RequestBodyTooLargeException(maxBodyBytes);
            }
            return new BoundedInputStream(super.getInputStream(), maxBodyBytes);
        }

        @Override
        public BufferedReader getReader() throws IOException {
            String encoding = getCharacterEncoding();
            return new BufferedReader(new InputStreamReader(
                    getInputStream(), encoding == null ? StandardCharsets.UTF_8.name() : encoding));
        }
    }

    static final class BoundedInputStream extends ServletInputStream {

        private final ServletInputStream delegate;
        private final long maxBodyBytes;
        private long consumed;

        BoundedInputStream(ServletInputStream delegate, long maxBodyBytes) {
            this.delegate = delegate;
            this.maxBodyBytes = maxBodyBytes;
        }

        @Override
        public int read() throws IOException {
            int value = delegate.read();
            if (value != -1) {
                count(1);
            }
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int read = delegate.read(buffer, offset, length);
            if (read > 0) {
                count(read);
            }
            return read;
        }

        private void count(int bytes) throws IOException {
            consumed += bytes;
            if (consumed > maxBodyBytes) {
                throw new RequestBodyTooLargeException(maxBodyBytes);
            }
        }

        @Override
        public void close() throws IOException {
            delegate.close();
        }

        @Override
        public boolean isFinished() {
            return delegate.isFinished();
        }

        @Override
        public boolean isReady() {
            return delegate.isReady();
        }

        @Override
        public void setReadListener(ReadListener listener) {
            delegate.setReadListener(listener);
        }
    }
}
