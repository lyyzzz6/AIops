package com.netdata.ops.security;

import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * JWT Token 提供者
 * 负责Token的生成、验证、解析
 */
@Slf4j
@Component
public class JwtTokenProvider {

    private final SecretKey secretKey;
    private final long accessTokenExpiration;
    private final long refreshTokenExpiration;
    private final StringRedisTemplate redisTemplate;

    private static final String REFRESH_TOKEN_PREFIX = "refresh_token:";
    private static final String TOKEN_BLACKLIST_PREFIX = "token_blacklist:";

    public JwtTokenProvider(
            @Value("${security.jwt.secret:netdata-ops-default-jwt-secret-key-2026-must-be-256-bits}") String secret,
            @Value("${security.jwt.access-token-expiration:7200000}") long accessTokenExpiration,
            @Value("${security.jwt.refresh-token-expiration:604800000}") long refreshTokenExpiration,
            StringRedisTemplate redisTemplate) {
        this.secretKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.accessTokenExpiration = accessTokenExpiration;
        this.refreshTokenExpiration = refreshTokenExpiration;
        this.redisTemplate = redisTemplate;
    }

    /**
     * 生成Access Token
     */
    public String generateAccessToken(Long userId, String username, List<String> roles) {
        Date now = new Date();
        Date expiry = new Date(now.getTime() + accessTokenExpiration);

        return Jwts.builder()
                .id(UUID.randomUUID().toString())
                .subject(String.valueOf(userId))
                .claim("username", username)
                .claim("roles", roles)
                .issuedAt(now)
                .expiration(expiry)
                .signWith(secretKey)
                .compact();
    }

    /**
     * 生成Refresh Token
     */
    public String generateRefreshToken(Long userId) {
        String tokenId = UUID.randomUUID().toString();
        Date now = new Date();
        Date expiry = new Date(now.getTime() + refreshTokenExpiration);

        String token = Jwts.builder()
                .id(tokenId)
                .subject(String.valueOf(userId))
                .claim("type", "refresh")
                .issuedAt(now)
                .expiration(expiry)
                .signWith(secretKey)
                .compact();

        // 存储Refresh Token到Redis（支持主动注销）
        String redisKey = REFRESH_TOKEN_PREFIX + userId + ":" + tokenId;
        redisTemplate.opsForValue().set(redisKey, "valid", refreshTokenExpiration, TimeUnit.MILLISECONDS);

        return token;
    }

    /**
     * 验证Token有效性
     */
    public boolean validateToken(String token) {
        try {
            Jwts.parser().verifyWith(secretKey).build().parseSignedClaims(token);

            // 检查黑名单
            String jti = getTokenId(token);
            if (Boolean.TRUE.equals(redisTemplate.hasKey(TOKEN_BLACKLIST_PREFIX + jti))) {
                log.debug("Token已在黑名单中: {}", jti);
                return false;
            }

            return true;
        } catch (ExpiredJwtException e) {
            log.debug("Token已过期");
        } catch (JwtException e) {
            log.debug("Token无效: {}", e.getMessage());
        }
        return false;
    }

    /**
     * 从Token中获取用户ID
     */
    public Long getUserIdFromToken(String token) {
        Claims claims = parseClaims(token);
        return Long.parseLong(claims.getSubject());
    }

    /**
     * 从Token中获取用户名
     */
    public String getUsernameFromToken(String token) {
        Claims claims = parseClaims(token);
        return claims.get("username", String.class);
    }

    /**
     * 从Token中获取角色列表
     */
    @SuppressWarnings("unchecked")
    public List<String> getRolesFromToken(String token) {
        Claims claims = parseClaims(token);
        return claims.get("roles", List.class);
    }

    /**
     * 获取Token ID
     */
    public String getTokenId(String token) {
        Claims claims = parseClaims(token);
        return claims.getId();
    }

    /**
     * 获取Token过期时间
     */
    public Date getExpirationFromToken(String token) {
        Claims claims = parseClaims(token);
        return claims.getExpiration();
    }

    /**
     * 将Token加入黑名单（登出时使用）
     */
    public void blacklistToken(String token) {
        try {
            String jti = getTokenId(token);
            Date expiration = getExpirationFromToken(token);
            long ttl = expiration.getTime() - System.currentTimeMillis();
            if (ttl > 0) {
                redisTemplate.opsForValue().set(TOKEN_BLACKLIST_PREFIX + jti, "blacklisted", ttl, TimeUnit.MILLISECONDS);
            }
        } catch (Exception e) {
            log.warn("Token黑名单处理失败: {}", e.getMessage());
        }
    }

    /**
     * 验证Refresh Token
     */
    public boolean validateRefreshToken(String token) {
        try {
            Claims claims = parseClaims(token);
            String type = claims.get("type", String.class);
            if (!"refresh".equals(type)) {
                return false;
            }

            Long userId = Long.parseLong(claims.getSubject());
            String tokenId = claims.getId();
            String redisKey = REFRESH_TOKEN_PREFIX + userId + ":" + tokenId;
            return Boolean.TRUE.equals(redisTemplate.hasKey(redisKey));
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 注销用户所有Refresh Token
     */
    public void revokeAllRefreshTokens(Long userId) {
        var keys = redisTemplate.keys(REFRESH_TOKEN_PREFIX + userId + ":*");
        if (keys != null && !keys.isEmpty()) {
            redisTemplate.delete(keys);
        }
    }

    private Claims parseClaims(String token) {
        return Jwts.parser()
                .verifyWith(secretKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}
