package com.github.jfs;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

/**
 * End-to-end demonstration of {@link Jfs}: it stores a couple of small "files"
 * (a tiny 1x1 PNG and a text note), reads them back, verifies integrity, lists the
 * contents, reopens the store to prove persistence, and deletes a record.
 */
public final class Demo {

    // A minimal, valid 1x1 transparent PNG, used to stand in for a real small image.
    private static final byte[] TINY_PNG = {
        (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
        0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52,
        0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01,
        0x08, 0x06, 0x00, 0x00, 0x00, 0x1F, 0x15, (byte) 0xC4,
        (byte) 0x89, 0x00, 0x00, 0x00, 0x0D, 0x49, 0x44, 0x41,
        0x54, 0x78, (byte) 0x9C, 0x62, 0x00, 0x01, 0x00, 0x00,
        0x05, 0x00, 0x01, 0x0D, 0x0A, 0x2D, (byte) 0xB4, 0x00,
        0x00, 0x00, 0x00, 0x49, 0x45, 0x4E, 0x44, (byte) 0xAE,
        0x42, 0x60, (byte) 0x82
    };

    public static void main(String[] args) throws IOException {
        Path dir = args.length > 0
            ? Path.of(args[0])
            : Files.createTempDirectory("jfs-demo");

        System.out.println("jfs demo -- container directory: " + dir);
        System.out.println("------------------------------------------------------------");

        String pngId;
        String noteId;

        try (Jfs store = Jfs.open(dir)) {
            pngId = store.put(TINY_PNG);
            byte[] note = "hello from jfs -- storing small files since 2021".getBytes(StandardCharsets.UTF_8);
            noteId = "note.txt";
            store.put(noteId, note);

            System.out.println("Stored PNG   -> id=" + pngId + " (" + TINY_PNG.length + " bytes)");
            System.out.println("Stored note  -> id=" + noteId + " (" + note.length + " bytes)");

            byte[] readBack = store.get(pngId);
            boolean pngOk = Arrays.equals(TINY_PNG, readBack);
            System.out.println("Read-back PNG integrity: " + (pngOk ? "OK" : "FAILED"));
            if (!pngOk) {
                throw new IllegalStateException("PNG round-trip mismatch");
            }

            System.out.println("Note contents: " + new String(store.get(noteId), StandardCharsets.UTF_8));
            System.out.println("Live records : " + store.list());
            System.out.println("Container size: " + store.fileSize() + " bytes");
        }

        System.out.println("------------------------------------------------------------");
        System.out.println("Reopening store to prove on-disk persistence...");
        try (Jfs store = Jfs.open(dir)) {
            List<String> ids = store.list();
            System.out.println("Recovered " + store.size() + " records from disk: " + ids);
            if (!store.contains(pngId) || !store.contains(noteId)) {
                throw new IllegalStateException("Persistence check failed");
            }

            boolean deleted = store.delete(noteId);
            System.out.println("Deleted note (" + noteId + "): " + deleted);
            System.out.println("Records after delete: " + store.list());
        }

        System.out.println("------------------------------------------------------------");
        System.out.println("Demo completed successfully.");
    }

    private Demo() {
    }
}
