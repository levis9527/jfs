package com.levis9527.jfs.cli;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Parsed CLI invocation. */
final class CliArgs {
    String url = firstNonBlank(System.getenv("JFS_URL"), "http://127.0.0.1:8080");
    String token = firstNonBlank(System.getenv("JFS_TOKEN"), "");
    String command;
    final List<String> positionals = new ArrayList<>();
    String bucket;
    String name;
    String mime;
    String output;
    String query;
    String rename;
    Boolean auth;
    boolean json;
    boolean help;

    static CliArgs parse(String[] argv) {
        CliArgs a = new CliArgs();
        for (int i = 0; i < argv.length; i++) {
            String s = argv[i];
            switch (s) {
                case "-h", "--help", "help" -> a.help = true;
                case "-u", "--url" -> a.url = next(argv, ++i, s);
                case "-t", "--token" -> a.token = next(argv, ++i, s);
                case "-b", "--bucket" -> a.bucket = next(argv, ++i, s);
                case "-n", "--name", "--filename" -> a.name = next(argv, ++i, s);
                case "--mime" -> a.mime = next(argv, ++i, s);
                case "-o", "--out", "--output" -> a.output = next(argv, ++i, s);
                case "-q", "--query" -> a.query = next(argv, ++i, s);
                case "--rename" -> a.rename = next(argv, ++i, s);
                case "--json" -> a.json = true;
                case "--auth" -> {
                    if (i + 1 < argv.length && isFlagValue(argv[i + 1])) {
                        a.auth = parseAuth(argv[++i]);
                    } else {
                        a.auth = true;
                    }
                }
                default -> {
                    if (s.startsWith("-")) {
                        throw new IllegalArgumentException("unknown option: " + s);
                    }
                    if (a.command == null) {
                        a.command = s;
                    } else {
                        a.positionals.add(s);
                    }
                }
            }
        }
        a.applyObjectRef();
        return a;
    }

    private void applyObjectRef() {
        // Allow bucket/name as a single positional: img/photo.jpg
        if (bucket == null || name == null) {
            for (String p : positionals) {
                int slash = p.indexOf('/');
                if (slash > 0 && slash < p.length() - 1 && !p.startsWith(".") && !looksLikePath(p)) {
                    if (bucket == null) {
                        bucket = p.substring(0, slash);
                    }
                    if (name == null) {
                        name = p.substring(slash + 1);
                    }
                    break;
                }
            }
        }
    }

    Path localFile() {
        String object = (bucket != null && name != null) ? bucket + "/" + name : null;
        Path fallback = null;
        for (String p : positionals) {
            if (object != null && object.equals(p)) {
                continue;
            }
            Path path = Path.of(p);
            if (Files.isRegularFile(path)) {
                return path;
            }
            if (fallback == null) {
                fallback = path;
            }
        }
        return fallback;
    }

    private static boolean looksLikePath(String p) {
        return p.startsWith("/") || p.startsWith("./") || p.startsWith("../");
    }

    private static boolean isFlagValue(String s) {
        return "0".equals(s) || "1".equals(s) || "true".equalsIgnoreCase(s) || "false".equalsIgnoreCase(s);
    }

    static boolean parseAuth(String raw) {
        return "1".equals(raw) || "true".equalsIgnoreCase(raw) || "yes".equalsIgnoreCase(raw);
    }

    private static String next(String[] argv, int i, String opt) {
        if (i >= argv.length) {
            throw new IllegalArgumentException(opt + " requires a value");
        }
        return argv[i];
    }

    private static String firstNonBlank(String a, String b) {
        return a != null && !a.isBlank() ? a : b;
    }

    String requireBucket() {
        return Objects.requireNonNull(nonBlank(bucket), "bucket required (-b or bucket/name)");
    }

    String requireName() {
        return Objects.requireNonNull(nonBlank(name), "filename required (-n or bucket/name)");
    }

    private static String nonBlank(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
