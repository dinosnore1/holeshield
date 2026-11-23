package com.dino.holeshield;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.database.DatabaseUtils;
import android.database.sqlite.SQLiteDatabase;
import android.os.Bundle;

import org.json.JSONArray;

import java.util.Arrays;

/** Runs in an isolated test APK, never in the installed Hole Shield package. */
public final class TestQueryDb extends Instrumentation {
    private String phase;
    private static final int OVERLOAD_ROWS = 25000;

    @Override public void onCreate(Bundle args) {
        phase = args.getString("phase");
        start();
    }

    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            Context context = getTargetContext();
            if ("seed".equals(phase)) {
                context.deleteDatabase("queries.db");
                QueryDb.open(context);
                QueryDb.insert(entry(System.currentTimeMillis(), "startup-probe"));
                QueryDb.flush();
                check(QueryDb.count() == 1, "initial write");
                try (SQLiteDatabase db = open(context)) {
                    db.execSQL("UPDATE queries SET at=?", new Object[]{
                            Retention.cutoff(System.currentTimeMillis()) - 60_000});
                }
                long now = System.currentTimeMillis();
                for (int i = 0; i < 5000; i++) {
                    QueryDb.insert(entry(now, "burst-" + i));
                }
                QueryDb.flush();
                check(QueryDb.totals(Retention.cutoff(now))[0] == 5000,
                        "rolling totals exclude physically present expired rows");
                Stats.Entry same = entry(now, "burst-0");
                Stats.Entry other = entry(now, "backup-only");
                Stats.Entry expired = entry(Retention.cutoff(now) - 1000, "old-backup");
                for (int i = 0; i < 2; i++) {
                    QueryDb.restore(Arrays.asList(same, same, other, expired));
                    check(QueryDb.count() == 5003, "repeat restore preserves multiplicity");
                }
                JSONArray snapshot = Stats.exportLog();
                check(snapshot.length() == 5002, "JSON backup excludes expired rows");
                QueryDb.deleteAll();
                QueryDb.insert(entry(now, "live-after-backup"));
                QueryDb.flush();
                Stats.importLog(context, snapshot);
                Stats.importLog(context, snapshot);
                check(QueryDb.count() == 5003, "JSON restore round trip retains newer queries");
                try (SQLiteDatabase db = open(context)) {
                    db.execSQL("UPDATE queries SET at=? WHERE domain='live-after-backup'",
                            new Object[]{Retention.cutoff(now) - 60_000});
                }
            } else if ("restart".equals(phase)) {
                QueryDb.open(context);
                long deadline = android.os.SystemClock.elapsedRealtime() + 5000;
                while (QueryDb.count() != 5002
                        && android.os.SystemClock.elapsedRealtime() < deadline) {
                    android.os.SystemClock.sleep(20);
                }
                check(QueryDb.count() == 5002, "startup purge and restart persistence");
                try (SQLiteDatabase db = open(context)) {
                    check(DatabaseUtils.longForQuery(db,
                            "SELECT COUNT(*) FROM queries WHERE domain='burst-0'", null) == 2,
                            "same-millisecond rows survived restart");
                }
            } else if ("overload".equals(phase)) {
                checkOverload(context);
            } else if ("overload-restart".equals(phase)) {
                QueryDb.open(context);
                check(QueryDb.count() == context.getSharedPreferences("test", 0)
                        .getLong("persisted", -1), "overload rows survived restart");
                check(QueryDb.queueDrops() == 0 && QueryDb.writeFailures() == 0,
                        "diagnostics reset on process restart");
            } else {
                throw new AssertionError("Unexpected phase: " + phase);
            }
            result.putString("stream", "\nQueryDb " + phase + ": PASS\n");
            finish(Activity.RESULT_OK, result);
        } catch (Throwable e) {
            result.putString("stream", "\n" + android.util.Log.getStackTraceString(e));
            finish(Activity.RESULT_CANCELED, result);
        }
    }

    private static void checkOverload(Context context) {
        QueryDb.open(context);
        long initial = QueryDb.count();
        long at = System.currentTimeMillis();
        QueryDb.insert(outcome(at, "FORWARDED", 0, false));
        QueryDb.insert(outcome(at, "ALLOWED", 3, false));
        QueryDb.insert(outcome(at, "CACHED", 0, true));
        QueryDb.insert(outcome(at, "FORWARDED", 2, false));
        QueryDb.insert(outcome(at, "ERROR", 2, false));
        QueryDb.insert(outcome(at, "BLOCKED", 0, false));
        long[] totals = QueryDb.totals(Retention.cutoff(at));
        check(totals[5] == 3, "allowed excludes blocks and failures: " + Arrays.toString(totals));
        try (SQLiteDatabase db = open(context)) {
            db.beginTransactionNonExclusive();
            try {
                // Hold the SQLite writer lock while the DNS-side producer keeps enqueueing.
                Stats.Entry query = entry(at, "overload");
                for (int i = 0; i < OVERLOAD_ROWS; i++) QueryDb.insert(query);
                check(QueryDb.queueDrops() > 0, "slow storage fills the queue");
            } finally {
                db.endTransaction();
            }
            long overloadDeadline = android.os.SystemClock.elapsedRealtime() + 15_000;
            while (QueryDb.count() < initial + 6 + OVERLOAD_ROWS - QueryDb.queueDrops()
                    && android.os.SystemClock.elapsedRealtime() < overloadDeadline) {
                // count waits briefly for pending writes
            }
            long persisted = QueryDb.count();
            check(QueryDb.writeFailures() == 0, "no write errors after unlocking storage");
            check(persisted + QueryDb.queueDrops() == initial + 6 + OVERLOAD_ROWS,
                    "every attempt was persisted or counted as dropped");
            db.execSQL("CREATE TRIGGER test_write_failure BEFORE INSERT ON queries"
                    + " WHEN NEW.domain='fail' BEGIN SELECT RAISE(ABORT,'test'); END");
            QueryDb.insert(entry(at, "fail"));
            check(QueryDb.count() == persisted && QueryDb.writeFailures() == 1,
                    "failed transactions are counted");
            db.execSQL("DROP TRIGGER test_write_failure");
            QueryDb.insert(entry(at, "recover"));
            check(QueryDb.count() == persisted + 1, "writer continues after an error");
            check(context.getSharedPreferences("test", 0).edit()
                    .putLong("persisted", persisted + 1).commit(), "save restart expectation");
        }
    }

    private static Stats.Entry outcome(long at, String status, int rcode, boolean cached) {
        return new Stats.Entry(at, "test-client", "allowed-test", 1, status, "test",
                rcode, 0L, "", "", "BLOCKED".equals(status), cached);
    }

    private static SQLiteDatabase open(Context context) {
        return SQLiteDatabase.openDatabase(context.getDatabasePath("queries.db").getPath(),
                null, SQLiteDatabase.OPEN_READWRITE | SQLiteDatabase.ENABLE_WRITE_AHEAD_LOGGING);
    }

    private static Stats.Entry entry(long at, String domain) {
        return new Stats.Entry(at, "test-client", domain, 1, "BLOCKED", "gravity",
                0, 0L, "", "Ads", true, false);
    }

    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }
}
