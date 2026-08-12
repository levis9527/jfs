package com.levis9527.jfs.needle;

public class NeedleException extends RuntimeException {
    public NeedleException(String message) {
        super(message);
    }

    public NeedleException(String message, Throwable cause) {
        super(message, cause);
    }
}
