/*
 * Copyright 2012-2013 Sergey Ignatov
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.intellij.erlang.console;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.consulo.compiler.ModuleCompilerPathsManager;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.mustbe.consulo.erlang.module.extension.ErlangModuleExtension;
import org.mustbe.consulo.roots.impl.ProductionContentFolderTypeProvider;
import org.mustbe.consulo.roots.impl.TestContentFolderTypeProvider;
import com.intellij.execution.ExecutionException;
import com.intellij.execution.ui.ConsoleView;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.module.ModuleUtilCore;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.projectRoots.Sdk;
import com.intellij.openapi.roots.ModuleRootManager;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.util.Processor;

public final class ErlangConsoleUtil {
  public static final String EUNIT_FAILURE_PATH = "\\[\\{file,\"" + FileReferenceFilter.PATH_MACROS + "\"\\},\\{line," + FileReferenceFilter.LINE_MACROS + "\\}\\]";
  public static final String EUNIT_ERROR_PATH = FileReferenceFilter.PATH_MACROS + ", line " + FileReferenceFilter.LINE_MACROS;
  public static final String COMPILATION_ERROR_PATH = FileReferenceFilter.PATH_MACROS + ":" + FileReferenceFilter.LINE_MACROS;

  private ErlangConsoleUtil() {
  }

  public static void attachFilters(@NotNull Project project, @NotNull ConsoleView consoleView) {
    consoleView.addMessageFilter(new FileReferenceFilter(project, COMPILATION_ERROR_PATH));
    consoleView.addMessageFilter(new FileReferenceFilter(project, EUNIT_ERROR_PATH));
    consoleView.addMessageFilter(new FileReferenceFilter(project, EUNIT_FAILURE_PATH));
  }

  @NotNull
  public static List<String> getCodePath(@NotNull Module module, boolean forTests) {
    return getCodePath(module.getProject(), module, forTests);
  }

  @NotNull
  public static List<String> getCodePath(@NotNull Project project, @Nullable Module module, boolean useTestOutputPath) {
    final Set<Module> codePathModules = new HashSet<Module>();
    if (module != null) {
      final ModuleRootManager moduleRootMgr = ModuleRootManager.getInstance(module);
      moduleRootMgr.orderEntries().recursively().forEachModule(new Processor<Module>() {
        @Override
        public boolean process(@NotNull Module dependencyModule) {
          codePathModules.add(dependencyModule);
          return true;
        }
      });
    }
    else {
      codePathModules.addAll(Arrays.asList(ModuleManager.getInstance(project).getModules()));
    }

    final List<String> codePath = new ArrayList<String>(codePathModules.size() * 2);
    for (Module codePathModule : codePathModules) {
      final ModuleRootManager moduleRootManager = ModuleRootManager.getInstance(codePathModule);
      final ModuleCompilerPathsManager compilerModuleExt = ModuleCompilerPathsManager.getInstance(codePathModule);
      final VirtualFile buildOutput = useTestOutputPath && codePathModule == module ?
        getCompilerOutputPathForTests(compilerModuleExt) : 
        compilerModuleExt.getCompilerOutput(ProductionContentFolderTypeProvider.getInstance());
      if (buildOutput != null) {
        codePath.add("-pa");
        codePath.add(buildOutput.getCanonicalPath());
      }
      for (VirtualFile contentRoot : ModuleRootManager.getInstance(codePathModule).getContentRoots()) {
        codePath.add("-pa");
        codePath.add(contentRoot.getPath());
      }
    }

    return codePath;
  }

  @Nullable
  private static VirtualFile getCompilerOutputPathForTests(ModuleCompilerPathsManager module) {
    final VirtualFile testPath = module.getCompilerOutput(TestContentFolderTypeProvider.getInstance());
    return testPath == null || !testPath.exists() ? module.getCompilerOutput(ProductionContentFolderTypeProvider.getInstance()) : testPath;
  }

  @NotNull
  static String getWorkingDirPath(@NotNull Project project, @NotNull String workingDirPath) {
    if (workingDirPath.isEmpty()) {
      return project.getBasePath();
    }
    return workingDirPath;
  }

  @NotNull
  static String getErlPath(@NotNull Project project, @Nullable Module module) throws ExecutionException {
    Sdk sdk = module != null ? ModuleUtilCore.getSdk(module, ErlangModuleExtension.class) : null;

    if (sdk == null) {
      throw new ExecutionException("Erlang SDK is not configured");
    }
    return sdk.getHomePath() + File.separator + "bin" + File.separator + "erl";
  }
}

