package compbase.util;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * CSV 小工具：编码探测 + RFC 4180 风格解析。
 * Excel 导出的中文 CSV 常见 UTF-8（带或不带 BOM）与 GBK 两种，这里自动处理。
 */
public final class Csv {
    private static final Charset GBK = Charset.forName("GBK");

    private Csv() {
    }

    /** 字节解码：优先 UTF-8（严格校验），失败回退 GBK。 */
    public static String decode(byte[] bytes) {
        if (bytes == null || bytes.length == 0) return "";
        int offset = 0;
        if (bytes.length >= 3
                && (bytes[0] & 0xFF) == 0xEF
                && (bytes[1] & 0xFF) == 0xBB
                && (bytes[2] & 0xFF) == 0xBF) {
            offset = 3;
        }
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes, offset, bytes.length - offset))
                    .toString();
        } catch (CharacterCodingException e) {
            return new String(bytes, offset, bytes.length - offset, GBK);
        }
    }

    /** 解析为「行 -> 字段」。未闭合的引号按容错处理（读到末尾为止）。 */
    public static List<List<String>> parse(String text) {
        List<List<String>> rows = new ArrayList<>();
        if (text == null || text.isEmpty()) return rows;

        List<String> row = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        int index = 0;

        while (index < text.length()) {
            char c = text.charAt(index);
            if (quoted) {
                if (c == '"') {
                    if (index + 1 < text.length() && text.charAt(index + 1) == '"') {
                        field.append('"');
                        index += 2;
                        continue;
                    }
                    quoted = false;
                    index++;
                    continue;
                }
                field.append(c);
                index++;
                continue;
            }
            switch (c) {
                case '"':
                    quoted = true;
                    index++;
                    break;
                case ',':
                    row.add(field.toString());
                    field.setLength(0);
                    index++;
                    break;
                case '\r':
                    index++;
                    break;
                case '\n':
                    row.add(field.toString());
                    field.setLength(0);
                    rows.add(row);
                    row = new ArrayList<>();
                    index++;
                    break;
                default:
                    field.append(c);
                    index++;
            }
        }
        if (field.length() > 0 || !row.isEmpty()) {
            row.add(field.toString());
            rows.add(row);
        }
        return rows;
    }

    /** 综合便捷方法：字节 -> 解析后的表格（表头 + 数据行）。 */
    public static CsvTable parseTable(byte[] bytes) {
        return CsvTable.from(parse(decode(bytes)));
    }
}
