package com.monitor.agent.reporter;

import com.monitor.agent.model.MetricsRecord;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class JsonSerializerTest {

    @Test
    void escapeHandlesQuotesBackslashAndControlChars() {
        assertEquals("a\\nb", JsonSerializer.escape("a\nb"));
        assertEquals("a\\r\\nb", JsonSerializer.escape("a\r\nb"));
        assertEquals("tab\\there", JsonSerializer.escape("tab\there"));
        assertEquals("say \\\"hi\\\"", JsonSerializer.escape("say \"hi\""));
        assertEquals("c:\\\\dir", JsonSerializer.escape("c:\\dir"));
        assertEquals("\\u0001", JsonSerializer.escape("\u0001"));
        assertEquals("\\b\\f", JsonSerializer.escape("\b\f"));
    }

    @Test
    void escapeLeavesPlainAndUnicodeTextUntouched() {
        String s = "普通文本 abc 123";
        assertSame(s, JsonSerializer.escape(s));
        assertEquals("", JsonSerializer.escape(null));
    }

    @Test
    void serializedMetricsNeverContainRawControlCharacters() {
        MetricsRecord m = new MetricsRecord();
        m.setAppName("app");
        m.setClassName("com.x.Y");
        m.setMethodName("run");
        m.setCostMs(12);
        m.setTimestamp(1L);
        m.setSuccess(false);
        m.setTraceId("t1");
        // 多行异常信息：之前会产生非法 JSON，整批被服务端拒绝
        m.setErrorMsg("java.sql.SQLException: bad\nsql \"x\"\r\n\tat line");

        List<Object> batch = new ArrayList<>();
        batch.add(m);
        String json = JsonSerializer.serializeMetrics(batch);

        for (char c : json.toCharArray()) {
            assertTrue(c >= 0x20, "JSON 中不应出现未转义的控制字符: " + (int) c);
        }
        assertTrue(json.contains("bad\\nsql \\\"x\\\"\\r\\n\\tat line"));
    }

    @Test
    void overlongErrorMessageIsTruncated() {
        MetricsRecord m = new MetricsRecord();
        m.setAppName("app");
        m.setSuccess(false);
        m.setErrorMsg("e".repeat(5000));
        List<Object> batch = new ArrayList<>();
        batch.add(m);
        String json = JsonSerializer.serializeMetrics(batch);
        assertTrue(json.length() < 2000);
    }
}
