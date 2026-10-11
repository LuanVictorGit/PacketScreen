package com.packetscreen;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UploadStoreTest {

    @TempDir
    File directory;

    private static final UploadStore.Validator ACCEPT = (file, kind) -> {
    };

    private UploadStore.Upload store(UploadStore store, String name, byte[] data, long limit, long quota) throws MediaException {
        return store.store(name, new ByteArrayInputStream(data), data.length, limit, limit, quota, ACCEPT);
    }

    @Test
    void storesImagesUnderSafeNames() throws Exception {
        UploadStore store = new UploadStore(directory);
        byte[] png = TestSupport.image("png", 4, 4, 0xFF00FF00);
        UploadStore.Upload upload = store(store, "..\\..//Foto Ção!.PNG", png, 100000, 1000000);
        assertTrue(upload.id.matches("foto-cao-[0-9a-f]{6}\\.png"), upload.id);
        assertEquals(UploadStore.Kind.IMAGE, upload.kind);
        assertTrue(new File(directory, upload.id).isFile());
        assertEquals(png.length, upload.size);
        assertTrue(store.exists(upload.id));
        List<UploadStore.Upload> list = store.list();
        assertEquals(1, list.size());
        assertTrue(store.delete(upload.id));
        assertFalse(store.exists(upload.id));
    }

    @Test
    void rejectsDangerousOrInvalidContent() throws Exception {
        UploadStore store = new UploadStore(directory);
        byte[] page = "<html><script>alert(1)</script></html>".getBytes(StandardCharsets.UTF_8);
        assertEquals(MediaException.Reason.WEB_PAGE, assertThrows(MediaException.class, () -> store(store, "a.png", page, 1000, 1000)).reason);
        byte[] random = {9, 8, 7, 6, 5, 4, 3, 2, 1, 0, 1, 2, 3, 4};
        assertEquals(MediaException.Reason.UNSUPPORTED_FORMAT, assertThrows(MediaException.class, () -> store(store, "a.exe", random, 1000, 1000)).reason);
        byte[] png = TestSupport.image("png", 64, 64, 0xFF123456);
        assertEquals(MediaException.Reason.TOO_LARGE, assertThrows(MediaException.class, () -> store(store, "a.png", png, 10, 100000)).reason);
        assertEquals(MediaException.Reason.STORAGE_FULL, assertThrows(MediaException.class, () -> store(store, "a.png", png, 100000, 10)).reason);
        assertEquals(MediaException.Reason.NETWORK, assertThrows(MediaException.class,
                () -> store.store("a.png", new ByteArrayInputStream(png), png.length + 50, 100000, 100000, 100000, ACCEPT)).reason);
        assertEquals(0, store.list().size());
        File[] leftovers = directory.listFiles();
        assertTrue(leftovers == null || leftovers.length == 0);
    }

    @Test
    void validatorCanRejectUploads() throws Exception {
        UploadStore store = new UploadStore(directory);
        byte[] png = TestSupport.image("png", 4, 4, 0xFF00FF00);
        UploadStore.Validator reject = (file, kind) -> {
            throw new MediaException(MediaException.Reason.DECODE);
        };
        assertThrows(MediaException.class, () -> store.store("a.png", new ByteArrayInputStream(png), png.length, 10000, 10000, 100000, reject));
        assertEquals(0, store.list().size());
    }

    @Test
    void detectsVideoContainers() throws Exception {
        byte[] mp4 = new byte[64];
        System.arraycopy("ftypisom".getBytes(StandardCharsets.ISO_8859_1), 0, mp4, 4, 8);
        assertEquals("mp4", UploadStore.extension(mp4, mp4.length));
        byte[] mov = new byte[64];
        System.arraycopy("ftypqt  ".getBytes(StandardCharsets.ISO_8859_1), 0, mov, 4, 8);
        assertEquals("mov", UploadStore.extension(mov, mov.length));
        byte[] webm = new byte[64];
        webm[0] = 0x1A;
        webm[1] = 0x45;
        webm[2] = (byte) 0xDF;
        webm[3] = (byte) 0xA3;
        System.arraycopy("webm".getBytes(StandardCharsets.ISO_8859_1), 0, webm, 20, 4);
        assertEquals("webm", UploadStore.extension(webm, webm.length));
        assertEquals(UploadStore.Kind.VIDEO, UploadStore.kind("clip-abc123.webm"));
    }

    @Test
    void resolvesOnlyValidIds() {
        UploadStore store = new UploadStore(directory);
        assertNull(store.file("../config.yml"));
        assertNull(store.file("a/b.png"));
        assertNull(store.file("UPPER.png"));
        assertFalse(UploadStore.validId("x.exe"));
        assertTrue(UploadStore.validId("logo-0a1b2c.png"));
        assertEquals("media", UploadStore.base("...."));
        assertEquals("upload:logo-0a1b2c.png".substring(UploadStore.PREFIX.length()), UploadStore.id("upload:logo-0a1b2c.png"));
    }
}
