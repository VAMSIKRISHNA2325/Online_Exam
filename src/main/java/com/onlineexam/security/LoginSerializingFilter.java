package com.onlineexam.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.onlineexam.controller.ApiError;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

/** Keeps Spring Security's limit check and session registration in one critical section. */
public final class LoginSerializingFilter extends OncePerRequestFilter {
    public static final String THROTTLE_ATTEMPT = LoginSerializingFilter.class.getName() + ".attempt";
    // MySQL's accent-insensitive username collation can equate differently
    // spelled requests. One lock covers every login until registration ends.
    private final LoginRegistrationLock loginLock;
    private final LoginThrottle throttle;
    private final ObjectMapper json;

    public LoginSerializingFilter(LoginThrottle throttle, ObjectMapper json,
                                  LoginRegistrationLock loginLock) {
        this.throttle = throttle;
        this.json = json;
        this.loginLock = loginLock;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"POST".equals(request.getMethod()) || !"/login".equals(request.getServletPath());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        loginLock.lock();
        try {
            String username = request.getParameter("username");
            String password = request.getParameter("password");
            if ((username != null && username.length() > 80)
                    || (password != null && password.length() > 128)) {
                response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
                response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                json.writeValue(response.getOutputStream(), new ApiError("BAD_REQUEST",
                        "Check the submitted data."));
                return;
            }
            LoginThrottle.Attempt attempt;
            try {
                attempt = throttle.begin(username, request.getRemoteAddr());
            } catch (DataAccessException ignored) {
                response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
                response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                json.writeValue(response.getOutputStream(), new ApiError("AUTH_UNAVAILABLE",
                        "Authentication is temporarily unavailable."));
                return;
            }
            request.setAttribute(THROTTLE_ATTEMPT, attempt);
            if (attempt.throttled()) {
                response.setStatus(429);
                response.setHeader("Retry-After", Integer.toString(attempt.retryAfterSeconds()));
                response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                json.writeValue(response.getOutputStream(), new ApiError("LOGIN_THROTTLED",
                        "Too many failed sign-in attempts. Try again later."));
                return;
            }
            chain.doFilter(request, response);
        } finally {
            loginLock.unlock();
        }
    }
}
