package com.levis9527.jfs;

import com.levis9527.jfs.directory.Directory;
import com.levis9527.jfs.proxy.ProxyServer;
import com.levis9527.jfs.store.Store;

import java.nio.file.Path;

/**
 * All-in-one entry: store + directory + proxy (bfs module split, single process).
 */
public final class JfsServer {
    public static void main(String[] args) throws Exception {
        String addr = ":8080";
        Path data = Path.of("./data");
        int volumes = 2;
        long volumeSize = 1L << 30;
        long workerId = 1;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "-addr" -> addr = args[++i];
                case "-data" -> data = Path.of(args[++i]);
                case "-volumes" -> volumes = Integer.parseInt(args[++i]);
                case "-volume-size" -> volumeSize = Long.parseLong(args[++i]);
                case "-worker" -> workerId = Long.parseLong(args[++i]);
                case "-h", "--help" -> {
                    printHelp();
                    return;
                }
                default -> throw new IllegalArgumentException("unknown arg: " + args[i]);
            }
        }

        String host = "0.0.0.0";
        int port = 8080;
        if (addr.startsWith(":")) {
            port = Integer.parseInt(addr.substring(1));
        } else if (addr.contains(":")) {
            int idx = addr.lastIndexOf(':');
            host = addr.substring(0, idx);
            port = Integer.parseInt(addr.substring(idx + 1));
        } else {
            port = Integer.parseInt(addr);
        }

        Store store = new Store(data, volumes, volumeSize);
        Directory directory = new Directory(data, store, workerId);
        ProxyServer proxy = new ProxyServer(directory, store);
        proxy.start(host, port);
        System.out.printf("jfs listening on %s:%d (data=%s volumes=%d)%n", host, port, data.toAbsolutePath(), volumes);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("shutting down...");
            proxy.stop();
            try {
                directory.close();
            } catch (Exception ignored) {
            }
            try {
                store.close();
            } catch (Exception ignored) {
            }
        }));

        Thread.currentThread().join();
    }

    private static void printHelp() {
        System.out.println("""
                jfs - Java small-file storage (bfs / Haystack inspired)
                Usage:
                  java -jar jfs.jar [-addr :8080] [-data ./data] [-volumes 2] [-volume-size 1073741824] [-worker 1]
                Then open http://127.0.0.1:8080/admin
                """);
    }
}
