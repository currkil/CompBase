package compbase.util;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 极简 xlsx 读取器：xlsx 本质是 zip + XML，用 JDK 自带的 ZipInputStream 与 StAX 解析，
 * 不引入 Apache POI 之类的重依赖（保持项目「零外部依赖」的路线）。
 * <p>
 * 只读取第一个工作表，单元格按行/列还原成字符串矩阵；支持共享字符串、内联字符串、
 * 数字、布尔与公式缓存值。日期会按 Excel 序列号显示成数字（BOM 场景用不到日期）。
 */
public final class Xlsx {
    private static final long MAX_ENTRY_BYTES = 32L * 1024 * 1024;
    private static final long MAX_TOTAL_BYTES = 128L * 1024 * 1024;

    private Xlsx() {
    }

    /** 文件是否是 zip 容器（xlsx 的开头是 PK\x03\x04）。 */
    public static boolean isZip(byte[] bytes) {
        return bytes != null && bytes.length >= 4
                && (bytes[0] & 0xFF) == 0x50 && (bytes[1] & 0xFF) == 0x4B
                && (bytes[2] & 0xFF) == 0x03 && (bytes[3] & 0xFF) == 0x04;
    }

    /** 读取第一个工作表为字符串矩阵（含表头行）。 */
    public static List<List<String>> readFirstSheet(byte[] bytes) throws IOException, XMLStreamException {
        if (!isZip(bytes)) {
            throw new IllegalArgumentException("不是有效的 xlsx 文件");
        }
        Map<String, byte[]> entries = readEntries(bytes);
        List<SheetRef> sheets = readSheets(entries);
        if (sheets.isEmpty()) {
            throw new IllegalArgumentException("xlsx 中没有工作表");
        }
        Map<String, String> relationships = readRelationships(entries);

        SheetRef first = sheets.get(0);
        String target = first.relationshipId == null ? null : relationships.get(first.relationshipId);
        String path = target == null ? "xl/worksheets/sheet1.xml" : resolveTarget(target);
        byte[] sheetXml = entries.get(path);
        if (sheetXml == null) {
            // 少数工具生成的 rels 与文件不一致，退化为按文件名找第一个 sheetN.xml
            path = fallbackWorksheet(entries);
            sheetXml = path == null ? null : entries.get(path);
        }
        if (sheetXml == null) {
            throw new IllegalArgumentException("xlsx 中找不到工作表内容（工作表：" + sheetNames(sheets) + "）");
        }
        List<String> sharedStrings = readSharedStrings(entries);
        return readRows(sheetXml, sharedStrings);
    }

    /** 工作表名称列表（便于前端/日志提示）。 */
    public static List<String> sheetNames(byte[] bytes) throws IOException, XMLStreamException {
        List<SheetRef> sheets = readSheets(readEntries(bytes));
        List<String> names = new ArrayList<>();
        for (SheetRef sheet : sheets) names.add(sheet.name);
        return names;
    }

    // ==================== zip ====================

    private static Map<String, byte[]> readEntries(byte[] bytes) throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        long total = 0;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry;
            byte[] buffer = new byte[8192];
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.isDirectory()) continue;
                String name = entry.getName().replace('\\', '/');
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                int read;
                long size = 0;
                while ((read = zip.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                    size += read;
                    total += read;
                    if (size > MAX_ENTRY_BYTES || total > MAX_TOTAL_BYTES) {
                        throw new IllegalArgumentException("xlsx 文件过大");
                    }
                }
                entries.put(name, out.toByteArray());
            }
        }
        return entries;
    }

    // ==================== workbook / rels ====================

    private static final class SheetRef {
        final String name;
        final String relationshipId;

        SheetRef(String name, String relationshipId) {
            this.name = name;
            this.relationshipId = relationshipId;
        }
    }

    private static List<SheetRef> readSheets(Map<String, byte[]> entries) throws XMLStreamException {
        byte[] workbook = entries.get("xl/workbook.xml");
        if (workbook == null) {
            throw new IllegalArgumentException("不是有效的 xlsx：缺少 xl/workbook.xml");
        }
        List<SheetRef> sheets = new ArrayList<>();
        XMLStreamReader reader = newReader(workbook);
        try {
            while (reader.hasNext()) {
                int event = reader.next();
                if (event != XMLStreamConstants.START_ELEMENT
                        || !"sheet".equals(reader.getLocalName())) {
                    continue;
                }
                String name = null;
                String relationshipId = null;
                for (int i = 0; i < reader.getAttributeCount(); i++) {
                    String attribute = reader.getAttributeLocalName(i);
                    if ("name".equals(attribute)) {
                        name = reader.getAttributeValue(i);
                    } else if ("id".equals(attribute)) {
                        relationshipId = reader.getAttributeValue(i);
                    }
                }
                if (name != null) {
                    sheets.add(new SheetRef(name, relationshipId));
                }
            }
        } finally {
            reader.close();
        }
        return sheets;
    }

    private static Map<String, String> readRelationships(Map<String, byte[]> entries)
            throws XMLStreamException {
        Map<String, String> relationships = new HashMap<>();
        byte[] data = entries.get("xl/_rels/workbook.xml.rels");
        if (data == null) return relationships;
        XMLStreamReader reader = newReader(data);
        try {
            while (reader.hasNext()) {
                int event = reader.next();
                if (event != XMLStreamConstants.START_ELEMENT
                        || !"Relationship".equals(reader.getLocalName())) {
                    continue;
                }
                String id = null;
                String target = null;
                for (int i = 0; i < reader.getAttributeCount(); i++) {
                    String attribute = reader.getAttributeLocalName(i);
                    if ("Id".equals(attribute)) {
                        id = reader.getAttributeValue(i);
                    } else if ("Target".equals(attribute)) {
                        target = reader.getAttributeValue(i);
                    }
                }
                if (id != null && target != null) {
                    relationships.put(id, target);
                }
            }
        } finally {
            reader.close();
        }
        return relationships;
    }

    private static String resolveTarget(String target) {
        String path = target.replace('\\', '/');
        if (path.startsWith("/")) {
            path = path.substring(1);
        } else {
            path = "xl/" + path;
        }
        List<String> parts = new ArrayList<>();
        for (String part : path.split("/")) {
            if (part.isEmpty() || ".".equals(part)) continue;
            if ("..".equals(part)) {
                if (!parts.isEmpty()) parts.remove(parts.size() - 1);
            } else {
                parts.add(part);
            }
        }
        return String.join("/", parts);
    }

    private static String fallbackWorksheet(Map<String, byte[]> entries) {
        String best = null;
        for (String name : entries.keySet()) {
            if (name.startsWith("xl/worksheets/") && name.endsWith(".xml")) {
                if (best == null || name.compareTo(best) < 0) best = name;
            }
        }
        return best;
    }

    private static String sheetNames(List<SheetRef> sheets) {
        List<String> names = new ArrayList<>();
        for (SheetRef sheet : sheets) names.add(sheet.name);
        return String.join(", ", names);
    }

    // ==================== 共享字符串 ====================

    private static List<String> readSharedStrings(Map<String, byte[]> entries) throws XMLStreamException {
        List<String> strings = new ArrayList<>();
        byte[] data = entries.get("xl/sharedStrings.xml");
        if (data == null) return strings;

        XMLStreamReader reader = newReader(data);
        StringBuilder current = null;
        try {
            while (reader.hasNext()) {
                int event = reader.next();
                if (event == XMLStreamConstants.START_ELEMENT) {
                    String name = reader.getLocalName();
                    if ("si".equals(name)) {
                        current = new StringBuilder();
                    } else if ("t".equals(name) && current != null) {
                        current.append(reader.getElementText());
                    }
                } else if (event == XMLStreamConstants.END_ELEMENT && "si".equals(reader.getLocalName())) {
                    if (current != null) {
                        strings.add(current.toString());
                        current = null;
                    }
                }
            }
        } finally {
            reader.close();
        }
        return strings;
    }

    // ==================== 工作表 ====================

    private static List<List<String>> readRows(byte[] sheetXml, List<String> sharedStrings)
            throws XMLStreamException {
        List<List<String>> rows = new ArrayList<>();
        List<String> currentRow = null;
        int column = -1;
        String cellType = null;
        StringBuilder raw = null;
        StringBuilder text = null;
        boolean inValue = false;
        boolean inText = false;
        boolean inCell = false;

        XMLStreamReader reader = newReader(sheetXml);
        try {
            while (reader.hasNext()) {
                int event = reader.next();
                if (event == XMLStreamConstants.START_ELEMENT) {
                    switch (reader.getLocalName()) {
                        case "row":
                            currentRow = new ArrayList<>();
                            break;
                        case "c":
                            column = columnIndex(attribute(reader, "r"));
                            cellType = attribute(reader, "t");
                            raw = new StringBuilder();
                            text = new StringBuilder();
                            inCell = true;
                            break;
                        case "v":
                            inValue = true;
                            break;
                        case "t":
                            inText = true;
                            break;
                        default:
                            break;
                    }
                } else if (event == XMLStreamConstants.CHARACTERS
                        || event == XMLStreamConstants.CDATA) {
                    if (inValue && raw != null) raw.append(reader.getText());
                    else if (inText && text != null) text.append(reader.getText());
                } else if (event == XMLStreamConstants.END_ELEMENT) {
                    switch (reader.getLocalName()) {
                        case "v":
                            inValue = false;
                            break;
                        case "t":
                            inText = false;
                            break;
                        case "c":
                            if (inCell && currentRow != null) {
                                placeCell(currentRow, column, cellValue(cellType, raw, text, sharedStrings));
                            }
                            inCell = false;
                            raw = null;
                            text = null;
                            cellType = null;
                            column = -1;
                            break;
                        case "row":
                            if (currentRow != null) {
                                rows.add(currentRow);
                                currentRow = null;
                            }
                            break;
                        default:
                            break;
                    }
                }
            }
        } finally {
            reader.close();
        }
        return rows;
    }

    private static void placeCell(List<String> row, int column, String value) {
        int index = column < 0 ? row.size() : column;
        while (row.size() < index) row.add("");
        if (index == row.size()) {
            row.add(value);
        } else {
            row.set(index, value);
        }
    }

    private static String cellValue(String type, StringBuilder raw, StringBuilder text,
                                    List<String> sharedStrings) {
        String rawValue = raw == null ? "" : raw.toString();
        String textValue = text == null ? "" : text.toString();
        if (type == null) {
            return rawValue;
        }
        switch (type) {
            case "s": {
                Long index = Values.toLong(rawValue);
                if (index == null || index < 0 || index >= sharedStrings.size()) return rawValue;
                return sharedStrings.get(index.intValue());
            }
            case "inlineStr":
                return textValue.isEmpty() ? rawValue : textValue;
            case "str":
                return rawValue.isEmpty() ? textValue : rawValue;
            case "b":
                return "1".equals(rawValue.trim()) ? "TRUE" : "FALSE";
            default:
                return rawValue;
        }
    }

    private static int columnIndex(String reference) {
        if (reference == null || reference.isEmpty()) return -1;
        int column = 0;
        int index = 0;
        while (index < reference.length() && Character.isLetter(reference.charAt(index))) {
            column = column * 26 + (Character.toUpperCase(reference.charAt(index)) - 'A' + 1);
            index++;
        }
        return column - 1;
    }

    private static String attribute(XMLStreamReader reader, String name) {
        for (int i = 0; i < reader.getAttributeCount(); i++) {
            if (name.equals(reader.getAttributeLocalName(i))) {
                return reader.getAttributeValue(i);
            }
        }
        return null;
    }

    private static XMLStreamReader newReader(byte[] data) throws XMLStreamException {
        XMLInputFactory factory = XMLInputFactory.newInstance();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        try {
            factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        } catch (IllegalArgumentException ignored) {
            // 某些实现不支持该属性
        }
        return factory.createXMLStreamReader(new ByteArrayInputStream(data), StandardCharsets.UTF_8.name());
    }
}
