package com.monitor.server.config;

import com.monitor.server.service.AuthService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AuthInterceptorAgentTokenTest {

    private static final String DEFAULT_TOKEN = "lp-agent-secret-token";

    private boolean call(String configured, boolean strict, String headerToken, MockHttpServletResponse resp) throws Exception {
        AuthConfig cfg = new AuthConfig();
        cfg.setAgentSecretToken(configured);
        cfg.setStrictAgentAuth(strict);
        AuthService authService = mock(AuthService.class);
        when(authService.getAuthConfig()).thenReturn(cfg);

        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/agent/metrics");
        if (headerToken != null) {
            req.addHeader("X-Agent-Token", headerToken);
        }
        return new AuthInterceptor(authService).preHandle(req, resp, new Object());
    }

    @Test
    void defaultOrBlankTokenKeepsLegacyOpenBehavior() throws Exception {
        assertTrue(call("", false, null, new MockHttpServletResponse()));
        assertTrue(call(DEFAULT_TOKEN, false, null, new MockHttpServletResponse()));
        assertTrue(call(null, false, null, new MockHttpServletResponse()));
    }

    @Test
    void strictModeIsIgnoredWhenNoCustomTokenConfigured() throws Exception {
        // 没配自定义 Token 就开严格模式，不能把所有 Agent 锁在门外
        assertTrue(call("", true, null, new MockHttpServletResponse()));
        assertTrue(call(DEFAULT_TOKEN, true, null, new MockHttpServletResponse()));
    }

    @Test
    void customTokenRequiresHeaderButLegacyStillAcceptsDefaultToken() throws Exception {
        MockHttpServletResponse missing = new MockHttpServletResponse();
        assertFalse(call("s3cret", false, null, missing));
        assertEquals(401, missing.getStatus());

        assertTrue(call("s3cret", false, "s3cret", new MockHttpServletResponse()));
        // 旧行为：非严格模式下默认 Token 仍被接受（升级后不影响现有 Agent）
        assertTrue(call("s3cret", false, DEFAULT_TOKEN, new MockHttpServletResponse()));
    }

    @Test
    void strictModeRejectsDefaultTokenAndWrongToken() throws Exception {
        MockHttpServletResponse viaDefault = new MockHttpServletResponse();
        assertFalse(call("s3cret", true, DEFAULT_TOKEN, viaDefault));
        assertEquals(401, viaDefault.getStatus());

        assertFalse(call("s3cret", true, "wrong", new MockHttpServletResponse()));
        assertFalse(call("s3cret", true, null, new MockHttpServletResponse()));
        assertTrue(call("s3cret", true, "s3cret", new MockHttpServletResponse()));
    }
}
