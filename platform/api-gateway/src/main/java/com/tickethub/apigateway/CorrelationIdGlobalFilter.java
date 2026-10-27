package com.tickethub.apigateway;

import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpRequestDecorator;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.UUID;

/** Creates (or keeps) X-Correlation-Id for every request and strips any client-supplied internal key. */
@Component
public class CorrelationIdGlobalFilter implements GlobalFilter, Ordered {

    static final String HEADER = "X-Correlation-Id";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String incoming = exchange.getRequest().getHeaders().getFirst(HEADER);
        String id = (incoming == null || incoming.isBlank() || incoming.length() > 100) ? UUID.randomUUID().toString() : incoming;
        HttpHeaders headers = new HttpHeaders();
        headers.putAll(exchange.getRequest().getHeaders());
        headers.set(HEADER, id);
        headers.remove("X-Internal-Key");
        ServerHttpRequest request = new ServerHttpRequestDecorator(exchange.getRequest()) {
            @Override
            public HttpHeaders getHeaders() {
                return HttpHeaders.readOnlyHttpHeaders(headers);
            }
        };
        exchange.getResponse().getHeaders().set(HEADER, id);
        return chain.filter(exchange.mutate().request(request).build());
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
