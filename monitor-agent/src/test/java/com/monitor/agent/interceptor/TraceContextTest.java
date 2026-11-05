package com.monitor.agent.interceptor;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TraceContextTest {

    @AfterEach
    void cleanup() {
        TraceContext.clear();
    }

    @Test
    void nestedCallsKeepTraceIdUntilOutermostExits() {
        // 外层方法进入
        String outerId = TraceContext.getOrCreate();
        TraceContext.enterMethod();
        // 内层方法进入（同一线程，同一 TraceID）
        assertEquals(outerId, TraceContext.getOrCreate());
        TraceContext.enterMethod();

        // 内层退出：不是最外层，不应清理
        assertFalse(TraceContext.exitMethod());
        assertEquals(outerId, TraceContext.get());

        // 外层退出：最外层，可以清理
        assertTrue(TraceContext.exitMethod());
        TraceContext.clear();
        assertNull(TraceContext.get());
    }

    @Test
    void unbalancedExitDoesNotGoNegative() {
        assertTrue(TraceContext.exitMethod());
        TraceContext.enterMethod();
        assertTrue(TraceContext.exitMethod());
    }
}
