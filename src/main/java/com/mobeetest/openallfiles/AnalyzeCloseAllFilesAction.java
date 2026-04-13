package com.mobeetest.openallfiles;

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vfs.VfsUtilCore;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

public final class AnalyzeCloseAllFilesAction extends DumbAwareAction {

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

        FileEditorManager editorManager = FileEditorManager.getInstance(project);
        VirtualFile[] openFiles = editorManager.getOpenFiles();

        List<VirtualFile> toClose = new ArrayList<>();
        for (VirtualFile file : openFiles) {
            if (VfsUtilCore.isAncestor(root, file, false)) {
                toClose.add(file);
            }
        }

        ApplicationManager.getApplication().invokeLater(() -> {
            for (VirtualFile file : toClose) {
                editorManager.closeFile(file);
            }
            Messages.showInfoMessage(
                    project,
                    "Closed " + toClose.size() + " file(s) from:\n" + root.getPath(),
                    "Close All Files"
            );
        }, project.getDisposed());
    }
}
