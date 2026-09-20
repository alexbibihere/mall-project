package com.mall.gateway.filter;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 全局 JWT 鉴权过滤器（M2）：校验通过后剥离客户端伪造头，注入可信 X-User-Id。
 * 白名单：/api/auth、/api/products（GET 浏览）、/api/payments/mock/notify（渠道回调）。
 */
@Component
public class AuthGlobalFilter implements GlobalFilter, Ordered {

    private final SecretKey key;

    @Autowired
    public AuthGlobalFilter(@Value("${mall.jwt.secret}") String secret) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    private static final List<String> OPEN_PREFIXES = List.of(
            "/api/auth",
            "/api/products",
            "/api/payments/mock/notify/"
    );

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getPath().value();
        boolean open = OPEN_PREFIXES.stream().anyMatch(path::startsWith);
        if (open) {
            return chain.filter(strip(exchange));
        }
        String auth = exchange.getRequest().getHeaders().getFirst("Authorization");
        if (auth == null || !auth.startsWith("Bearer ")) {
            exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
            return exchange.getResponse().setComplete();
        }
        try {
            Claims claims = Jwts.parser().verifyWith(key).build()
                    .parseSignedClaims(auth.substring(7)).getPayload();
            String userId = claims.getSubject();
            ServerWebExchange mutated = exchange.mutate().request(
                    r -> r.headers(h -> h.set("X-User-Id", userId))).build();
            return chain.filter(mutated);
        } catch (Exception e) {
            exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
            return exchange.getResponse().setComplete();
        }
    }

    /** 剥离外部传入的 X-User-Id，防伪造。 */
    private ServerWebExchange strip(ServerWebExchange exchange) {
        return exchange.mutate().request(
                r -> r.headers(h -> h.remove("X-User-Id"))).build();
    }

    @Override
    public int getOrder() {
        return -100;
    }
}
