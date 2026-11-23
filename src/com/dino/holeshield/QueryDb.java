package com.dino.holeshield;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.database.sqlite.SQLiteStatement;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;
import java.util.zip.GZIPInputStream;

/**
 * Indexed SQLite store for DNS query history. It is the source of truth for
 * the rolling counters, charts and paged history; nothing is kept in a
 * separate counter file that could drift from the records.
 */
final class QueryDb {

    private static final String NAME = "queries.db";
    private static final int VERSION = 4;
    private static final int MIGRATION_BATCH = 500;
    private static final long MAINTENANCE_MS = 15L * 60 * 1000;

    private static final String COLUMNS = "at,client,domain,type,status,upstream,"
            + "rcode,ms,rule,category,blocked,cached";
    private static final String INSERT = "INSERT INTO queries (" + COLUMNS
            + ",day,hour,origin) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";

    private static Db helper;
    private static Context app;
    private static boolean started;
    private static final ArrayDeque<Stats.Entry> pending = new ArrayDeque<>();
    private static boolean writing;
    private static final Object QUEUE = new Object();
    private static final Object WRITE = new Object();
    private static final int PENDING_MAX = 20000;
    private static final AtomicLong queueDrops = new AtomicLong();
    private static final AtomicLong writeFailures = new AtomicLong();

    private QueryDb() { }

    private static final class Db extends SQLiteOpenHelper {
        Db(Context context) {
            super(context.getApplicationContext(), NAME, null, VERSION);
            setWriteAheadLoggingEnabled(true);
        }

        @Override public void onConfigure(SQLiteDatabase db) {
            db.setForeignKeyConstraintsEnabled(true);
        }

        @Override public void onCreate(SQLiteDatabase db) {
            db.execSQL("CREATE TABLE queries ("
                    + "id INTEGER PRIMARY KEY,"
                    + "at INTEGER NOT NULL,"
                    + "day TEXT NOT NULL,"
                    + "hour TEXT NOT NULL,"
                    + "client TEXT NOT NULL,"
                    + "domain TEXT NOT NULL,"
                    + "type INTEGER NOT NULL,"
                    + "status TEXT NOT NULL DEFAULT '',"
                    + "upstream TEXT NOT NULL DEFAULT '',"
                    + "rcode INTEGER NOT NULL DEFAULT 0,"
                    + "ms INTEGER NOT NULL DEFAULT 0,"
                    + "rule TEXT NOT NULL DEFAULT '',"
                    + "category TEXT NOT NULL DEFAULT '',"
                    + "blocked INTEGER NOT NULL DEFAULT 0,"
                    + "cached INTEGER NOT NULL DEFAULT 0,"
                    + "origin INTEGER NOT NULL DEFAULT 0)");
            db.execSQL("CREATE INDEX queries_at ON queries(at)");
            db.execSQL("CREATE INDEX queries_day ON queries(day)");
            db.execSQL("CREATE INDEX queries_hour ON queries(hour)");
            db.execSQL("CREATE INDEX queries_domain ON queries(domain)");
            db.execSQL("CREATE INDEX queries_client ON queries(client)");
            db.execSQL("CREATE INDEX queries_blocked ON queries(blocked, at)");
            db.execSQL("CREATE TABLE meta (key TEXT PRIMARY KEY, value TEXT NOT NULL)");
            db.execSQL("INSERT INTO meta VALUES ('migrated','0')");
        }

        @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            if (oldVersion < 2) {
                db.execSQL("ALTER TABLE queries ADD COLUMN origin INTEGER NOT NULL DEFAULT 0");
            }
            if (oldVersion < 4) {
                // Row-level uniqueness is wrong here: bursts can record the
                // same domain, type and millisecond more than once. Drop the
                // index an unreleased build created.
                db.execSQL("DROP INDEX IF EXISTS queries_unique");
            }
        }
    }

    static synchronized void open(Context context) {
        if (context == null) return;
        app = context.getApplicationContext();
        if (helper == null) helper = new Db(app);
        helper.getWritableDatabase();
        if (!started) {
            started = true;
            startWriter();
            startMaintenance();
            Thread migration = new Thread(QueryDb::migrateIfNeeded, "query-migrate");
            migration.setDaemon(true);
            migration.start();
        }
    }

    /** Never blocks DNS: the record is queued for the writer thread. */
    static void insert(Stats.Entry e) {
        if (e == null) return;
        synchronized (QUEUE) {
            if (pending.size() >= PENDING_MAX) {
                pending.removeFirst();
                queueDrops.incrementAndGet();
            }
            pending.addLast(e);
            QUEUE.notifyAll();
        }
    }

    private static void startWriter() {
        Thread thread = new Thread(() -> {
            while (true) {
                ArrayList<Stats.Entry> batch = new ArrayList<>();
                synchronized (QUEUE) {
                    while (pending.isEmpty()) {
                        try {
                            QUEUE.wait();
                        } catch (InterruptedException e) {
                            return;
                        }
                    }
                    while (!pending.isEmpty() && batch.size() < 500) batch.add(pending.removeFirst());
                    writing = true;
                }
                try {
                    synchronized (WRITE) {
                        write(batch);
                    }
                } catch (RuntimeException e) {
                    if (writeFailures.getAndAdd(batch.size()) == 0) {
                        android.util.Log.w("HoleShield", "Could not persist a batch of queries", e);
                    }
                } finally {
                    synchronized (QUEUE) {
                        writing = false;
                        QUEUE.notifyAll();
                    }
                }
            }
        }, "query-writer");
        thread.setDaemon(true);
        thread.start();
    }

    private static void write(ArrayList<Stats.Entry> batch) {
        SQLiteDatabase db = helper.getWritableDatabase();
        db.beginTransaction();
        try (SQLiteStatement statement = db.compileStatement(INSERT)) {
            for (Stats.Entry e : batch) {
                bind(statement, e, 0);
                statement.executeInsert();
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    static long queueDrops() { return queueDrops.get(); }
    static long writeFailures() { return writeFailures.get(); }

    /** Waits briefly for queued records so reads see the same data as writes. */
    static void flush() {
        long deadline = System.currentTimeMillis() + 2000L;
        synchronized (QUEUE) {
            while ((!pending.isEmpty() || writing) && System.currentTimeMillis() < deadline) {
                try {
                    QUEUE.wait(5L);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }
    }

    private static void bind(SQLiteStatement s, Stats.Entry e, int origin) {
        s.bindLong(1, e.at);
        s.bindString(2, e.client);
        s.bindString(3, e.domain);
        s.bindLong(4, e.type);
        s.bindString(5, e.status);
        s.bindString(6, e.upstream);
        s.bindLong(7, e.rcode);
        s.bindLong(8, e.ms);
        s.bindString(9, e.rule);
        s.bindString(10, e.category);
        s.bindLong(11, e.blocked ? 1 : 0);
        s.bindLong(12, e.cached ? 1 : 0);
        s.bindString(13, dayKey(e.at));
        s.bindString(14, hourKey(e.at));
        s.bindLong(15, origin);
    }

    static String dayKey(long at) {
        return Retention.dayKey(at);
    }

    static String hourKey(long at) {
        return Retention.hourKey(at);
    }

    /** Start of the local day that contains {@code at}. */
    static long startOfDay(long at) {
        return Retention.startOfDay(at);
    }

    /** Rolling totals for the last 30 days, computed from the rows. */
    static long[] totals(long since) {
        return totals(since, Long.MAX_VALUE);
    }

    /** Totals between {@code from} and {@code to}, computed from the rows. */
    static synchronized long[] totals(long from, long to) {
        long[] out = new long[6];
        if (helper == null) return out;
        flush();
        try {
            SQLiteDatabase db = helper.getReadableDatabase();
            try (Cursor c = db.rawQuery("SELECT COUNT(*),"
                    + "COALESCE(SUM(blocked),0),"
                    + "COALESCE(SUM(cached),0),"
                    + "COALESCE(SUM(CASE WHEN status='FORWARDED' OR status='ALLOWED'"
                    + " THEN 1 ELSE 0 END),0),"
                    + "COALESCE(SUM(CASE WHEN status='ERROR' THEN 1 ELSE 0 END),0),"
                    + "COALESCE(SUM(CASE WHEN blocked=0 AND status IN ('ALLOWED','CACHED','FORWARDED')"
                    + " AND rcode IN (0,3) THEN 1 ELSE 0 END),0)"
                    + " FROM queries WHERE at >= ? AND at < ?",
                    new String[]{Long.toString(from), Long.toString(to)})) {
                if (c.moveToFirst()) {
                    for (int i = 0; i < out.length; i++) out[i] = c.getLong(i);
                }
            }
        } catch (Exception ignored) { }
        return out;
    }

    static synchronized long count() {
        if (helper == null) return 0;
        flush();
        try (Cursor c = helper.getReadableDatabase()
                .rawQuery("SELECT COUNT(*) FROM queries", null)) {
            return c.moveToFirst() ? c.getLong(0) : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    /** Newest-first page, with an optional in-memory filter. */
    static synchronized List<Stats.Entry> page(int offset, int limit, Predicate<Stats.Entry> filter) {
        List<Stats.Entry> out = new ArrayList<>();
        if (helper == null || limit <= 0) return out;
        flush();
        long cutoff = System.currentTimeMillis() - ReverseLogReader.RETENTION_MS;
        try {
            SQLiteDatabase db = helper.getReadableDatabase();
            if (filter == null) {
                try (Cursor c = db.rawQuery("SELECT " + COLUMNS
                        + " FROM queries WHERE at >= ? ORDER BY at DESC, id DESC LIMIT ? OFFSET ?",
                        new String[]{Long.toString(cutoff), Integer.toString(limit),
                                Integer.toString(Math.max(0, offset))})) {
                    while (c.moveToNext()) out.add(read(c));
                }
                return out;
            }
            int skipped = 0;
            try (Cursor c = db.rawQuery("SELECT " + COLUMNS
                    + " FROM queries WHERE at >= ? ORDER BY at DESC, id DESC",
                    new String[]{Long.toString(cutoff)})) {
                while (c.moveToNext()) {
                    Stats.Entry e = read(c);
                    if (!filter.test(e)) continue;
                    if (skipped < offset) {
                        skipped++;
                        continue;
                    }
                    out.add(e);
                    if (out.size() >= limit) break;
                }
            }
        } catch (Exception ignored) { }
        return out;
    }

    private static Stats.Entry read(Cursor c) {
        return new Stats.Entry(c.getLong(0), c.getString(1), c.getString(2),
                (int) c.getLong(3), c.getString(4), c.getString(5), (int) c.getLong(6),
                c.getLong(7), c.getString(8), c.getString(9),
                c.getLong(10) != 0, c.getLong(11) != 0);
    }

    /** One row per local day up to today, oldest first. */
    static synchronized List<Stats.Bucket> daily(int days) {
        flush();
        java.util.Calendar cal = java.util.Calendar.getInstance();
        cal.set(java.util.Calendar.HOUR_OF_DAY, 0);
        cal.set(java.util.Calendar.MINUTE, 0);
        cal.set(java.util.Calendar.SECOND, 0);
        cal.set(java.util.Calendar.MILLISECOND, 0);
        cal.add(java.util.Calendar.DAY_OF_YEAR, -(days - 1));
        Map<String, long[]> rows = group("day", cal.getTimeInMillis());
        List<Stats.Bucket> out = new ArrayList<>(days);
        for (int i = 0; i < days; i++) {
            long at = cal.getTimeInMillis();
            out.add(bucket(rows, dayKey(at), at));
            cal.add(java.util.Calendar.DAY_OF_YEAR, 1);
        }
        return out;
    }

    /** One row per local hour in the requested range, oldest first. */
    static synchronized List<Stats.Bucket> hourly(int hours) {
        flush();
        java.util.Calendar cal = java.util.Calendar.getInstance();
        cal.set(java.util.Calendar.MINUTE, 0);
        cal.set(java.util.Calendar.SECOND, 0);
        cal.set(java.util.Calendar.MILLISECOND, 0);
        cal.add(java.util.Calendar.HOUR_OF_DAY, -(hours - 1));
        Map<String, long[]> rows = group("hour", cal.getTimeInMillis());
        List<Stats.Bucket> out = new ArrayList<>(hours);
        for (int i = 0; i < hours; i++) {
            long at = cal.getTimeInMillis();
            out.add(bucket(rows, hourKey(at), at));
            cal.add(java.util.Calendar.HOUR_OF_DAY, 1);
        }
        return out;
    }

    /** Per-minute buckets read from the rows, oldest first. */
    static synchronized List<Stats.Bucket> live(long start, int minutes) {
        long[] q = new long[minutes];
        long[] b = new long[minutes];
        flush();
        if (helper != null) {
            try (Cursor c = helper.getReadableDatabase().rawQuery(
                    "SELECT at, blocked FROM queries WHERE at >= ? ORDER BY at",
                    new String[]{Long.toString(start)})) {
                while (c.moveToNext()) {
                    int index = (int) ((c.getLong(0) - start) / 60000L);
                    if (index < 0 || index >= minutes) continue;
                    q[index]++;
                    if (c.getLong(1) != 0) b[index]++;
                }
            } catch (Exception ignored) { }
        }
        List<Stats.Bucket> out = new ArrayList<>(minutes);
        for (int i = 0; i < minutes; i++) {
            out.add(new Stats.Bucket(start + i * 60000L, q[i], b[i]));
        }
        return out;
    }

    private static Map<String, long[]> group(String column, long since) {
        Map<String, long[]> rows = new HashMap<>();
        if (helper == null) return rows;
        try (Cursor c = helper.getReadableDatabase().rawQuery(
                "SELECT " + column + ", COUNT(*), COALESCE(SUM(blocked),0)"
                        + " FROM queries WHERE at >= ? GROUP BY " + column,
                new String[]{Long.toString(since)})) {
            while (c.moveToNext()) {
                rows.put(c.getString(0), new long[]{c.getLong(1), c.getLong(2)});
            }
        } catch (Exception ignored) { }
        return rows;
    }

    private static Stats.Bucket bucket(Map<String, long[]> rows, String key, long at) {
        long[] value = rows.get(key);
        return new Stats.Bucket(at, value == null ? 0 : value[0], value == null ? 0 : value[1]);
    }

    static synchronized int unique(String column, long since) {
        if (helper == null) return 0;
        flush();
        try (Cursor c = helper.getReadableDatabase().rawQuery(
                "SELECT COUNT(DISTINCT " + column + ") FROM queries"
                        + " WHERE at >= ? AND " + column + " <> ''",
                new String[]{Long.toString(since)})) {
            return c.moveToFirst() ? c.getInt(0) : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    /** Top values from the retained rows for one column. */
    static synchronized List<Stats.Top> top(String expression, String where, long since,
                                            int limit) {
        List<Stats.Top> out = new ArrayList<>();
        if (helper == null || limit <= 0) return out;
        flush();
        try (Cursor c = helper.getReadableDatabase().rawQuery(
                "SELECT " + expression + " AS key, COUNT(*) AS c FROM queries"
                        + " WHERE at >= ? AND " + where
                        + " GROUP BY key ORDER BY c DESC, key ASC LIMIT ?",
                new String[]{Long.toString(since), Integer.toString(limit)})) {
            while (c.moveToNext()) out.add(new Stats.Top(c.getString(0), c.getInt(1)));
        } catch (Exception ignored) { }
        return out;
    }

    static synchronized void deleteAll() {
        if (helper == null) return;
        synchronized (WRITE) {
            synchronized (QUEUE) {
                pending.clear();
            }
            try {
                helper.getWritableDatabase().delete("queries", null, null);
            } catch (Exception ignored) { }
        }
    }

    /**
     * Merges a bounded backup without replacing live history. For each exact
     * row, preserve the larger of the backup and database occurrence counts.
     * Equal rows can represent separate queries, even at the same millisecond.
     */
    static synchronized void restore(List<Stats.Entry> entries) {
        if (helper == null || entries.isEmpty()) return;
        synchronized (WRITE) {
            SQLiteDatabase db = helper.getWritableDatabase();
            long cutoff = Retention.cutoff(System.currentTimeMillis());
            Map<List<String>, Long> remaining = new HashMap<>();
            db.beginTransactionNonExclusive();
            try (SQLiteStatement statement = db.compileStatement(INSERT)) {
                for (Stats.Entry e : entries) {
                    if (e.at < cutoff) continue;
                    String[] args = {Long.toString(e.at), e.client, e.domain,
                            Integer.toString(e.type), e.status, e.upstream,
                            Integer.toString(e.rcode), Long.toString(e.ms), e.rule,
                            e.category, e.blocked ? "1" : "0", e.cached ? "1" : "0"};
                    List<String> key = Arrays.asList(args);
                    Long count = remaining.get(key);
                    if (count == null) {
                        try (Cursor c = db.rawQuery("SELECT COUNT(*) FROM queries WHERE at=?"
                                + " AND client=? AND domain=? AND type=? AND status=?"
                                + " AND upstream=? AND rcode=? AND ms=? AND rule=?"
                                + " AND category=? AND blocked=? AND cached=?", args)) {
                            count = c.moveToFirst() ? c.getLong(0) : 0L;
                        }
                    }
                    remaining.put(key, Math.max(0, count - 1));
                    if (count > 0) continue;
                    bind(statement, e, 1);
                    statement.executeInsert();
                }
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
        }
    }

    /** Deletes rows that fell out of the rolling window. */
    static synchronized int purge() {
        if (helper == null) return 0;
        long cutoff = System.currentTimeMillis() - ReverseLogReader.RETENTION_MS;
        try {
            int deleted = helper.getWritableDatabase().delete("queries", "at < ?",
                    new String[]{Long.toString(cutoff)});
            if (deleted > 0) {
                android.util.Log.i("HoleShield", "expired " + deleted + " query rows");
            }
            return deleted;
        } catch (Exception e) {
            return 0;
        }
    }

    private static void startMaintenance() {
        Thread thread = new Thread(() -> {
            while (true) {
                try {
                    // Expire on launch, then keep the window trimmed in the
                    // background so a DNS lookup never waits for deletion.
                    purge();
                    Thread.sleep(MAINTENANCE_MS);
                } catch (InterruptedException e) {
                    return;
                } catch (Exception ignored) { }
            }
        }, "query-maintenance");
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * Imports the day-partitioned files and the pre-2.3.6 single log once.
     * Rows go to a staging table so live DNS inserts are never dropped, and
     * the source files are removed only after the merge commits.
     */
    private static void migrateIfNeeded() {
        File files;
        synchronized (QueryDb.class) {
            if (app == null) return;
            files = app.getFilesDir();
        }
        try {
            if (isMigrated()) {
                deleteSources(files);
                android.util.Log.i("HoleShield", "query store ready: " + count() + " rows");
                return;
            }
            File legacy = new File(files, Stats.LOG_LEGACY);
            File dir = new File(files, Stats.LOG_DIR);
            List<File> sources = new ArrayList<>();
            if (legacy.isFile()) sources.add(legacy);
            File[] parts = dir.listFiles();
            if (parts != null) {
                for (File part : parts) {
                    String name = part.getName();
                    if (name.endsWith(".tsv") || name.endsWith(".tsv.gz")) sources.add(part);
                }
            }
            Collections.sort(sources);
            if (sources.isEmpty()) {
                markMigrated();
                android.util.Log.i("HoleShield", "query store created: no history to import");
                return;
            }
            android.util.Log.i("HoleShield", "importing " + sources.size()
                    + " query history files into SQLite");

            SQLiteDatabase db = helper.getWritableDatabase();
            db.execSQL("DROP TABLE IF EXISTS staging");
            db.execSQL("CREATE TABLE staging ("
                    + "at INTEGER, day TEXT, hour TEXT, client TEXT, domain TEXT,"
                    + " type INTEGER, status TEXT, upstream TEXT, rcode INTEGER,"
                    + " ms INTEGER, rule TEXT, category TEXT, blocked INTEGER, cached INTEGER)");
            SQLiteStatement insert = db.compileStatement(
                    "INSERT INTO staging VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)");
            long cutoff = System.currentTimeMillis() - ReverseLogReader.RETENTION_MS;
            int pending = 0;
            long parsed = 0;
            db.beginTransaction();
            try {
                for (File source : sources) {
                    try (BufferedReader reader = open(source)) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            Stats.Entry e = Stats.parseLogLine(line);
                            if (e == null || e.at < cutoff) continue;
                            parsed++;
                            insert.clearBindings();
                            insert.bindLong(1, e.at);
                            insert.bindString(2, dayKey(e.at));
                            insert.bindString(3, hourKey(e.at));
                            insert.bindString(4, e.client);
                            insert.bindString(5, e.domain);
                            insert.bindLong(6, e.type);
                            insert.bindString(7, e.status);
                            insert.bindString(8, e.upstream);
                            insert.bindLong(9, e.rcode);
                            insert.bindLong(10, e.ms);
                            insert.bindString(11, e.rule);
                            insert.bindString(12, e.category);
                            insert.bindLong(13, e.blocked ? 1 : 0);
                            insert.bindLong(14, e.cached ? 1 : 0);
                            insert.executeInsert();
                            if (++pending >= MIGRATION_BATCH) {
                                db.setTransactionSuccessful();
                                db.endTransaction();
                                db.beginTransaction();
                                pending = 0;
                            }
                        }
                    } catch (Exception ignored) { }
                }
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
            db.beginTransaction();
            try {
                db.execSQL("INSERT INTO queries (" + COLUMNS + ",day,hour,origin)"
                        + " SELECT " + COLUMNS + ",day,hour,0 FROM staging ORDER BY at");
                db.execSQL("DROP TABLE staging");
                db.execSQL("UPDATE meta SET value='1' WHERE key='migrated'");
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
            long merged = count();
            android.util.Log.i("HoleShield", "imported " + parsed + " history rows; store has "
                    + merged);
            if (parsed == 0 || merged >= parsed) deleteSources(files);
        } catch (Exception e) {
            android.util.Log.w("HoleShield", "query history import failed", e);
        }
    }

    private static BufferedReader open(File file) throws Exception {
        if (file.getName().endsWith(".gz")) {
            return new BufferedReader(new InputStreamReader(
                    new GZIPInputStream(new FileInputStream(file)), StandardCharsets.UTF_8));
        }
        return new BufferedReader(new InputStreamReader(
                new FileInputStream(file), StandardCharsets.UTF_8));
    }

    private static boolean isMigrated() {
        try (Cursor c = helper.getReadableDatabase().rawQuery(
                "SELECT value FROM meta WHERE key='migrated'", null)) {
            return c.moveToFirst() && "1".equals(c.getString(0));
        } catch (Exception e) {
            return false;
        }
    }

    private static void markMigrated() {
        try {
            helper.getWritableDatabase().execSQL(
                    "UPDATE meta SET value='1' WHERE key='migrated'");
        } catch (Exception ignored) { }
    }

    private static void deleteSources(File files) {
        File legacy = new File(files, Stats.LOG_LEGACY);
        if (legacy.isFile()) legacy.delete();
        File dir = new File(files, Stats.LOG_DIR);
        File[] parts = dir.listFiles();
        if (parts != null) for (File part : parts) part.delete();
        dir.delete();
    }
}
