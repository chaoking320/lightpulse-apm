package com.monitor.agent.config;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ConfigFetcherParseTest {

    @Test
    void nullValueIsSkippedInsteadOfAbortingWholeConfig() {
        // 服务端 Jackson 默认会输出 null（如策略的 methodMatch 为空），以前会抛 NumberFormatException，整份配置被静默丢弃
        Map<String, Object> m = ConfigFetcher.parseSimpleJson(
                "{\"samplingRate\":0.5,\"c0_methodMatch\":null,\"captureCount\":1,\"c0_matchType\":\"annotation\"}");
        assertEquals(0.5, (Double) m.get("samplingRate"), 1e-9);
        assertFalse(m.containsKey("c0_methodMatch"));
        assertEquals(1L, m.get("captureCount"));
        assertEquals("annotation", m.get("c0_matchType"));
    }

    @Test
    void escapedQuotesAndBackslashesInStringsAreHandled() {
        Map<String, Object> m = ConfigFetcher.parseSimpleJson(
                "{\"a\":\"x\\\"y\",\"b\":\"c:\\\\dir\",\"c\":\"line1\\nline2\",\"d\":\"after\"}");
        assertEquals("x\"y", m.get("a"));
        assertEquals("c:\\dir", m.get("b"));
        assertEquals("line1\nline2", m.get("c"));
        assertEquals("after", m.get("d"));
    }

    @Test
    void unicodeEscapeIsDecoded() {
        Map<String, Object> m = ConfigFetcher.parseSimpleJson("{\"a\":\"\\u4e2d\\u6587\"}");
        assertEquals("中文", m.get("a"));
    }

    @Test
    void unparsableNumberOnlyAffectsThatField() {
        Map<String, Object> m = ConfigFetcher.parseSimpleJson("{\"bad\":12abc,\"good\":7,\"flag\":true}");
        assertFalse(m.containsKey("bad"));
        assertEquals(7L, m.get("good"));
        assertEquals(Boolean.TRUE, m.get("flag"));
    }

    @Test
    void scientificNotationIsParsedAsDouble() {
        Map<String, Object> m = ConfigFetcher.parseSimpleJson("{\"v\":1.5E3}");
        assertEquals(1500.0, (Double) m.get("v"), 1e-9);
    }

    @Test
    void nestedStructuresAreKeptAsRawText() {
        Map<String, Object> m = ConfigFetcher.parseSimpleJson("{\"list\":[{\"a\":1},{\"a\":2}],\"n\":3}");
        assertEquals("[{\"a\":1},{\"a\":2}]", m.get("list"));
        assertEquals(3L, m.get("n"));
    }
}
