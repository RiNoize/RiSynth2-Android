package com.rinoize.risynth2;
import java.io.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
public final class FavoriteStoreTest {
    static void check(boolean value,String message) { if(!value)throw new AssertionError(message); }
    static String patch(int i) { return "Pad "+i+"\nver=112\n0,1\n19,"+(i%128)+"\n"; }
    public static void main(String[] args) throws Exception {
        File base=Files.createTempDirectory("risynth2-favorites-").toFile();
        try {
            PatchStore store=new PatchStore(new File(base,"banks"),new File(base,"cache"));
            check(store.scan(patch(0)).size()==1,"empty favorites must not change INIT");
            store.importFile(new ByteArrayInputStream(patch(777).getBytes(StandardCharsets.UTF_8)),"original.sy1");
            PatchStore.Bank original=store.scan(patch(0)).get(1);
            for(int i=0;i<260;++i) {
                FavoriteStore.Saved saved=store.favorites.save(patch(i));
                check(!saved.alreadySaved && saved.slot==i%128,"pagination/slots");
            }
            check(store.favorites.save(patch(2)).alreadySaved,"identical repeated press must not duplicate");
            String edited=patch(2)+"20,95\n";
            check(!store.favorites.save(edited).alreadySaved,"edited same-name patch needs independent copy");
            String hostile="../../Fuera ★\nver=112\n0,1\n";
            store.favorites.save(hostile);
            List<PatchStore.Bank> all=store.scan(patch(0));
            check(all.size()==5 && FavoriteStore.isFavorite(all.get(1)),"favorites pinned, 3 pages, original retained");
            check(all.get(1).slots[2].readText().equals(patch(2)),"original snapshot retained");
            check(all.get(3).slots[4].readText().equals(edited),"edits preserved");
            check(all.get(3).slots[5].readText().equals(hostile),"unicode and path-like name preserved safely");
            check(original.slots[0].readText().equals(patch(777)),"source bank untouched");
            check(all.get(1).slots[0].isLazy(),"lazy favorites data");
            check(!new File(base,"Fuera ★").exists(),"no path injection");
            PatchStore reopened=new PatchStore(new File(base,"banks"),new File(base,"cache"));
            check(reopened.favorites.save(edited).alreadySaved,"dedup survives restart");
            File interrupted=new File(base,"banks/.favorites/.pending-interrupted");interrupted.mkdir();
            Files.writeString(new File(interrupted,"patch.sy1").toPath(),patch(999));
            check(reopened.favorites.scan().size()==3,"incomplete staging is never visible");
            try {reopened.favorites.save("invalid");throw new AssertionError("invalid accepted");}catch(IOException expected){}
            File blocked=new File(base,"blocked");Files.writeString(blocked.toPath(),"file");
            try {new FavoriteStore(blocked).save(patch(1));throw new AssertionError("blocked path accepted");}catch(IOException expected){}
            check(reopened.scan(patch(0)).size()==5,"failed save leaves library untouched");
            System.out.println("Favorites: 262 snapshots, repeat dedup, edits, restart, 128-slot pages, lazy reads, source safety and failed/partial writes OK");
        } finally {try(var paths=Files.walk(base.toPath())) {paths.sorted(Comparator.reverseOrder()).forEach(p->{try{Files.delete(p);}catch(IOException e){throw new UncheckedIOException(e);}});}}
    }
}
