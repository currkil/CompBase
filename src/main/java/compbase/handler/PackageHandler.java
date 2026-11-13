package compbase.handler;

import com.sun.net.httpserver.HttpExchange;
import compbase.repository.PackageRepository;
import compbase.util.Json;
import compbase.util.Values;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * /api/package-types 与 /api/packages
 * <pre>
 * GET    /api/package-types                  封装类型列表（内嵌 packages）
 * POST   /api/package-types                  新增封装类型
 * PUT    /api/package-types/{id}             修改封装类型
 * DELETE /api/package-types/{id}             删除封装类型（连同封装，被引用时 409）
 * GET    /api/package-types/{id}/packages    某封装类型下的封装
 * POST   /api/package-types/{id}/packages    新增封装
 * GET    /api/packages?pkg_type_id=          封装列表
 * GET    /api/packages/{id}                  单个封装
 * PUT    /api/packages/{id}                  修改封装
 * DELETE /api/packages/{id}                  删除封装（被引用时 409）
 * </pre>
 */
public class PackageHandler extends BaseHandler {
    private final PackageRepository repository;

    public PackageHandler(PackageRepository repository) {
        this.repository = repository;
    }

    @Override
    protected void route(HttpExchange exchange) throws Exception {
        List<String> segments = segments(exchange);
        if (segments.size() < 2) {
            notFound(exchange);
            return;
        }
        String resource = segments.get(1);
        String method = method(exchange);

        if ("package-types".equals(resource)) {
            handlePackageTypes(exchange, segments, method);
        } else if ("packages".equals(resource)) {
            handlePackages(exchange, segments, method);
        } else {
            notFound(exchange);
        }
    }

    private void handlePackageTypes(HttpExchange exchange, List<String> segments, String method)
            throws Exception {
        if (segments.size() == 2) {
            switch (method) {
                case "GET":
                    sendJson(exchange, 200, repository.listPackageTypes());
                    return;
                case "POST": {
                    Map<String, Object> body = jsonBody(exchange);
                    String name = required(body, "pkg_type_name", "封装类型名称");
                    long id = repository.createPackageType(name);
                    sendJson(exchange, 201, repository.findPackageType(id));
                    return;
                }
                default:
                    methodNotAllowed(exchange);
                    return;
            }
        }

        long id = pathId(segments.get(2));
        if (segments.size() == 3) {
            switch (method) {
                case "GET": {
                    Map<String, Object> type = repository.findPackageType(id);
                    if (type == null) {
                        notFound(exchange);
                    } else {
                        type.put("packages", repository.listPackages(id));
                        sendJson(exchange, 200, type);
                    }
                    return;
                }
                case "PUT": {
                    Map<String, Object> body = jsonBody(exchange);
                    String name = required(body, "pkg_type_name", "封装类型名称");
                    if (!repository.updatePackageType(id, name)) {
                        notFound(exchange);
                    } else {
                        sendJson(exchange, 200, repository.findPackageType(id));
                    }
                    return;
                }
                case "DELETE":
                    if (!repository.deletePackageType(id)) {
                        notFound(exchange);
                    } else {
                        sendJson(exchange, 200, data("deleted", true, "pkg_type_id", id));
                    }
                    return;
                default:
                    methodNotAllowed(exchange);
                    return;
            }
        }

        if (segments.size() == 4 && "packages".equals(segments.get(3))) {
            switch (method) {
                case "GET":
                    sendJson(exchange, 200, repository.listPackages(id));
                    return;
                case "POST": {
                    if (repository.findPackageType(id) == null) {
                        sendError(exchange, 404, "封装类型不存在: " + id);
                        return;
                    }
                    Map<String, Object> body = jsonBody(exchange);
                    required(body, "package_name", "封装名称");
                    long packageId = repository.createPackage(id, packagePayload(body, null));
                    sendJson(exchange, 201, repository.findPackage(packageId));
                    return;
                }
                default:
                    methodNotAllowed(exchange);
                    return;
            }
        }

        notFound(exchange);
    }

    private void handlePackages(HttpExchange exchange, List<String> segments, String method)
            throws Exception {
        if (segments.size() == 2) {
            switch (method) {
                case "GET": {
                    Long pkgTypeId = Values.toLong(query(exchange).get("pkg_type_id"));
                    sendJson(exchange, 200, repository.listPackages(pkgTypeId));
                    return;
                }
                case "POST": {
                    Map<String, Object> body = jsonBody(exchange);
                    Long pkgTypeId = Json.getLong(body, "pkg_type_id");
                    if (pkgTypeId == null) throw new IllegalArgumentException("封装类型不能为空");
                    required(body, "package_name", "封装名称");
                    long id = repository.createPackage(pkgTypeId, packagePayload(body, null));
                    sendJson(exchange, 201, repository.findPackage(id));
                    return;
                }
                default:
                    methodNotAllowed(exchange);
                    return;
            }
        }

        // 从 3D 模型库批量导入封装（自动解析文件名并补齐字段）
        if (segments.size() == 3 && "from-models".equals(segments.get(2))) {
            if (!"POST".equals(method)) {
                methodNotAllowed(exchange);
                return;
            }
            Map<String, Object> body = jsonBody(exchange);
            Long pkgTypeId = Json.getLong(body, "pkg_type_id");
            if (pkgTypeId == null) throw new IllegalArgumentException("请选择封装类型");
            if (repository.findPackageType(pkgTypeId) == null) {
                sendError(exchange, 404, "封装类型不存在: " + pkgTypeId);
                return;
            }
            Object rawFiles = body.get("model_files");
            if (!(rawFiles instanceof List) || ((List<?>) rawFiles).isEmpty()) {
                throw new IllegalArgumentException("请选择要导入的模型文件");
            }
            List<String> modelFiles = new ArrayList<>();
            for (Object item : (List<?>) rawFiles) {
                if (item != null && !String.valueOf(item).isBlank()) {
                    modelFiles.add(String.valueOf(item).trim());
                }
            }
            if (modelFiles.isEmpty()) throw new IllegalArgumentException("请选择要导入的模型文件");
            sendJson(exchange, 200, repository.importFromModels(pkgTypeId, modelFiles));
            return;
        }

        long id = pathId(segments.get(2));
        if (segments.size() != 3) {
            notFound(exchange);
            return;
        }
        switch (method) {
            case "GET": {
                Map<String, Object> pkg = repository.findPackage(id);
                if (pkg == null) {
                    notFound(exchange);
                } else {
                    sendJson(exchange, 200, pkg);
                }
                return;
            }
            case "PUT": {
                Map<String, Object> body = jsonBody(exchange);
                Map<String, Object> existing = repository.findPackage(id);
                if (existing == null) {
                    notFound(exchange);
                    return;
                }
                Long pkgTypeId = Json.getLong(body, "pkg_type_id");
                if (pkgTypeId == null) pkgTypeId = Values.toLong(existing.get("pkg_type_id"));
                repository.updatePackage(id, pkgTypeId, packagePayload(body, existing));
                sendJson(exchange, 200, repository.findPackage(id));
                return;
            }
            case "DELETE":
                if (!repository.deletePackage(id)) {
                    notFound(exchange);
                } else {
                    sendJson(exchange, 200, data("deleted", true, "package_id", id));
                }
                return;
            default:
                methodNotAllowed(exchange);
        }
    }

    /** 把请求体规范化为封装字段；existing 非空时用于 PUT 的字段回填。 */
    private static Map<String, Object> packagePayload(Map<String, Object> body,
                                                      Map<String, Object> existing) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("package_name", stringValue(body, existing, "package_name"));
        data.put("full_name", stringValue(body, existing, "full_name"));
        data.put("pin_count", longValue(body, existing, "pin_count"));
        data.put("pitch", doubleValue(body, existing, "pitch"));
        data.put("model_file", stringValue(body, existing, "model_file"));
        data.put("model_format", stringValue(body, existing, "model_format"));
        data.put("description", stringValue(body, existing, "description"));
        return data;
    }

    private static String stringValue(Map<String, Object> body, Map<String, Object> existing,
                                      String key) {
        String value = Json.getString(body, key);
        if (value != null) return value;
        return existing == null ? null : Values.toStr(existing.get(key));
    }

    private static Long longValue(Map<String, Object> body, Map<String, Object> existing, String key) {
        Long value = Json.getLong(body, key);
        if (value != null) return value;
        return existing == null ? null : Values.toLong(existing.get(key));
    }

    private static Double doubleValue(Map<String, Object> body, Map<String, Object> existing,
                                      String key) {
        Double value = Json.getDouble(body, key);
        if (value != null) return value;
        return existing == null ? null : Values.toDouble(existing.get(key));
    }
}
