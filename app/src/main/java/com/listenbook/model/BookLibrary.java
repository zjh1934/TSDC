package com.listenbook.model;

import android.content.Context;

import com.listenbook.util.Io;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 书库索引，JSON 落盘到 App 私有目录。
 * 只存我们自己的元数据，读权限无需外部存储权限。
 */
public class BookLibrary {

    private final File indexFile;
    private final List<Book> books = new ArrayList<Book>();

    public BookLibrary(Context context) {
        File dir = new File(context.getFilesDir(), "library");
        if (!dir.exists()) {
            dir.mkdirs();
        }
        this.indexFile = new File(dir, "books.json");
        load();
    }

    /** 导入的书籍正本存放目录（私有目录，我们自己读没问题） */
    public static File booksDir(Context context) {
        File dir = new File(context.getFilesDir(), "books");
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return dir;
    }

    private void load() {
        books.clear();
        if (!indexFile.isFile()) {
            return;
        }
        try {
            String json = Io.readString(indexFile, "UTF-8");
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o != null) {
                    books.add(Book.fromJson(o));
                }
            }
        } catch (Exception e) {
            // 索引坏了就当作空书库，不崩溃
            books.clear();
        }
    }

    public void save() {
        JSONArray arr = new JSONArray();
        for (int i = 0; i < books.size(); i++) {
            arr.put(books.get(i).toJson());
        }
        try {
            File tmp = new File(indexFile.getParentFile(), "books.json.tmp");
            Io.writeString(tmp, arr.toString());
            if (indexFile.exists()) {
                indexFile.delete();
            }
            tmp.renameTo(indexFile);
        } catch (Exception ignored) {
        }
    }

    public int size() {
        return books.size();
    }

    public Book get(int i) {
        return books.get(i);
    }

    public List<Book> all() {
        return books;
    }

    public int indexOfId(String id) {
        for (int i = 0; i < books.size(); i++) {
            if (books.get(i).id.equals(id)) {
                return i;
            }
        }
        return -1;
    }

    public Book findById(String id) {
        int i = indexOfId(id);
        return i < 0 ? null : books.get(i);
    }

    public void add(Book b) {
        books.add(0, b);
        save();
    }

    /** 更新已存在的书（进度、字号等），不存在则忽略 */
    public void update(Book b) {
        int i = indexOfId(b.id);
        if (i >= 0) {
            books.set(i, b);
            save();
        }
    }

    /** 删除记录并连带删掉正本文件 */
    public void remove(String id) {
        int i = indexOfId(id);
        if (i < 0) {
            return;
        }
        Book b = books.remove(i);
        if (b.path != null && b.path.length() > 0) {
            Io.deleteQuietly(new File(b.path));
        }
        save();
    }
}
