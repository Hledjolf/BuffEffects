package buffeffects.core.window;

/** File access for the editor, so its rules are tested headless. Paths use '/' and are absolute or server-relative. */
public interface Storage {
    /** The file's text, or null if there's no such file. */
    String read(String path);

    void write(String path, String text);

    boolean isDirectory(String path);
}
