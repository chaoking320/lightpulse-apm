package com.monitor.server.util;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 极简、零依赖、高性能 JSONPath 解析与业务规则断言引擎
 * 支持字段导航 ($.data.time)、数组下标 (items[0])、时间延迟计算与数值比对
 */
public class SimpleJsonPath {

    private static final DateTimeFormatter[] DATE_FORMATTERS = new DateTimeFormatter[]{
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd")
    };

    public static class AssertResult {
        private final boolean passed;
        private final String message;
        private final String extractedValue;

        public AssertResult(boolean passed, String message, String extractedValue) {
            this.passed = passed;
            this.message = message;
            this.extractedValue = extractedValue;
        }

        public boolean isPassed() { return passed; }
        public String getMessage() { return message; }
        public String getExtractedValue() { return extractedValue; }
    }

    public static Object extract(String json, String path) {
        if (json == null || json.isBlank() || path == null || path.isBlank()) {
            return null;
        }

        String normalizedPath = path.trim();
        if (normalizedPath.startsWith("$.")) {
            normalizedPath = normalizedPath.substring(2);
        } else if (normalizedPath.startsWith("$")) {
            normalizedPath = normalizedPath.substring(1);
        }
        if (normalizedPath.startsWith(".")) {
            normalizedPath = normalizedPath.substring(1);
        }

        try {
            Object root = parseJson(json.trim());
            if (normalizedPath.isEmpty()) {
                return root;
            }

            List<String> segments = parsePathSegments(normalizedPath);
            Object current = root;

            for (String seg : segments) {
                if (current == null) return null;
                if (seg.startsWith("[") && seg.endsWith("]")) {
                    int index = Integer.parseInt(seg.substring(1, seg.length() - 1));
                    if (current instanceof List<?> list) {
                        if (index >= 0 && index < list.size()) {
                            current = list.get(index);
                        } else {
                            return null;
                        }
                    } else {
                        return null;
                    }
                } else {
                    if (current instanceof Map<?, ?> map) {
                        current = map.get(seg);
                    } else {
                        return null;
                    }
                }
            }
            return current;
        } catch (Exception e) {
            return null;
        }
    }

    public static AssertResult evaluate(String jsonBody, String path, String operator, String expected) {
        if (path == null || path.isBlank()) {
            return new AssertResult(true, "未配置 JSONPath 规则", "");
        }

        Object extracted = extract(jsonBody, path);
        String extractedStr = extracted != null ? String.valueOf(extracted) : "null";

        if (extracted == null) {
            return new AssertResult(false, "JSONPath [" + path + "] 提取失败: 未在响应体中找到对应字段", extractedStr);
        }

        String op = (operator != null && !operator.isBlank()) ? operator.trim().toUpperCase() : "EQUALS";
        String exp = expected != null ? expected.trim() : "";

        try {
            switch (op) {
                case "DELAY_MINUTES_GT" -> {
                    long timestampMs = parseToTimestamp(extracted);
                    if (timestampMs <= 0) {
                        return new AssertResult(false, "无法解析字段 [" + path + "] 为有效时间戳/日期: " + extractedStr, extractedStr);
                    }
                    long now = System.currentTimeMillis();
                    long diffMinutes = Math.max(0, (now - timestampMs) / 60000);
                    double threshold = Double.parseDouble(exp);
                    if (diffMinutes > threshold) {
                        return new AssertResult(false,
                                String.format("业务延迟告警: [%s] 提取时间距今相差 %d 分钟，超过设定阈值 (%.0f 分钟)", path, diffMinutes, threshold),
                                extractedStr + " (延迟" + diffMinutes + "m)");
                    }
                    return new AssertResult(true, "业务延迟正常 (当前相差 " + diffMinutes + " 分钟 <= 阈值 " + threshold + " 分钟)",
                            extractedStr + " (延迟" + diffMinutes + "m)");
                }
                case "DELAY_SECONDS_GT" -> {
                    long timestampMs = parseToTimestamp(extracted);
                    if (timestampMs <= 0) {
                        return new AssertResult(false, "无法解析字段 [" + path + "] 为有效时间戳/日期: " + extractedStr, extractedStr);
                    }
                    long now = System.currentTimeMillis();
                    long diffSeconds = Math.max(0, (now - timestampMs) / 1000);
                    double threshold = Double.parseDouble(exp);
                    if (diffSeconds > threshold) {
                        return new AssertResult(false,
                                String.format("业务延迟告警: [%s] 提取时间距今相差 %d 秒，超过设定阈值 (%.0f 秒)", path, diffSeconds, threshold),
                                extractedStr + " (延迟" + diffSeconds + "s)");
                    }
                    return new AssertResult(true, "业务延迟正常 (当前相差 " + diffSeconds + " 秒 <= 阈值 " + threshold + " 秒)",
                            extractedStr + " (延迟" + diffSeconds + "s)");
                }
                case "NUM_GT", "GT" -> {
                    double actualNum = Double.parseDouble(extractedStr);
                    double threshold = Double.parseDouble(exp);
                    if (actualNum > threshold) {
                        return new AssertResult(false,
                                String.format("数值过高告警: [%s] 实际数值 %s 超过预警阈值 %s", path, extractedStr, exp),
                                extractedStr);
                    }
                    return new AssertResult(true, "数值正常 (实际 " + extractedStr + " <= 阈值 " + exp + ")", extractedStr);
                }
                case "NUM_GTE", "GTE" -> {
                    double actualNum = Double.parseDouble(extractedStr);
                    double threshold = Double.parseDouble(exp);
                    if (actualNum >= threshold) {
                        return new AssertResult(false,
                                String.format("数值过高告警: [%s] 实际数值 %s 达到或超过预警阈值 %s", path, extractedStr, exp),
                                extractedStr);
                    }
                    return new AssertResult(true, "数值正常 (实际 " + extractedStr + " < 阈值 " + exp + ")", extractedStr);
                }
                case "NUM_LT", "LT" -> {
                    double actualNum = Double.parseDouble(extractedStr);
                    double threshold = Double.parseDouble(exp);
                    if (actualNum < threshold) {
                        return new AssertResult(false,
                                String.format("数值过低告警: [%s] 实际数值 %s 低于预警阈值 %s", path, extractedStr, exp),
                                extractedStr);
                    }
                    return new AssertResult(true, "数值正常 (实际 " + extractedStr + " >= 阈值 " + exp + ")", extractedStr);
                }
                case "NUM_LTE", "LTE" -> {
                    double actualNum = Double.parseDouble(extractedStr);
                    double threshold = Double.parseDouble(exp);
                    if (actualNum <= threshold) {
                        return new AssertResult(false,
                                String.format("数值过低告警: [%s] 实际数值 %s 达到或低于预警阈值 %s", path, extractedStr, exp),
                                extractedStr);
                    }
                    return new AssertResult(true, "数值正常 (实际 " + extractedStr + " > 阈值 " + exp + ")", extractedStr);
                }
                case "NOT_EQUALS", "NEQ" -> {
                    if (extractedStr.equalsIgnoreCase(exp)) {
                        return new AssertResult(false,
                                String.format("值匹配异常告警: [%s] 实际值与禁止值 [%s] 相同", path, exp),
                                extractedStr);
                    }
                    return new AssertResult(true, "值校验通过 (实际 " + extractedStr + " != " + exp + ")", extractedStr);
                }
                case "EQUALS", "EQ" -> {
                    if (!extractedStr.equalsIgnoreCase(exp)) {
                        return new AssertResult(false,
                                String.format("值不匹配告警: [%s] 实际值 [%s] 不等于期望值 [%s]", path, extractedStr, exp),
                                extractedStr);
                    }
                    return new AssertResult(true, "值校验匹配成功 (" + extractedStr + " == " + exp + ")", extractedStr);
                }
                case "NOT_CONTAINS" -> {
                    if (extractedStr.contains(exp)) {
                        return new AssertResult(false,
                                String.format("内容异常告警: [%s] 实际文本包含禁止字符 [%s]", path, exp),
                                extractedStr);
                    }
                    return new AssertResult(true, "文本不包含禁止字符校验通过", extractedStr);
                }
                default -> {
                    if (!extractedStr.contains(exp)) {
                        return new AssertResult(false,
                                String.format("内容缺失告警: [%s] 实际文本未包含期望字符 [%s]", path, exp),
                                extractedStr);
                    }
                    return new AssertResult(true, "文本包含期望字符校验通过", extractedStr);
                }
            }
        } catch (Exception e) {
            return new AssertResult(false, "断言计算异常: " + e.getMessage(), extractedStr);
        }
    }

    public static long parseToTimestamp(Object val) {
        if (val == null) return -1;
        if (val instanceof Number num) {
            long l = num.longValue();
            if (l < 10000000000L) {
                return l * 1000L;
            }
            return l;
        }

        String str = String.valueOf(val).trim();
        if (str.isEmpty() || "null".equalsIgnoreCase(str)) return -1;

        if (str.matches("^\\d+$")) {
            long l = Long.parseLong(str);
            if (l < 10000000000L) return l * 1000L;
            return l;
        }

        try {
            return Instant.parse(str).toEpochMilli();
        } catch (Exception ignored) {}

        for (DateTimeFormatter fmt : DATE_FORMATTERS) {
            try {
                LocalDateTime ldt = LocalDateTime.parse(str, fmt);
                return ldt.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
            } catch (Exception ignored) {}
        }

        return -1;
    }

    private static List<String> parsePathSegments(String path) {
        List<String> list = new ArrayList<>();
        Pattern p = Pattern.compile("([^\\[\\].]+)|\\[(\\d+)\\]");
        Matcher m = p.matcher(path);
        while (m.find()) {
            if (m.group(1) != null) {
                list.add(m.group(1));
            } else if (m.group(2) != null) {
                list.add("[" + m.group(2) + "]");
            }
        }
        return list;
    }

    public static Object parseJson(String text) {
        if (text == null) return null;
        text = text.trim();
        if (text.isEmpty()) return null;
        return new JsonParser(text).parse();
    }

    private static class JsonParser {
        private final String src;
        private int pos = 0;

        public JsonParser(String src) {
            this.src = src;
        }

        public Object parse() {
            skipWhitespace();
            if (pos >= src.length()) return null;
            char c = src.charAt(pos);
            if (c == '{') return parseObject();
            if (c == '[') return parseArray();
            if (c == '"' || c == '\'') return parseString();
            if (c == 't' || c == 'f') return parseBoolean();
            if (c == 'n') return parseNull();
            return parseNumber();
        }

        private Map<String, Object> parseObject() {
            Map<String, Object> map = new LinkedHashMap<>();
            pos++;
            skipWhitespace();
            if (peek('}')) { pos++; return map; }

            while (pos < src.length()) {
                skipWhitespace();
                String key = parseString();
                skipWhitespace();
                if (!peek(':')) break;
                pos++;
                skipWhitespace();
                Object val = parse();
                map.put(key, val);
                skipWhitespace();
                if (peek(',')) {
                    pos++;
                } else if (peek('}')) {
                    pos++;
                    break;
                } else {
                    break;
                }
            }
            return map;
        }

        private List<Object> parseArray() {
            List<Object> list = new ArrayList<>();
            pos++;
            skipWhitespace();
            if (peek(']')) { pos++; return list; }

            while (pos < src.length()) {
                skipWhitespace();
                Object val = parse();
                list.add(val);
                skipWhitespace();
                if (peek(',')) {
                    pos++;
                } else if (peek(']')) {
                    pos++;
                    break;
                } else {
                    break;
                }
            }
            return list;
        }

        private String parseString() {
            if (pos >= src.length()) return "";
            char quote = src.charAt(pos);
            if (quote != '"' && quote != '\'') return "";
            pos++;
            StringBuilder sb = new StringBuilder();
            while (pos < src.length()) {
                char c = src.charAt(pos++);
                if (c == quote) {
                    return sb.toString();
                } else if (c == '\\' && pos < src.length()) {
                    char esc = src.charAt(pos++);
                    switch (esc) {
                        case '"' -> sb.append('"');
                        case '\'' -> sb.append('\'');
                        case '\\' -> sb.append('\\');
                        case '/' -> sb.append('/');
                        case 'b' -> sb.append('\b');
                        case 'f' -> sb.append('\f');
                        case 'n' -> sb.append('\n');
                        case 'r' -> sb.append('\r');
                        case 't' -> sb.append('\t');
                        case 'u' -> {
                            if (pos + 4 <= src.length()) {
                                String hex = src.substring(pos, pos + 4);
                                try {
                                    sb.append((char) Integer.parseInt(hex, 16));
                                    pos += 4;
                                } catch (Exception ignored) {
                                    sb.append("\\u").append(hex);
                                }
                            }
                        }
                        default -> sb.append(esc);
                    }
                } else {
                    sb.append(c);
                }
            }
            return sb.toString();
        }

        private Object parseNumber() {
            int start = pos;
            if (peek('-') || peek('+')) pos++;
            while (pos < src.length() && (Character.isDigit(src.charAt(pos)) || src.charAt(pos) == '.' || src.charAt(pos) == 'e' || src.charAt(pos) == 'E' || src.charAt(pos) == '+' || src.charAt(pos) == '-')) {
                pos++;
            }
            String raw = src.substring(start, pos);
            try {
                if (raw.contains(".") || raw.contains("e") || raw.contains("E")) {
                    return Double.parseDouble(raw);
                } else {
                    return Long.parseLong(raw);
                }
            } catch (Exception e) {
                return raw;
            }
        }

        private Boolean parseBoolean() {
            if (src.startsWith("true", pos)) { pos += 4; return Boolean.TRUE; }
            if (src.startsWith("false", pos)) { pos += 5; return Boolean.FALSE; }
            return Boolean.FALSE;
        }

        private Object parseNull() {
            if (src.startsWith("null", pos)) { pos += 4; return null; }
            return null;
        }

        private void skipWhitespace() {
            while (pos < src.length() && Character.isWhitespace(src.charAt(pos))) {
                pos++;
            }
        }

        private boolean peek(char expected) {
            return pos < src.length() && src.charAt(pos) == expected;
        }
    }
}
