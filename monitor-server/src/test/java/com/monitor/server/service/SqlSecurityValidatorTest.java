package com.monitor.server.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SqlSecurityValidatorTest {

    private static void ok(String sql) {
        assertDoesNotThrow(() -> SqlSecurityValidator.validateSelectOnly(sql), sql);
    }

    private static void rejected(String sql) {
        assertThrows(SecurityException.class, () -> SqlSecurityValidator.validateSelectOnly(sql), sql);
    }

    @Test
    void normalReadOnlyQueriesPass() {
        ok("SELECT COUNT(*) FROM orders WHERE status = 'failed'");
        ok("select 1");
        ok("SELECT 1;");
        ok("WITH t AS (SELECT 1 a) SELECT a FROM t");
        ok("SELECT COUNT(*) /* 统计 */ FROM t -- 末尾注释\n WHERE a = 1");
        ok("SELECT count(*) FROM t # mysql comment");
    }

    @Test
    void keywordsInsideStringLiteralsAreNotFalsePositives() {
        // 以前会因为字面量里的 update / delete 被误杀
        ok("SELECT COUNT(*) FROM audit WHERE action = 'update'");
        ok("SELECT COUNT(*) FROM logs WHERE msg LIKE '%delete%' AND note = 'a;b'");
        ok("SELECT COUNT(*) FROM t WHERE name = 'it''s drop'");
        ok("SELECT COUNT(*) FROM t WHERE c = 'x--y'");
    }

    @Test
    void writesAndDdlAreRejected() {
        rejected("DELETE FROM t");
        rejected("UPDATE t SET a = 1");
        rejected("INSERT INTO t VALUES (1)");
        rejected("DROP TABLE t");
        rejected("TRUNCATE TABLE t");
        rejected("SELECT 1 INTO OUTFILE '/tmp/x'");
        rejected("WITH x AS (DELETE FROM t RETURNING 1) SELECT * FROM x");
    }

    @Test
    void multiStatementIsRejected() {
        rejected("SELECT 1; DROP TABLE t");
        rejected("SELECT 1; SELECT 2");
    }

    @Test
    void literalAndCommentTricksCannotHideStatements() {
        // 旧实现会把 'a-- 之后的内容当注释删掉，从而放过后面的语句
        rejected("SELECT 'a--' ; DROP TABLE t");
        rejected("SELECT 1 /* x */ ; DROP TABLE t");
        // 反斜杠转义语义不一致的场景：任意一种语义下藏不住就拒绝
        rejected("SELECT 'a\\' ; DROP TABLE t -- '");
        // 不带空格的 -- 在 MySQL 中是运算符不是注释，不能拿来藏语句
        rejected("SELECT 1--1; DROP TABLE t");
    }

    @Test
    void executableCommentsAreRejected() {
        rejected("SELECT 1 /*!50000 UNION SELECT password FROM apm_user */");
        rejected("SELECT 1 /*M! SLEEP(5) */");
    }

    @Test
    void unterminatedQuoteOrCommentIsRejected() {
        rejected("SELECT 'abc");
        rejected("SELECT 1 /* abc");
    }

    @Test
    void lockingAndSlowFunctionsAreRejected() {
        rejected("SELECT * FROM t FOR UPDATE");
        rejected("SELECT * FROM t LOCK IN SHARE MODE");
        rejected("SELECT SLEEP(10)");
        rejected("SELECT sleep (10)");
        rejected("SELECT BENCHMARK(100000000, MD5('a'))");
        rejected("SELECT GET_LOCK('a', 10)");
        rejected("SELECT pg_sleep(10)");
        // 同名列不是函数调用，不应误杀
        ok("SELECT COUNT(*) FROM t WHERE sleep = 1");
    }

    @Test
    void mustStartWithSelectOrWith() {
        rejected("SHOW TABLES");
        rejected("EXPLAIN SELECT 1");
        rejected("(SELECT 1)");
    }

    @Test
    void emptyInputIsIllegalArgument() {
        assertThrows(IllegalArgumentException.class, () -> SqlSecurityValidator.validateSelectOnly(null));
        assertThrows(IllegalArgumentException.class, () -> SqlSecurityValidator.validateSelectOnly("   "));
        assertThrows(IllegalArgumentException.class, () -> SqlSecurityValidator.validateSelectOnly("-- only comment"));
    }
}
