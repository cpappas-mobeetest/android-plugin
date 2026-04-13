package com.mobeetest.openallfiles;

import com.intellij.openapi.fileTypes.FileType;
import com.intellij.openapi.fileTypes.FileTypeManager;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;

import java.util.Set;

final class FolderFileFilters {

    private static final Set<String> SKIPPED_DIR_NAMES = Set.of("build", ".gradle", ".idea", ".git");

    private FolderFileFilters() {
    }

    static boolean isOpenableTextLikeFile(@NotNull VirtualFile file) {
        FileType fileType = FileTypeManager.getInstance().getFileTypeByFile(file);
        return !fileType.isBinary();
    }

    static boolean shouldSkipDirectory(@NotNull VirtualFile file) {
        return file.isDirectory() && SKIPPED_DIR_NAMES.contains(file.getName());
    }
}
