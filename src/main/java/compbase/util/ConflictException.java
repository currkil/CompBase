package compbase.util;

/**
 * 业务冲突：例如删除正在被元器件引用的类型。
 * 由 BaseHandler 统一映射为 HTTP 409。
 */
public class ConflictException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public ConflictException(String message) {
        super(message);
    }
}
