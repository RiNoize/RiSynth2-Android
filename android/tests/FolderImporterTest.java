package com.rinoize.risynth2;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

public final class FolderImporterTest {
    private static void check(boolean x,String message) { if(!x) throw new AssertionError(message); }
    private static String patch(String name) { return name+"\nver=112\n0,1\n19,90\n94,16\n"; }
    private static class Source implements FolderImporter.Source {
        final Map<String,List<FolderImporter.Node>> dirs=new HashMap<>();
        final Map<String,String> data=new HashMap<>();
        final FolderImporter.Node root=new FolderImporter.Node("root","Colección",true,-1);
        Source() { dirs.put("root",new ArrayList<>()); }
        FolderImporter.Node dir(FolderImporter.Node parent,String name) {
            String id=parent.id+"/"+name;
            FolderImporter.Node n=new FolderImporter.Node(id,name,true,-1);
            dirs.get(parent.id).add(n); dirs.put(id,new ArrayList<>()); return n;
        }
        void file(FolderImporter.Node parent,String filename,String text) {
            String id=parent.id+"/"+filename;
            dirs.get(parent.id).add(new FolderImporter.Node(id,filename,false,-1)); data.put(id,text);
        }
        public List<FolderImporter.Node> children(FolderImporter.Node node) throws IOException { return new ArrayList<>(dirs.get(node.id)); }
        public InputStream open(FolderImporter.Node node) throws IOException { return new ByteArrayInputStream(data.get(node.id).getBytes(StandardCharsets.UTF_8)); }
    }
    private static FolderImporter.Result run(PatchStore store,Source source) throws Exception {
        return FolderImporter.run(store,source,source.root,()->false,(f,p,s,n)->{});
    }
    public static void main(String[] args) throws Exception {
        Path temp=Files.createTempDirectory("risynth2-folders-");
        try {
            PatchStore store=new PatchStore(temp.resolve("banks").toFile(),temp.resolve("cache").toFile());
            Source source=new Source(); FolderImporter.Node a=source.dir(source.root,"Bancos"), b=source.dir(a,"Pads");
            source.file(b,"001_A.sy1",patch("First")); source.file(b,"001_B.sy1",patch("Duplicate slot"));
            source.file(b,"002.sy1",patch("Second")); source.file(b,"A.sy1",patch("Unnamed")); source.file(b,"bad.sy1","invalid");
            source.file(b,"readme.txt","not a patch"); source.file(source.root,"127.SY1",patch("Root"));
            FolderImporter.Node hidden=source.dir(source.root,".hidden"); source.file(hidden,"001.sy1",patch("Hidden"));
            // A repeated directory ID emulates a provider alias/cycle.
            source.dirs.get(b.id).add(source.root);
            FolderImporter.Result result=run(store,source);
            check(result.banks==2 && result.patches==5 && result.invalid==1 && result.ignored==1,"nested, root, hidden, invalid and cycles");
            List<PatchStore.Bank> banks=store.scan(patch("INIT"));
            PatchStore.Bank pads=banks.stream().filter(x->x.name.endsWith("Bancos / Pads")).findFirst().orElseThrow();
            check(pads.slots[0].name.equals("First") && pads.slots[1].name.equals("Second"),"numbered slots protected from duplicates");
            check(pads.slots[0].isLazy() && pads.slots[0].readText().contains("First"),"lazy read retains sound data");
            run(store,source); check(store.scan(patch("INIT")).size()==5,"duplicate import does not overwrite original");
            Source full=new Source(); FolderImporter.Node big=full.dir(full.root,"Large");
            for(int i=1;i<=260;i++) full.file(big,String.format(Locale.ROOT,"%03d.sy1",i),patch("P"+i));
            result=run(store,full); check(result.banks==3 && result.patches==260,"split >128 without losses");
            int original=store.scan(patch("INIT")).size();
            AtomicBoolean cancel=new AtomicBoolean(); boolean refused=false;
            try { FolderImporter.run(store,full,full.root,cancel::get,(f,p,skip,n)->{if(p>=140)cancel.set(true);}); }
            catch(InterruptedIOException expected){refused=true;}
            check(refused && store.scan(patch("INIT")).size()==original,"cancel after staged bank rolls back entire import");
            try(java.util.stream.Stream<Path> children=Files.list(temp.resolve("banks"))) {
                check(children.noneMatch(path->path.getFileName().toString().startsWith(".")),"no stale partial import");
            }
            // Old 0.1.0 bank without an index migrates without rewriting its .sy1.
            Path legacy=temp.resolve("banks/old/0001"); Files.createDirectories(legacy);
            Files.writeString(legacy.resolve("name.txt"),"Legacy bank"); Files.writeString(legacy.resolve("003.sy1"),patch("Legacy sound"));
            store.scan(patch("INIT")); check(Files.isRegularFile(legacy.resolve("index.tsv")),"old library indexed");
            check(Files.readString(legacy.resolve("003.sy1")).equals(patch("Legacy sound")),"legacy source unchanged");
            // Hundreds of banks, not a 4096-preset ceiling or all patches in RAM.
            Source collection=new Source();
            for(int bank=0;bank<220;bank++) {
                FolderImporter.Node dir=collection.dir(collection.root,String.format(Locale.ROOT,"Bank %03d",bank));
                for(int slot=1;slot<=128;slot++) collection.file(dir,String.format(Locale.ROOT,"%03d.sy1",slot),patch("Bank "+bank+" Patch "+slot));
            }
            result=run(store,collection); check(result.banks==220 && result.patches==28160,"220 complete banks");
            banks=store.scan(patch("INIT"));
            long lazy=banks.stream().flatMap(x->Arrays.stream(x.slots)).filter(Objects::nonNull).filter(PatchStore.Patch::isLazy).count();
            check(lazy>=28160,"large collection lazily indexed");
            System.out.println("Folder import: 220 banks / 28160 patches, nesting, numeric slots, invalid files, aliases, cancellation, duplicate safety, split and legacy migration OK");
        } finally {
            try(java.util.stream.Stream<Path> paths=Files.walk(temp)){paths.sorted(Comparator.reverseOrder()).forEach(p->p.toFile().delete());}
        }
    }
}
