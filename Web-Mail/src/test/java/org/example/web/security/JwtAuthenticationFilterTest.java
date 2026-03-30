package org.example.web.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

public class JwtAuthenticationFilterTest {

    @Test
    public void doFilterInternal_UnprotectedPath_ShouldContinueWithoutRedirect() throws Exception {
        JwtAuthenticationFilter filter = new JwtAuthenticationFilter();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertEquals(200, response.getStatus());
        assertNull(response.getRedirectedUrl());
        assertEquals("/", ((MockHttpServletRequest) chain.getRequest()).getRequestURI());
    }

    @Test
    public void doFilterInternal_ProtectedPathWithoutToken_ShouldRedirectToLanding() throws Exception {
        JwtAuthenticationFilter filter = new JwtAuthenticationFilter();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/imap");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertEquals("/", response.getRedirectedUrl());
    }

    @Test
    public void doFilterInternal_ProtectedPathWithToken_ShouldContinue() throws Exception {
        JwtAuthenticationFilter filter = new JwtAuthenticationFilter();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/compose");
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("JWT_TOKEN", "token");
        request.setSession(session);

        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertNull(response.getRedirectedUrl());
        assertEquals("/compose", ((MockHttpServletRequest) chain.getRequest()).getRequestURI());
    }
}
