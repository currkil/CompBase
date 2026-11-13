package compbase.service;

import compbase.repository.BomRepository;
import compbase.repository.ImportLogRepository;
import compbase.util.CsvTable;
import compbase.util.MappingRequiredException;
import compbase.util.SheetReader;
import compbase.util.Values;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * BOM 导入与匹配。
 * 只做匹配（写 bom_item 的 match_status），不占用库存；占用在确认时进行。
 */
public class BomService {
    private static final String[] VALUE_COLUMNS = {
            "值", "型号", "元件值", "参数", "器件值", "规格值", "value", "name"
    };
    private static final String[] PACKAGE_COLUMNS = {
            "封装", "封装名", "封装规格", "footprint", "package"
    };
    private static final String[] QUANTITY_COLUMNS = {
            "数量", "用量", "个数", "数目", "quantity", "qty"
    };

    private final BomRepository bomRepository;
    private final ImportLogRepository logRepository;

    public BomService(BomRepository bomRepository, ImportLogRepository logRepository) {
        this.bomRepository = bomRepository;
        this.logRepository = logRepository;
    }

    /**
     * 导入 BOM（CSV 或 xlsx）。value/package/quantity 三列可自动识别，也可由调用方指定列号。
     * xlsx 只读第一个工作表；表头前有标题行时会自动跳过。
     */
    public Map<String, Object> importFile(byte[] bytes, String fileName, String projectName,
                                          Integer valueCol, Integer packageCol, Integer quantityCol)
            throws SQLException {
        List<List<String>> rawRows = SheetReader.readRows(fileName, bytes);
        int headerRow = CsvTable.detectHeaderRow(rawRows,
                Arrays.asList(VALUE_COLUMNS, PACKAGE_COLUMNS, QUANTITY_COLUMNS));
        CsvTable table = CsvTable.from(rawRows, headerRow);

        if (table.displayHeaders().isEmpty()) {
            throw new IllegalArgumentException("文件内容为空或缺少表头");
        }

        int valueIndex = valueCol != null ? valueCol : table.column(VALUE_COLUMNS);
        int quantityIndex = quantityCol != null ? quantityCol : table.column(QUANTITY_COLUMNS);
        int packageIndex = packageCol != null ? packageCol : table.column(PACKAGE_COLUMNS);

        if (valueIndex < 0 || quantityIndex < 0) {
            List<Map<String, Object>> fields = new ArrayList<>();
            fields.add(MappingRequiredException.field("value_col", "BOM 值列", true));
            fields.add(MappingRequiredException.field("package_col", "BOM 封装列", false));
            fields.add(MappingRequiredException.field("quantity_col", "数量列", true));
            Map<String, String> values = new LinkedHashMap<>();
            values.put("value_col", String.valueOf(Math.max(valueIndex, 0)));
            values.put("package_col", String.valueOf(Math.max(packageIndex, 0)));
            values.put("quantity_col", String.valueOf(Math.max(quantityIndex, 0)));
            throw new MappingRequiredException("BOM", table.displayHeaders(), fields, values);
        }
        if (table.rowCount() == 0) {
            throw new IllegalArgumentException("文件没有数据行");
        }

        long bomId = bomRepository.createImport(
                Values.trimToNull(projectName), fileName == null ? "bom.csv" : fileName, null);

        int total = table.rowCount();
        int matched = 0;
        int failed = 0;
        List<String> errors = new ArrayList<>();

        for (int row = 0; row < total; row++) {
            int line = row + 2;
            String rawValue = table.cell(row, valueIndex);
            String rawPackage = packageIndex >= 0 ? table.cell(row, packageIndex) : "";
            long quantity = parseQuantity(table.cell(row, quantityIndex));

            long itemId = bomRepository.insertItem(bomId,
                    rawValue.isEmpty() ? null : rawValue,
                    rawPackage.isEmpty() ? null : rawPackage,
                    quantity);

            Map<String, Object> item = bomRepository.findItem(itemId);
            match(item);
            Map<String, Object> after = bomRepository.findItem(itemId);
            String status = Values.toStr(after.get("match_status"));
            if (BomRepository.STATUS_ERROR_NOT_FOUND.equals(status)) {
                failed++;
                errors.add("第" + line + "行：" + Values.toStr(after.get("match_note")));
            } else if (BomRepository.STATUS_MATCHED.equals(status)) {
                matched++;
            }
        }

        long logId = logRepository.insert("BOM",
                fileName == null ? "bom.csv" : fileName, null, total, matched, 0, failed, errors);
        bomRepository.linkLog(bomId, logId);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("bom", bomRepository.findImport(bomId));
        result.put("items", bomRepository.listItems(bomId));
        result.put("summary", summary(total, matched, failed));
        result.put("log_id", logId);
        return result;
    }

    /** 对一条明细做自动匹配（导入时与人工重置时都会用到）。 */
    public void match(Map<String, Object> item) throws SQLException {
        long itemId = Values.toLong(item.get("bom_item_id"));
        String rawValue = Values.trimToNull(item.get("raw_value"));
        String rawPackage = Values.trimToNull(item.get("raw_package"));
        long quantity = Values.toLong(item.get("quantity")) == null
                ? 0 : Values.toLong(item.get("quantity"));

        if (rawValue == null) {
            bomRepository.updateItemMatch(itemId, null, BomRepository.STATUS_ERROR_NOT_FOUND,
                    "BOM 值为空", false);
            return;
        }

        List<Map<String, Object>> candidates = bomRepository.findComponentsByValue(rawValue);
        if (candidates.isEmpty()) {
            bomRepository.updateItemMatch(itemId, null, BomRepository.STATUS_ERROR_NOT_FOUND,
                    "未找到值为 " + rawValue + " 的元器件", false);
            return;
        }

        Map<String, Object> chosen = null;
        String status = BomRepository.STATUS_WARN_AMBIGUOUS;
        String note = null;
        boolean packageMismatch = false;

        if (rawPackage != null) {
            List<Map<String, Object>> samePackage = new ArrayList<>();
            for (Map<String, Object> candidate : candidates) {
                String packageName = Values.toStr(candidate.get("package_name"));
                if (packageName != null && packageName.equalsIgnoreCase(rawPackage)) {
                    samePackage.add(candidate);
                }
            }
            if (samePackage.size() == 1) {
                chosen = samePackage.get(0);
            } else if (samePackage.size() > 1) {
                status = BomRepository.STATUS_WARN_AMBIGUOUS;
                note = "值与封装都匹配到 " + samePackage.size() + " 条记录，请人工指定";
            } else if (candidates.size() == 1) {
                chosen = candidates.get(0);
                status = BomRepository.STATUS_WARN_PACKAGE_MISMATCH;
                packageMismatch = true;
                String actual = Values.toStr(chosen.get("package_name"));
                note = "封装不匹配：BOM 为 " + rawPackage + "，库存为 " + (actual == null ? "无封装" : actual);
            } else {
                status = BomRepository.STATUS_WARN_AMBIGUOUS;
                note = "同值 " + candidates.size() + " 条，且封装都不匹配，请人工指定";
            }
        } else if (candidates.size() == 1) {
            chosen = candidates.get(0);
        } else {
            status = BomRepository.STATUS_WARN_AMBIGUOUS;
            note = "同值匹配到 " + candidates.size() + " 条记录，请人工指定";
        }

        if (chosen != null) {
            Long componentId = Values.toLong(chosen.get("id"));
            Long available = Values.toLong(chosen.get("available_qty"));
            long availableQty = available == null ? 0 : available;
            if (packageMismatch) {
                // 封装不符要人工确认，即使数量足够也保持黄色
                bomRepository.updateItemMatch(itemId, componentId,
                        BomRepository.STATUS_WARN_PACKAGE_MISMATCH, note, false);
            } else if (availableQty >= quantity) {
                bomRepository.updateItemMatch(itemId, componentId, BomRepository.STATUS_MATCHED,
                        note, false);
            } else {
                bomRepository.updateItemMatch(itemId, componentId,
                        BomRepository.STATUS_WARN_SHORTAGE,
                        "可用 " + availableQty + " 少于 BOM 需要的 " + quantity, false);
            }
        } else {
            bomRepository.updateItemMatch(itemId, null, status, note, false);
        }
    }

    /** 人工指定元器件，重新计算结果状态。 */
    public Map<String, Object> matchItem(long itemId, long componentId) throws SQLException {
        Map<String, Object> item = bomRepository.findItem(itemId);
        if (item == null) return null;
        Map<String, Object> component = bomRepository.findComponentBrief(componentId);
        if (component == null) {
            throw new IllegalArgumentException("元器件不存在: " + componentId);
        }
        long quantity = Values.toLong(item.get("quantity")) == null
                ? 0 : Values.toLong(item.get("quantity"));
        Long available = Values.toLong(component.get("available_qty"));
        long availableQty = available == null ? 0 : available;
        if (availableQty >= quantity) {
            bomRepository.updateItemMatch(itemId, componentId, BomRepository.STATUS_MATCHED,
                    "人工指定", true);
        } else {
            bomRepository.updateItemMatch(itemId, componentId, BomRepository.STATUS_WARN_SHORTAGE,
                    "人工指定，但可用 " + availableQty + " 少于需要 " + quantity, true);
        }
        return bomRepository.findItem(itemId);
    }

    private static long parseQuantity(String raw) {
        if (raw == null || raw.isBlank()) return 0;
        try {
            double value = Double.parseDouble(raw.trim());
            if (value < 0) return 0;
            return Math.round(value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static Map<String, Object> summary(int total, int matched, int failed) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("total", total);
        map.put("matched", matched);
        map.put("failed", failed);
        map.put("warning", total - matched - failed);
        return map;
    }
}
