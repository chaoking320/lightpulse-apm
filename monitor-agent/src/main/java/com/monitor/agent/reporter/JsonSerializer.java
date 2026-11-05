package com.monitor.agent.reporter;

import com.monitor.agent.model.ExceptionRecord;
import com.monitor.agent.model.MetricsRecord;
import com.monitor.agent.model.SqlRecord;

import java.util.List;

/**
 * 极简 JSON 序列化器（无外部依赖）
 */
public class JsonSerializer {

    public static String serializeMetrics(List<Object> records) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"metrics\":[");
        boolean first = true;
        for (Object obj : records) {
            if (!first) sb.append(",");
            first = false;
            if (obj instanceof MetricsRecord) {
                MetricsRecord m = (MetricsRecord) obj;
                sb.append("{");
                appendStr(sb, "appName", m.getAppName());
                sb.append(",");
                appendStr(sb, "className", m.getClassName());
                sb.append(",");
                appendStr(sb, "methodName", m.getMethodName());
                sb.append(",");
                appendNum(sb, "costMs", m.getCostMs());
                sb.append(",");
                appendNum(sb, "timestamp", m.getTimestamp());
                sb.append(",");
                appendBool(sb, "success", m.isSuccess());
                sb.append(",");
                appendStr(sb, "traceId", m.getTraceId());
                if (m.getErrorMsg() != null) {
                    sb.append(",");
                    String em = m.getErrorMsg();
                    if (em.length() > 1000) em = em.substring(0, 1000);
                    appendStr(sb, "errorMsg", em);
                }
                sb.append("}");
            }
        }
        sb.append("]}");
        return sb.toString();
    }

    private static void appendStr(StringBuilder sb, String key, String value) {
        sb.append("\"").append(key).append("\":\"");
        if (value != null) {
            sb.append(escape(value));
        }
        sb.append("\"");
    }

    /**
     * JSON 字符串转义（RFC 8259）：反斜杠、双引号以及所有 U+0000~U+001F 控制字符。
     * 之前只转义了 \ 和 "，errorMsg 含换行时会生成非法 JSON，导致整批上报被服务端拒绝。
     */
    public static String escape(String s) {
        if (s == null) return "";
        StringBuilder out = null;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            String rep = null;
            switch (c) {
                case '\\': rep = "\\\\"; break;
                case '"':  rep = "\\\""; break;
                case '\n': rep = "\\n"; break;
                case '\r': rep = "\\r"; break;
                case '\t': rep = "\\t"; break;
                case '\b': rep = "\\b"; break;
                case '\f': rep = "\\f"; break;
                default:
                    if (c < 0x20) rep = String.format("\\u%04x", (int) c);
            }
            if (rep != null) {
                if (out == null) { out = new StringBuilder(s.length() + 16); out.append(s, 0, i); }
                out.append(rep);
            } else if (out != null) {
                out.append(c);
            }
        }
        return out == null ? s : out.toString();
    }

    private static void appendNum(StringBuilder sb, String key, long value) {
        sb.append("\"").append(key).append("\":").append(value);
    }

    private static void appendBool(StringBuilder sb, String key, boolean value) {
        sb.append("\"").append(key).append("\":").append(value);
    }
}
