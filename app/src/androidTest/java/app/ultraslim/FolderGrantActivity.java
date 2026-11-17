package app.ultraslim;

/** Test picker stand-in: grants a tree from the provider's owning UID. */
public class FolderGrantActivity extends android.app.Activity {
    @Override public void onCreate(android.os.Bundle savedState) {
        super.onCreate(savedState);
        String target = getIntent().getStringExtra("target");
        if (target == null || !target.startsWith("app.ultraslim.")) throw new IllegalArgumentException();
        boolean export = getIntent().getBooleanExtra("export", false);
        int writeFlag = export ? android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION : 0;
        android.net.Uri tree = android.provider.DocumentsContract.buildTreeDocumentUri(getPackageName() + (export ? ".exports" : ".documents"), "root");
        grantUriPermission(target, tree, writeFlag | android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION |
            android.content.Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION | android.content.Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        finish();
    }
}
