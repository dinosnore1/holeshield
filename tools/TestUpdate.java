package com.dino.holeshield;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** Checks for the in-app updater's checksum and version rules. */
public final class TestUpdate {

    private static int passed = 0;

    public static void main(String[] args) throws Exception {
        versions();
        checksums();
        System.out.println("OK — " + passed + " update checks passed");
    }

    private static void versions() {
        check(UpdateVerifier.compareVersions("2.3.5", "2.3.5") == 0, "same version");
        check(UpdateVerifier.compareVersions("2.3.5", "2.3.6") < 0, "patch newer");
        check(UpdateVerifier.compareVersions("2.10.0", "2.9.9") > 0, "numeric not lexical");
        check(UpdateVerifier.compareVersions("2.3", "2.3.0") == 0, "missing part is zero");
        check(UpdateVerifier.compareVersions("3.0.0-beta", "2.9.9") > 0, "suffix ignored");
        check(UpdateVerifier.compareVersions(null, "1.0.0") < 0, "null is oldest");
    }

    private static void checksums() throws Exception {
        File file = File.createTempFile("holeshield-update", ".apk");
        try {
            Files.write(file.toPath(), "hole shield".getBytes(StandardCharsets.UTF_8));
            String hash = UpdateVerifier.sha256Hex(file);
            check(hash.equals("a62812623d66c8db3996ad2f355fe00f04e938f6f7804ee1a2b00a0a16846d8e"), "sha256 matches the known digest");
            check(UpdateVerifier.matches(hash, hash.toUpperCase()), "hash compare ignores case");
            check(!UpdateVerifier.matches(hash, hash.substring(1) + "0"), "wrong hash rejected");
            check(!UpdateVerifier.matches("", hash), "empty expected hash rejected");
            check(!UpdateVerifier.matches(null, hash), "missing expected hash rejected");
            check(UpdateVerifier.sha256Hex(file).equals(hash), "hash is stable");
        } finally {
            file.delete();
        }
    }

    private static void check(boolean condition, String what) {
        if (!condition) throw new AssertionError("FAILED: " + what);
        passed++;
    }
}
