package com.rinoize.risynth2;

import android.content.ContentResolver;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import java.io.*;
import java.util.*;

/** Uses only access granted by Android's picker; never needs broad storage access. */
final class DocumentSource implements FolderImporter.Source {
    final FolderImporter.Node root;
    private final ContentResolver resolver;
    private final Uri tree;
    private final List<FolderImporter.Node> selected;
    private DocumentSource(ContentResolver resolver,Uri tree,FolderImporter.Node root,List<FolderImporter.Node> selected) {
        this.resolver=resolver; this.tree=tree; this.root=root; this.selected=selected;
    }
    static DocumentSource tree(ContentResolver resolver,Uri tree) throws IOException {
        try {
            Uri rootUri=DocumentsContract.buildDocumentUriUsingTree(tree,DocumentsContract.getTreeDocumentId(tree));
            String name=displayName(resolver,rootUri,"Carpeta seleccionada");
            return new DocumentSource(resolver,tree,new FolderImporter.Node(rootUri.toString(),name,true,-1),null);
        } catch (RuntimeException ex) { throw new IOException("No se pudo abrir la carpeta elegida: "+ex.getMessage(),ex); }
    }
    static DocumentSource files(ContentResolver resolver,List<Uri> uris) throws IOException {
        List<FolderImporter.Node> nodes=new ArrayList<>();
        for (Uri uri : uris) {
            String name=displayName(resolver,uri,"Patch.sy1");
            if (!name.toLowerCase(Locale.ROOT).endsWith(".sy1"))
                throw new IOException("Para seleccionar varios archivos, elegí solo .sy1. Los ZIP se importan de a uno; las carpetas pueden contener todos tus bancos.");
            nodes.add(new FolderImporter.Node(uri.toString(),name,false,-1));
        }
        return new DocumentSource(resolver,null,new FolderImporter.Node(":selected","Archivos seleccionados",true,-1),nodes);
    }
    static String displayName(ContentResolver resolver,Uri uri,String fallback) throws IOException {
        try (Cursor c=resolver.query(uri,new String[]{OpenableColumns.DISPLAY_NAME},null,null,null)) {
            if (c!=null && c.moveToFirst() && !c.isNull(0)) return c.getString(0);
            return fallback;
        } catch (RuntimeException ex) { throw new IOException("No se pudo leer el nombre del documento",ex); }
    }
    @Override public List<FolderImporter.Node> children(FolderImporter.Node directory) throws IOException {
        if (tree==null) return selected;
        List<FolderImporter.Node> out=new ArrayList<>();
        try {
            Uri children=DocumentsContract.buildChildDocumentsUriUsingTree(tree,DocumentsContract.getDocumentId(Uri.parse(directory.id)));
            String[] projection={DocumentsContract.Document.COLUMN_DOCUMENT_ID,DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,DocumentsContract.Document.COLUMN_SIZE};
            try (Cursor c=resolver.query(children,projection,null,null,null)) {
                if (c==null) throw new IOException("El proveedor no devolvió el contenido de "+directory.name);
                while (c.moveToNext()) {
                    if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Lectura cancelada");
                    if (out.size()>=FolderImporter.MAX_ENTRIES) throw new IOException("Demasiadas entradas en "+directory.name);
                    String id=c.getString(0), name=c.getString(1);
                    boolean folder=DocumentsContract.Document.MIME_TYPE_DIR.equals(c.getString(2));
                    Uri uri=DocumentsContract.buildDocumentUriUsingTree(tree,id);
                    out.add(new FolderImporter.Node(uri.toString(),name,folder,c.isNull(3)?-1:c.getLong(3)));
                }
            }
        } catch (RuntimeException ex) { throw new IOException("No se pudo recorrer "+directory.name+": "+ex.getMessage(),ex); }
        return out;
    }
    @Override public InputStream open(FolderImporter.Node file) throws IOException {
        try {
            InputStream in=resolver.openInputStream(Uri.parse(file.id));
            if (in==null) throw new IOException("No se pudo abrir "+file.name);
            return in;
        } catch (RuntimeException ex) { throw new IOException("Sin acceso al patch "+file.name,ex); }
    }
}
