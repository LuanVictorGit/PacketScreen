package com.packetscreen;

import javax.net.ssl.SNIHostName;
import javax.net.ssl.SNIServerName;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

final class HttpFetcher {

    private static final int MAX_HEADER_BYTES = 32768;
    private static final Pattern IPV4 = Pattern.compile("\\d{1,3}(\\.\\d{1,3}){3}");
    private static final Pattern GOOGLE = Pattern.compile("((www|images)\\.)?google(\\.[a-z]{2,3}){1,2}");

    private final Supplier<Settings> settings;
    private final String version;
    private final ScheduledExecutorService timer;

    HttpFetcher(Supplier<Settings> settings, String version, ScheduledExecutorService timer) {
        this.settings = settings;
        this.version = version;
        this.timer = timer;
    }

    static String directLink(String url) {
        try {
            URI uri = new URI(url.trim());
            String host = uri.getHost();
            String query = uri.getRawQuery();
            if (host != null && query != null && "/imgres".equals(uri.getPath())
                    && GOOGLE.matcher(host.toLowerCase(Locale.ROOT)).matches()) {
                for (String pair : query.split("&")) {
                    if (pair.startsWith("imgurl=")) {
                        return URLDecoder.decode(pair.substring(7), "UTF-8");
                    }
                }
            }
        } catch (URISyntaxException | UnsupportedEncodingException | IllegalArgumentException ignored) {
        }
        return url;
    }

    byte[] fetch(String url, long maxBytes, BooleanSupplier cancelled) throws MediaException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(65536);
        download(url, maxBytes, out, cancelled);
        return out.toByteArray();
    }

    void download(String url, long maxBytes, OutputStream out, BooleanSupplier cancelled) throws MediaException {
        Settings settings = this.settings.get();
        URI uri = validate(url, settings);
        long deadline = System.currentTimeMillis() + settings.totalTimeoutMs;
        for (int redirects = 0; ; redirects++) {
            Connection connection = null;
            try {
                connection = open(uri, settings, deadline);
                int status = connection.status;
                if (status == 301 || status == 302 || status == 303 || status == 307 || status == 308) {
                    String location = connection.headers.get("location");
                    if (location == null) {
                        throw new MediaException(MediaException.Reason.HTTP_STATUS, "status", status);
                    }
                    if (redirects >= settings.maxRedirects) {
                        throw new MediaException(MediaException.Reason.TOO_MANY_REDIRECTS);
                    }
                    uri = validate(resolve(uri, location), settings);
                    continue;
                }
                if (status != 200) {
                    throw new MediaException(MediaException.Reason.HTTP_STATUS, "status", status);
                }
                String type = connection.headers.get("content-type");
                if (type != null && type.toLowerCase(Locale.ROOT).startsWith("text/html")) {
                    throw new MediaException(MediaException.Reason.WEB_PAGE);
                }
                long declared = -1;
                String length = connection.headers.get("content-length");
                if (length != null) {
                    try {
                        declared = Long.parseLong(length.trim());
                    } catch (NumberFormatException e) {
                        throw new MediaException(MediaException.Reason.NETWORK, "detail", "invalid content-length");
                    }
                }
                if (declared > maxBytes) {
                    throw new MediaException(MediaException.Reason.TOO_LARGE, "max", formatBytes(maxBytes));
                }
                copy(connection.body(declared), out, maxBytes, deadline, cancelled);
                return;
            } catch (IOException e) {
                if (e instanceof SocketTimeoutException || System.currentTimeMillis() >= deadline) {
                    throw new MediaException(MediaException.Reason.TIMEOUT, e);
                }
                throw new MediaException(MediaException.Reason.NETWORK, e, "detail", e.getClass().getSimpleName());
            } finally {
                if (connection != null) {
                    connection.close();
                }
            }
        }
    }

    URI validate(String url, Settings settings) throws MediaException {
        if (url == null || url.isEmpty() || url.length() > settings.maxUrlLength) {
            throw new MediaException(MediaException.Reason.INVALID_URL);
        }
        URI uri;
        try {
            uri = new URI(url.trim()).normalize();
            uri = new URI(uri.toASCIIString());
        } catch (URISyntaxException e) {
            throw new MediaException(MediaException.Reason.INVALID_URL);
        }
        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            throw new MediaException(MediaException.Reason.INVALID_URL);
        }
        String host = uri.getHost();
        if (host == null || host.isEmpty() || uri.getRawUserInfo() != null) {
            throw new MediaException(MediaException.Reason.INVALID_URL);
        }
        if (!settings.allowedPorts.contains(port(uri))) {
            throw new MediaException(MediaException.Reason.BLOCKED_ADDRESS);
        }
        String name = bare(host).toLowerCase(Locale.ROOT);
        while (name.endsWith(".")) {
            name = name.substring(0, name.length() - 1);
        }
        if (!settings.allowedHosts.isEmpty() && !matchesHost(name, settings.allowedHosts)) {
            throw new MediaException(MediaException.Reason.BLOCKED_ADDRESS);
        }
        if (matchesHost(name, settings.blockedHosts)) {
            throw new MediaException(MediaException.Reason.BLOCKED_ADDRESS);
        }
        return uri;
    }

    static boolean isBlocked(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) {
            return true;
        }
        byte[] b = address.getAddress();
        if (b.length == 4) {
            return blockedV4(b, 0);
        }
        if ((b[0] & 0xFE) == 0xFC) {
            return true;
        }
        if ((b[0] & 0xFF) == 0xFE && (b[1] & 0xC0) == 0xC0) {
            return true;
        }
        if (zero(b, 0, 10) && (b[10] & 0xFF) == 0xFF && (b[11] & 0xFF) == 0xFF) {
            return blockedV4(b, 12);
        }
        if (zero(b, 0, 12)) {
            return true;
        }
        if (b[0] == 0 && (b[1] & 0xFF) == 0x64 && (b[2] & 0xFF) == 0xFF && (b[3] & 0xFF) == 0x9B && zero(b, 4, 12)) {
            return blockedV4(b, 12);
        }
        if ((b[0] & 0xFF) == 0x20 && (b[1] & 0xFF) == 0x02) {
            return blockedV4(b, 2);
        }
        if ((b[0] & 0xFF) == 0x20 && (b[1] & 0xFF) == 0x01 && b[2] == 0 && b[3] == 0) {
            return true;
        }
        return (b[0] & 0xFF) == 0x20 && (b[1] & 0xFF) == 0x01 && (b[2] & 0xFF) == 0x0D && (b[3] & 0xFF) == 0xB8;
    }

    private static boolean blockedV4(byte[] b, int offset) {
        int a = b[offset] & 0xFF;
        int c = b[offset + 1] & 0xFF;
        int d = b[offset + 2] & 0xFF;
        return a == 0 || a == 10 || a == 127 || a >= 224
                || (a == 100 && (c & 0xC0) == 64)
                || (a == 169 && c == 254)
                || (a == 172 && (c & 0xF0) == 16)
                || (a == 192 && c == 168)
                || (a == 192 && c == 0 && (d == 0 || d == 2))
                || (a == 198 && (c & 0xFE) == 18)
                || (a == 198 && c == 51 && d == 100)
                || (a == 203 && c == 0 && d == 113);
    }

    private static boolean zero(byte[] b, int from, int to) {
        for (int i = from; i < to; i++) {
            if (b[i] != 0) {
                return false;
            }
        }
        return true;
    }

    private static boolean matchesHost(String host, List<String> entries) {
        for (String entry : entries) {
            if (host.equals(entry) || host.endsWith("." + entry)) {
                return true;
            }
        }
        return false;
    }

    private static String resolve(URI base, String location) throws MediaException {
        try {
            return base.resolve(new URI(location.trim())).toString();
        } catch (URISyntaxException | IllegalArgumentException e) {
            throw new MediaException(MediaException.Reason.INVALID_URL);
        }
    }

    private static int port(URI uri) {
        int port = uri.getPort();
        if (port != -1) {
            return port;
        }
        return uri.getScheme().equalsIgnoreCase("https") ? 443 : 80;
    }

    private static String bare(String host) {
        return host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
    }

    private Connection open(URI uri, Settings settings, long deadline) throws IOException, MediaException {
        String host = uri.getHost();
        String name = bare(host);
        boolean https = uri.getScheme().equalsIgnoreCase("https");
        int port = port(uri);
        InetAddress[] addresses = InetAddress.getAllByName(name);
        if (!settings.allowPrivateAddresses) {
            for (InetAddress address : addresses) {
                if (isBlocked(address)) {
                    throw new MediaException(MediaException.Reason.BLOCKED_ADDRESS);
                }
            }
        }
        long remaining = deadline - System.currentTimeMillis();
        if (remaining <= 0) {
            throw new MediaException(MediaException.Reason.TIMEOUT);
        }
        Socket socket = new Socket();
        Socket plain = socket;
        ScheduledFuture<?> watchdog = timer.schedule(() -> close(plain), remaining, TimeUnit.MILLISECONDS);
        try {
            socket.connect(new InetSocketAddress(addresses[0], port), (int) Math.min(settings.connectTimeoutMs, remaining));
            socket.setSoTimeout(settings.readTimeoutMs);
            if (https) {
                socket = secure(socket, name, port);
            }
            String path = uri.getRawPath();
            if (path == null || path.isEmpty()) {
                path = "/";
            }
            if (uri.getRawQuery() != null) {
                path = path + "?" + uri.getRawQuery();
            }
            String hostHeader = host + (port == (https ? 443 : 80) ? "" : ":" + port);
            String request = "GET " + path + " HTTP/1.1\r\n"
                    + "Host: " + hostHeader + "\r\n"
                    + "User-Agent: " + settings.userAgent + "/" + version + "\r\n"
                    + "Accept: */*\r\n"
                    + "Accept-Encoding: gzip, identity\r\n"
                    + "Connection: close\r\n\r\n";
            OutputStream output = socket.getOutputStream();
            output.write(request.getBytes(StandardCharsets.ISO_8859_1));
            output.flush();
            Connection connection = new Connection(socket, new BufferedInputStream(socket.getInputStream(), 16384), watchdog);
            connection.readHead();
            return connection;
        } catch (IOException | RuntimeException e) {
            watchdog.cancel(false);
            close(socket);
            throw e;
        }
    }

    private static void close(Socket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }

    private static Socket secure(Socket socket, String host, int port) throws IOException {
        SSLSocketFactory factory = (SSLSocketFactory) SSLSocketFactory.getDefault();
        SSLSocket ssl = (SSLSocket) factory.createSocket(socket, host, port, true);
        SSLParameters parameters = ssl.getSSLParameters();
        parameters.setEndpointIdentificationAlgorithm("HTTPS");
        if (!host.contains(":") && !IPV4.matcher(host).matches()) {
            parameters.setServerNames(Collections.<SNIServerName>singletonList(new SNIHostName(host)));
        }
        ssl.setSSLParameters(parameters);
        ssl.startHandshake();
        return ssl;
    }

    private static void copy(InputStream in, OutputStream out, long max, long deadline, BooleanSupplier cancelled)
            throws IOException, MediaException {
        byte[] buffer = new byte[16384];
        long total = 0;
        int read;
        while ((read = in.read(buffer)) != -1) {
            total += read;
            if (total > max) {
                throw new MediaException(MediaException.Reason.TOO_LARGE, "max", formatBytes(max));
            }
            if (System.currentTimeMillis() > deadline) {
                throw new MediaException(MediaException.Reason.TIMEOUT);
            }
            if (cancelled != null && cancelled.getAsBoolean()) {
                throw new MediaException(MediaException.Reason.CANCELLED);
            }
            out.write(buffer, 0, read);
        }
        out.flush();
    }

    static String formatBytes(long bytes) {
        if (bytes >= 1048576) {
            return String.format(Locale.ROOT, "%.1f MB", bytes / 1048576.0);
        }
        return String.format(Locale.ROOT, "%.0f KB", bytes / 1024.0);
    }

    private static String line(InputStream in, int limit) throws IOException {
        StringBuilder builder = new StringBuilder();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') {
                int length = builder.length();
                if (length > 0 && builder.charAt(length - 1) == '\r') {
                    builder.setLength(length - 1);
                }
                return builder.toString();
            }
            if (builder.length() >= limit) {
                throw new IOException("Line too long");
            }
            builder.append((char) c);
        }
        throw new EOFException("Unexpected end of stream");
    }

    private static final class Connection implements Closeable {

        private final Socket socket;
        private final InputStream in;
        private final ScheduledFuture<?> watchdog;
        private final Map<String, String> headers = new HashMap<>();
        private int status;

        Connection(Socket socket, InputStream in, ScheduledFuture<?> watchdog) {
            this.socket = socket;
            this.in = in;
            this.watchdog = watchdog;
        }

        void readHead() throws IOException {
            do {
                headers.clear();
                String statusLine = line(in, 8192);
                String[] parts = statusLine.split(" ", 3);
                if (parts.length < 2 || !parts[0].startsWith("HTTP/")) {
                    throw new IOException("Invalid HTTP response");
                }
                try {
                    status = Integer.parseInt(parts[1]);
                } catch (NumberFormatException e) {
                    throw new IOException("Invalid HTTP status");
                }
                int total = statusLine.length();
                while (true) {
                    String header = line(in, 8192);
                    if (header.isEmpty()) {
                        break;
                    }
                    total += header.length();
                    if (total > MAX_HEADER_BYTES || headers.size() > 128) {
                        throw new IOException("HTTP headers too large");
                    }
                    int colon = header.indexOf(':');
                    if (colon > 0) {
                        headers.put(header.substring(0, colon).trim().toLowerCase(Locale.ROOT), header.substring(colon + 1).trim());
                    }
                }
            } while (status >= 100 && status < 200);
        }

        InputStream body(long declared) throws IOException, MediaException {
            InputStream stream;
            String transfer = headers.get("transfer-encoding");
            if (transfer != null && transfer.toLowerCase(Locale.ROOT).contains("chunked")) {
                stream = new ChunkedStream(in);
            } else if (declared >= 0) {
                stream = new LimitedStream(in, declared);
            } else {
                stream = in;
            }
            String encoding = headers.get("content-encoding");
            if (encoding != null) {
                String value = encoding.trim().toLowerCase(Locale.ROOT);
                if (value.equals("gzip") || value.equals("x-gzip")) {
                    stream = new GZIPInputStream(stream, 8192);
                } else if (!value.equals("identity")) {
                    throw new MediaException(MediaException.Reason.UNSUPPORTED_FORMAT);
                }
            }
            return stream;
        }

        @Override
        public void close() {
            watchdog.cancel(false);
            HttpFetcher.close(socket);
        }
    }

    private static final class LimitedStream extends InputStream {

        private final InputStream in;
        private long remaining;

        LimitedStream(InputStream in, long length) {
            this.in = in;
            this.remaining = length;
        }

        @Override
        public int read() throws IOException {
            byte[] single = new byte[1];
            return read(single, 0, 1) < 0 ? -1 : single[0] & 0xFF;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            if (remaining <= 0) {
                return -1;
            }
            int read = in.read(buffer, offset, (int) Math.min(length, remaining));
            if (read < 0) {
                throw new EOFException("Truncated response");
            }
            remaining -= read;
            return read;
        }
    }

    private static final class ChunkedStream extends InputStream {

        private final InputStream in;
        private long remaining;
        private boolean finished;

        ChunkedStream(InputStream in) {
            this.in = in;
        }

        @Override
        public int read() throws IOException {
            byte[] single = new byte[1];
            return read(single, 0, 1) < 0 ? -1 : single[0] & 0xFF;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            if (finished) {
                return -1;
            }
            if (remaining == 0) {
                String size = line(in, 1024);
                int extension = size.indexOf(';');
                if (extension >= 0) {
                    size = size.substring(0, extension);
                }
                try {
                    remaining = Long.parseLong(size.trim(), 16);
                } catch (NumberFormatException e) {
                    throw new IOException("Invalid chunk size");
                }
                if (remaining < 0) {
                    throw new IOException("Invalid chunk size");
                }
                if (remaining == 0) {
                    while (!line(in, 8192).isEmpty()) {
                        continue;
                    }
                    finished = true;
                    return -1;
                }
            }
            int read = in.read(buffer, offset, (int) Math.min(length, remaining));
            if (read < 0) {
                throw new EOFException("Truncated chunk");
            }
            remaining -= read;
            if (remaining == 0) {
                line(in, 16);
            }
            return read;
        }
    }
}
