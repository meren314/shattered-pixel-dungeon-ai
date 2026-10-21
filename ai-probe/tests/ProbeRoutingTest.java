package com.shatteredpixel.shatteredpixeldungeon.desktop;

import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpExchange;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/** Exercise the real HTTP dispatcher without starting a game or touching saves. */
public class ProbeRoutingTest {
    public static void main(String[] args) throws Exception {
        Method serve = AiProbe.class.getDeclaredMethod("serve", HttpExchange.class);
        serve.setAccessible(true);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            try { serve.invoke(null, exchange); }
            catch (Exception e) { throw new RuntimeException(e); }
        });
        server.start();
        int count = 0;
        try {
            for (String path : new String[]{"/", "/capabilities", "/action", "/chat"}) {
                check(server, path, "attacker.invalid:18765", null, 400, "foreign_host"); count++;
                check(server, path, "127.0.0.1:18765", "https://attacker.invalid", 400, "foreign_origin"); count++;
            }
            check(server, "/capabilities", "127.0.0.1:18765", null, 200, "actions"); count++;
        } finally { server.stop(0); }
        System.out.println("Real HTTP dispatcher: " + count + " cases passed; no game started");
    }
    private static void check(HttpServer server, String path, String host, String origin,
                              int status, String content) throws Exception {
        try (Socket socket = new Socket("127.0.0.1", server.getAddress().getPort())) {
            socket.setSoTimeout(5000);
            String request = "GET " + path + " HTTP/1.1\r\nHost: " + host + "\r\n" +
                    (origin == null ? "" : "Origin: " + origin + "\r\n") + "Connection: close\r\n\r\n";
            socket.getOutputStream().write(request.getBytes(StandardCharsets.UTF_8));
            String response = new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!response.startsWith("HTTP/1.1 " + status) || !response.contains(content))
                throw new AssertionError(path + ": " + response.substring(0, Math.min(response.length(), 400)));
        }
    }
}
