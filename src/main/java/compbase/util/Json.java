package compbase.util;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 极简 JSON 工具：手写序列化与解析，不引入第三方依赖（交接文档中约定的做法）。
 * <p>
 * 序列化支持：Map、Iterable、数组会被当作字符串处理之外的类型，其余按 toString 输出。
 * 解析结果：对象 -> LinkedHashMap，数组 -> ArrayList，整数 -> Long，小数 -> Double。
 */
public final class Json {
    private Json() {
    }

    // ==================== 序列化 ====================

    public static String write(Object value) {
        StringBuilder sb = new StringBuilder(128);
        writeValue(sb, value);
        return sb.toString();
    }

    private static void writeValue(StringBuilder sb, Object value) {
        if (value == null) {
            sb.append("null");
        } else if (value instanceof String) {
            writeString(sb, (String) value);
        } else if (value instanceof Boolean) {
            sb.append(((Boolean) value) ? "true" : "false");
        } else if (value instanceof Double || value instanceof Float) {
            double d = ((Number) value).doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) {
                sb.append("null");
            } else {
                sb.append(d);
            }
        } else if (value instanceof Number) {
            sb.append(value);
        } else if (value instanceof Map) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                if (!first) sb.append(',');
                first = false;
                writeString(sb, String.valueOf(entry.getKey()));
                sb.append(':');
                writeValue(sb, entry.getValue());
            }
            sb.append('}');
        } else if (value instanceof Iterable) {
            sb.append('[');
            boolean first = true;
            for (Object item : (Iterable<?>) value) {
                if (!first) sb.append(',');
                first = false;
                writeValue(sb, item);
            }
            sb.append(']');
        } else {
            // 兜底：当成字符串输出，保证不会生成非法 JSON
            writeString(sb, String.valueOf(value));
        }
    }

    private static void writeString(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\b':
                    sb.append("\\b");
                    break;
                case '\f':
                    sb.append("\\f");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                default:
                    if (c < 0x20) {
                        sb.append("\\u");
                        String hex = Integer.toHexString(c);
                        for (int pad = hex.length(); pad < 4; pad++) sb.append('0');
                        sb.append(hex);
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
    }

    // ==================== 解析 ====================

    /** 解析任意 JSON 文本。 */
    public static Object parse(String text) {
        if (text == null) throw new IllegalArgumentException("JSON 内容为空");
        Parser parser = new Parser(text);
        parser.skipWhitespace();
        if (parser.eof()) throw new IllegalArgumentException("JSON 内容为空");
        Object value = parser.readValue();
        parser.skipWhitespace();
        if (!parser.eof()) {
            throw new IllegalArgumentException("JSON 末尾存在多余内容（位置 " + parser.pos + "）");
        }
        return value;
    }

    /** 解析为 JSON 对象（顶层必须是 {...}）。 */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String text) {
        Object value = parse(text);
        if (!(value instanceof Map)) {
            throw new IllegalArgumentException("请求体必须是 JSON 对象");
        }
        return (Map<String, Object>) value;
    }

    private static final class Parser {
        private final String text;
        private int pos;

        Parser(String text) {
            this.text = text;
        }

        boolean eof() {
            return pos >= text.length();
        }

        void skipWhitespace() {
            while (pos < text.length()) {
                char c = text.charAt(pos);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                    pos++;
                } else {
                    break;
                }
            }
        }

        Object readValue() {
            skipWhitespace();
            if (eof()) throw error("内容意外结束");
            char c = text.charAt(pos);
            switch (c) {
                case '{':
                    return readObject();
                case '[':
                    return readArray();
                case '"':
                    return readString();
                case 't':
                    expect("true");
                    return Boolean.TRUE;
                case 'f':
                    expect("false");
                    return Boolean.FALSE;
                case 'n':
                    expect("null");
                    return null;
                default:
                    return readNumber();
            }
        }

        Map<String, Object> readObject() {
            Map<String, Object> map = new LinkedHashMap<>();
            pos++; // '{'
            skipWhitespace();
            if (peek() == '}') {
                pos++;
                return map;
            }
            while (true) {
                skipWhitespace();
                String key = readString();
                skipWhitespace();
                expectChar(':');
                map.put(key, readValue());
                skipWhitespace();
                char c = nextChar();
                if (c == '}') return map;
                if (c != ',') throw error("对象属性之间缺少逗号");
            }
        }

        List<Object> readArray() {
            List<Object> list = new ArrayList<>();
            pos++; // '['
            skipWhitespace();
            if (peek() == ']') {
                pos++;
                return list;
            }
            while (true) {
                list.add(readValue());
                skipWhitespace();
                char c = nextChar();
                if (c == ']') return list;
                if (c != ',') throw error("数组元素之间缺少逗号");
            }
        }

        String readString() {
            skipWhitespace();
            expectChar('"');
            StringBuilder sb = new StringBuilder();
            while (true) {
                if (eof()) throw error("字符串没有结束引号");
                char c = text.charAt(pos++);
                if (c == '"') return sb.toString();
                if (c != '\\') {
                    sb.append(c);
                    continue;
                }
                if (eof()) throw error("字符串没有结束引号");
                char escaped = text.charAt(pos++);
                switch (escaped) {
                    case '"':
                        sb.append('"');
                        break;
                    case '\\':
                        sb.append('\\');
                        break;
                    case '/':
                        sb.append('/');
                        break;
                    case 'b':
                        sb.append('\b');
                        break;
                    case 'f':
                        sb.append('\f');
                        break;
                    case 'n':
                        sb.append('\n');
                        break;
                    case 'r':
                        sb.append('\r');
                        break;
                    case 't':
                        sb.append('\t');
                        break;
                    case 'u':
                        if (pos + 4 > text.length()) throw error("\\u 转义不完整");
                        String hex = text.substring(pos, pos + 4);
                        try {
                            sb.append((char) Integer.parseInt(hex, 16));
                        } catch (NumberFormatException e) {
                            throw error("非法 \\u 转义: " + hex);
                        }
                        pos += 4;
                        break;
                    default:
                        throw error("非法转义字符: \\" + escaped);
                }
            }
        }

        Object readNumber() {
            int start = pos;
            if (peek() == '-') pos++;
            while (!eof() && isDigit(text.charAt(pos))) pos++;
            boolean floating = false;
            if (peek() == '.') {
                floating = true;
                pos++;
                while (!eof() && isDigit(text.charAt(pos))) pos++;
            }
            if (peek() == 'e' || peek() == 'E') {
                floating = true;
                pos++;
                if (peek() == '+' || peek() == '-') pos++;
                while (!eof() && isDigit(text.charAt(pos))) pos++;
            }
            if (start == pos) throw error("非法数字");
            String raw = text.substring(start, pos);
            try {
                return floating ? (Object) Double.valueOf(raw) : (Object) Long.valueOf(raw);
            } catch (NumberFormatException e) {
                throw error("非法数字: " + raw);
            }
        }

        private char peek() {
            return pos < text.length() ? text.charAt(pos) : '\0';
        }

        private char nextChar() {
            if (eof()) throw error("内容意外结束");
            return text.charAt(pos++);
        }

        private void expect(String word) {
            if (!text.startsWith(word, pos)) throw error("期望 \"" + word + "\"");
            pos += word.length();
        }

        private void expectChar(char c) {
            if (eof() || text.charAt(pos) != c) throw error("期望 '" + c + "'");
            pos++;
        }

        private IllegalArgumentException error(String message) {
            return new IllegalArgumentException(message + "（位置 " + pos + "）");
        }

        private static boolean isDigit(char c) {
            return c >= '0' && c <= '9';
        }
    }

    // ==================== 便捷读取 ====================

    public static boolean has(Map<String, ?> map, String key) {
        return map != null && map.containsKey(key);
    }

    public static String getString(Map<String, ?> map, String key) {
        return map == null ? null : Values.trimToNull(map.get(key));
    }

    public static Long getLong(Map<String, ?> map, String key) {
        return map == null ? null : Values.toLong(map.get(key));
    }

    public static Integer getInteger(Map<String, ?> map, String key) {
        return map == null ? null : Values.toInteger(map.get(key));
    }

    public static Double getDouble(Map<String, ?> map, String key) {
        return map == null ? null : Values.toDouble(map.get(key));
    }

    public static Boolean getBoolean(Map<String, ?> map, String key) {
        Object value = map == null ? null : map.get(key);
        if (value instanceof Boolean) return (Boolean) value;
        if (value instanceof Number) return ((Number) value).intValue() != 0;
        if (value == null) return null;
        String s = String.valueOf(value).trim().toLowerCase();
        if ("true".equals(s) || "1".equals(s) || "yes".equals(s)) return Boolean.TRUE;
        if ("false".equals(s) || "0".equals(s) || "no".equals(s)) return Boolean.FALSE;
        return null;
    }
}
