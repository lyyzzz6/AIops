package com.netdata.ops.service;

import com.netdata.ops.dto.request.LoginRequest;
import com.netdata.ops.dto.response.TokenResponse;
import com.netdata.ops.entity.SysUser;
import com.netdata.ops.exception.BusinessException;
import com.netdata.ops.exception.ErrorCode;
import com.netdata.ops.mapper.SysUserMapper;
import com.netdata.ops.security.JwtTokenProvider;
import com.netdata.ops.util.IpUtils;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 认证服务
 * 处理登录、登出、Token刷新
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final SysUserMapper userMapper;
    private final JwtTokenProvider jwtTokenProvider;
    private final PasswordEncoder passwordEncoder;

    @Value("${security.jwt.access-token-expiration:7200000}")
    private long accessTokenExpiration;

    /**
     * 最大连续登录失败次数
     */
    private static final int MAX_LOGIN_FAIL_COUNT = 5;

    /**
     * 账户锁定时长（分钟）
     */
    private static final int LOCK_DURATION_MINUTES = 30;

    /**
     * 用户登录
     */
    @Transactional
    public TokenResponse login(LoginRequest request, HttpServletRequest httpRequest) {
        SysUser user = userMapper.selectByUsername(request.getUsername());
        if (user == null) {
            throw new BusinessException(ErrorCode.LOGIN_FAILED);
        }

        if (user.getStatus() == 0) {
            throw new BusinessException(ErrorCode.ACCOUNT_DISABLED);
        }

        if (user.getLockedUntil() != null && user.getLockedUntil().isAfter(LocalDateTime.now())) {
            throw new BusinessException(ErrorCode.ACCOUNT_LOCKED);
        }

        if (!passwordEncoder.matches(request.getPassword(), user.getPassword())) {
            handleLoginFailure(user);
            throw new BusinessException(ErrorCode.LOGIN_FAILED);
        }

        if (user.getIsFirstLogin() == 1) {
            throw new BusinessException(ErrorCode.FIRST_LOGIN_PASSWORD_CHANGE_REQUIRED);
        }

        user.setLoginFailCount(0);
        user.setLockedUntil(null);
        user.setLastLoginAt(LocalDateTime.now());
        user.setLastLoginIp(IpUtils.getClientIp(httpRequest));
        userMapper.updateById(user);

        List<String> roles = userMapper.selectRoleCodesByUserId(user.getId());
        List<String> permissions = userMapper.selectPermissionsByUserId(user.getId());

        String accessToken = jwtTokenProvider.generateAccessToken(user.getId(), user.getUsername(), roles);
        String refreshToken = jwtTokenProvider.generateRefreshToken(user.getId());

        log.info("用户登录成功: {} from {}", user.getUsername(), user.getLastLoginIp());

        return TokenResponse.builder()
                .accessToken(accessToken)
                .refreshToken(refreshToken)
                .expiresIn(accessTokenExpiration / 1000)
                .tokenType("Bearer")
                .user(TokenResponse.UserVO.builder()
                        .id(user.getId())
                        .username(user.getUsername())
                        .nickname(user.getNickname())
                        .email(user.getEmail())
                        .avatar(user.getAvatar())
                        .roles(roles)
                        .permissions(permissions)
                        .isFirstLogin(user.getIsFirstLogin())
                        .build())
                .build();
    }

    /**
     * 登出
     */
    public void logout(String accessToken) {
        jwtTokenProvider.blacklistToken(accessToken);
        Long userId = jwtTokenProvider.getUserIdFromToken(accessToken);
        jwtTokenProvider.revokeAllRefreshTokens(userId);
        log.info("用户登出: userId={}", userId);
    }

    /**
     * 刷新Token
     */
    public TokenResponse refreshToken(String refreshToken) {
        if (!jwtTokenProvider.validateRefreshToken(refreshToken)) {
            throw new BusinessException(ErrorCode.TOKEN_INVALID, "Refresh Token无效或已过期");
        }

        Long userId = jwtTokenProvider.getUserIdFromToken(refreshToken);
        SysUser user = userMapper.selectById(userId);
        if (user == null || user.getStatus() == 0) {
            throw new BusinessException(ErrorCode.ACCOUNT_DISABLED);
        }

        List<String> roles = userMapper.selectRoleCodesByUserId(userId);
        List<String> permissions = userMapper.selectPermissionsByUserId(userId);

        String newAccessToken = jwtTokenProvider.generateAccessToken(userId, user.getUsername(), roles);
        String newRefreshToken = jwtTokenProvider.generateRefreshToken(userId);

        return TokenResponse.builder()
                .accessToken(newAccessToken)
                .refreshToken(newRefreshToken)
                .expiresIn(accessTokenExpiration / 1000)
                .tokenType("Bearer")
                .user(TokenResponse.UserVO.builder()
                        .id(user.getId())
                        .username(user.getUsername())
                        .nickname(user.getNickname())
                        .email(user.getEmail())
                        .avatar(user.getAvatar())
                        .roles(roles)
                        .permissions(permissions)
                        .build())
                .build();
    }

    /**
     * 获取当前登录用户信息
     */
    public TokenResponse.UserVO getCurrentUser(Long userId) {
        SysUser user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(ErrorCode.USER_NOT_FOUND);
        }

        List<String> roles = userMapper.selectRoleCodesByUserId(userId);
        List<String> permissions = userMapper.selectPermissionsByUserId(userId);

        return TokenResponse.UserVO.builder()
                .id(user.getId())
                .username(user.getUsername())
                .nickname(user.getNickname())
                .email(user.getEmail())
                .avatar(user.getAvatar())
                .roles(roles)
                .permissions(permissions)
                .build();
    }

    /**
     * 处理登录失败 - 累计失败次数并可能锁定账户
     */
    private void handleLoginFailure(SysUser user) {
        int failCount = user.getLoginFailCount() + 1;
        user.setLoginFailCount(failCount);

        if (failCount >= MAX_LOGIN_FAIL_COUNT) {
            user.setLockedUntil(LocalDateTime.now().plusMinutes(LOCK_DURATION_MINUTES));
            log.warn("账户被锁定: {} (连续{}次登录失败)", user.getUsername(), failCount);
        }

        userMapper.updateById(user);
    }
}
