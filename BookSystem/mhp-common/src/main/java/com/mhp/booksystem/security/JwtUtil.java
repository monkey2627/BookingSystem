package com.mhp.booksystem.security;

import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

import javax.crypto.SecretKey;
import java.util.Date;

public class JwtUtil {

    // 生产环境应从 Nacos 配置中心读取，此处硬编码供开发/演示使用
    private static final String SECRET = "mhp-cosplay-jwt-secret-key-32chars!";
    private static final SecretKey KEY = Keys.hmacShaKeyFor(SECRET.getBytes());
    private static final long EXPIRE_MS = 7L * 24 * 3600 * 1000;

    public static String generate(Long userId) {
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .expiration(new Date(System.currentTimeMillis() + EXPIRE_MS))
                .signWith(KEY)
                .compact();
    }

    /**
     * 解析 token，返回 userId。token 非法或已过期时抛出 {@link JwtException}。
     */
    public static Long parse(String token) {
        return Long.parseLong(
                Jwts.parser()
                        .verifyWith(KEY)
                        .build()
                        .parseSignedClaims(token)
                        .getPayload()
                        .getSubject());
    }
}
