package com.mall.common;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

/**
 * JWT 工具：登录签发、请求侧解析校验。
 */
@Component
public class JwtUtil {

    private final SecretKey key;
    private final long expireMs;

    public JwtUtil(@Value("${mall.jwt.secret}") String secret,
                   @Value("${mall.jwt.expire-hours}") long expireHours) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.expireMs = expireHours * 3600_000L;
    }

    public String issue(Long userId) {
        Date now = new Date();
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .issuedAt(now)
                .expiration(new Date(now.getTime() + expireMs))
                .signWith(key)
                .compact();
    }

    /** 解析并校验签名/有效期；非法返回 null（调用方决定如何拒绝）。 */
    public Long parseUserId(String token) {
        try {
            Claims claims = Jwts.parser().verifyWith(key).build()
                    .parseSignedClaims(token).getPayload();
            return Long.valueOf(claims.getSubject());
        } catch (Exception e) {
            return null;
        }
    }
}
