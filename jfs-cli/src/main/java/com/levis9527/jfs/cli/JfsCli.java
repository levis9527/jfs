package com.levis9527.jfs.cli;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Command-line client for a running jfs server.
 *
 * <pre>
 *   java -jar jfs-cli.jar [--url URL] [--token TOKEN] &lt;command&gt; ...
 * </pre>
 */
public final class JfsCli {
    public static void main(String[] args) {
        int code = run(args, System.out, System.err);
        if (code != 0) {
            System.exit(code);
        }
    }

    static int run(String[] args, PrintStream out, PrintStream err) {
        CliArgs parsed;
        try {
            parsed = CliArgs.parse(args);
        } catch (IllegalArgumentException e) {
            err.println(e.getMessage());
            err.println(help());
            return 2;
        }
        if (parsed.help || parsed.command == null) {
            out.println(help());
            return parsed.help ? 0 : 2;
        }
        JfsClient client = new JfsClient(parsed.url, parsed.token);
        try {
            return execute(parsed, client, out);
        } catch (CliException e) {
            err.println("error: " + e.getMessage());
            return 1;
        } catch (Exception e) {
            err.println("error: " + e.getMessage());
            return 1;
        }
    }

    private static int execute(CliArgs a, JfsClient client, PrintStream out) throws Exception {
        String cmd = a.command.toLowerCase();
        return switch (cmd) {
            case "ping" -> printJson(out, a, client.ping());
            case "auth" -> printJson(out, a, client.auth());
            case "overview", "ov" -> printJson(out, a, client.overview());
            case "stats" -> printJson(out, a, client.stats());
            case "buckets" -> printJson(out, a, client.buckets());
            case "list", "ls" -> {
                JsonObject root = client.list(a.bucket, a.query);
                if (a.json) {
                    out.println(client.pretty(root));
                } else {
                    printList(out, root);
                }
                yield 0;
            }
            case "upload", "put" -> {
                Path file = a.localFile();
                if (file == null || !Files.isRegularFile(file)) {
                    throw new CliException("upload requires a local file");
                }
                String bucket = a.requireBucket();
                String name = a.name != null ? a.name : file.getFileName().toString();
                String mime = a.mime != null ? a.mime : probeMime(name);
                boolean auth = a.auth != null && a.auth;
                JsonObject root = client.upload(bucket, name, mime, auth, Files.readAllBytes(file));
                printJson(out, a, root);
                yield 0;
            }
            case "get", "download" -> {
                byte[] data = client.get(a.requireBucket(), a.requireName());
                if (a.output != null) {
                    Path dest = Path.of(a.output);
                    if (dest.getParent() != null) {
                        Files.createDirectories(dest.getParent());
                    }
                    Files.write(dest, data);
                    out.println("wrote " + dest.toAbsolutePath() + " (" + data.length + " bytes)");
                } else {
                    out.write(data);
                }
                yield 0;
            }
            case "cat" -> {
                out.write(client.get(a.requireBucket(), a.requireName()));
                yield 0;
            }
            case "meta", "stat" -> {
                if (a.rename != null || a.mime != null || a.auth != null) {
                    printJson(out, a, client.updateMeta(a.requireBucket(), a.requireName(), a.rename, a.mime, a.auth));
                } else {
                    printJson(out, a, client.getMeta(a.requireBucket(), a.requireName()));
                }
                yield 0;
            }
            case "rm", "delete", "del" -> printJson(out, a, client.delete(a.requireBucket(), a.requireName()));
            default -> throw new CliException("unknown command: " + a.command);
        };
    }

    private static int printJson(PrintStream out, CliArgs a, JsonObject root) {
        JsonElement payload = root.has("data") ? root.get("data") : root;
        if (payload == null || payload.isJsonNull()) {
            String msg = root.has("msg") && !root.get("msg").isJsonNull() ? root.get("msg").getAsString() : "ok";
            out.println(msg);
            return 0;
        }
        out.println(new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(payload));
        return 0;
    }

    private static void printList(PrintStream out, JsonObject root) {
        JsonElement data = root.get("data");
        if (data == null || !data.isJsonArray()) {
            out.println(root);
            return;
        }
        JsonArray arr = data.getAsJsonArray();
        out.printf("%-12s %-28s %10s %-6s %4s %s%n", "BUCKET", "NAME", "SIZE", "AUTH", "VID", "KEY");
        for (JsonElement el : arr) {
            JsonObject f = el.getAsJsonObject();
            out.printf("%-12s %-28s %10s %-6s %4s %s%n",
                    text(f, "bucket"),
                    text(f, "filename"),
                    text(f, "size"),
                    f.has("auth") && f.get("auth").getAsBoolean() ? "yes" : "no",
                    text(f, "vid"),
                    text(f, "key"));
        }
        out.println(arr.size() + " file(s)");
    }

    private static String text(JsonObject o, String field) {
        if (!o.has(field) || o.get(field).isJsonNull()) {
            return "";
        }
        return o.get(field).getAsString();
    }

    private static String probeMime(String name) {
        String lower = name.toLowerCase();
        if (lower.endsWith(".png")) {
            return "image/png";
        }
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
            return "image/jpeg";
        }
        if (lower.endsWith(".gif")) {
            return "image/gif";
        }
        if (lower.endsWith(".webp")) {
            return "image/webp";
        }
        if (lower.endsWith(".txt")) {
            return "text/plain";
        }
        if (lower.endsWith(".json")) {
            return "application/json";
        }
        return "application/octet-stream";
    }

    static String help() {
        return """
                jfs-cli — operate files on a running jfs server

                Usage:
                  java -jar jfs-cli.jar [global] <command> [args]

                Global:
                  -u, --url URL       server, default $JFS_URL or http://127.0.0.1:8080
                  -t, --token TOKEN   bearer token, default $JFS_TOKEN
                      --json          print raw JSON
                  -h, --help

                Commands:
                  ping
                  auth
                  overview | ov
                  stats
                  buckets
                  list | ls              [-b bucket] [-q query]
                  upload | put FILE      -b bucket [-n name] [--mime TYPE] [--auth]
                  get | download         -b bucket -n name [-o FILE]
                  cat                    -b bucket -n name
                  meta | stat            -b bucket -n name [--rename NEW] [--mime TYPE] [--auth 0|1]
                  rm | delete            -b bucket -n name

                Object id can also be bucket/name:
                  jfs-cli put photo.jpg img/photo.jpg --auth
                  jfs-cli get img/photo.jpg -o photo.jpg
                  jfs-cli rm img/photo.jpg
                """;
    }
}
