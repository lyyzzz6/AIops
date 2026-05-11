package com.netdata.ops.dto.response;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 登录成功响应 - Token信息
 */
@Data
@Builder
public class TokenResponse {

    private String accessToken;
    private String refreshToken;
    private long expiresIn;
    private String tokenType;
    private UserVO user;

    @Data
    @Builder
    public static class UserVO {
        private Long id;
        private String username;
        private String nickname;
        private String email;
        private String avatar;
        private List<String> roles;
        private List<String> permissions;
        private Integer isFirstLogin;
    }
}
