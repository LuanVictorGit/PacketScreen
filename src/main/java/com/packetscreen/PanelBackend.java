package com.packetscreen;

import java.io.InputStream;
import java.util.Map;

interface PanelBackend {

    Settings settings();

    WebAccess access();

    UploadStore uploads();

    void audit(WebAccess.Session session, String action);

    void debug(String message, Throwable error);

    Map<String, String> texts();

    String text(String key, Object... placeholders);

    String reason(MediaException error);

    Map<String, Object> state(WebAccess.Session session);

    UploadStore.Upload store(WebAccess.Session session, String name, InputStream body, long length) throws MediaException;

    void deleteUpload(WebAccess.Session session, String id) throws ScreenException;

    void apply(WebAccess.Session session, String screen, String upload) throws ScreenException;

    void create(WebAccess.Session session, Map<String, String> fields) throws ScreenException;

    void remove(WebAccess.Session session, String screen) throws ScreenException;
}
