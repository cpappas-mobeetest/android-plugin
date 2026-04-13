package com.mobeetest.openallfiles;

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vfs.VfsUtilCore;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

abstract class AbstractFolderFilesAction extends DumbAwareAction {

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }

    @Override
    public void update(@NotNull AnActionEvent event) {
        VirtualFile selected = event.getData(CommonDataKeys.VIRTUAL_FILE);
        boolean enabled = selected != null && selected.isDirectory();

        event.getPresentation().setVisible(true);
        event.getPresentation().setEnabled(enabled);
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent event) {
        Project project = event.getProject();
        VirtualFile root = event.getData(CommonDataKeys.VIRTUAL_FILE);
        if (project == null || root == null || !root.isDirectory()) {
            return;
        }

        ProgressManager.getInstance().run(new Task.Backgroundable(project, getProgressTitle(), false) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                List<VirtualFile> filesToOpen = collectFiles(project, root, indicator);

                ApplicationManager.getApplication().invokeLater(() -> {
                    FileEditorManager editorManager = FileEditorManager.getInstance(project);
                    for (VirtualFile file : filesToOpen) {
                        editorManager.openFile(file, false);
                    }

                    Messages.showInfoMessage(
                            project,
                            buildResultMessage(filesToOpen.size(), root),
                            getDialogTitle()
                    );
                }, project.getDisposed());
            }
        });
    }

    private @NotNull List<VirtualFile> collectFiles(
            @NotNull Project project,
            @NotNull VirtualFile root,
            @NotNull ProgressIndicator indicator
    ) {
        List<VirtualFile> result = new ArrayList<>();

        VfsUtilCore.iterateChildrenRecursively(
                root,
                file -> {
                    indicator.checkCanceled();
                    return !FolderFileFilters.shouldSkipDirectory(file);
                },
                file -> {
                    indicator.checkCanceled();

                    if (!file.isDirectory() && file.isValid() && FolderFileFilters.isOpenableTextLikeFile(file) && shouldIncludeFile(project, file)) {
                        result.add(file);
                    }
                    return true;
                }
        );

        result.sort(Comparator.comparing(VirtualFile::getPath));
        return result;
    }

    protected @NotNull String buildResultMessage(int openedCount, @NotNull VirtualFile root) {
        return "Opened " + openedCount + " file(s) from:\n" + root.getPath();
    }

    protected abstract boolean shouldIncludeFile(@NotNull Project project, @NotNull VirtualFile file);

    protected abstract @NotNull String getProgressTitle();

    protected abstract @NotNull String getDialogTitle();
}
