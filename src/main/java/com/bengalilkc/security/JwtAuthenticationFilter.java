package com.bengalilkc.security;

import com.bengalilkc.config.LkcProperties;
import com.bengalilkc.domain.entity.identity.AppUser;
import com.bengalilkc.domain.entity.identity.Credential;
import com.bengalilkc.domain.enums.Role;
import com.bengalilkc.repository.identity.AppUserRepository;
import com.bengalilkc.repository.identity.CredentialRepository;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtService jwtService;
    private final LkcProperties properties;
    private final AppUserRepository userRepository;
    private final CredentialRepository credentialRepository;

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        if (SecurityContextHolder.getContext().getAuthentication() == null) {
            String token = resolveToken(request);
            if (token != null) {
                try {
                    Claims claims = jwtService.parse(token);
                    String userId = claims.getSubject();
                    AppUser user = userRepository.findById(userId).orElse(null);
                    Credential credential = credentialRepository.findByUserId(userId).orElse(null);
                    if (user != null
                            && user.isActive()
                            && !user.isSuspended()
                            && credential != null
                            && !credential.isDeactivated()) {
                        boolean mustChange = credential.isOneTime()
                                || Boolean.TRUE.equals(claims.get("mustChangePassword", Boolean.class));
                        UserPrincipal principal = new UserPrincipal(
                                user.getId(),
                                user.getUsername(),
                                user.getName(),
                                credential.getPasswordHash(),
                                user.getRole(),
                                user.isActive(),
                                user.isSuspended(),
                                mustChange);
                        UsernamePasswordAuthenticationToken auth =
                                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
                        auth.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                        SecurityContextHolder.getContext().setAuthentication(auth);
                    }
                } catch (Exception ignored) {
                    SecurityContextHolder.clearContext();
                }
            }
        }
        filterChain.doFilter(request, response);
    }

    private String resolveToken(HttpServletRequest request) {
        LkcProperties.Jwt jwt = properties.getSecurity().getJwt();
        String header = request.getHeader(jwt.getHeaderName() != null ? jwt.getHeaderName() : HttpHeaders.AUTHORIZATION);
        if (header != null && header.startsWith(jwt.getTokenPrefix())) {
            return header.substring(jwt.getTokenPrefix().length()).trim();
        }
        Cookie[] cookies = request.getCookies();
        if (cookies != null) {
            for (Cookie cookie : cookies) {
                if (jwt.getCookieName().equals(cookie.getName())) {
                    String value = cookie.getValue();
                    return (value == null || value.isBlank()) ? null : value;
                }
            }
        }
        return null;
    }
}
