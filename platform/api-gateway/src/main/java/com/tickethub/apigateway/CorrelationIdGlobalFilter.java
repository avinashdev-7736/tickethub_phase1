package com.tickethub.apigateway;

import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
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
        ServerHttpRequest request = exchange.getRequest().mutate()
                .headers(h -> {
                    h.set(HEADER, id);
                    h.remove("X-Internal-Key");
                })
                .build();
        exchange.getResponse().getHeaders().set(HEADER, id);
        return chain.filter(exchange.mutate().request(request).build());
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
