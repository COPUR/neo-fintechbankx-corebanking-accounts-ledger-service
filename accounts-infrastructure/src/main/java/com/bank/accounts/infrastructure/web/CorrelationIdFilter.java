package com.bank.accounts.infrastructure.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Carries the FAPI interaction id through the request: it is echoed on the
 * response, written to the log MDC, and used as the correlationId of every
 * event the request raises.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "x-fapi-interaction-id";
    public static final String MDC_KEY = "correlationId";
    public static final String TRACEPARENT_HEADER = "traceparent";
    public static final String TRACEPARENT_MDC_KEY = "traceparent";
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9._:-]{1,128}");
    // W3C Trace Context version 00: 00-<32 hex trace id>-<16 hex parent id>-<2 hex flags>
    private static final Pattern TRACEPARENT = Pattern.compile("00-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String incoming = request.getHeader(HEADER);
        String correlationId = incoming != null && SAFE_ID.matcher(incoming).matches()
            ? incoming
            : UUID.randomUUID().toString();
        MDC.put(MDC_KEY, correlationId);
        String traceparent = request.getHeader(TRACEPARENT_HEADER);
        if (traceparent != null && TRACEPARENT.matcher(traceparent).matches()) {
            MDC.put(TRACEPARENT_MDC_KEY, traceparent);
        }
        response.setHeader(HEADER, correlationId);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
            MDC.remove(TRACEPARENT_MDC_KEY);
        }
    }
}
