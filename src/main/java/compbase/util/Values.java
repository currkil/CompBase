package compbase.util;

/**
 * 基础类型转换工具。
 * 数据库驱动返回的 Number 类型并不固定（Integer / Long / Double），
 * 而请求体里解析出来的也是 Object，这里统一做一次收敛。
 */
public final class Values {
    private Values() {
    }

    /** 转为 Long，无法转换时返回 null。 */
    public static Long toLong(Object value) {
        if (value == null) return null;
        if (value instanceof Number) return ((Number) value).longValue();
        String s = String.valueOf(value).trim();
        if (s.isEmpty()) return null;
        try {
            return Long.valueOf(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 转为 Integer，无法转换时返回 null。 */
    public static Integer toInteger(Object value) {
        Long l = toLong(value);
        if (l == null) return null;
        if (l > Integer.MAX_VALUE || l < Integer.MIN_VALUE) return null;
        return l.intValue();
    }

    /** 转为 Double，无法转换时返回 null。 */
    public static Double toDouble(Object value) {
        if (value == null) return null;
        if (value instanceof Number) return ((Number) value).doubleValue();
        String s = String.valueOf(value).trim();
        if (s.isEmpty()) return null;
        try {
            return Double.valueOf(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 转为 String，null 保持 null。 */
    public static String toStr(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    /** 去掉首尾空白；空串返回 null。 */
    public static String trimToNull(String value) {
        if (value == null) return null;
        String s = value.trim();
        return s.isEmpty() ? null : s;
    }

    /** 取字符串并去空白，空串返回 null。 */
    public static String trimToNull(Object value) {
        if (value == null) return null;
        return trimToNull(String.valueOf(value));
    }
}
