package compbase.util;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 从 3D 模型文件名猜封装信息（KiCad 风格命名）。
 * 例：DIP-8_W7.62mm.glb -> 简称 DIP-8、引脚 8
 *     SOP-8_3.9x4.9mm_P1.27mm.glb -> 简称 SOP-8、引脚 8、间距 1.27
 *     R_0603_1608Metric.glb -> 简称 0603
 * 只做「有把握」的推断，不确定的字段留空，由用户在封装编辑页补。
 */
public final class PackageNameParser {
    private static final List<String> PASSIVE_PREFIXES = Arrays.asList(
            "R", "C", "L", "D", "LED", "SW", "F", "FB", "TP", "Y", "X",
            "BAT", "TVS", "ANT", "REL", "FID", "Q");
    private static final Pattern SIZE_CODE = Pattern.compile("^\\d{4}$");
    private static final Pattern PINNED = Pattern.compile(
            "^(DIP|PDIP|SOP|SOIC|SSOP|TSSOP|MSOP|QFP|TQFP|LQFP|QFN|DFN|PLCC|BGA|PGA|SIP|ZIP)[-_]?(\\d+)$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern TO_PINS = Pattern.compile("^TO-\\d+-(\\d+)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern PITCH = Pattern.compile(
            "(?:^|[_-])P(\\d+(?:\\.\\d+)?)mm", Pattern.CASE_INSENSITIVE);

    private PackageNameParser() {
    }

    /**
     * @param fileName 仅文件名，如 DIP-8_W7.62mm.glb
     * @return package_name / full_name / pin_count / pitch / model_file / model_format / auto_filled
     */
    public static Map<String, Object> suggest(String fileName) {
        Map<String, Object> result = new LinkedHashMap<>();
        String name = fileName == null ? "" : fileName.trim();
        String extension = "";
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            extension = name.substring(dot + 1).toUpperCase(Locale.ROOT);
            name = name.substring(0, dot);
        }
        String fullName = name;

        List<String> tokens = new ArrayList<>();
        for (String token : fullName.split("_")) {
            if (!token.isBlank()) tokens.add(token);
        }
        String packageName = tokens.isEmpty() ? fullName : tokens.get(0);
        if (tokens.size() > 1
                && PASSIVE_PREFIXES.contains(tokens.get(0).toUpperCase(Locale.ROOT))
                && SIZE_CODE.matcher(tokens.get(1)).matches()) {
            packageName = tokens.get(1);
        }

        Long pinCount = parsePinCount(packageName);
        Double pitch = parsePitch(fullName);

        List<String> autoFilled = new ArrayList<>();
        autoFilled.add("package_name");
        if (!fullName.equals(packageName)) autoFilled.add("full_name");
        if (pinCount != null) autoFilled.add("pin_count");
        if (pitch != null) autoFilled.add("pitch");

        result.put("package_name", packageName);
        result.put("full_name", fullName);
        result.put("pin_count", pinCount);
        result.put("pitch", pitch);
        result.put("model_file", fileName);
        result.put("model_format", extension.isEmpty() ? null : extension);
        result.put("auto_filled", autoFilled);
        return result;
    }

    private static Long parsePinCount(String packageName) {
        Matcher pinned = PINNED.matcher(packageName);
        if (pinned.matches()) {
            return Long.parseLong(pinned.group(2));
        }
        Matcher toPins = TO_PINS.matcher(packageName);
        if (toPins.matches()) {
            return Long.parseLong(toPins.group(1));
        }
        return null;
    }

    private static Double parsePitch(String fullName) {
        Matcher matcher = PITCH.matcher(fullName);
        if (matcher.find()) {
            try {
                return Double.parseDouble(matcher.group(1));
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }
}
