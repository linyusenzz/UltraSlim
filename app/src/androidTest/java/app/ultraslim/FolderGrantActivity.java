package app.ultraslim;

/** Test picker stand-in: grants a tree from the provider's owning UID. */
public class FolderGrantActivity extends android.app.Activity {
    @Override public void onCreate(android.os.Bundle savedState) {
        super.onCreate(savedState);
        String target = getIntent().getStringExtra("target");
        if (target == null || !target.startsWith("app.ultraslim.")) throw new IllegalArgumentException();
        String photos = getIntent().getStringExtra("singlePhotos");
        if ("grant".equals(photos) || "revoke".equals(photos)) {
            for (int i = 0; i < 370; i++) {
                android.net.Uri uri = android.provider.DocumentsContract.buildDocumentUri(getPackageName() + ".documents", "photo-" + i);
                int read = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION;
                if ("grant".equals(photos)) grantUriPermission(target, uri, read | android.content.Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
                else revokeUriPermission(target, uri, read);
            }
            finish();
            return;
        }
        boolean export = getIntent().getBooleanExtra("export", false);
        int writeFlag = export ? android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION : 0;
        android.net.Uri tree = android.provider.DocumentsContract.buildTreeDocumentUri(getPackageName() + (export ? ".exports" : ".documents"), "root");
        grantUriPermission(target, tree, writeFlag | android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION |
            android.content.Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION | android.content.Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        finish();
    }
}
