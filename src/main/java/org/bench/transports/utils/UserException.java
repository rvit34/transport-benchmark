package org.bench.transports.utils;

public class UserException extends IllegalArgumentException {

    public UserException(String message) {
        super(message);
    }

    public UserException(String message, Throwable e) {
        super(message, e);
    }
}
