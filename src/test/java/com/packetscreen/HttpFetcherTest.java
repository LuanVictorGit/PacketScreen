package com.packetscreen;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HttpFetcherTest {

    private static HttpServer server;
    private static ScheduledExecutorService timer;
    private static int port;
    private static final byte[] PAYLOAD = "packetscreen".getBytes(StandardCharsets.UTF_8);

    @BeforeAll
    static void start() throws Exception {
        timer = Executors.newSingleThreadScheduledExecutor();
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        port = server.getAddress().getPort();
        server.createContext("/ok", exchange -> {
            exchange.sendResponseHeaders(200, PAYLOAD.length);
            exchange.getResponseBody().write(PAYLOAD);
            exchange.close();
        });
        server.createContext("/chunked", exchange -> {
            exchange.sendResponseHeaders(200, 0);
            OutputStream body = exchange.getResponseBody();
            body.write(PAYLOAD, 0, 4);
            body.flush();
            body.write(PAYLOAD, 4, PAYLOAD.length - 4);
            exchange.close();
        });
        server.createContext("/gzip", exchange -> {
            ByteArrayOutputStream compressed = new ByteArrayOutputStream();
            try (GZIPOutputStream gzip = new GZIPOutputStream(compressed)) {
                gzip.write(PAYLOAD);
            }
            exchange.getResponseHeaders().set("Content-Encoding", "gzip");
            exchange.sendResponseHeaders(200, compressed.size());
            exchange.getResponseBody().write(compressed.toByteArray());
            exchange.close();
        });
        server.createContext("/redirect", exchange -> {
            exchange.getResponseHeaders().set("Location", "/ok");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.createContext("/elsewhere", exchange -> {
            exchange.getResponseHeaders().set("Location", "http://localhost:" + port + "/ok");
            exchange.sendResponseHeaders(301, -1);
            exchange.close();
        });
        server.createContext("/loop", exchange -> {
            exchange.getResponseHeaders().set("Location", "/loop");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.createContext("/big", exchange -> {
            byte[] big = new byte[5000];
            exchange.sendResponseHeaders(200, big.length);
            exchange.getResponseBody().write(big);
            exchange.close();
        });
        server.createContext("/big-chunked", exchange -> {
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().write(new byte[5000]);
            exchange.close();
        });
        server.createContext("/html", exchange -> {
            byte[] page = "<html></html>".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(200, page.length);
            exchange.getResponseBody().write(page);
            exchange.close();
        });
        server.createContext("/missing", exchange -> {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        });
        server.start();
    }

    @AfterAll
    static void stop() {
        server.stop(0);
        timer.shutdownNow();
    }

    private static HttpFetcher fetcher(Object... overrides) {
        Object[] base = {"security.allow-private-addresses", true, "security.allowed-ports", Arrays.asList(80, 443, port)};
        Object[] all = Arrays.copyOf(base, base.length + overrides.length);
        System.arraycopy(overrides, 0, all, base.length, overrides.length);
        Settings settings = TestSupport.settings(all);
        return new HttpFetcher(() -> settings, "test", timer);
    }

    private static int slowServer(String head, long interval) throws Exception {
        ServerSocket listener = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
        Thread thread = new Thread(() -> {
            try (ServerSocket owned = listener; Socket client = owned.accept()) {
                OutputStream out = client.getOutputStream();
                out.write(head.getBytes(StandardCharsets.ISO_8859_1));
                out.flush();
                while (true) {
                    Thread.sleep(interval);
                    out.write('a');
                    out.flush();
                }
            } catch (Exception ignored) {
            }
        }, "slow-server");
        thread.setDaemon(true);
        thread.start();
        return listener.getLocalPort();
    }

    private static String url(String path) {
        return "http://127.0.0.1:" + port + path;
    }

    private static MediaException.Reason failure(HttpFetcher fetcher, String url, long limit) {
        return assertThrows(MediaException.class, () -> fetcher.fetch(url, limit, null)).reason;
    }

    @Test
    void downloadsPlainChunkedAndCompressedBodies() throws Exception {
        HttpFetcher fetcher = fetcher();
        assertArrayEquals(PAYLOAD, fetcher.fetch(url("/ok"), 1000, null));
        assertArrayEquals(PAYLOAD, fetcher.fetch(url("/chunked"), 1000, null));
        assertArrayEquals(PAYLOAD, fetcher.fetch(url("/gzip"), 1000, null));
    }

    @Test
    void followsAndLimitsRedirects() throws Exception {
        HttpFetcher fetcher = fetcher();
        assertArrayEquals(PAYLOAD, fetcher.fetch(url("/redirect"), 1000, null));
        assertEquals(MediaException.Reason.TOO_MANY_REDIRECTS, failure(fetcher, url("/loop"), 1000));
    }

    @Test
    void validatesEveryRedirectTarget() {
        HttpFetcher fetcher = fetcher("security.allowed-hosts", Collections.singletonList("127.0.0.1"));
        assertEquals(MediaException.Reason.BLOCKED_ADDRESS, failure(fetcher, url("/elsewhere"), 1000));
    }

    @Test
    void enforcesSizeLimits() {
        HttpFetcher fetcher = fetcher();
        assertEquals(MediaException.Reason.TOO_LARGE, failure(fetcher, url("/big"), 1000));
        assertEquals(MediaException.Reason.TOO_LARGE, failure(fetcher, url("/big-chunked"), 1000));
    }

    @Test
    void rejectsPagesAndErrors() {
        HttpFetcher fetcher = fetcher();
        assertEquals(MediaException.Reason.WEB_PAGE, failure(fetcher, url("/html"), 1000));
        assertEquals(MediaException.Reason.HTTP_STATUS, failure(fetcher, url("/missing"), 1000));
    }

    @Test
    void enforcesTheTotalTimeoutOnSlowServers() throws Exception {
        int headers = slowServer("HTTP/1.1 200 OK\r\nX-Slow: ", 100);
        int body = slowServer("HTTP/1.1 200 OK\r\nContent-Length: 100000\r\n\r\n", 100);
        for (int slow : new int[]{headers, body}) {
            HttpFetcher fetcher = fetcher("http.total-timeout-ms", 1000, "http.read-timeout-ms", 5000,
                    "security.allowed-ports", Arrays.asList(80, 443, slow));
            long start = System.nanoTime();
            assertEquals(MediaException.Reason.TIMEOUT, failure(fetcher, "http://127.0.0.1:" + slow + "/a.png", 1000000));
            long elapsed = (System.nanoTime() - start) / 1000000;
            assertTrue(elapsed < 3000, "took " + elapsed + " ms");
        }
    }

    @Test
    void reportsSilentServersAndRefusedConnections() throws Exception {
        int silent = slowServer("", 60000);
        HttpFetcher fetcher = fetcher("http.read-timeout-ms", 300, "security.allowed-ports", Arrays.asList(80, 443, silent));
        assertEquals(MediaException.Reason.TIMEOUT, failure(fetcher, "http://127.0.0.1:" + silent + "/a.png", 1000));
        int closed;
        try (ServerSocket probe = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            closed = probe.getLocalPort();
        }
        HttpFetcher refused = fetcher("security.allowed-ports", Arrays.asList(80, 443, closed));
        assertEquals(MediaException.Reason.NETWORK, failure(refused, "http://127.0.0.1:" + closed + "/a.png", 1000));
    }

    @Test
    void usesTheImageInsideGoogleResultLinks() {
        String result = "https://www.google.com/imgres?q=imagem%204k&imgurl=https%3A%2F%2Fpng.pngtree.com%2Fthumb_back"
                + "%2Ffh260%2Fpngtree-image_15718240.jpg&imgrefurl=https%3A%2F%2F";
        assertEquals("https://png.pngtree.com/thumb_back/fh260/pngtree-image_15718240.jpg", HttpFetcher.directLink(result));
        assertEquals("https://example.com/imgres?imgurl=x", HttpFetcher.directLink("https://example.com/imgres?imgurl=x"));
        assertEquals("not a url", HttpFetcher.directLink("not a url"));
    }

    @Test
    void blocksPrivateAddressesByDefault() {
        HttpFetcher fetcher = fetcher("security.allow-private-addresses", false);
        assertEquals(MediaException.Reason.BLOCKED_ADDRESS, failure(fetcher, url("/ok"), 1000));
        assertEquals(MediaException.Reason.BLOCKED_ADDRESS, failure(fetcher, "http://localhost:" + port + "/ok", 1000));
    }

    @Test
    void validatesUrls() {
        HttpFetcher fetcher = fetcher("security.allowed-ports", Arrays.asList(80, 443));
        Settings settings = TestSupport.settings("security.blocked-hosts", Collections.singletonList("bad.example"));
        assertThrows(MediaException.class, () -> fetcher.validate("ftp://example.com/a.png", settings));
        assertThrows(MediaException.class, () -> fetcher.validate("http://user:pass@example.com/a.png", settings));
        assertThrows(MediaException.class, () -> fetcher.validate("http://example.com:8080/a.png", settings));
        assertThrows(MediaException.class, () -> fetcher.validate("https://cdn.bad.example/a.png", settings));
        assertThrows(MediaException.class, () -> fetcher.validate("not a url", settings));
    }

    @Test
    void recognisesInternalAddresses() throws Exception {
        for (String blocked : new String[]{"127.0.0.1", "10.1.2.3", "172.16.5.4", "192.168.0.10", "169.254.169.254",
                "100.64.0.1", "0.0.0.0", "224.0.0.1", "::1", "fe80::1", "fc00::1", "::ffff:127.0.0.1", "64:ff9b::7f00:1",
                "2002:7f00:1::1"}) {
            assertTrue(HttpFetcher.isBlocked(InetAddress.getByName(blocked)), blocked);
        }
        for (String allowed : new String[]{"8.8.8.8", "1.1.1.1", "2606:4700:4700::1111"}) {
            assertFalse(HttpFetcher.isBlocked(InetAddress.getByName(allowed)), allowed);
        }
    }
}
