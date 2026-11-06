package com.rinoize.risynth2;

import java.io.*;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Streaming tree import, independent of Android's document provider for host tests.
 * Only one bank (128 patches) is held in memory; source names never form output paths.
 */
final class FolderImporter {
    static final int MAX_ENTRIES=200000, MAX_PATCHES=131072, MAX_DEPTH=32;
    static final long MAX_BYTES=256L*1024*1024;
    static final class Node {
        final String id, name;
        final boolean directory;
        final long size;
        Node(String id,String name,boolean directory,long size) {
            this.id=id; this.name=name==null?"Sin nombre":name; this.directory=directory; this.size=size;
        }
    }
    interface Source {
        List<Node> children(Node directory) throws IOException;
        InputStream open(Node file) throws IOException;
    }
    interface Progress { void update(int folders,int patches,int skipped,String current); }
    static final class Result {
        int folders, banks, patches, invalid, ignored;
        final List<String> warnings = new ArrayList<>();
        String summary() {
            return banks+" bancos · "+patches+" patches importados · "+invalid+" .sy1 omitidos" +
                (ignored>0 ? " · "+ignored+" archivos no .sy1 ignorados" : "");
        }
    }
    private static final class Pending {
        final Node node; final String title; final int depth;
        Pending(Node n,String title,int depth) { node=n; this.title=title; this.depth=depth; }
    }
    private static void check(BooleanSupplier cancel) throws InterruptedIOException {
        if (cancel.getAsBoolean() || Thread.currentThread().isInterrupted())
            throw new InterruptedIOException("Importación cancelada; no se agregó ningún banco");
    }
    static String label(String name) {
        String s=name.replaceAll("[\\p{Cntrl}]", " ").replace('/', '／').replace('\\', '＼');
        return s.substring(0,Math.min(s.length(),200));
    }
    private static boolean hidden(String name) { return name.startsWith(".") || name.equals("__MACOSX"); }
    private static void invalid(Result result,String name,String why) {
        ++result.invalid;
        if (result.warnings.size()<8) result.warnings.add(name+": "+why);
    }
    static Result run(PatchStore store,Source source,Node root,BooleanSupplier cancel,Progress progress) throws IOException {
        Result result=new Result();
        ArrayDeque<Pending> todo=new ArrayDeque<>();
        Set<String> visited=new HashSet<>();
        todo.push(new Pending(root,label(root.name),0));
        int entries=0,candidates=0;
        long bytes=0;
        try (PatchStore.ImportBatch batch=store.beginBatch()) {
            while (!todo.isEmpty()) {
                check(cancel);
                Pending dir=todo.pop();
                if (dir.depth>MAX_DEPTH) throw new IOException("Carpetas anidadas por encima del límite de "+MAX_DEPTH);
                if (!visited.add(dir.node.id)) continue; // aliases/cycles must not recurse forever
                ++result.folders;
                progress.update(result.folders,result.patches,result.invalid,dir.title);
                List<Node> children=source.children(dir.node);
                entries+=children.size();
                if (entries>MAX_ENTRIES) throw new IOException("Más de "+MAX_ENTRIES+" entradas; elige una carpeta menor");
                List<Node> patches=new ArrayList<>(), directories=new ArrayList<>();
                for (Node n : children) {
                    check(cancel);
                    if (hidden(n.name)) continue;
                    if (n.directory) directories.add(n);
                    else if (n.name.toLowerCase(Locale.ROOT).endsWith(".sy1")) patches.add(n);
                    else ++result.ignored;
                }
                Comparator<Node> names=Comparator.comparing((Node n)->n.name.toLowerCase(Locale.ROOT)).thenComparing(n->n.name).thenComparing(n->n.id);
                directories.sort(names);
                for (int i=directories.size()-1;i>=0;--i) {
                    Node n=directories.get(i);
                    todo.push(new Pending(n,dir.title+" / "+label(n.name),dir.depth+1));
                }
                // Reserve the first valid filename for every explicit slot before
                // duplicates/unnumbered files can use spare positions.
                patches.sort(names);
                Node[] numbered=new Node[128]; List<Node> extras=new ArrayList<>();
                for (Node node:patches) {
                    int slot=PatchStore.requestedSlot(node.name);
                    if (slot>=0 && numbered[slot]==null) numbered[slot]=node; else extras.add(node);
                }
                patches.clear();
                for (Node node:numbered) if (node!=null) patches.add(node);
                patches.addAll(extras);
                PatchStore.Patch[] slots=new PatchStore.Patch[128];
                int used=0,part=1;
                boolean split=patches.size()>128;
                for (Node node : patches) {
                    check(cancel);
                    if (++candidates>MAX_PATCHES) throw new IOException("Más de "+MAX_PATCHES+" patches; divide la colección");
                    if (node.size>PatchStore.MAX_PATCH) { invalid(result,node.name,"mayor a 64 KiB"); continue; }
                    byte[] data;
                    InputStream opened=source.open(node);
                    if (opened==null) throw new IOException("No se pudo leer "+node.name);
                    // A provider with unknown or false size is still bounded by readBounded.
                    try (InputStream in=opened) {
                        ByteArrayOutputStream out=new ByteArrayOutputStream();
                        byte[] buf=new byte[4096]; int n;
                        while ((n=in.read(buf))!=-1) {
                            check(cancel);
                            if (out.size()+n>PatchStore.MAX_PATCH) throw new IOException("Patch mayor a 64 KiB: "+node.name);
                            out.write(buf,0,n);
                        }
                        data=out.toByteArray();
                    }
                    bytes+=data.length;
                    if (bytes>MAX_BYTES) throw new IOException("La colección supera 256 MiB; elige una carpeta menor");
                    String text;
                    try { text=PatchStore.validate(data); }
                    catch (IOException ex) { invalid(result,dir.title+" / "+node.name,ex.getMessage()); continue; }
                    if (used==128) {
                        check(cancel); batch.write(dir.title+" ("+(part++)+ ")",slots);
                        slots=new PatchStore.Patch[128]; used=0;
                    }
                    int slot=PatchStore.requestedSlot(node.name);
                    if (slot<0 || slots[slot]!=null) { slot=0; while (slots[slot]!=null) ++slot; }
                    slots[slot]=new PatchStore.Patch(text,slot); ++used; ++result.patches;
                    progress.update(result.folders,result.patches,result.invalid,dir.title);
                }
                if (used>0) { check(cancel); batch.write(dir.title+(split?" ("+part+")":""),slots); }
            }
            check(cancel); batch.commit(); result.banks=batch.banks;
        }
        return result;
    }
}
