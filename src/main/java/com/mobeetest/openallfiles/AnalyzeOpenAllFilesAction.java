package com.mobeetest.openallfiles;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;

public final class AnalyzeOpenAllFilesAction extends AbstractFolderFilesAction {

    @Override
    protected boolean shouldIncludeFile(@NotNull Project project, @NotNull VirtualFile file) {
        return true;
    }

    @Override
    protected @NotNull String getProgressTitle() {
        return "Opening all files";
    }

    @Override
    protected @NotNull String getDialogTitle() {
        return "Open All Files";
    }
}
