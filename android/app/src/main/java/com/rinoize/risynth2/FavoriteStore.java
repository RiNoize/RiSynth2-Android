package com.rinoize.risynth2;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/** Private, independent snapshots, not references to imported banks.
 * One hidden staging directory and one atomic rename per saved sound.
 * Serialized by the control worker; no audio-thread or UI-thread file I/O. */
final class FavoriteStore {
    private static final String PREFIX = ":favorites:";
    private final File root;
    static final class Saved {
        final String bankKey;
        final int slot;
        final boolean alreadySaved;
        Saved(int index, boolean alreadySaved) {
            bankKey = PREFIX + (index / 128); slot = index % 128;
            this.alreadySaved = alreadySaved;
        }
    }
    FavoriteStore(File root) { this.root = root; }
    static boolean isFavorite(PatchStore.Bank bank) { return bank.key.startsWith(PREFIX); }
    private List<File> entries() throws IOException {
        if (!root.exists()) return new ArrayList<>();
        File[] files = root.listFiles(f -> f.isDirectory() && f.getName().matches("[0-9]{8}-[0-9a-f]{64}"));
        if (files == null) throw new IOException("No se pudo leer Favoritos");
        Arrays.sort(files, Comparator.comparing(File::getName));
        return new ArrayList<>(Arrays.asList(files));
    }
    List<PatchStore.Bank> scan() throws IOException {
        List<PatchStore.Bank> banks = new ArrayList<>();
        List<File> entries = entries();
        for (int start = 0; start < entries.size(); start += 128) {
            PatchStore.Patch[] slots = new PatchStore.Patch[128];
            for (int i = start; i < Math.min(start + 128, entries.size()); ++i) {
                File dir = entries.get(i), sound = new File(dir,"patch.sy1");
                if (!sound.isFile()) throw new IOException("Favorito incompleto: " + dir.getName());
                String name = new String(PatchStore.readBounded(new FileInputStream(new File(dir,"name.txt")), 4096), StandardCharsets.UTF_8);
                slots[i-start] = new PatchStore.Patch(sound, name, i-start);
            }
            int page = start/128;
            banks.add(new PatchStore.Bank(PREFIX+page, page==0 ? "★ Favoritos" : "★ Favoritos ("+(page+1)+")", slots));
        }
        return banks;
    }
    synchronized Saved save(String snapshot) throws IOException {
        byte[] data = snapshot.getBytes(StandardCharsets.UTF_8);
        if (data.length > PatchStore.MAX_PATCH) throw new IOException("Patch demasiado grande");
        String text = PatchStore.validate(data);
        data = text.getBytes(StandardCharsets.UTF_8);
        String hash;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(data);
            StringBuilder hex = new StringBuilder();
            for (byte b : digest) hex.append(String.format(Locale.ROOT,"%02x",b & 255));
            hash = hex.toString();
        } catch (NoSuchAlgorithmException ex) { throw new IOException("SHA-256 no disponible",ex); }
        List<File> saved = entries();
        for (int i=0; i<saved.size(); ++i) if (saved.get(i).getName().endsWith("-"+hash)) return new Saved(i,true);
        if (saved.size() >= 131072) throw new IOException("Favoritos alcanzó su límite de 131072 sonidos");
        if (!root.isDirectory() && !root.mkdirs()) throw new IOException("No se pudo crear Favoritos");
        int sequence = saved.isEmpty() ? 1 : Integer.parseInt(saved.get(saved.size()-1).getName().substring(0,8)) + 1;
        File staging = new File(root,".pending-"+UUID.randomUUID());
        if (!staging.mkdir()) throw new IOException("No se pudo preparar el favorito");
        boolean committed = false;
        try {
            // Never use the patch's display name as a path. Edited patches
            // with the same name get their own snapshot; originals are untouched.
            String name = new PatchStore.Patch(text,0).name;
            if (name.length()>512) name=name.substring(0,512);
            Files.write(new File(staging,"patch.sy1").toPath(),data);
            Files.write(new File(staging,"name.txt").toPath(),name.getBytes(StandardCharsets.UTF_8));
            File destination = new File(root,String.format(Locale.ROOT,"%08d-%s",sequence,hash));
            if (destination.exists() || !staging.renameTo(destination)) throw new IOException("No se pudo guardar el favorito");
            committed = true;
            return new Saved(saved.size(),false);
        } finally {
            if (!committed) {
                File[] children=staging.listFiles();
                if (children!=null) for (File child:children) child.delete();
                staging.delete();
            }
        }
    }
}
