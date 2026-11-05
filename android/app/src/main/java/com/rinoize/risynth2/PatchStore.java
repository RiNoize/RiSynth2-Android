package com.rinoize.risynth2;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.nio.file.Files;
import java.util.*;
import java.util.regex.*;
import java.util.zip.*;

/** Private library. Imports are copied, bounded and staged atomically.
 * ZIP entry names never become filesystem paths. No bundled third-party banks. */
final class PatchStore {
    static final int MAX_PATCH = 64 * 1024;
    private static final int MAX_ZIP = 32 * 1024 * 1024, MAX_TOTAL = 32 * 1024 * 1024, MAX_ENTRIES = 8192;
    private static final Pattern VALUE = Pattern.compile("(?m)^\\s*\\d{1,2}\\s*,\\s*-?\\d+\\s*$");
    private static final Pattern SLOT = Pattern.compile("^(\\d{1,3})(?:\\D.*)?$");
    static final class Patch {
        final String name, text;
        final int slot;
        Patch(String text, int slot) {
            this.text = text; this.slot = slot;
            String[] lines = text.split("\\R", 2);
            name = lines.length == 0 || lines[0].isBlank() ? "Sin nombre" : lines[0];
        }
    }
    static final class Bank {
        final String key, name;
        final Patch[] slots;
        Bank(String key, String name, Patch[] slots) { this.key = key; this.name = name; this.slots = slots; }
        int first() { for (int i = 0; i < 128; ++i) if (slots[i] != null) return i; return -1; }
    }
    private static final class Entry {
        final String file, text;
        Entry(String file, String text) { this.file = file; this.text = text; }
    }
    private final File root, cache;
    PatchStore(File root, File cache) throws IOException {
        this.root = root; this.cache = cache;
        if ((!root.isDirectory() && !root.mkdirs()) || (!cache.isDirectory() && !cache.mkdirs())) throw new IOException("No se pudo crear la biblioteca");
    }
    List<Bank> scan(String init) throws IOException {
        List<Bank> result = new ArrayList<>();
        Patch[] initial = new Patch[128]; initial[0] = new Patch(init, 0);
        result.add(new Bank(":init", "RiSynth2 · INIT", initial));
        File[] imports = root.listFiles(f -> f.isDirectory() && !f.getName().startsWith("."));
        if (imports == null) throw new IOException("No se pudo leer la biblioteca");
        Arrays.sort(imports, Comparator.comparing(File::getName));
        for (File batch : imports) {
            File[] folders = batch.listFiles(File::isDirectory);
            if (folders == null) continue;
            Arrays.sort(folders, Comparator.comparing(File::getName));
            for (File folder : folders) {
                File title = new File(folder, "name.txt");
                String name = title.isFile() ? decode(readBounded(new FileInputStream(title), MAX_PATCH)) : folder.getName();
                Patch[] slots = new Patch[128];
                for (int i = 0; i < 128; ++i) {
                    File file = new File(folder, String.format(Locale.ROOT, "%03d.sy1", i + 1));
                    if (file.isFile()) slots[i] = new Patch(validate(readBounded(new FileInputStream(file), MAX_PATCH)), i);
                }
                Bank bank = new Bank(batch.getName() + "/" + folder.getName(), name, slots);
                if (bank.first() >= 0) result.add(bank);
            }
        }
        if (result.size() > 1) result.subList(1, result.size()).sort(Comparator.comparing(b -> b.name.toLowerCase(Locale.ROOT)));
        return Collections.unmodifiableList(result);
    }
    int importFile(InputStream input, String filename) throws IOException {
        File source = File.createTempFile("risynth2-", ".import", cache);
        File staging = new File(root, ".import-" + UUID.randomUUID());
        if (!staging.mkdir()) throw new IOException("No se pudo preparar la importacion");
        boolean committed = false;
        try {
            try (InputStream in = input; OutputStream out = new FileOutputStream(source)) {
                byte[] b = new byte[8192]; int n, total = 0;
                while ((n = in.read(b)) != -1) { total += n; if (total > MAX_ZIP) throw new IOException("Archivo mayor a 32 MiB"); out.write(b, 0, n); }
            }
            boolean zip;
            try (InputStream in = new FileInputStream(source)) { zip = in.read() == 'P' && in.read() == 'K'; }
            String title = filename == null ? "Importado" : filename.replaceAll("(?i)\\.(zip|sy1)$", "");
            Map<String, List<Entry>> groups = new TreeMap<>();
            int totalBytes = 0, count = 0;
            if (zip) {
                try (ZipFile archive = new ZipFile(source, Charset.forName("CP437"))) {
                    Enumeration<? extends ZipEntry> entries = archive.entries();
                    int seen = 0;
                    while (entries.hasMoreElements()) {
                        ZipEntry entry = entries.nextElement();
                        if (++seen > MAX_ENTRIES) throw new IOException("ZIP con demasiadas entradas");
                        String name = entry.getName().replace('\\', '/');
                        if (entry.isDirectory() || !name.toLowerCase(Locale.ROOT).endsWith(".sy1")) continue;
                        boolean hidden = false;
                        for (String part : name.split("/")) if (part.startsWith(".") || part.equals("__MACOSX")) hidden = true;
                        if (hidden || name.startsWith("/")) continue;
                        if (entry.getSize() > MAX_PATCH) throw new IOException("Patch demasiado grande: " + name);
                        byte[] data = readBounded(archive.getInputStream(entry), MAX_PATCH);
                        totalBytes += data.length;
                        if (totalBytes > MAX_TOTAL || ++count > 4096) throw new IOException("Banco mayor al limite de 4096 patches / 32 MiB");
                        int slash = name.lastIndexOf('/');
                        String group = title + (slash < 0 ? "" : " / " + name.substring(0, slash));
                        groups.computeIfAbsent(group, k -> new ArrayList<>()).add(new Entry(name.substring(slash + 1), validate(data)));
                    }
                }
            } else {
                groups.put(title, new ArrayList<>(List.of(new Entry(filename == null ? "001.sy1" : filename, validate(readBounded(new FileInputStream(source), MAX_PATCH))))));
                count = 1;
            }
            if (count == 0) throw new IOException("No se encontraron archivos .sy1");
            int bankIndex = 0;
            for (Map.Entry<String, List<Entry>> group : groups.entrySet()) {
                List<Entry> patches = group.getValue();
                patches.sort(Comparator.comparing(e -> e.file.toLowerCase(Locale.ROOT)));
                Patch[] slots = new Patch[128]; int used = 0, part = 1;
                for (Entry entry : patches) {
                    if (used == 128) {
                        writeBank(staging, ++bankIndex, group.getKey() + " (" + part++ + ")", slots);
                        slots = new Patch[128]; used = 0;
                    }
                    int slot = requestedSlot(entry.file);
                    if (slot < 0 || slots[slot] != null) { slot = 0; while (slots[slot] != null) ++slot; }
                    slots[slot] = new Patch(entry.text, slot); ++used;
                }
                if (used > 0) writeBank(staging, ++bankIndex, group.getKey() + (part > 1 ? " (" + part + ")" : ""), slots);
            }
            File destination = new File(root, "import-" + UUID.randomUUID());
            if (!staging.renameTo(destination)) throw new IOException("No se pudo guardar la biblioteca");
            committed = true;
            return count;
        } finally {
            source.delete();
            if (!committed) deleteTree(staging);
        }
    }
    private static void writeBank(File staging, int index, String title, Patch[] slots) throws IOException {
        File dir = new File(staging, String.format(Locale.ROOT, "%04d", index));
        if (!dir.mkdir()) throw new IOException("No se pudo crear banco");
        Files.write(new File(dir, "name.txt").toPath(), title.getBytes(StandardCharsets.UTF_8));
        for (int i = 0; i < 128; ++i) if (slots[i] != null) Files.write(new File(dir, String.format(Locale.ROOT, "%03d.sy1", i + 1)).toPath(), slots[i].text.getBytes(StandardCharsets.UTF_8));
    }
    private static int requestedSlot(String filename) {
        String stem = filename.replaceAll("(?i)\\.sy1$", "");
        Matcher m = SLOT.matcher(stem);
        if (!m.matches()) return -1;
        int value = Integer.parseInt(m.group(1)); return value >= 1 && value <= 128 ? value - 1 : -1;
    }
    static byte[] readBounded(InputStream input, int limit) throws IOException {
        try (InputStream in = input; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096]; int n;
            while ((n = in.read(buffer)) != -1) {
                if (out.size() + n > limit) throw new IOException("Archivo demasiado grande");
                out.write(buffer, 0, n);
            }
            return out.toByteArray();
        }
    }
    static String validate(byte[] bytes) throws IOException {
        String text = decode(bytes).replace("\r\n", "\n").replace('\r', '\n');
        if (text.startsWith("\ufeff")) text = text.substring(1);
        if (text.indexOf('\0') >= 0 || !VALUE.matcher(text).find()) throw new IOException("Archivo .sy1 no valido");
        return text;
    }
    private static String decode(byte[] bytes) {
        try { return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString(); }
        catch (CharacterCodingException e) { return new String(bytes, Charset.forName("windows-1252")); }
    }
    private static void deleteTree(File f) {
        File[] children = f.listFiles(); if (children != null) for (File child : children) deleteTree(child);
        f.delete();
    }
}
