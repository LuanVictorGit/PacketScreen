package com.packetscreen;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.Base64;
import java.util.Deque;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

final class WebAccess {

    private static final int MAX_TOKENS = 32;
    private static final int MAX_SESSIONS = 32;

    static final class Grant {
        final String user;
        final UUID userId;
        final boolean create;
        final boolean video;
        final boolean remove;
        final String world;
        final int x;
        final int y;
        final int z;
        final float yaw;

        Grant(String user, UUID userId, boolean create, boolean video, boolean remove, String world, int x, int y, int z,
              float yaw) {
            this.user = user;
            this.userId = userId;
            this.create = create;
            this.video = video;
            this.remove = remove;
            this.world = world;
            this.x = x;
            this.y = y;
            this.z = z;
            this.yaw = yaw;
        }
    }

    static final class Session {
        final String id;
        final String csrf;
        final Grant grant;
        final long expires;
        final AtomicInteger uploads = new AtomicInteger();

        Session(String id, String csrf, Grant grant, long expires) {
            this.id = id;
            this.csrf = csrf;
            this.grant = grant;
            this.expires = expires;
        }
    }

    private static final class Pending {
        final Grant grant;
        final long expires;

        Pending(Grant grant, long expires) {
            this.grant = grant;
            this.expires = expires;
        }
    }

    private final SecureRandom random = new SecureRandom();
    private final Map<String, Pending> tokens = new ConcurrentHashMap<>();
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final Map<String, Deque<Long>> attempts = new ConcurrentHashMap<>();

    String createToken(Grant grant, long lifetimeMs) {
        prune();
        while (tokens.size() >= MAX_TOKENS) {
            removeOldest(tokens);
        }
        String token = randomToken();
        tokens.put(hash(token), new Pending(grant, System.currentTimeMillis() + lifetimeMs));
        return token;
    }

    Session redeem(String token, long lifetimeMs) {
        if (token == null || token.length() < 20 || token.length() > 128) {
            return null;
        }
        Pending pending = tokens.remove(hash(token));
        long now = System.currentTimeMillis();
        if (pending == null || pending.expires < now) {
            return null;
        }
        prune();
        while (sessions.size() >= MAX_SESSIONS) {
            removeOldestSession();
        }
        Session session = new Session(randomToken(), randomToken(), pending.grant, now + lifetimeMs);
        sessions.put(hash(session.id), session);
        return session;
    }

    Session session(String id) {
        if (id == null || id.isEmpty() || id.length() > 128) {
            return null;
        }
        String key = hash(id);
        Session session = sessions.get(key);
        if (session == null) {
            return null;
        }
        if (session.expires < System.currentTimeMillis()) {
            sessions.remove(key);
            return null;
        }
        return session;
    }

    void end(Session session) {
        sessions.remove(hash(session.id));
    }

    void revokeAll() {
        tokens.clear();
        sessions.clear();
    }

    int activeSessions() {
        prune();
        return sessions.size();
    }

    boolean allowAttempt(String address, int perMinute) {
        long now = System.currentTimeMillis();
        Deque<Long> window = attempts.computeIfAbsent(address, key -> new ArrayDeque<>());
        synchronized (window) {
            while (!window.isEmpty() && now - window.peekFirst() > 60000) {
                window.pollFirst();
            }
            if (window.size() >= perMinute) {
                return false;
            }
            window.addLast(now);
        }
        if (attempts.size() > 4096) {
            attempts.entrySet().removeIf(entry -> {
                synchronized (entry.getValue()) {
                    return entry.getValue().isEmpty() || now - entry.getValue().peekLast() > 60000;
                }
            });
        }
        return true;
    }

    static boolean safeEquals(String expected, String provided) {
        if (expected == null || provided == null) {
            return false;
        }
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), provided.getBytes(StandardCharsets.UTF_8));
    }

    private void prune() {
        long now = System.currentTimeMillis();
        tokens.values().removeIf(pending -> pending.expires < now);
        sessions.values().removeIf(session -> session.expires < now);
    }

    private static void removeOldest(Map<String, Pending> map) {
        String oldest = null;
        long time = Long.MAX_VALUE;
        for (Map.Entry<String, Pending> entry : map.entrySet()) {
            if (entry.getValue().expires < time) {
                time = entry.getValue().expires;
                oldest = entry.getKey();
            }
        }
        if (oldest != null) {
            map.remove(oldest);
        }
    }

    private void removeOldestSession() {
        String oldest = null;
        long time = Long.MAX_VALUE;
        Iterator<Map.Entry<String, Session>> iterator = sessions.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, Session> entry = iterator.next();
            if (entry.getValue().expires < time) {
                time = entry.getValue().expires;
                oldest = entry.getKey();
            }
        }
        if (oldest != null) {
            sessions.remove(oldest);
        }
    }

    private String randomToken() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String hash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
