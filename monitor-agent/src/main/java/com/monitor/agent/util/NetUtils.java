package com.monitor.agent.util;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;

/**
 * 网络与系统基础工具类
 * 智能探测宿主机真实物理/内网 IP 与主机名，跨 Linux/Windows 完美适配
 */
public class NetUtils {

    /**
     * 智能解析本机非回环 (Non-loopback) 真实内网 IPv4 地址
     * 自动排除 127.0.0.1、docker 虚拟网卡、veth 等干扰
     */
    public static String resolveLocalIp() {
        try {
            List<InetAddress> candidates = new ArrayList<>();
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            if (interfaces != null) {
                while (interfaces.hasMoreElements()) {
                    NetworkInterface ni = interfaces.nextElement();
                    if (ni.isLoopback() || !ni.isUp() || ni.isVirtual()) continue;
                    String name = ni.getName().toLowerCase();
                    if (name.startsWith("docker") || name.startsWith("veth") || name.startsWith("br-")
                            || name.startsWith("flannel") || name.startsWith("cni")) {
                        continue;
                    }
                    Enumeration<InetAddress> addresses = ni.getInetAddresses();
                    while (addresses.hasMoreElements()) {
                        InetAddress addr = addresses.nextElement();
                        if (addr instanceof Inet4Address && !addr.isLoopbackAddress() && !addr.isLinkLocalAddress()) {
                            if (addr.isSiteLocalAddress()) {
                                return addr.getHostAddress();
                            }
                            candidates.add(addr);
                        }
                    }
                }
            }
            if (!candidates.isEmpty()) {
                return candidates.get(0).getHostAddress();
            }
            InetAddress local = InetAddress.getLocalHost();
            if (local != null && !local.isLoopbackAddress()) {
                return local.getHostAddress();
            }
        } catch (Exception ignored) {}
        return "127.0.0.1";
    }

    /**
     * 解析主机名，优先读取环境变量 HOSTNAME (Linux) / COMPUTERNAME (Windows)
     */
    public static String resolveHostName() {
        String host = System.getenv("HOSTNAME");
        if (host == null || host.isBlank()) {
            host = System.getenv("COMPUTERNAME");
        }
        if (host == null || host.isBlank()) {
            try {
                host = InetAddress.getLocalHost().getHostName();
            } catch (Exception ignored) {}
        }
        return (host != null && !host.isBlank()) ? host : "unknown-host";
    }
}
