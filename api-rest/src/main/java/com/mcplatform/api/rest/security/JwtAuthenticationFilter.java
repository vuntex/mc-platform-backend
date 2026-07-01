package com.mcplatform.api.rest.security;

import com.mcplatform.application.webauth.port.AccessTokenInvalidException;
import com.mcplatform.application.webauth.port.TokenVerifier;
import com.mcplatform.domain.player.PlayerId;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collections;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Isolated authentication filter: pulls the UUID out of a {@code Authorization: Bearer} access token and
 * puts it into the request's SecurityContext as the principal — nothing more. It depends ONLY on the
 * {@link TokenVerifier} port (the JWT library stays in {@code app}). Authorities are EMPTY on purpose:
 * the filter establishes identity, never rights — authorization is the PermissionResolver's job at
 * request time (Constitution §12). A missing/invalid token leaves the context empty; the
 * SecurityFilterChain then rejects protected paths with 401. Feature controllers know nothing of this.
 */
public final class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";

    private final TokenVerifier tokenVerifier;

    public JwtAuthenticationFilter(TokenVerifier tokenVerifier) {
        this.tokenVerifier = tokenVerifier;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith(BEARER_PREFIX)
                && SecurityContextHolder.getContext().getAuthentication() == null) {
            String token = header.substring(BEARER_PREFIX.length());
            try {
                PlayerId player = tokenVerifier.verify(token);
                var authentication = new UsernamePasswordAuthenticationToken(
                        player, null, Collections.emptyList()); // empty authorities = identity only
                SecurityContextHolder.getContext().setAuthentication(authentication);
            } catch (AccessTokenInvalidException e) {
                SecurityContextHolder.clearContext(); // leave unauthenticated; chain handles 401
            }
        }
        chain.doFilter(request, response);
    }

    /**
     * Also run on ASYNC dispatches. Async endpoints (e.g. the economy SSE {@code SseEmitter}) re-enter
     * the filter chain on an async dispatch where the {@code AuthorizationFilter} runs again; the
     * default OncePerRequestFilter behaviour SKIPS this filter on async, leaving an empty SecurityContext
     * so authorization denies a still-valid request (the SSE response is already committed → the 403 can't
     * even be written). This filter is stateless (re-reads the Bearer header), so running it on the async
     * dispatch safely re-establishes identity and keeps the stream authorized.
     */
    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        return false;
    }
}
