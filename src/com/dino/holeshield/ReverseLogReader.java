package com.dino.holeshield;

import java.io.Closeable;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.BiConsumer;
import java.util.function.Predicate;
import java.util.function.ToLongFunction;
import java.util.zip.GZIPInputStream;

/** Reads complete UTF-8 log rows newest first, using fixed-size disk reads. */
final class ReverseLogReader implements Closeable {
    static final long RETENTION_MS = Retention.WINDOW_MS;

    @FunctionalInterface
    interface RowWriter<E> {
        void write(BufferedWriter out, E entry) throws Exception;
    }

    /** Publish all migrated partitions together; leave the source intact on failure. */
    static <E> boolean initialize(File directory, File legacy, long cutoff,
                                  Function<String, E> parse, ToLongFunction<E> timestamp,
                                  Function<E, String> partition, RowWriter<E> writer) {
        if (directory.isDirectory()) return true;
        if (!legacy.isFile()) return directory.mkdirs();
        File stage = null;
        try {
            stage = java.nio.file.Files.createTempDirectory(
                    directory.getParentFile().toPath(), "query-migration-").toFile();
            try (ReverseLogReader reader = new ReverseLogReader(legacy)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    E entry = parse.apply(line);
                    if (entry == null || timestamp.applyAsLong(entry) < cutoff) continue;
                    try (BufferedWriter out = new BufferedWriter(new OutputStreamWriter(
                            new FileOutputStream(new File(stage, partition.apply(entry) + ".tsv"),
                                    true), StandardCharsets.UTF_8))) {
                        writer.write(out, entry);
                    }
                }
            }
            java.nio.file.Files.move(stage.toPath(), directory.toPath(),
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            legacy.delete();
            return true;
        } catch (Exception e) {
            return false;
        } finally {
            if (stage != null) {
                File[] files = stage.listFiles();
                if (files != null) for (File path : files) path.delete();
                stage.delete();
            }
        }
    }

    static <E> List<E> page(List<File> files, int offset, int limit, long cutoff,
                            Function<String, E> parse, ToLongFunction<E> timestamp,
                            Predicate<E> filter) {
        List<E> result = new ArrayList<>();
        if (limit <= 0) return result;
        for (File path : files) {
            try (ReverseLogReader reader = new ReverseLogReader(path)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    E entry = parse.apply(line);
                    if (entry == null || timestamp.applyAsLong(entry) < cutoff
                            || !filter.test(entry)) continue;
                    if (offset > 0) {
                        offset--;
                    } else {
                        result.add(entry);
                        if (result.size() == limit) return result;
                    }
                }
            } catch (IOException e) {
                // An expired partition can disappear after the snapshot was taken.
            }
        }
        return result;
    }

    static <E> long[] boundary(List<File> files, String day, long cutoff,
                                Function<String, E> parse, ToLongFunction<E> timestamp,
                                BiConsumer<long[], E> count) {
        long[] totals = new long[5];
        for (File path : files) {
            if (!path.getName().startsWith(day + ".")) continue;
            try (ReverseLogReader reader = new ReverseLogReader(path)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    E entry = parse.apply(line);
                    if (entry != null && timestamp.applyAsLong(entry) >= cutoff) {
                        count.accept(totals, entry);
                    }
                }
            } catch (IOException e) {
                // Expired partitions can disappear during a read.
            }
        }
        return totals;
    }

    static final class BoundaryCache {
        private long second = Long.MIN_VALUE;
        private List<File> files;
        private long[] totals;

        synchronized <E> long get(List<File> paths, String day, long cutoff, int index,
                                   Function<String, E> parse, ToLongFunction<E> timestamp,
                                   BiConsumer<long[], E> count) {
            if (second != cutoff / 1000 || !paths.equals(files)) {
                totals = boundary(paths, day, cutoff, parse, timestamp, count);
                files = paths;
                second = cutoff / 1000;
            }
            return totals[index];
        }
    }
    private final RandomAccessFile file;
    private final File temporary;
    private final byte[] block = new byte[8192];
    private byte[] row = new byte[512];
    private long position;
    private int index;

    ReverseLogReader(File path) throws IOException {
        temporary = path.getName().endsWith(".gz") ? unpack(path) : null;
        try {
            file = new RandomAccessFile(temporary == null ? path : temporary, "r");
        } catch (IOException e) {
            if (temporary != null) temporary.delete();
            throw e;
        }
        try {
            position = file.length();
            // A concurrent append or interrupted write may leave an incomplete row.
            if (previous() != '\n') {
                int value;
                do { value = previous(); } while (value >= 0 && value != '\n');
            }
        } catch (IOException e) {
            close();
            throw e;
        }
    }

    private static File unpack(File path) throws IOException {
        File temporary = File.createTempFile("query-read-", ".tmp", path.getParentFile());
        try (FileInputStream source = new FileInputStream(path);
             GZIPInputStream in = new GZIPInputStream(source);
             FileOutputStream out = new FileOutputStream(temporary)) {
            byte[] bytes = new byte[8192];
            int n;
            while ((n = in.read(bytes)) != -1) out.write(bytes, 0, n);
        } catch (IOException e) {
            temporary.delete();
            throw e;
        }
        return temporary;
    }

    private int previous() throws IOException {
        if (index == 0) {
            if (position == 0) return -1;
            index = (int) Math.min(position, block.length);
            position -= index;
            file.seek(position);
            file.readFully(block, 0, index);
        }
        return block[--index] & 255;
    }

    String readLine() throws IOException {
        int size = 0;
        int value;
        while ((value = previous()) >= 0 && value != '\n') {
            if (size == row.length) row = Arrays.copyOf(row, row.length * 2);
            row[size++] = (byte) value;
        }
        if (size == 0 && value < 0) return null;
        for (int i = 0, j = size - 1; i < j; i++, j--) {
            byte b = row[i];
            row[i] = row[j];
            row[j] = b;
        }
        return new String(row, 0, size, StandardCharsets.UTF_8);
    }

    @Override public void close() throws IOException {
        try {
            file.close();
        } finally {
            if (temporary != null) temporary.delete();
        }
    }
}
