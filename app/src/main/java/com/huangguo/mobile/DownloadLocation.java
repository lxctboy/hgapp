package com.huangguo.mobile;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.UriPermission;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;

/**
 * 短剧下载保存目录（用户在「我的 → 设置 → 本地存储」里自定义）。
 *
 * 为什么不能直接填一个路径字符串：
 * Android 10（API 29）起启用分区存储，应用不能凭自己拼出来的路径往
 * /storage/emulated/0/... 写文件，会直接抛权限异常。合规做法是让用户通过
 * 系统文件选择器（Storage Access Framework，简称 SAF）授权一个目录，
 * 拿到的是形如 content://.../tree/primary:Download/短剧 的「树 Uri」，
 * 再调用 takePersistableUriPermission 把写权限持久化下来。
 * 所以这里保存的是 Uri，展示给用户的路径只是从 Uri 反推出来给人看的。
 *
 * 只在「目录可用」时生效；用户撤销授权或删掉目录后自动回退到应用私有目录，
 * 不会让下载功能挂掉。
 */
final class DownloadLocation {
    private static final String TAG = "HuangGuoDownload";
    private static final String PREFS = "download-location";
    private static final String KEY_URI = "treeUri";

    private static DownloadLocation instance;

    private final Context app;
    private final SharedPreferences prefs;

    private DownloadLocation(Context context) {
        app = context.getApplicationContext();
        prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static synchronized DownloadLocation get(Context context) {
        if (instance == null) instance = new DownloadLocation(context);
        return instance;
    }

    /** 用户授权的目录；null 表示还没设置。 */
    Uri uri() {
        String value = prefs.getString(KEY_URI, null);
        if (value == null || value.isEmpty()) return null;
        return Uri.parse(value);
    }

    /** 授权是否仍然有效（用户可能在系统设置里撤销，或目录被删掉）。 */
    synchronized boolean usable() {
        Uri tree = uri();
        if (tree == null) return false;
        List<UriPermission> granted = app.getContentResolver().getPersistedUriPermissions();
        for (UriPermission permission : granted) {
            if (tree.equals(permission.getUri()) && permission.isWritePermission()) return true;
        }
        return false;
    }

    synchronized void save(Uri tree) {
        if (tree == null) { prefs.edit().remove(KEY_URI).apply(); return; }
        prefs.edit().putString(KEY_URI, tree.toString()).apply();
    }

    synchronized void clear() { prefs.edit().remove(KEY_URI).apply(); }

    /** 给用户看的路径；未自定义或授权失效时返回 null。 */
    String display() {
        if (!usable()) return null;
        return describe(uri());
    }

    /**
     * 在用户目录里创建一个新文件，返回它的 Uri；失败返回 null。
     * 树 Uri 要先转成「文档 Uri」才能当父目录用，这是 SAF 的固定套路。
     */
    Uri create(String mime, String name) {
        if (!usable()) return null;
        Uri tree = uri();
        try {
            Uri parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree));
            return DocumentsContract.createDocument(app.getContentResolver(), parent, mime, name);
        } catch (Exception error) {
            Log.w(TAG, "在自定义目录创建文件失败", error);
            return null;
        }
    }

    /** 把已下载好的文件搬进用户目录；成功返回目标 Uri，失败返回 null。 */
    boolean copy(File source, Uri target) {
        try (InputStream in = new FileInputStream(source);
             OutputStream out = app.getContentResolver().openOutputStream(target)) {
            if (out == null) return false;
            byte[] buffer = new byte[256 * 1024];
            int read;
            while ((read = in.read(buffer)) > 0) out.write(buffer, 0, read);
            out.flush();
            return true;
        } catch (Exception error) {
            Log.w(TAG, "写入自定义目录失败", error);
            return false;
        }
    }

    /** content:// 形式的文件还存不存在。 */
    static boolean exists(Context context, Uri uri) {
        if (uri == null) return false;
        try (Cursor cursor = context.getContentResolver().query(uri,
                new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID}, null, null, null)) {
            return cursor != null && cursor.getCount() > 0;
        } catch (Exception error) {
            return false;
        }
    }

    /** 删除 content:// 形式的文件。 */
    static boolean remove(Context context, Uri uri) {
        if (uri == null) return false;
        try { return DocumentsContract.deleteDocument(context.getContentResolver(), uri); }
        catch (Exception error) { return false; }
    }

    /** 打开系统目录选择器，让用户挑保存位置。 */
    static Intent picker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        return intent;
    }

    /**
     * 把树 Uri 还原成人类可读的路径。
     * - 内置存储：.../tree/primary:Download/短剧 → /storage/emulated/0/Download/短剧
     * - 下载目录：.../tree/raw:/storage/emulated/0/Download → /storage/emulated/0/Download
     * - 其它卷（SD 卡）无法可靠还原，原样展示卷 id
     */
    private static String describe(Uri tree) {
        String path = tree.getPath(); // getPath() 返回的是已解码的路径
        if (path == null || path.isEmpty()) return tree.toString();
        int mark = path.lastIndexOf("/tree/");
        String tail = mark >= 0 ? path.substring(mark + "/tree/".length()) : path;
        if (tail.startsWith("raw:")) return tail.substring("raw:".length());
        if (tail.startsWith("primary:")) return "/storage/emulated/0/" + tail.substring("primary:".length());
        if (tail.startsWith("home:")) return "/storage/emulated/0/" + tail.substring("home:".length());
        return tail;
    }
}
