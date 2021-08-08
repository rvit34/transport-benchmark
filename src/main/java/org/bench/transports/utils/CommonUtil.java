package org.bench.transports.utils;

import com.google.protobuf.MessageLite;
import com.google.protobuf.Parser;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nullable;
import java.io.*;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.locks.Lock;

@Slf4j
public class CommonUtil {
    public static String exceptionToError(Throwable exception) {
        return exceptionToError(exception.getClass().getSimpleName());
    }

    public static String exceptionToError(String exception) {
        String errorName = camelCaseToSnakeCase(exception).toUpperCase();
        if (errorName.toUpperCase(Locale.ENGLISH).endsWith("_EXCEPTION")) {
            errorName = errorName.substring(0, errorName.length() - "_EXCEPTION".length());
        }
        return errorName;
    }

    public static String camelCaseToSnakeCase(String message) {
        return message.replaceAll("(.)(\\p{Upper})", "$1_$2").toLowerCase();
    }

    public static Optional<BigDecimal> parseNumber(String s) {
        try {
            return Optional.of(new BigDecimal(s));
        } catch (NullPointerException | NumberFormatException e) {
            return Optional.empty();
        }
    }

    @Nullable
    public static byte[] toByteArrayDelimited(ByteArrayOutputStream outputStream, MessageLite message) {
        try {
            message.writeDelimitedTo(outputStream);
            return outputStream.toByteArray();
        } catch (IOException e) {
            log.error("unable to serialize proto: {}", e.getMessage());
        }
        return null;
    }

    @Nullable
    public static <T extends MessageLite> T tryParse(byte[] bytes, Parser<T> parser) {
        try (var stream = new ByteArrayInputStream(bytes)) {
            return parser.parseDelimitedFrom(stream);
        } catch (IOException e) {
            log.error("unable to parse proto: {}", e.getMessage());
        }
        return null;
    }

    public static void withLock(Lock lock, Runnable code) {
        try {
            lock.lock();
            code.run();
        } finally {
            lock.unlock();
        }
    }

    public static void closeQuietly(Closeable closeable) {
        try {
            closeable.close();
        } catch (Throwable th) {
            log.error("unable to close resource {}", closeable.getClass().toString(), th);
        }
    }

    public static void closeQuietly(AutoCloseable autoCloseable) {
        try {
            autoCloseable.close();
        } catch (Throwable th) {
            log.error("unable to close resource {}", autoCloseable.getClass().toString(), th);
        }
    }
}
