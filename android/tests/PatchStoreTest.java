package com.rinoize.risynth2;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
public final class PatchStoreTest {
    private static void check(boolean ok, String text) { if (!ok) throw new AssertionError(text); }
    private static byte[] bytes(String text) { return text.getBytes(StandardCharsets.UTF_8); }
    private static String patch(String name) { return name+"\nver=112\n0,1\n19,90\n94,16\n"; }
    public static void main(String[] args) throws Exception {
        Path temp = Files.createTempDirectory("risynth2-test-");
        try {
            PatchStore store = new PatchStore(temp.resolve("banks").toFile(), temp.resolve("cache").toFile());
            ByteArrayOutputStream zipBytes = new ByteArrayOutputStream();
            try (ZipOutputStream zip = new ZipOutputStream(zipBytes)) {
                zip.putNextEntry(new ZipEntry("Bank/003_Example.sy1")); zip.write(bytes(patch("Example"))); zip.closeEntry();
                zip.putNextEntry(new ZipEntry("Bank/127.sy1")); zip.write(bytes(patch("Other"))); zip.closeEntry();
                zip.putNextEntry(new ZipEntry("../../escape.sy1")); zip.write(bytes(patch("Unsafe"))); zip.closeEntry();
                zip.putNextEntry(new ZipEntry("__MACOSX/001.sy1")); zip.write(bytes(patch("Ignored"))); zip.closeEntry();
            }
            check(store.importFile(new ByteArrayInputStream(zipBytes.toByteArray()),"Test.zip") == 2,"only valid entries imported");
            List<PatchStore.Bank> banks = store.scan(patch("INIT"));
            check(banks.size() == 2,"one imported bank plus init");
            check(banks.get(1).slots[2].name.equals("Example"),"slot 003 preserved");
            check(banks.get(1).slots[126].name.equals("Other"),"slot 127 preserved");
            check(!temp.resolve("escape.sy1").toFile().exists(),"no traversal");
            boolean refused = false;
            try { store.importFile(new ByteArrayInputStream(bytes("not a preset")),"bad.sy1"); } catch (IOException expected) { refused = true; }
            check(refused,"invalid patch rejected");
            check(store.scan(patch("INIT")).size() == 2,"failed import is atomic");
            refused = false;
            try { PatchStore.readBounded(new ByteArrayInputStream(new byte[100]),16); } catch (IOException expected) { refused = true; }
            check(refused,"byte limit enforced");
            System.out.println("Patch store: ZIP, slots, traversal, invalid and bounded input passed");
        } finally {
            try (java.util.stream.Stream<Path> paths = Files.walk(temp)) { paths.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete()); }
        }
    }
}
