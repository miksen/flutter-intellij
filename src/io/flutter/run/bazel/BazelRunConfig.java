/*
 * Copyright 2016 The Chromium Authors. All rights reserved.
 * Use of this source code is governed by a BSD-style license that can be
 * found in the LICENSE file.
 */
package io.flutter.run.bazel;

import com.intellij.execution.ExecutionException;
import com.intellij.execution.Executor;
import com.intellij.execution.configurations.*;
import com.intellij.execution.runners.ExecutionEnvironment;
import com.intellij.execution.runners.RunConfigurationWithSuppressedDefaultRunAction;
import com.intellij.openapi.options.SettingsEditor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.InvalidDataException;
import com.intellij.openapi.util.WriteExternalException;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.util.xmlb.SkipDefaultValuesSerializationFilters;
import com.intellij.util.xmlb.XmlSerializer;
import io.flutter.run.Launcher;
import io.flutter.run.daemon.FlutterAppService;
import io.flutter.run.daemon.RunMode;
import org.jdom.Element;
import org.jetbrains.annotations.NotNull;

public class BazelRunConfig extends RunConfigurationBase
  implements RunConfigurationWithSuppressedDefaultRunAction, Launcher.RunConfig {
  private @NotNull BazelFields fields = new BazelFields();

  BazelRunConfig(final @NotNull Project project, final @NotNull ConfigurationFactory factory, @NotNull final String name) {
    super(project, factory, name);
  }

  @NotNull
  BazelFields getFields() {
    return fields;
  }

  void setFields(@NotNull BazelFields newFields) {
    fields = newFields;
  }

  @Override
  public void checkConfiguration() throws RuntimeConfigurationException {
    fields.checkRunnable(getProject());
  }

  @NotNull
  @Override
  public SettingsEditor<? extends RunConfiguration> getConfigurationEditor() {
    return new FlutterBazelConfigurationEditorForm(getProject());
  }

  @NotNull
  @Override
  public Launcher getState(@NotNull Executor executor, @NotNull ExecutionEnvironment env) throws ExecutionException {
    try {
      fields.checkRunnable(env.getProject());
    } catch (RuntimeConfigurationError e) {
      throw new ExecutionException(e);
    }

    final VirtualFile workDir = fields.chooseWorkDir(env.getProject());
    assert workDir != null; // already checked

    final String launchingScript = fields.getLaunchingScript();
    assert launchingScript != null; // already checked

    final String target = fields.getBazelTarget();
    assert target != null; // already checked

    final String additionalArgs = fields.getAdditionalArgs();

    final FlutterAppService appService = FlutterAppService.getInstance(env.getProject());
    final Launcher.Callback callback = (device) ->
      appService.startBazelApp(workDir.getPath(), launchingScript, device, RunMode.fromEnv(env), target, additionalArgs);

    return new Launcher(env, workDir, workDir, this, callback);
  }

  public BazelRunConfig clone() {
    final BazelRunConfig clone = (BazelRunConfig)super.clone();
    clone.fields = fields.copy();
    return clone;
  }

  RunConfiguration copyTemplateToNonTemplate(String name) {
    final BazelRunConfig copy = (BazelRunConfig)super.clone();
    copy.setName(name);
    copy.fields = fields.copyTemplateToNonTemplate(getProject());
    return copy;
  }

  @Override
  public void writeExternal(final Element element) throws WriteExternalException {
    super.writeExternal(element);
    XmlSerializer.serializeInto(fields, element, new SkipDefaultValuesSerializationFilters());
  }

  @Override
  public void readExternal(final Element element) throws InvalidDataException {
    super.readExternal(element);
    XmlSerializer.deserializeInto(fields, element);
  }
}
