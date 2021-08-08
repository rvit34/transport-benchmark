package org.bench.transports.utils;

import io.github.cdimascio.dotenv.Dotenv;

import javax.annotation.Nullable;

public class EnvVars {

    private static final Dotenv dotEnv = loadDotEnv();

    private static Dotenv loadDotEnv() {
        return Dotenv.configure()
                .directory(System.getProperty("envFile"))
                .ignoreIfMalformed()
                .ignoreIfMissing()
                .systemProperties()
                .load();
    }

    @Nullable
    public static String getValue(String param) { return dotEnv.get(param); }

    public static String getValue(String param, String defaultValue) { return dotEnv.get(param, defaultValue);}
}
