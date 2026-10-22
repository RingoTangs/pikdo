package io.github.ringotangs.pikdo.server.shared.web.request;

import jakarta.servlet.http.HttpServletRequest;

import org.jspecify.annotations.Nullable;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.*;

/** 仅沿明确可信的代理链读取 XFF；所有地址先验证为字面量，禁止 DNS 查询。 */
public final class ClientIpResolver {
    private final List<Network> trusted;

    public ClientIpResolver(List<String> proxies) {
        trusted = proxies.stream().map(Network::parse).toList();
    }

    public String resolve(HttpServletRequest request) {
        InetAddress remote;
        try {
            remote = literal(request.getRemoteAddr());
        } catch (IllegalArgumentException failure) {
            return "unknown";
        }
        String fallback = remote.getHostAddress();
        if (!trusted(remote)) return fallback;
        var headers = request.getHeaders("X-Forwarded-For");
        var addresses = new ArrayList<InetAddress>();
        int length = 0;
        try {
            while (headers.hasMoreElements()) {
                String header = headers.nextElement();
                length += header.length() + (length == 0 ? 0 : 1);
                if (length > 4096) return fallback;
                for (String value : header.split(",", -1)) {
                    if (addresses.size() >= 32) return fallback;
                    addresses.add(literal(value.trim()));
                }
            }
        } catch (IllegalArgumentException failure) {
            return fallback;
        }
        if (addresses.isEmpty()) return fallback;
        InetAddress current = remote;
        for (int i = addresses.size() - 1; i >= 0 && trusted(current); i--)
            current = addresses.get(i);
        return current.getHostAddress();
    }

    private boolean trusted(InetAddress address) {
        return trusted.stream().anyMatch(network -> network.matches(address));
    }

    private static InetAddress literal(@Nullable String value) {
        if (value == null || value.isEmpty())
            throw new IllegalArgumentException("Invalid IP literal");
        if (value.contains(":")) {
            if (!value.matches("[0-9a-fA-F:.]+"))
                throw new IllegalArgumentException("Invalid IP literal");
            if (value.contains(".")) ipv4(value.substring(value.lastIndexOf(':') + 1));
        } else ipv4(value);
        try {
            return InetAddress.getByName(value);
        } catch (UnknownHostException failure) {
            throw new IllegalArgumentException("Invalid IP literal");
        }
    }

    private static void ipv4(String value) {
        String[] parts = value.split("\\.", -1);
        if (parts.length != 4) throw new IllegalArgumentException("Invalid IP literal");
        for (String part : parts)
            if (!part.matches("0|[1-9][0-9]{0,2}") || Integer.parseInt(part) > 255)
                throw new IllegalArgumentException("Invalid IP literal");
    }

    private record Network(byte[] address, int prefix) {
        static Network parse(String value) {
            try {
                String[] parts = value.split("/", -1);
                if (parts.length > 2) throw new IllegalArgumentException();
                byte[] address = literal(parts[0]).getAddress();
                if (parts.length == 2 && !parts[1].matches("0|[1-9][0-9]{0,2}"))
                    throw new IllegalArgumentException();
                int prefix = parts.length == 1 ? address.length * 8 : Integer.parseInt(parts[1]);
                // JDK 将 IPv4 映射 IPv6 还原为四字节地址，CIDR 前缀同步去掉映射部分。
                if (parts.length == 2 && parts[0].contains(":") && address.length == 4) {
                    if (prefix < 96 || prefix > 128) throw new IllegalArgumentException();
                    prefix -= 96;
                }
                if (prefix > address.length * 8) throw new IllegalArgumentException();
                return new Network(address, prefix);
            } catch (IllegalArgumentException failure) {
                throw new IllegalArgumentException("Invalid trusted proxy IP/CIDR");
            }
        }

        boolean matches(InetAddress candidate) {
            byte[] other = candidate.getAddress();
            if (address.length != other.length) return false;
            for (int bit = 0; bit < prefix; bit++)
                if (((address[bit / 8] ^ other[bit / 8]) & (128 >> (bit % 8))) != 0) return false;
            return true;
        }
    }
}
