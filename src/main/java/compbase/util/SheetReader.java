package compbase.util;

import java.util.List;
import java.util.Locale;

/**
 * 表格读取入口：CSV 与 xlsx 统一成「行 -> 字段」的字符串矩阵。
 * 通过 zip 魔数或扩展名判断格式；旧版 .xls 给出明确提示。
 */
public final class SheetReader {
    private SheetReader() {
    }

    /** 读取为原始行（含表头行，未做表头识别）。 */
    public static List<List<String>> readRows(String fileName, byte[] bytes) {
        String name = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        if (Xlsx.isZip(bytes)) {
            return readXlsx(bytes);
        }
        if (name.endsWith(".xlsx")) {
            throw new IllegalArgumentException("文件扩展名是 .xlsx，但内容不是有效的 xlsx 压缩包");
        }
        if (name.endsWith(".xls")) {
            throw new IllegalArgumentException("暂不支持旧版 .xls，请在 Excel/WPS 中另存为 .xlsx 或 CSV");
        }
        return Csv.parse(Csv.decode(bytes));
    }

    private static List<List<String>> readXlsx(byte[] bytes) {
        try {
            return Xlsx.readFirstSheet(bytes);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            String message = e.getMessage();
            throw new IllegalArgumentException("xlsx 解析失败"
                    + (message == null || message.isBlank() ? "" : "：" + message));
        }
    }
}
