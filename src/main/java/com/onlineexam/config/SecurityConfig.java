package com.onlineexam.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.onlineexam.controller.ApiError;
import com.onlineexam.repository.UserRepository;
import com.onlineexam.security.DuplicateLoginAudit;
import com.onlineexam.security.ExamPrincipal;
import com.onlineexam.security.LoginSerializingFilter;
import com.onlineexam.security.LoginRegistrationLock;
import com.onlineexam.security.LoginThrottle;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.authentication.session.SessionAuthenticationException;
import org.springframework.security.web.authentication.session.SessionLimit;
import org.springframework.security.web.session.HttpSessionEventPublisher;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.core.session.SessionRegistryImpl;

@Configuration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class SecurityConfig {
    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, ObjectMapper json,
                                            SessionRegistry sessions, DuplicateLoginAudit audit,
                                            LoginThrottle throttle, LoginRegistrationLock loginLock)
            throws Exception {
        http.headers(headers -> headers.contentSecurityPolicy(csp -> csp.policyDirectives(
                        "default-src 'self'; script-src 'self'; style-src 'self'; "
                                + "img-src 'self' data:; connect-src 'self'; object-src 'none'; "
                                + "base-uri 'none'; frame-ancestors 'none'")))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/", "/index.html", "/login.html", "/home.html", "/instructor.html", "/student.html", "/exam.html", "/favicon.ico", "/assets/**",
                                "/css/**", "/js/**", "/actuator/health/**", "/api/ready",
                                "/api/csrf").permitAll()
                        .requestMatchers("/api/instructor/**").hasRole("INSTRUCTOR")
                        .requestMatchers("/api/**").authenticated()
                        .anyRequest().denyAll())
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint((request, response, cause) ->
                                error(json, response, 401, "UNAUTHENTICATED", "Sign in to continue."))
                        .accessDeniedHandler((request, response, cause) ->
                                error(json, response, 403, "FORBIDDEN", "The request is not allowed.")))
                .formLogin(form -> form
                        .loginProcessingUrl("/login")
                        .successHandler((request, response, authentication) -> {
                            throttle.success((LoginThrottle.Attempt) request.getAttribute(
                                    LoginSerializingFilter.THROTTLE_ATTEMPT));
                            response.setStatus(HttpServletResponse.SC_NO_CONTENT);
                        })
                        .failureHandler((request, response, cause) -> {
                            if (cause instanceof SessionAuthenticationException) {
                                try {
                                    audit.record(request.getParameter("username"));
                                } catch (RuntimeException ignored) {
                                    error(json, response, 503, "AUTH_UNAVAILABLE",
                                            "Authentication is temporarily unavailable.");
                                    return;
                                }
                                error(json, response, 409, "SESSION_IN_USE",
                                        "This student account is already signed in.");
                            } else if (cause instanceof AuthenticationServiceException) {
                                error(json, response, 503, "AUTH_UNAVAILABLE",
                                        "Authentication is temporarily unavailable.");
                            } else {
                                throttle.credentialFailure((LoginThrottle.Attempt) request.getAttribute(
                                        LoginSerializingFilter.THROTTLE_ATTEMPT));
                                error(json, response, 401, "BAD_CREDENTIALS",
                                        "Username or password is incorrect.");
                            }
                        }))
                .logout(logout -> logout
                        .logoutUrl("/logout")
                        .invalidateHttpSession(true)
                        .deleteCookies("JSESSIONID")
                        .logoutSuccessHandler((request, response, authentication) ->
                                response.setStatus(HttpServletResponse.SC_NO_CONTENT)))
                .sessionManagement(session -> session
                        .sessionFixation(fixation -> fixation.changeSessionId())
                        .maximumSessions(1)
                        .maximumSessions((SessionLimit) authentication -> authentication.getAuthorities().contains(
                                new SimpleGrantedAuthority("ROLE_STUDENT")) ? 1 : -1)
                        .maxSessionsPreventsLogin(true)
                        .sessionRegistry(sessions)
                        .expiredSessionStrategy(event -> error(json, event.getResponse(), 401,
                                "SESSION_EXPIRED", "This session has ended. Sign in again.")))
                .addFilterBefore(new LoginSerializingFilter(throttle, json, loginLock),
                        UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    SessionRegistry sessionRegistry() {
        return new SessionRegistryImpl();
    }

    @Bean
    LoginRegistrationLock loginRegistrationLock() {
        return new LoginRegistrationLock();
    }

    @Bean
    HttpSessionEventPublisher httpSessionEventPublisher() {
        return new HttpSessionEventPublisher();
    }

    @Bean
    UserDetailsService userDetailsService(UserRepository users) {
        return username -> {
            try {
                return users.findByUsername(username)
                        .map(ExamPrincipal::from)
                        .orElseThrow(() -> new UsernameNotFoundException("Account unavailable"));
            } catch (DataAccessException ignored) {
                // The filter logs authentication service exceptions. Do not pass
                // JDBC exception messages or their causes into that log path.
                throw new AuthenticationServiceException("Authentication store unavailable");
            }
        };
    }

    private static void error(ObjectMapper json, HttpServletResponse response, int status,
                              String code, String message) throws java.io.IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        json.writeValue(response.getOutputStream(), new ApiError(code, message));
    }
}
