package com.packetscreen;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

final class Json {

    private Json() {
    }

    static String quote(String value) {
        StringBuilder builder = new StringBuilder(value.length() + 2).append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"':
                    builder.append("\\\"");
                    break;
                case '\\':
                    builder.append("\\\\");
                    break;
                case '\n':
                    builder.append("\\n");
                    break;
                case '\r':
                    builder.append("\\r");
                    break;
                case '\t':
                    builder.append("\\t");
                    break;
                case '<':
                    builder.append("\\u003c");
                    break;
                case '>':
                    builder.append("\\u003e");
                    break;
                case '&':
                    builder.append("\\u0026");
                    break;
                default:
                    if (c < 0x20 || c == ' ' || c == ' ') {
                        builder.append(String.format("\\u%04x", (int) c));
                    } else {
                        builder.append(c);
                    }
            }
        }
        return builder.append('"').toString();
    }

    static String write(Object value) {
        StringBuilder builder = new StringBuilder();
        append(builder, value);
        return builder.toString();
    }

    static Map<String, Object> object(Object... entries) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < entries.length; i += 2) {
            map.put(String.valueOf(entries[i]), entries[i + 1]);
        }
        return map;
    }

    private static void append(StringBuilder builder, Object value) {
        if (value == null) {
            builder.append("null");
        } else if (value instanceof String) {
            builder.append(quote((String) value));
        } else if (value instanceof Boolean || value instanceof Integer || value instanceof Long) {
            builder.append(value);
        } else if (value instanceof Number) {
            double number = ((Number) value).doubleValue();
            builder.append(Double.isFinite(number) ? String.valueOf(number) : "null");
        } else if (value instanceof Map) {
            builder.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                if (!first) {
                    builder.append(',');
                }
                first = false;
                builder.append(quote(String.valueOf(entry.getKey()))).append(':');
                append(builder, entry.getValue());
            }
            builder.append('}');
        } else if (value instanceof Collection) {
            builder.append('[');
            boolean first = true;
            for (Object item : (Collection<?>) value) {
                if (!first) {
                    builder.append(',');
                }
                first = false;
                append(builder, item);
            }
            builder.append(']');
        } else {
            builder.append(quote(String.valueOf(value)));
        }
    }

    static Map<String, String> parseObject(String text) {
        Parser parser = new Parser(text);
        Map<String, String> result = parser.object();
        parser.whitespace();
        if (!parser.end()) {
            throw new IllegalArgumentException("Trailing characters");
        }
        return result;
    }

    private static final class Parser {

        private final String text;
        private int position;

        Parser(String text) {
            this.text = text;
        }

        boolean end() {
            return position >= text.length();
        }

        void whitespace() {
            while (position < text.length() && Character.isWhitespace(text.charAt(position))) {
                position++;
            }
        }

        char next() {
            if (position >= text.length()) {
                throw new IllegalArgumentException("Unexpected end");
            }
            return text.charAt(position++);
        }

        void expect(char c) {
            whitespace();
            if (next() != c) {
                throw new IllegalArgumentException("Expected " + c);
            }
        }

        Map<String, String> object() {
            Map<String, String> map = new LinkedHashMap<>();
            expect('{');
            whitespace();
            if (position < text.length() && text.charAt(position) == '}') {
                position++;
                return map;
            }
            while (true) {
                whitespace();
                String key = string();
                expect(':');
                whitespace();
                map.put(key, value());
                if (map.size() > 64) {
                    throw new IllegalArgumentException("Too many fields");
                }
                whitespace();
                char c = next();
                if (c == '}') {
                    return map;
                }
                if (c != ',') {
                    throw new IllegalArgumentException("Expected , or }");
                }
            }
        }

        String value() {
            if (end()) {
                throw new IllegalArgumentException("Unexpected end");
            }
            char c = text.charAt(position);
            if (c == '"') {
                return string();
            }
            int start = position;
            while (position < text.length() && "{}[],: \t\r\n".indexOf(text.charAt(position)) < 0) {
                position++;
            }
            String literal = text.substring(start, position);
            if (literal.equals("null")) {
                return null;
            }
            if (literal.equals("true") || literal.equals("false") || literal.matches("-?\\d+(\\.\\d+)?([eE][+-]?\\d+)?")) {
                return literal;
            }
            throw new IllegalArgumentException("Unsupported value");
        }

        String string() {
            if (next() != '"') {
                throw new IllegalArgumentException("Expected string");
            }
            StringBuilder builder = new StringBuilder();
            while (true) {
                char c = next();
                if (c == '"') {
                    return builder.toString();
                }
                if (c == '\\') {
                    char escape = next();
                    switch (escape) {
                        case '"':
                        case '\\':
                        case '/':
                            builder.append(escape);
                            break;
                        case 'n':
                            builder.append('\n');
                            break;
                        case 'r':
                            builder.append('\r');
                            break;
                        case 't':
                            builder.append('\t');
                            break;
                        case 'b':
                            builder.append('\b');
                            break;
                        case 'f':
                            builder.append('\f');
                            break;
                        case 'u':
                            if (position + 4 > text.length()) {
                                throw new IllegalArgumentException("Bad escape");
                            }
                            builder.append((char) Integer.parseInt(text.substring(position, position + 4), 16));
                            position += 4;
                            break;
                        default:
                            throw new IllegalArgumentException("Bad escape");
                    }
                } else {
                    builder.append(c);
                }
                if (builder.length() > 4096) {
                    throw new IllegalArgumentException("String too long");
                }
            }
        }
    }
}
