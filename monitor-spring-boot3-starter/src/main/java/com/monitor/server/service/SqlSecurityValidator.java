package com.monitor.server.service;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SQL 探针安全白名单校验器 (零注入漏洞、严禁越权变更)
 * 1. 严格只允许只读查询 (SELECT / WITH)
 * 2. 强行拦截任何写操作 (INSERT/UPDATE/DELETE/REPLACE)
 * 3. 强行拦截任何 DDL (DROP/ALTER/TRUNCATE/CREATE)
 * 4. 强行拦截管理与授权操作 (GRANT/REVOKE/SHUTDOWN/EXEC/CALL)
 * 5. 拦截多语句注入分号 (;)
 * 6. 拦截加锁与耗时型函数 (FOR UPDATE / SLEEP / BENCHMARK / GET_LOCK 等)，防止探针拖垮目标库
 * 7. 拦截 MySQL 可执行注释 (/*! ... *&#47;)，其内容会被数据库真实执行却容易被误当作注释
 *
 * 校验前先用带引号感知的扫描器去掉注释并抹掉字符串字面量内容：
 * 既不会因为字面量里出现 update 之类的普通文本而误杀，也不会被 'a--' 这类写法把后面的语句藏进"注释"里。
 * 由于不同数据库对反斜杠转义的处理不同，同一条 SQL 会按"反斜杠转义 / 不转义"两种语义各扫描一次，任意一种不通过即拒绝。
 */
public class SqlSecurityValidator {

    private static final Pattern FORBIDDEN_KEYWORDS = Pattern.compile(
            "\\b(INSERT|UPDATE|DELETE|DROP|ALTER|TRUNCATE|CREATE|REPLACE|GRANT|REVOKE|EXEC|EXECUTE|CALL|LOCK|UNLOCK|INTO\\s+OUTFILE|INTO\\s+DUMPFILE|LOAD_FILE|LOAD\\s+DATA|FOR\\s+UPDATE|FOR\\s+SHARE|LOCK\\s+IN\\s+SHARE\\s+MODE)\\b",
            Pattern.CASE_INSENSITIVE
    );

    /** 耗时 / 加锁 / 读写文件类函数，必须是函数调用形式才拦截，避免误伤同名列 */
    private static final Pattern FORBIDDEN_FUNCTIONS = Pattern.compile(
            "\\b(SLEEP|BENCHMARK|GET_LOCK|RELEASE_LOCK|PG_SLEEP|PG_READ_FILE|LO_IMPORT|LO_EXPORT|DBMS_LOCK)\\s*\\(",
            Pattern.CASE_INSENSITIVE
    );

    /**
     * 校验 SQL 是否仅为安全只读 SELECT 语句，若非法则直接抛出 SecurityException
     */
    public static void validateSelectOnly(String sql) {
        if (sql == null || sql.isBlank()) {
            throw new IllegalArgumentException("SQL 语句不能为空");
        }

        // 两种反斜杠语义各校验一次，任意一种不通过即拒绝
        checkCleaned(sanitize(sql, true));
        checkCleaned(sanitize(sql, false));
    }

    private static void checkCleaned(String cleaned) {
        String clean = cleaned.trim();
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
        Matcher matcher = FORBIDDEN_KEYWORDS.matcher(clean);
        if (matcher.find()) {
            throw new SecurityException("安全拦截: 检测到高危非查询关键字 [" + matcher.group(1).toUpperCase() + "]，已被系统拒绝！");
        }

        // 拦截耗时 / 加锁类函数
        Matcher fn = FORBIDDEN_FUNCTIONS.matcher(clean);
        if (fn.find()) {
            throw new SecurityException("安全拦截: 检测到高危函数 [" + fn.group(1).toUpperCase() + "]，已被系统拒绝！");
        }
    }

    /**
     * 带引号感知的清洗：注释替换为空格，字符串字面量 / 反引号标识符内容抹成 ''。
     *
     * @param backslashEscapes true 表示按 MySQL 语义把 \' 当作转义；false 表示按 SQL 标准语义只认 '' 转义
     */
    static String sanitize(String sql, boolean backslashEscapes) {
        StringBuilder out = new StringBuilder(sql.length());
        int n = sql.length();
        int i = 0;
        while (i < n) {
            char c = sql.charAt(i);

            // 块注释 /* ... */
            if (c == '/' && i + 1 < n && sql.charAt(i + 1) == '*') {
                // MySQL / MariaDB 可执行注释：内容会被真实执行，直接拒绝
                if (i + 2 < n && sql.charAt(i + 2) == '!') {
                    throw new SecurityException("安全拦截: 不允许使用 MySQL 可执行注释 (/*! ... */)！");
                }
                if (i + 3 < n && (sql.charAt(i + 2) == 'M' || sql.charAt(i + 2) == 'm') && sql.charAt(i + 3) == '!') {
                    throw new SecurityException("安全拦截: 不允许使用 MariaDB 可执行注释 (/*M! ... */)！");
                }
                int end = sql.indexOf("*/", i + 2);
                if (end < 0) {
                    throw new SecurityException("安全拦截: SQL 注释未闭合！");
                }
                out.append(' ');
                i = end + 2;
                continue;
            }

            // 行注释 -- （按 MySQL 规则，后面必须是空白或结尾才算注释；否则按运算符原样保留，宁可误杀不可漏杀）
            if (c == '-' && i + 1 < n && sql.charAt(i + 1) == '-'
                    && (i + 2 >= n || Character.isWhitespace(sql.charAt(i + 2)))) {
                i = skipToLineEnd(sql, i + 2);
                out.append(' ');
                continue;
            }

            // 行注释 #
            if (c == '#') {
                i = skipToLineEnd(sql, i + 1);
                out.append(' ');
                continue;
            }

            // 字符串字面量 / 带引号标识符
            if (c == '\'' || c == '"' || c == '`') {
                boolean allowBackslash = backslashEscapes && c != '`';
                int j = i + 1;
                boolean closed = false;
                while (j < n) {
                    char d = sql.charAt(j);
                    if (allowBackslash && d == '\\' && j + 1 < n) {
                        j += 2;
                        continue;
                    }
                    if (d == c) {
                        if (j + 1 < n && sql.charAt(j + 1) == c) {
                            j += 2; // 连写两个引号表示一个引号字符
                            continue;
                        }
                        closed = true;
                        j++;
                        break;
                    }
                    j++;
                }
                if (!closed) {
                    throw new SecurityException("安全拦截: SQL 引号未闭合！");
                }
                out.append(c).append(c);
                i = j;
                continue;
            }

            out.append(c);
            i++;
        }
        return out.toString();
    }

    private static int skipToLineEnd(String s, int from) {
        int i = from;
        while (i < s.length() && s.charAt(i) != '\n' && s.charAt(i) != '\r') {
            i++;
        }
        return i;
    }
}
