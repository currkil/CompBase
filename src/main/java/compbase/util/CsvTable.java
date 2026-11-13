package compbase.util;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 表格视图：第一行当表头，其余为数据行；空行忽略。
 * 数据来源可以是 CSV 或 xlsx（见 {@link SheetReader}），列识别支持中文/英文候选名，
 * 先精确匹配再包含匹配。表头前有标题行时用 {@link #detectHeaderRow} 自动跳过。
 */
public final class CsvTable {
    private final List<String> headers;
    private final List<String> rawHeaders;
    private final List<List<String>> rows;

    private CsvTable(List<String> headers, List<String> rawHeaders, List<List<String>> rows) {
        this.headers = headers;
        this.rawHeaders = rawHeaders;
        this.rows = rows;
    }

    /** 用原始解析结果构造；第一行是表头。 */
    public static CsvTable from(List<List<String>> raw) {
        return from(raw, 0);
    }

    /** 指定表头所在行（从 0 开始，已忽略空行）。 */
    public static CsvTable from(List<List<String>> raw, int headerRowIndex) {
        List<List<String>> cleaned = withoutBlankRows(raw);
        if (cleaned.isEmpty()) {
            return new CsvTable(new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
        }
        int headerIndex = Math.max(0, Math.min(headerRowIndex, cleaned.size() - 1));
        List<String> headerCells = cleaned.get(headerIndex);
        List<String> normalized = new ArrayList<>();
        List<String> rawHeader = new ArrayList<>();
        for (String cell : headerCells) {
            rawHeader.add(cell.trim());
            normalized.add(normalize(cell));
        }
        List<List<String>> data = new ArrayList<>(
                cleaned.subList(headerIndex + 1, cleaned.size()));
        return new CsvTable(normalized, rawHeader, data);
    }

    /**
     * 在前 20 个非空行里找最像表头的一行：候选列名精确命中计 3 分、包含计 1 分，
     * 同分时优先非空单元格更多的行，避免把「XXX BOM 表」这种标题行当成表头。
     * 没有明显表头时回退到第 0 行。
     */
    public static int detectHeaderRow(List<List<String>> raw, List<String[]> candidateGroups) {
        List<List<String>> cleaned = withoutBlankRows(raw);
        if (cleaned.isEmpty() || candidateGroups == null || candidateGroups.isEmpty()) return 0;

        int limit = Math.min(cleaned.size(), 20);
        int bestRow = 0;
        int bestScore = -1;
        int bestWeight = 0;
        for (int row = 0; row < limit; row++) {
            int weight = 0;
            for (String[] group : candidateGroups) {
                weight += groupWeight(cleaned.get(row), group);
            }
            int score = weight * 100 + countNonEmpty(cleaned.get(row));
            if (score > bestScore) {
                bestScore = score;
                bestRow = row;
                bestWeight = weight;
            }
        }
        boolean acceptable = candidateGroups.size() <= 1 ? bestWeight >= 3 : bestWeight >= 2;
        return acceptable ? bestRow : 0;
    }

    private static int groupWeight(List<String> row, String[] candidates) {
        int best = 0;
        for (String cell : row) {
            String value = normalize(cell);
            if (value.isEmpty()) continue;
            for (String candidate : candidates) {
                String wanted = normalize(candidate);
                if (wanted.isEmpty()) continue;
                if (value.equals(wanted)) return 3;
                if (value.contains(wanted)) best = Math.max(best, 1);
            }
        }
        return best;
    }

    private static int countNonEmpty(List<String> row) {
        int count = 0;
        for (String cell : row) {
            if (cell != null && !cell.trim().isEmpty()) count++;
        }
        return count;
    }

    /** 去掉全空行，方便表头识别与数据行编号。 */
    private static List<List<String>> withoutBlankRows(List<List<String>> raw) {
        List<List<String>> cleaned = new ArrayList<>();
        if (raw == null) return cleaned;
        for (List<String> row : raw) {
            if (isBlankRow(row)) continue;
            cleaned.add(row);
        }
        return cleaned;
    }

    private static boolean isBlankRow(List<String> row) {
        for (String cell : row) {
            if (cell != null && !cell.trim().isEmpty()) return false;
        }
        return true;
    }

    /** 表头归一化：去空白、转小写，去掉 BOM 与全角空格。 */
    public static String normalize(String value) {
        if (value == null) return "";
        String s = value.replace("\uFEFF", "").replace('\u3000', ' ');
        s = s.trim().toLowerCase(Locale.ROOT);
        return s.replaceAll("\\s+", "");
    }

    public List<String> headers() {
        return headers;
    }

    public List<String> displayHeaders() {
        return rawHeaders;
    }

    public List<List<String>> rows() {
        return rows;
    }

    public int rowCount() {
        return rows.size();
    }

    public String cell(int rowIndex, int columnIndex) {
        if (rowIndex < 0 || rowIndex >= rows.size()) return "";
        List<String> row = rows.get(rowIndex);
        if (columnIndex < 0 || columnIndex >= row.size()) return "";
        String value = row.get(columnIndex);
        return value == null ? "" : value.trim();
    }

    /** 按候选列名找列号，找不到返回 -1。 */
    public int column(String... candidates) {
        for (String candidate : candidates) {
            String wanted = normalize(candidate);
            if (wanted.isEmpty()) continue;
            for (int i = 0; i < headers.size(); i++) {
                if (headers.get(i).equals(wanted)) return i;
            }
        }
        for (String candidate : candidates) {
            String wanted = normalize(candidate);
            if (wanted.isEmpty()) continue;
            for (int i = 0; i < headers.size(); i++) {
                if (headers.get(i).contains(wanted)) return i;
            }
        }
        return -1;
    }
}
