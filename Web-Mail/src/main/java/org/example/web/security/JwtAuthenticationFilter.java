package org.example.web.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

public class JwtAuthenticationFilter extends OncePerRequestFilter {
    private static final String JWT_SESSION_KEY = "JWT_TOKEN";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String path = request.getRequestURI();

        boolean protectedPath = path.startsWith("/imap")
                || path.startsWith("/pop3")
                || path.startsWith("/compose");

        if (!protectedPath) {
            filterChain.doFilter(request, response);
            return;
        }

        HttpSession session = request.getSession(false);
        String token = session == null ? null : (String) session.getAttribute(JWT_SESSION_KEY);
        if (token == null || token.isBlank()) {
            response.sendRedirect("/");
            return;
        }

        filterChain.doFilter(request, response);
    }
}
