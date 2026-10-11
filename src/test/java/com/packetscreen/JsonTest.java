package com.packetscreen;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JsonTest {

    @Test
    void writesSafeJson() {
        String json = Json.write(Json.object("name", "</script><b>\"x\"", "count", 3, "ok", true, "list", Arrays.asList(1, "a"), "none", null));
        assertEquals("{\"name\":\"\\u003c/script\\u003e\\u003cb\\u003e\\\"x\\\"\",\"count\":3,\"ok\":true,\"list\":[1,\"a\"],\"none\":null}", json);
        assertFalse(json.contains("</"));
    }

    @Test
    void parsesFlatObjects() {
        Map<String, String> map = Json.parseObject("{ \"token\": \"a\\u00e7\\n\\\"b\", \"x\": -12.5, \"ok\": false, \"n\": null }");
        assertEquals("a\u00e7\n\"b", map.get("token"));
        assertEquals("-12.5", map.get("x"));
        assertEquals("false", map.get("ok"));
        assertNull(map.get("n"));
        assertEquals(0, Json.parseObject("{}").size());
    }

    @Test
    void rejectsMalformedInput() {
        for (String bad : new String[]{"", "[]", "{\"a\":}", "{\"a\":\"b\"", "{\"a\":\"b\"} x", "{\"a\":{}}", "{\"a\":\"\\q\"}"}) {
            assertThrows(IllegalArgumentException.class, () -> Json.parseObject(bad), bad);
        }
    }
}
