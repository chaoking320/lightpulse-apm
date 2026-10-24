package com.monitor.server.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 图形验证码与防爆破安全服务
 */
@Slf4j
@Service
public class CaptchaService {

    private static final String CHARS = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ";
    private static final int WIDTH = 120;
    private static final int HEIGHT = 40;
    private static final long CAPTCHA_TTL_MS = 180_000L; // 3分钟有效期
    private static final int MAX_FAILED_ATTEMPTS = 5;
    private static final long LOCKOUT_DURATION_MS = 15 * 60 * 1000L; // 锁定15分钟

    private final SecureRandom random = new SecureRandom();

    private static class CaptchaItem {
        final String code;
        final long expireAt;
        CaptchaItem(String code, long expireAt) {
            this.code = code;
            this.expireAt = expireAt;
        }
    }

    private static class IpAttempt {
        int failCount;
        long lockUntil;
        long lastAttemptAt;
    }

    private final Map<String, CaptchaItem> captchaStore = new ConcurrentHashMap<>();
    private final Map<String, IpAttempt> ipLockoutStore = new ConcurrentHashMap<>();

    static {
        // 确保 Linux 无图形界面环境下正常使用 Java2D 渲染验证码
        System.setProperty("java.awt.headless", "true");
    }

    /**
     * 生成图形验证码
     */
    public Map<String, String> generateCaptcha() {
        cleanExpiredCaptchas();

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 4; i++) {
            sb.append(CHARS.charAt(random.nextInt(CHARS.length())));
        }
        String code = sb.toString();
        String key = UUID.randomUUID().toString().replace("-", "");

        captchaStore.put(key, new CaptchaItem(code, System.currentTimeMillis() + CAPTCHA_TTL_MS));

        BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();

        // 平滑抗锯齿
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        // 柔和背景底色
        g.setColor(new Color(241, 245, 249));
        g.fillRect(0, 0, WIDTH, HEIGHT);

        // 绘制随机干扰线
        for (int i = 0; i < 4; i++) {
            g.setColor(new Color(180 + random.nextInt(50), 190 + random.nextInt(50), 200 + random.nextInt(50)));
            int x1 = random.nextInt(WIDTH);
            int y1 = random.nextInt(HEIGHT);
            int x2 = random.nextInt(WIDTH);
            int y2 = random.nextInt(HEIGHT);
            g.drawLine(x1, y1, x2, y2);
        }

        // 绘制字符
        Font font = new Font("SansSerif", Font.BOLD, 24);
        g.setFont(font);

        Color[] charColors = {
                new Color(30, 64, 175),  // 深蓝
                new Color(15, 118, 110), // 墨绿
                new Color(180, 83, 9),   // 深橙
                new Color(109, 40, 217)  // 深紫
        };

        for (int i = 0; i < 4; i++) {
            g.setColor(charColors[i % charColors.length]);
            int x = 16 + i * 24 + random.nextInt(4);
            int y = 28 + random.nextInt(4) - 2;
            g.drawString(String.valueOf(code.charAt(i)), x, y);
        }

        // 绘制微小干扰点
        for (int i = 0; i < 30; i++) {
            g.setColor(new Color(160 + random.nextInt(80), 160 + random.nextInt(80), 160 + random.nextInt(80)));
            int px = random.nextInt(WIDTH);
            int py = random.nextInt(HEIGHT);
            g.drawRect(px, py, 1, 1);
        }

        g.dispose();

        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            ImageIO.write(image, "png", baos);
            String base64 = "data:image/png;base64," + Base64.getEncoder().encodeToString(baos.toByteArray());
            return Map.of("captchaKey", key, "captchaImage", base64);
        } catch (Exception e) {
            log.error("[Captcha] 生成验证码图片异常", e);
            throw new RuntimeException("生成验证码失败", e);
        }
    }

    /**
     * 校验验证码（一次性消费，不论成功失败均销毁该 key 防重放）
     */
    public boolean validateCaptcha(String key, String userInput) {
        if (key == null || key.isBlank() || userInput == null || userInput.isBlank()) {
            return false;
        }
        CaptchaItem item = captchaStore.remove(key.trim());
        if (item == null) {
            return false;
        }
        if (System.currentTimeMillis() > item.expireAt) {
            return false;
        }
        return item.code.equalsIgnoreCase(userInput.trim());
    }

    /**
     * 检查客户端 IP 是否处于锁定冷却期
     * @return 剩余锁定秒数，0 表示未锁定
     */
    public long getIpLockoutRemainingSeconds(String ip) {
        if (ip == null || ip.isBlank()) return 0;
        IpAttempt attempt = ipLockoutStore.get(ip);
        if (attempt == null) return 0;
        long now = System.currentTimeMillis();
        if (now < attempt.lockUntil) {
            return Math.max(1, (attempt.lockUntil - now) / 1000);
        }
        // 锁定期已过，重置状态
        if (attempt.lockUntil > 0) {
            ipLockoutStore.remove(ip);
        }
        return 0;
    }

    /**
     * 记录登录失败
     * @return 剩余可尝试次数（若被锁定返回 0）
     */
    public int recordLoginFailure(String ip) {
        if (ip == null || ip.isBlank()) return MAX_FAILED_ATTEMPTS;
        IpAttempt attempt = ipLockoutStore.computeIfAbsent(ip, k -> new IpAttempt());
        long now = System.currentTimeMillis();

        // 超过10分钟无新尝试，重置计数
        if (now - attempt.lastAttemptAt > 10 * 60 * 1000L && attempt.lockUntil <= now) {
            attempt.failCount = 0;
        }

        attempt.failCount++;
        attempt.lastAttemptAt = now;

        if (attempt.failCount >= MAX_FAILED_ATTEMPTS) {
            attempt.lockUntil = now + LOCKOUT_DURATION_MS;
            log.warn("[Security] IP [{}] 连续登录失败达 {} 次，已被锁定 15 分钟！", ip, attempt.failCount);
            return 0;
        }
        return Math.max(0, MAX_FAILED_ATTEMPTS - attempt.failCount);
    }

    /**
     * 登录成功，重置失败计数
     */
    public void recordLoginSuccess(String ip) {
        if (ip != null) {
            ipLockoutStore.remove(ip);
        }
    }

    private void cleanExpiredCaptchas() {
        long now = System.currentTimeMillis();
        captchaStore.entrySet().removeIf(e -> now > e.getValue().expireAt);
    }
}
