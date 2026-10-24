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
                    appendStr(sb, "errorMsg", m.getErrorMsg());
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
            sb.append(value.replace("\\", "\\\\").replace("\"", "\\\""));
        }
        sb.append("\"");
    }

    private static void appendNum(StringBuilder sb, String key, long value) {
        sb.append("\"").append(key).append("\":").append(value);
    }

    private static void appendBool(StringBuilder sb, String key, boolean value) {
        sb.append("\"").append(key).append("\":").append(value);
    }
}
