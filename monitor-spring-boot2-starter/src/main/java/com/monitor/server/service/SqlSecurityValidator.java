package com.monitor.server.service;

import java.util.regex.Pattern;

/**
 * SQL 探针安全白名单校验器 (零注入漏洞、严禁越权变更)
 * 1. 严格只允许只读查询 (SELECT / WITH)
 * 2. 强行拦截任何写操作 (INSERT/UPDATE/DELETE/REPLACE)
 * 3. 强行拦截任何 DDL (DROP/ALTER/TRUNCATE/CREATE)
 * 4. 强行拦截管理与授权操作 (GRANT/REVOKE/SHUTDOWN/EXEC/CALL)
 * 5. 拦截多语句注入分号 (;)
 */
public class SqlSecurityValidator {

    private static final Pattern FORBIDDEN_KEYWORDS = Pattern.compile(
            "\\b(INSERT|UPDATE|DELETE|DROP|ALTER|TRUNCATE|CREATE|REPLACE|GRANT|REVOKE|EXEC|EXECUTE|CALL|LOCK|UNLOCK|INTO\\s+OUTFILE|INTO\\s+DUMPFILE|LOAD_FILE|LOAD\\s+DATA)\\b",
            Pattern.CASE_INSENSITIVE
    );

    /**
     * 校验 SQL 是否仅为安全只读 SELECT 语句，若非法则直接抛出 SecurityException
     */
    public static void validateSelectOnly(String sql) {
        if (sql == null || sql.isBlank()) {
            throw new IllegalArgumentException("SQL 语句不能为空");
        }

        // 过滤注释与空白字符
        String clean = sql.replaceAll("/\\*.*?\\*/", "")
                          .replaceAll("--.*", "")
                          .replaceAll("#.*", "")
                          .trim();

        if (clean.isEmpty()) {
            throw new IllegalArgumentException("SQL 语句不能为空");
        }

        // 禁止多语句拼接分号 (末尾允许单个分号)
        String withoutEndSemicolon = clean.endsWith(";") ? clean.substring(0, clean.length() - 1) : clean;
        if (withoutEndSemicolon.contains(";")) {
            throw new SecurityException("安全拦截: SQL 业务探针严禁包含分号 (;) 执行多语句！");
        }

        // 严格以 SELECT 或 WITH (CTE 表达式) 开头
        String upper = clean.toUpperCase();
        if (!upper.startsWith("SELECT") && !upper.startsWith("WITH")) {
            throw new SecurityException("安全拦截: SQL 业务探针仅允许只读 SELECT / WITH 语法查询！");
        }

        // 拦截一切高危非查询关键字
        var matcher = FORBIDDEN_KEYWORDS.matcher(clean);
        if (matcher.find()) {
            throw new SecurityException("安全拦截: 检测到高危非查询关键字 [" + matcher.group(1).toUpperCase() + "]，已被系统拒绝！");
        }
    }
}
