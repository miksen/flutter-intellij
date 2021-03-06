/*
 * Copyright 2017 The Chromium Authors. All rights reserved.
 * Use of this source code is governed by a BSD-style license that can be
 * found in the LICENSE file.
 */
package io.flutter.bazel;

import com.google.common.base.Joiner;
import com.google.common.base.Objects;
import com.google.common.collect.ImmutableSet;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ModuleRootManager;
import com.intellij.openapi.roots.ProjectRootManager;
import com.intellij.openapi.util.Computable;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;

/**
 * The directory tree for a Bazel workspace.
 *
 * <p>Bazel workspaces are identified by looking for a WORKSPACE file.
 *
 * <p>Also includes the flutter.json config file, which is loaded at the same time.
 */
public class Workspace {
  private static final String PLUGIN_CONFIG_PATH = "dart/config/intellij-plugins/flutter.json";

  private @NotNull final VirtualFile root;
  private @Nullable final PluginConfig config;

  private Workspace(@NotNull VirtualFile root, @Nullable PluginConfig config) {
    this.root = root;
    this.config = config;
  }

  /**
   * Returns true for a project that uses Flutter code within this workspace.
   */
  public boolean usesFlutter(Project project) {
    for (Module module : ModuleManager.getInstance(project).getModules()) {
      if (usesFlutter(module)) return true;
    }
    return false;
  }

  /**
   * Returns true for a module that uses flutter code within this workspace.
   *
   * <p>This is determined by looking for content roots that match a pattern.
   * By default, any path containing 'flutter' will match, but this can be configured
   * using the 'directoryPatterns' variable in flutter.json.
   */
  public boolean usesFlutter(Module module) {
    for (String path : getContentPaths(module)) {
      if (withinFlutterDirectory(path)) return true;
    }
    return false;
  }

  /**
   * Returns the path to each content root within the module that is below the workspace root.
   *
   * <p>Each path will be relative to the workspace root directory.
   */
  public ImmutableSet<String> getContentPaths(Module module) {
    // Find all the content roots within this workspace.
    final VirtualFile[] contentRoots = ModuleRootManager.getInstance(module).getContentRoots();
    final ImmutableSet.Builder<String> result = ImmutableSet.builder();
    for (VirtualFile root : contentRoots) {
      final String path = getRelativePath(root);
      if (path != null) {
        result.add(path);
      }
    }
    return result.build();
  }

  /**
   * Returns a VirtualFile's path relative to the workspace's root directory.
   *
   * <p>Returns null for the workspace root or anything outside the workspace.
   */
  public @Nullable String getRelativePath(@Nullable VirtualFile file) {
    final List<String> path = new ArrayList<>();
    while (file != null) {
      if (file.equals(root)) {
        if (path.isEmpty()) {
          return null; // This is the root.
        }
        Collections.reverse(path);
        return Joiner.on('/').join(path);
      }
      path.add(file.getName());
      file = file.getParent();
    }
    return null;
  }

  /**
   * Returns true if the given path is within a flutter project.
   *
   * <p>The path should be relative to the workspace root.
   */
  public boolean withinFlutterDirectory(@NotNull String path) {
    final PluginConfig c = getPluginConfig();
    if (c != null) {
      return c.withinFlutterDirectory(path);
    }
    // Default if unconfigured.
    return path.contains("flutter");
  }

  /**
   * Returns the directory containing the WORKSPACE file.
   */
  public @NotNull VirtualFile getRoot() {
    return root;
  }

  /**
   * Returns the script that starts 'flutter daemon', or null if not configured.
   */
  public @Nullable String getDaemonScript() {
    return (config == null) ? null : config.getDaemonScript();
  }

  /**
   * Returns the script that runs the bazel target for a Flutter app, or null if not configured.
   */
  public @Nullable String getLaunchScript() {
    return (config == null) ? null : config.getLaunchScript();
  }

  /**
   * Returns the flutter plugin configuration or null if not available.
   */
  public @Nullable PluginConfig getPluginConfig() {
    return config;
  }

  /**
   * Returns relative paths to the files within the workspace that it depends on.
   *
   * <p>When they change, the Workspace should be reloaded.
   */
  public @NotNull Set<String> getDependencies() {
    return ImmutableSet.of("WORKSPACE", PLUGIN_CONFIG_PATH);
  }

  @Override
  public boolean equals(Object obj) {
    if (!(obj instanceof Workspace)) return false;
    final Workspace other = (Workspace) obj;
    return Objects.equal(root, other.root) && Objects.equal(config, other.config);
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(root, config);
  }

  /**
   * Loads the Bazel workspace from the filesystem.
   *
   * <p>Also loads flutter.plugin if present.
   *
   * <p>(Note that we might not load all the way from disk due to the VirtualFileSystem's caching.)
   *
   * @return the Workspace, or null if there is none.
   */
  public static @Nullable Workspace load(@NotNull Project project) {
    final VirtualFile workspaceFile = findWorkspaceFile(project);
    if (workspaceFile == null) return null;

    final VirtualFile root = workspaceFile.getParent();
    final VirtualFile configFile = root.findFileByRelativePath(PLUGIN_CONFIG_PATH);
    final PluginConfig config = configFile == null ? null : PluginConfig.load(configFile);
    return new Workspace(root, config);
  }

  /**
   * Returns the Bazel WORKSPACE file for a Project, or null if not using Bazel.
   *
   * At least one content root must be within the workspace, and the project cannot have
   * content roots in more than one workspace.
   */
  private static @Nullable VirtualFile findWorkspaceFile(@NotNull Project p) {
    final Computable<VirtualFile> readAction = () -> {
      final Map<String, VirtualFile> candidates = new HashMap<>();
      for (VirtualFile contentRoot : ProjectRootManager.getInstance(p).getContentRoots()) {
        final VirtualFile wf = findContainingWorkspaceFile(contentRoot);
        if (wf != null) {
          candidates.put(wf.getPath(), wf);
        }
      }

      if (candidates.size() == 1) {
        return candidates.values().iterator().next();
      }

      // not found
      return null;
    };
    return ApplicationManager.getApplication().runReadAction(readAction);
  }

  /**
   * Returns the closest WORKSPACE file within or above the given directory, or null if not found.
   */
  private static @Nullable VirtualFile findContainingWorkspaceFile(@NotNull VirtualFile dir) {
    while (dir != null) {
      final VirtualFile child = dir.findChild("WORKSPACE");
      if (child != null && child.exists() && !child.isDirectory()) {
        return child;
      }
      dir = dir.getParent();
    }

    // not found
    return null;
  }

  private static final Logger LOG = Logger.getInstance(Workspace.class);
}
