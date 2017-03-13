package co.zync.zync.api;

public class ZyncError {
    private final int code;
    private final String message;

    public ZyncError(int code, String message) {
        this.code = code;
        this.message = message;
    }

    public int code() {
        return code;
    }

    public String message() {
        return message;
    }
}
