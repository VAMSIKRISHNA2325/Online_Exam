package com.onlineexam.controller;

import com.onlineexam.domain.Role;
import com.onlineexam.security.ExamPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AuthenticationController {
    @GetMapping("/api/csrf")
    public CsrfResponse csrf(HttpServletRequest request) {
        CsrfToken token = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
        if (token == null) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "CSRF_UNAVAILABLE",
                    "A request token could not be issued.");
        }
        return new CsrfResponse(token.getHeaderName(), token.getParameterName(), token.getToken());
    }

    @GetMapping("/api/me")
    public MeResponse me(@AuthenticationPrincipal ExamPrincipal principal) {
        if (principal == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Sign in to continue.");
        }
        return new MeResponse(principal.id(), principal.username(), principal.displayName(), principal.role());
    }

    public record CsrfResponse(String headerName, String parameterName, String token) {
    }

    public record MeResponse(long id, String username, String displayName, Role role) {
    }
}
