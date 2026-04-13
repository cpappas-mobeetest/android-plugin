package com.mobeetest.openallfiles;

import com.intellij.codeInsight.daemon.impl.HighlightInfo;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.markup.RangeHighlighter;
import com.intellij.openapi.fileEditor.FileEditor;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.TextEditor;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vfs.VfsUtilCore;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.problems.WolfTheProblemSolver;
import org.jetbrains.annotations.NotNull;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class AnalyzeOpenBadFilesAction extends AbstractFolderFilesAction {

    private static final int BATCH_SIZE = 12;
    private static final int MAX_FILES_TO_CONSIDER = 2000;

    // settle tuning
    private static final long SETTLE_MAX_MS = 5000;       // max wait per file
    private static final long SETTLE_MIN_WAIT_MS = 1000;  // minimum wait before a stable count is trusted
    private static final long SETTLE_POLL_MS = 250;       // poll interval
    private static final int SETTLE_STABLE_POLLS = 3;     // require N identical polls

    // typo scan
    private static final int MAX_BYTES_TO_SCAN = 256 * 1024;

    private static final Set<String> TYPO_TOKENS = new HashSet<>();
    static {
        TYPO_TOKENS.add("τόρα");
        // add more:
        // TYPO_TOKENS.add("κενούργιο");
    }

    @Override
    public void actionPerformed(@NotNull com.intellij.openapi.actionSystem.AnActionEvent event) {
        Project project = event.getProject();
        VirtualFile root = event.getData(com.intellij.openapi.actionSystem.CommonDataKeys.VIRTUAL_FILE);
        if (project == null || root == null || !root.isDirectory()) return;

        ProgressManager.getInstance().run(new Task.Backgroundable(project, getProgressTitle(), true) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                indicator.setIndeterminate(false);

                List<VirtualFile> collected = collectRelevantFiles(project, root, indicator);
                if (collected.isEmpty()) return;

                final boolean capped = collected.size() > MAX_FILES_TO_CONSIDER;
                final List<VirtualFile> scannedFiles = capped
                        ? new ArrayList<>(collected.subList(0, MAX_FILES_TO_CONSIDER))
                        : collected;

                final FileEditorManager editorManager = FileEditorManager.getInstance(project);
                final WolfTheProblemSolver wolf = WolfTheProblemSolver.getInstance(project);

                final List<VirtualFile> badFiles = new ArrayList<>();

                final int total = scannedFiles.size();
                for (int start = 0; start < total; start += BATCH_SIZE) {
                    indicator.checkCanceled();

                    int end = Math.min(total, start + BATCH_SIZE);
                    List<VirtualFile> batch = scannedFiles.subList(start, end);

                    indicator.setFraction(start / (double) total);
                    indicator.setText("Opening batch " + end + " / " + total);

                    ApplicationManager.getApplication().invokeAndWait(() -> {
                        for (VirtualFile f : batch) {
                            editorManager.openFile(f, false, true);
                        }
                    });

                    // evaluate each file with settle loop
                    for (VirtualFile f : batch) {
                        indicator.checkCanceled();
                        indicator.setText("Checking " + f.getName());

                        boolean hasTypos = looksLikeHasTyposFast(f);

                        // If we already have typos, we can keep it bad regardless of daemon state.
                        boolean hasWolfProblem = wolf.isProblemFile(f);

                        // For notices/unused/etc: wait for editor highlights to settle
                        int settledHighlightCount = settleAndGetHighlightCount(editorManager, f, indicator);

                        boolean hasNoticesLike = settledHighlightCount > 0;

                        if (hasWolfProblem || hasNoticesLike || hasTypos) {
                            badFiles.add(f);
                        }
                    }

                    // close non-bad in this batch
                    ApplicationManager.getApplication().invokeAndWait(() -> {
                        for (VirtualFile f : batch) {
                            if (!badFiles.contains(f)) {
                                FileEditor[] editors = editorManager.getEditors(f);
                                if (editors.length > 0) editorManager.closeFile(f);
                            }
                        }
                    });
                }

                badFiles.sort(Comparator.comparing(VirtualFile::getPath));

                ApplicationManager.getApplication().invokeLater(() -> {
                    for (VirtualFile f : badFiles) {
                        editorManager.openFile(f, false, true);
                    }

                    Messages.showInfoMessage(
                            project,
                            "Found " + badFiles.size() + " file(s) with issues.\n" +
                                    "Scanned: " + scannedFiles.size() + " file(s) under:\n" + root.getPath() +
                                    (capped ? "\n\n(Note: scan was capped at " + MAX_FILES_TO_CONSIDER + " files.)" : ""),
                            getDialogTitle()
                    );
                }, project.getDisposed());
            }
        });
    }

    private static int settleAndGetHighlightCount(
            @NotNull FileEditorManager editorManager,
            @NotNull VirtualFile file,
            @NotNull ProgressIndicator indicator
    ) {
        long start = System.currentTimeMillis();

        int last = -1;
        int stable = 0;

        while (System.currentTimeMillis() - start < SETTLE_MAX_MS) {
            indicator.checkCanceled();

            int current = getEditorHighlighterCount(editorManager, file);
            long elapsed = System.currentTimeMillis() - start;

            if (current == last) {
                stable++;
                // Only trust a stable count once we have waited long enough for the daemon
                // to start its first analysis pass.  Without this guard the loop can exit
                // with 0 after just 3×250 ms = 750 ms, before any inspections have run.
                if (stable >= SETTLE_STABLE_POLLS && elapsed >= SETTLE_MIN_WAIT_MS) {
                    return current;
                }
            } else {
                stable = 0;
                last = current;
            }

            sleep(SETTLE_POLL_MS);
        }

        // timeout: return last observed
        return Math.max(last, 0);
    }

    private static int getEditorHighlighterCount(@NotNull FileEditorManager editorManager, @NotNull VirtualFile file) {
        int[] result = {0};
        ApplicationManager.getApplication().runReadAction(() -> {
            TextEditor textEditor = null;
            for (FileEditor ed : editorManager.getEditors(file)) {
                if (ed instanceof TextEditor) {
                    textEditor = (TextEditor) ed;
                    break;
                }
            }
            if (textEditor == null) return;

            Editor editor = textEditor.getEditor();
            if (editor.isDisposed()) return;

            RangeHighlighter[] hs = editor.getMarkupModel().getAllHighlighters();
            if (hs == null) return;

            for (RangeHighlighter h : hs) {
                HighlightInfo info = HighlightInfo.fromRangeHighlighter(h);
                // Include every real diagnostic: ERROR, WARNING, WEAK_WARNING, INFORMATION,
                // TYPO (spell-check, value 8) and any custom severity above TEXT_ATTRIBUTES.
                // TEXT_ATTRIBUTES (value -1) are purely cosmetic syntax-colouring ranges and
                // must be excluded.  We therefore check strictly > TEXT_ATTRIBUTES rather than
                // >= INFORMATION, because TYPO (value 8) is below INFORMATION (value 10) and
                // would otherwise be missed.
                if (info != null && info.getSeverity().compareTo(HighlightSeverity.TEXT_ATTRIBUTES) > 0) {
                    result[0]++;
                }
            }
        });
        return result[0];
    }

    private static boolean looksLikeHasTyposFast(@NotNull VirtualFile file) {
        if (TYPO_TOKENS.isEmpty()) return false;
        try {
            byte[] bytes = VfsUtilCore.loadBytes(file);
            if (bytes.length == 0) return false;

            int len = Math.min(bytes.length, MAX_BYTES_TO_SCAN);
            String text = new String(bytes, 0, len, StandardCharsets.UTF_8);

            for (String tok : TYPO_TOKENS) {
                if (text.contains(tok)) return true;
            }
            return false;
        } catch (Exception ignored) {
            return false;
        }
    }

    private @NotNull List<VirtualFile> collectRelevantFiles(
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
                    if (!file.isDirectory()
                            && file.isValid()
                            && FolderFileFilters.isOpenableTextLikeFile(file)
                            && shouldIncludeFile(project, file)) {
                        result.add(file);
                        if (result.size() >= MAX_FILES_TO_CONSIDER * 2) return false;
                    }
                    return true;
                }
        );
        result.sort(Comparator.comparing(VirtualFile::getPath));
        return result;
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    protected boolean shouldIncludeFile(@NotNull Project project, @NotNull VirtualFile file) {
        String ext = file.getExtension();
        return "java".equalsIgnoreCase(ext)
                || "kt".equalsIgnoreCase(ext)
                || "xml".equalsIgnoreCase(ext);
    }

    @Override
    protected @NotNull String getProgressTitle() {
        return "Finding files with errors/warnings/notices/typos";
    }

    @Override
    protected @NotNull String getDialogTitle() {
        return "Open Bad Files";
    }
}