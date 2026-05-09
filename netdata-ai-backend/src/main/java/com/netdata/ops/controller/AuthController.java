package com.netdata.ops.controller;

import com.netdata.ops.dto.request.LoginRequest;
import com.netdata.ops.dto.response.R;
import com.netdata.ops.dto.response.TokenResponse;
import com.netdata.ops.security.SecurityUser;
import com.netdata.ops.service.AuthService;
import com.netdata.ops.util.SecurityUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 认证控制器
 * 处理登录、登出、Token刷新
 */
@Tag(name = "认证管理", description = "用户登录、登出、Token刷新")
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @Operation(summary = "用户登录")
    @PostMapping("/login")
    public R<TokenResponse> login(@Valid @RequestBody LoginRequest request,
                                  HttpServletRequest httpRequest) {
        TokenResponse response = authService.login(request, httpRequest);
        return R.ok(response);
    }

    @Operation(summary = "用户登出")
    @PostMapping("/logout")
    public R<Void> logout(HttpServletRequest request) {
        String token = extractToken(request);
        if (token != null) {
            authService.logout(token);
        }
        return R.ok();
    }

    @Operation(summary = "刷新Token")
    @PostMapping("/refresh")
    public R<TokenResponse> refresh(@RequestBody Map<String, String> body) {
        String refreshToken = body.get("refreshToken");
        if (refreshToken == null || refreshToken.isBlank()) {
            return R.badRequest("refreshToken不能为空");
        }
        TokenResponse response = authService.refreshToken(refreshToken);
        return R.ok(response);
    }

    @Operation(summary = "获取当前用户信息")
    @GetMapping("/me")
    public R<TokenResponse.UserVO> getCurrentUser() {
        SecurityUser user = SecurityUtils.getCurrentUser();
        if (user == null) {
            return R.unauthorized("未登录");
        }
        TokenResponse.UserVO userVO = authService.getCurrentUser(user.getUserId());
        return R.ok(userVO);
    }

    private String extractToken(HttpServletRequest request) {
        String bearerToken = request.getHeader("Authorization");
        if (bearerToken != null && bearerToken.startsWith("Bearer ")) {
            return bearerToken.substring(7);
        }
        return null;
    }
}
