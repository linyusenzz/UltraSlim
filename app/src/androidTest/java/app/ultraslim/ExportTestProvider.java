package app.ultraslim;

import android.database.MatrixCursor;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract.Document;
import android.provider.DocumentsProvider;
import java.io.File;
import java.io.FileNotFoundException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Test APK only. Own files use opaque IDs; display names deliberately normalize underscores. */
public class ExportTestProvider extends DocumentsProvider {
    private final Map<String, String> names = new LinkedHashMap<>();
    private boolean denied, noTimes;
    private File directory() { return new File(getContext().getCacheDir(), "export-provider"); }
    private File file(String id) throws FileNotFoundException {
        if (!names.containsKey(id)) throw new FileNotFoundException(id);
        return new File(directory(), id);
    }
    private void access() { if (denied) throw new SecurityException("test grant revoked"); }
    @Override public boolean onCreate() { return directory().mkdirs() || directory().isDirectory(); }
    @Override public synchronized Bundle call(String method, String arg, Bundle extras) {
        if (method.equals("reset")) {
            for (String id : names.keySet()) new File(directory(), id).delete();
            names.clear(); denied = noTimes = false;
        } else if (method.equals("deny")) denied = Boolean.parseBoolean(arg);
        else if (method.equals("noTimes")) noTimes = Boolean.parseBoolean(arg);
        else return super.call(method, arg, extras);
        return Bundle.EMPTY;
    }
    private MatrixCursor cursor(String[] projection) {
        return new MatrixCursor(projection == null ? new String[]{Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE, Document.COLUMN_SIZE,
            Document.COLUMN_FLAGS, Document.COLUMN_LAST_MODIFIED} : projection);
    }
    private void row(MatrixCursor cursor, String id) {
        MatrixCursor.RowBuilder row = cursor.newRow();
        File file = new File(directory(), id);
        for (String column : cursor.getColumnNames()) {
            Object value = null;
            switch (column) {
                case Document.COLUMN_DOCUMENT_ID: value = id; break;
                case Document.COLUMN_DISPLAY_NAME: value = id.equals("root") ? "Exports" : names.get(id); break;
                case Document.COLUMN_MIME_TYPE: value = id.equals("root") ? Document.MIME_TYPE_DIR : "image/jpeg"; break;
                case Document.COLUMN_FLAGS: value = id.equals("root") ? Document.FLAG_DIR_SUPPORTS_CREATE : Document.FLAG_SUPPORTS_WRITE | Document.FLAG_SUPPORTS_DELETE; break;
                case Document.COLUMN_SIZE: value = file.length(); break;
                case Document.COLUMN_LAST_MODIFIED: value = file.lastModified(); break;
            }
            row.add(value);
        }
    }
    @Override public MatrixCursor queryRoots(String[] projection) { return new MatrixCursor(new String[]{"root_id"}); }
    @Override public synchronized MatrixCursor queryDocument(String id, String[] projection) {
        access(); MatrixCursor cursor = cursor(projection); if (id.equals("root") || names.containsKey(id)) row(cursor, id); return cursor;
    }
    @Override public synchronized MatrixCursor queryChildDocuments(String parent, String[] projection, String sortOrder) {
        access(); MatrixCursor cursor = cursor(projection); for (String id : names.keySet()) row(cursor, id); return cursor;
    }
    @Override public boolean isChildDocument(String parent, String id) { return parent.equals("root"); }
    @Override public synchronized String createDocument(String parent, String mime, String name) throws FileNotFoundException {
        access(); String requested = name.replace('_', '-'); String chosen = requested; int suffix = 1;
        while (names.containsValue(chosen)) chosen = requested + " (" + suffix++ + ")";
        String id = UUID.randomUUID().toString();
        try { if (!new File(directory(), id).createNewFile()) throw new FileNotFoundException(); }
        catch (java.io.IOException e) { throw new FileNotFoundException(e.toString()); }
        names.put(id, chosen); return id;
    }
    @Override public synchronized void deleteDocument(String id) throws FileNotFoundException {
        access(); if (!file(id).delete()) throw new FileNotFoundException(id); names.remove(id);
    }
    @Override public synchronized ParcelFileDescriptor openDocument(String id, String mode, CancellationSignal signal) throws FileNotFoundException {
        access(); if (noTimes && mode.equals("rw")) throw new FileNotFoundException("no timestamp update");
        return ParcelFileDescriptor.open(file(id), ParcelFileDescriptor.parseMode(mode));
    }
}
