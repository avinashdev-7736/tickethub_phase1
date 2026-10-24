package com.tickethub.common.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;

/** Identity always comes from the validated token, never from request data. */
public final class CurrentUser {

    private CurrentUser() { }

    public static String id(Jwt jwt) {
        return jwt.getSubject();
    }

    public static String email(Jwt jwt) {
        return jwt.getClaimAsString("email");
    }

    public static boolean isAdmin(Authentication authentication) {
        return authentication != null && authentication.getAuthorities().stream()
                .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
    }
}
