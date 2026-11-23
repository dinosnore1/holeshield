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
    private boolean restart;

    @Override public void onCreate(Bundle args) {
        restart = "restart".equals(args.getString("phase"));
        start();
    }

    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            Context context = getTargetContext();
            if (!restart) {
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
            } else {
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
            }
            result.putString("stream", "\nQueryDb " + (restart ? "restart" : "seed") + ": PASS\n");
            finish(Activity.RESULT_OK, result);
        } catch (Throwable e) {
            result.putString("stream", "\n" + android.util.Log.getStackTraceString(e));
            finish(Activity.RESULT_CANCELED, result);
        }
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
