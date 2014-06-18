/*
 * Copyright 2000-2014 JetBrains s.r.o.
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
package com.intellij.ide.projectWizard;

import com.intellij.ide.actions.ImportModuleAction;
import com.intellij.ide.impl.NewProjectUtil;
import com.intellij.ide.util.newProjectWizard.AbstractProjectWizard;
import com.intellij.ide.util.newProjectWizard.AddModuleWizard;
import com.intellij.ide.util.newProjectWizard.SelectTemplateSettings;
import com.intellij.ide.util.projectWizard.ModuleWizardStep;
import com.intellij.ide.wizard.Step;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.projectRoots.ProjectJdkTable;
import com.intellij.openapi.projectRoots.Sdk;
import com.intellij.openapi.projectRoots.SdkTypeId;
import com.intellij.openapi.projectRoots.SimpleJavaSdkType;
import com.intellij.openapi.roots.ProjectRootManager;
import com.intellij.openapi.roots.ui.configuration.DefaultModulesProvider;
import com.intellij.openapi.roots.ui.configuration.actions.NewModuleAction;
import com.intellij.openapi.util.Condition;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.projectImport.ProjectImportProvider;
import com.intellij.testFramework.PlatformTestCase;
import com.intellij.util.Consumer;
import com.intellij.util.SystemProperties;
import com.intellij.util.containers.ContainerUtil;
import com.intellij.util.ui.UIUtil;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * @author Dmitry Avdeev
 *         Date: 10/29/12
 */
@SuppressWarnings("unchecked")
public abstract class ProjectWizardTestCase<T extends AbstractProjectWizard> extends PlatformTestCase {

  protected static final String DEFAULT_SDK = "default";
  protected final List<Sdk> mySdks = new ArrayList<Sdk>();
  protected T myWizard;
  @Nullable
  private Project myCreatedProject;

  protected Project createProjectFromTemplate(String group, String name, @Nullable Consumer<Step> adjuster) throws IOException {
    runWizard(group, name, null, adjuster);
    try {
      myCreatedProject = NewProjectUtil.createFromWizard(myWizard, null);
    }
    catch (Throwable e) {
      myCreatedProject = ContainerUtil.find(myProjectManager.getOpenProjects(), new Condition<Project>() {
        @Override
        public boolean value(Project project) {
          return myWizard.getProjectName().equals(project.getName());
        }
      });
      throw new RuntimeException(e);
    }
    assertNotNull(myCreatedProject);
    UIUtil.dispatchAllInvocationEvents();

    Project[] projects = myProjectManager.getOpenProjects();
    assertEquals(Arrays.asList(projects).toString(), 2, projects.length);
    return myCreatedProject;
  }

  @Nullable
  protected Module createModuleFromTemplate(String group, String name, @Nullable Consumer<Step> adjuster) throws IOException {
    runWizard(group, name, getProject(), adjuster);
    return createModuleFromWizard();
  }

  protected Module createModuleFromWizard() {
    return new NewModuleAction().createModuleFromWizard(myProject, null, myWizard);
  }

  protected void runWizard(String group, final String name, Project project, @Nullable final Consumer<Step> adjuster) throws IOException {

    createWizard(project);
    ProjectTypeStep step = (ProjectTypeStep)myWizard.getCurrentStepObject();
    boolean condition = step.setSelectedTemplate(group, name);
    if (!condition) {
      throw new IllegalArgumentException(group + "/" + name + " template not found");
    }

    runWizard(new Consumer<Step>() {
      @Override
      public void consume(Step step) {
        if (name != null && step instanceof ChooseTemplateStep) {
          ((ChooseTemplateStep)step).setSelectedTemplate(name);
        }
        if (adjuster != null) {
          adjuster.consume(step);
        }
      }
    });
  }

  protected void runWizard(Consumer<Step> adjuster) {
    while(true) {
      ModuleWizardStep currentStep = myWizard.getCurrentStepObject();
      if (adjuster != null) {
        adjuster.consume(currentStep);
      }
      if (myWizard.isLast()) {
        break;
      }
      myWizard.doNextAction();
      if (currentStep == myWizard.getCurrentStepObject()) {
        throw new RuntimeException(currentStep + " is not validated");
      }
    }
  }

  protected void createWizard(Project project) throws IOException {
    File directory = FileUtil.createTempDirectory(getName(), "new", false);
    myFilesToDelete.add(directory);
    myWizard = createWizard(project, directory);
    UIUtil.dispatchAllInvocationEvents(); // to make default selection applied
  }

  protected Project createProject(Consumer<Step> adjuster) throws IOException {
    createWizard(null);
    runWizard(adjuster);
    myCreatedProject = NewProjectUtil.createFromWizard(myWizard, null);
    return myCreatedProject;
  }

  protected T createWizard(Project project, File directory) {
    return (T)new AddModuleWizard(project, DefaultModulesProvider.createForProject(project), directory.getPath());
  }

  @Override
  protected void setUp() throws Exception {
    super.setUp();
    Sdk projectSdk = ProjectRootManager.getInstance(getProject()).getProjectSdk();
    Sdk[] jdks = ProjectJdkTable.getInstance().getAllJdks();
    for (final Sdk jdk : jdks) {
      if (projectSdk != jdk) {
        ApplicationManager.getApplication().runWriteAction(new Runnable() {
          @Override
          public void run() {
            ProjectJdkTable.getInstance().removeJdk(jdk);
          }
        });
      }
    }
  }

  protected void setupJdk() {
    ApplicationManager.getApplication().runWriteAction(new Runnable() {
      @Override
      public void run() {
        ProjectJdkTable jdkTable = ProjectJdkTable.getInstance();
        Sdk defaultJdk = new SimpleJavaSdkType().createJdk(DEFAULT_SDK, SystemProperties.getJavaHome());
        Sdk otherJdk = new SimpleJavaSdkType().createJdk("_other", SystemProperties.getJavaHome());
        jdkTable.addJdk(otherJdk);
        jdkTable.addJdk(defaultJdk);
        mySdks.add(defaultJdk);
        mySdks.add(otherJdk);

        Sdk[] jdks = jdkTable.getAllJdks();
        System.out.println(Arrays.asList(jdks));

        if (getName().contains("DefaultSdk")) {
          Project defaultProject = ProjectManager.getInstance().getDefaultProject();
          ProjectRootManager.getInstance(defaultProject).setProjectSdk(defaultJdk);
        }
      }
    });
  }

  @Override
  public void tearDown() throws Exception {
    if (myWizard != null) {
      Disposer.dispose(myWizard.getDisposable());
      myWizard = null;
    }
    if (myCreatedProject != null) {
      myProjectManager.closeProject(myCreatedProject);
      ApplicationManager.getApplication().runWriteAction(new Runnable() {
        @Override
        public void run() {
          Disposer.dispose(myCreatedProject);
        }
      });
      myCreatedProject = null;
    }
    ApplicationManager.getApplication().runWriteAction(new Runnable() {
      @Override
      public void run() {
        for (Sdk sdk : mySdks) {
          ProjectJdkTable.getInstance().removeJdk(sdk);
        }
      }
    });
    SelectTemplateSettings.getInstance().setLastTemplate(null, null);
    UIUtil.dispatchAllInvocationEvents();
    Thread.sleep(2000); //wait for JBCardLayout release timers
    UIUtil.dispatchAllInvocationEvents();
    super.tearDown();
  }

  protected Module importModuleFrom(ProjectImportProvider provider, String path) {
    return importFrom(path, getProject(), null, provider);
  }

  protected Module importProjectFrom(String path, Consumer<Step> adjuster, ProjectImportProvider... providers) {
    Module module = importFrom(path, null, adjuster, providers);
    if (module != null) {
      myCreatedProject = module.getProject();
    }
    return module;
  }

  private Module importFrom(String path,
                            @Nullable Project project, Consumer<Step> adjuster,
                            final ProjectImportProvider... providers) {
    VirtualFile file = LocalFileSystem.getInstance().refreshAndFindFileByPath(path);
    assertNotNull("Can't find " + path, file);
    assertTrue(providers[0].canImport(file, project));

    myWizard = (T)ImportModuleAction.createImportWizard(project, null, file, providers);
    assertNotNull(myWizard);
    if (myWizard.getStepCount() > 0) {
      runWizard(adjuster);
    }
    List<Module> modules = ImportModuleAction.createFromWizard(project, myWizard);
    return modules == null || modules.isEmpty() ? null : modules.get(0);
  }

  protected Sdk createSdk(String name, SdkTypeId sdkType) {
    final Sdk sdk = ProjectJdkTable.getInstance().createSdk(name, sdkType);
    ApplicationManager.getApplication().runWriteAction(new Runnable() {
      @Override
      public void run() {
        ProjectJdkTable.getInstance().addJdk(sdk);
      }
    });
    mySdks.add(sdk);
    return sdk;
  }
}
