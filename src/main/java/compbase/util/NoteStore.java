package compbase.util;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 备注文件的路径约定：notes/comp_{id}.md，图片放在 notes/images/。
 * 数据库只存元器件，备注按 id 由文件名推算，备份时 data/ 与 notes/ 一起拷走即可。
 */
public class NoteStore {
    private final Path notesDir;
    private final Path imagesDir;

    public NoteStore(Path notesDir, Path imagesDir) {
        this.notesDir = notesDir.toAbsolutePath().normalize();
        this.imagesDir = imagesDir.toAbsolutePath().normalize();
    }

    public Path notesDir() {
        return notesDir;
    }

    public Path imagesDir() {
        return imagesDir;
    }

    public Path notePath(long componentId) {
        return notesDir.resolve("comp_" + componentId + ".md");
    }

    /** 备注文件是否存在。 */
    public boolean noteExists(long componentId) {
        return Files.isRegularFile(notePath(componentId));
    }

    /** 删除备注文件，返回是否真的删掉了。 */
    public boolean deleteNote(long componentId) {
        try {
            return Files.deleteIfExists(notePath(componentId));
        } catch (IOException e) {
            return false;
        }
    }

    /** 删除该元器件的所有图片（comp_{id}_*），返回删除数量。 */
    public int deleteImages(long componentId) {
        String prefix = "comp_" + componentId + "_";
        int deleted = 0;
        if (!Files.isDirectory(imagesDir)) return 0;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(imagesDir, prefix + "*")) {
            for (Path file : stream) {
                if (Files.isRegularFile(file) && Files.deleteIfExists(file)) {
                    deleted++;
                }
            }
        } catch (IOException ignored) {
            // 图片清理由删除操作附带完成，失败不影响元器件删除
        }
        return deleted;
    }

    /** 启动时确保目录存在。 */
    public void ensureDirs() throws IOException {
        Files.createDirectories(notesDir);
        Files.createDirectories(imagesDir);
    }
}
