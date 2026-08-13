package com.miniclaudecode.tui.pane;

import com.googlecode.lanterna.gui2.CheckBoxList;
import com.googlecode.lanterna.gui2.Direction;
import com.googlecode.lanterna.gui2.LinearLayout;
import com.googlecode.lanterna.gui2.Panel;
import com.miniclaudecode.config.MiniClaudeCodeConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * 显示项目根一级路径，并合并内置与用户级忽略规则
 */
public class FileTreePane extends Panel {

    private static final List<String> DEFAULT_IGNORE = List.of(
            ".git", "node_modules", "target", "dist", ".idea", "*.class", "*.jar"
    );

    private final List<Path> projectRoots;
    private final List<String> ignorePatterns;
    private final CheckBoxList<String> fileList;

    public FileTreePane(MiniClaudeCodeConfig config) {
        super();
        setLayoutManager(new LinearLayout(Direction.VERTICAL));

        this.ignorePatterns = loadIgnorePatterns(config);

        Path projectRoot = Path.of("").toAbsolutePath();
        this.projectRoots = List.of(projectRoot);

        this.fileList = new CheckBoxList<>();
        loadFiles(projectRoot);

        addComponent(fileList.setLayoutData(
                LinearLayout.createLayoutData(LinearLayout.Alignment.Fill, LinearLayout.GrowPolicy.CanGrow)));
    }

    private void loadFiles(Path root) {
        fileList.clearItems();
        try (Stream<Path> stream = Files.list(root)) {
            stream.filter(path -> !matchesIgnore(path.getFileName().toString(), ignorePatterns))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .forEach(path -> fileList.addItem(path.getFileName().toString()));
        } catch (IOException e) {
            System.err.println("⚠️ 列出目录失败: " + root + " - " + e.getMessage());
        }
    }

    private static List<String> loadIgnorePatterns(MiniClaudeCodeConfig config) {
        List<String> patterns = new ArrayList<>(DEFAULT_IGNORE);

        Path userIgnore = Path.of(System.getProperty("user.home"), ".mini-claude-code", "filetree-ignore.txt");
        if (Files.exists(userIgnore)) {
            try {
                Files.readAllLines(userIgnore).stream()
                        .map(String::trim)
                        .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                        .forEach(patterns::add);
            } catch (IOException e) {
                System.err.println("⚠️ 读取 filetree-ignore.txt 失败: " + e.getMessage());
            }
        }

        return patterns;
    }

    public void refresh() {
        if (!projectRoots.isEmpty()) {
            loadFiles(projectRoots.get(0));
        }
    }

    private static boolean matchesIgnore(String name, List<String> patterns) {
        for (String pattern : patterns) {
            if (pattern.contains("*")) {
                String regex = pattern.replace(".", "\\.").replace("*", ".*");
                if (name.matches(regex)) {
                    return true;
                }
            } else {
                if (name.equals(pattern)) {
                    return true;
                }
            }
        }
        return false;
    }
}
