package org.intellij.erlang.debugger.xdebug;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URL;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import org.intellij.erlang.ErlangFileType;
import org.intellij.erlang.debugger.node.ErlangDebuggerEventListener;
import org.intellij.erlang.debugger.node.ErlangDebuggerNode;
import org.intellij.erlang.debugger.node.ErlangDebuggerNodeException;
import org.intellij.erlang.debugger.node.ErlangProcessSnapshot;
import org.intellij.erlang.debugger.remote.ErlangRemoteDebugRunConfiguration;
import org.intellij.erlang.debugger.remote.ErlangRemoteDebugRunningState;
import org.intellij.erlang.psi.ErlangFile;
import org.intellij.erlang.psi.ErlangModule;
import org.intellij.erlang.runconfig.ErlangRunConfigurationBase;
import org.intellij.erlang.runconfig.ErlangRunningState;
import org.intellij.erlang.sdk.ErlangSdkType;
import org.intellij.erlang.utils.ErlangModulesUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.mustbe.consulo.erlang.module.extension.ErlangModuleExtension;
import com.ericsson.otp.erlang.OtpErlangPid;
import com.intellij.execution.ExecutionException;
import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.execution.process.OSProcessHandler;
import com.intellij.execution.process.ProcessHandler;
import com.intellij.execution.runners.ExecutionEnvironment;
import com.intellij.execution.ui.ExecutionConsole;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileTypes.FileType;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleUtilCore;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.projectRoots.Sdk;
import com.intellij.openapi.ui.MessageType;
import com.intellij.openapi.util.Condition;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.testFramework.LightVirtualFile;
import com.intellij.util.Function;
import com.intellij.util.ResourceUtil;
import com.intellij.util.containers.ContainerUtil;
import com.intellij.util.io.URLUtil;
import com.intellij.xdebugger.XDebugProcess;
import com.intellij.xdebugger.XDebugSession;
import com.intellij.xdebugger.XSourcePosition;
import com.intellij.xdebugger.breakpoints.XBreakpointHandler;
import com.intellij.xdebugger.breakpoints.XLineBreakpoint;
import com.intellij.xdebugger.evaluation.EvaluationMode;
import com.intellij.xdebugger.evaluation.XDebuggerEditorsProvider;

public class ErlangXDebugProcess extends XDebugProcess {
  private final ExecutionEnvironment myExecutionEnvironment;
  @NotNull private ErlangDebuggerNode myDebuggerNode;
  private ProcessHandler myErlangProcessHandler;
  private XBreakpointHandler<?>[] myBreakpointHandlers = new XBreakpointHandler[]{new ErlangLineBreakpointHandler(this)};
  private ConcurrentHashMap<ErlangSourcePosition, XLineBreakpoint<ErlangLineBreakpointProperties>> myPositionToLineBreakpointMap =
    new ConcurrentHashMap<ErlangSourcePosition, XLineBreakpoint<ErlangLineBreakpointProperties>>();

  public ErlangXDebugProcess(@NotNull XDebugSession session, ExecutionEnvironment env) {
    //TODO add debug build targets and make sure the project is built using them.
    super(session);
    session.setPauseActionSupported(false);
    myExecutionEnvironment = env;
    myDebuggerNode = new ErlangDebuggerNode(myExecutionEnvironment.getProject());
    setDebuggerNodeListener();
    setModulesToInterpret();
  }

  private void setDebuggerNodeListener() {
    myDebuggerNode.setListener(new ErlangDebuggerEventListener() {
      @Override
      public void debuggerStarted() {
        getSession().reportMessage("Debug process started", MessageType.INFO);
      }

      @Override
      public void failedToInterpretModules(String nodeName, List<ErlangModule> modules) {
        String messagePrefix = "Failed to interpret modules on node " + nodeName + ": ";
        String modulesString = StringUtil.join(modules, new Function<ErlangModule, String>() {
          @Override
          public String fun(ErlangModule erlangModule) {
            return FileUtil.getNameWithoutExtension(erlangModule.getContainingFile().getName());
          }
        }, ", ");
        String messageSuffix = ".\nMake sure they are compiled with debug_info option, their sources are located in same directory as .beam files, modules are available on the node.";
        String message = messagePrefix + modulesString + messageSuffix;
        getSession().reportMessage(message, MessageType.WARNING);
      }

      @Override
      public void failedToDebugRemoteNode(String nodeName, String error) {
        String message = "Failed to debug remote node '" + nodeName + "'. Details: " + error;
        getSession().reportMessage(message, MessageType.ERROR);
      }

      @Override
      public void unknownMessage(String messageText) {
        getSession().reportMessage("Unknown message received: " + messageText, MessageType.WARNING);
      }

      @Override
      public void failedToSetBreakpoint(ErlangFile module, int line, String errorMessage) {
        XLineBreakpoint<ErlangLineBreakpointProperties> breakpoint = myPositionToLineBreakpointMap.get(new ErlangSourcePosition(module, line));
        if (breakpoint != null) {
          getSession().updateBreakpointPresentation(breakpoint, AllIcons.Debugger.Db_invalid_breakpoint, errorMessage);
        }
      }

      @Override
      public void breakpointIsSet(ErlangFile module, int line) {
      }

      @Override
      public void breakpointReached(final OtpErlangPid pid, List<ErlangProcessSnapshot> snapshots) {
        ErlangProcessSnapshot processInBreakpoint = ContainerUtil.find(snapshots, new Condition<ErlangProcessSnapshot>() {
          @Override
          public boolean value(ErlangProcessSnapshot erlangProcessSnapshot) {
            return erlangProcessSnapshot.getPid().equals(pid);
          }
        });
        assert processInBreakpoint != null;
        ErlangSourcePosition breakPosition = processInBreakpoint.getBreakPosition();
        assert breakPosition != null;
        XLineBreakpoint<ErlangLineBreakpointProperties> breakpoint = myPositionToLineBreakpointMap.get(breakPosition);
        ErlangSuspendContext suspendContext = new ErlangSuspendContext(pid, snapshots);
        if (breakpoint == null) {
          getSession().positionReached(suspendContext);
        }
        else {
          boolean shouldSuspend = getSession().breakpointReached(breakpoint, null, suspendContext);
          if (!shouldSuspend) {
            resume();
          }
        }
      }

      @Override
      public void debuggerStopped() {
        getSession().reportMessage("Debug process stopped", MessageType.INFO);
        getSession().stop();
      }
    });
  }

  private void setModulesToInterpret() {
    Project project = myExecutionEnvironment.getProject();
    Collection<ErlangFile> erlangModules = ErlangModulesUtil.getErlangModules(project);
    ErlangRunConfigurationBase<?> runConfiguration = getRunConfiguration();
    if (runConfiguration.isTestRunConfiguration()) {
      HashSet<ErlangFile> erlangTestModules = new HashSet<ErlangFile>();
      for (Module module : runConfiguration.getModules()) {
        erlangTestModules.addAll(ErlangModulesUtil.getErlangModules(module, true));
      }
      erlangTestModules.addAll(erlangModules);
      erlangModules = erlangTestModules;
    }
    List<String> modulesToInterpret = ContainerUtil.map(erlangModules, new Function<ErlangFile, String>() {
      @Override
      public String fun(ErlangFile erlangFile) {
        VirtualFile virtualFile = erlangFile.getVirtualFile();
        assert virtualFile != null;
        return virtualFile.getNameWithoutExtension();
      }
    });
    myDebuggerNode.interpretModules(modulesToInterpret);
  }

  @NotNull
  @Override
  public ExecutionConsole createConsole() {
    try {
      return createRunningState().createConsoleView(myExecutionEnvironment.getExecutor());
    } catch (ExecutionException e) {
      return super.createConsole();
    }
  }

  @Override
  public XBreakpointHandler<?>[] getBreakpointHandlers() {
    return myBreakpointHandlers;
  }

  @NotNull
  @Override
  public XDebuggerEditorsProvider getEditorsProvider() {
    return new XDebuggerEditorsProvider() {
      @NotNull
      @Override
      public FileType getFileType() {
        return ErlangFileType.MODULE;
      }

      @NotNull
      @Override
      public Document createDocument(@NotNull Project project, @NotNull String text, @Nullable XSourcePosition sourcePosition, @NotNull EvaluationMode mode) {
        final LightVirtualFile file = new LightVirtualFile("plain-text-erlang-debugger.txt", text);
        //noinspection ConstantConditions
        return FileDocumentManager.getInstance().getDocument(file);
      }
    };
  }

  @Override
  public void startStepOver() {
    myDebuggerNode.stepOver();
  }

  @Override
  public void startStepInto() {
    myDebuggerNode.stepInto();
  }

  @Override
  public void startStepOut() {
    myDebuggerNode.stepOut();
  }

  @Override
  public void stop() {
    myDebuggerNode.stop();
  }

  @Override
  public void resume() {
    myDebuggerNode.resume();
  }

  @Override
  public void runToPosition(@NotNull XSourcePosition position) {
    //TODO implement me
  }

  @Override
  public void sessionInitialized() {
    super.sessionInitialized();
    try {
      initErlangDebuggerNode();
      runDebugTarget();
    } catch (ErlangDebuggerNodeException e) {
      failDebugProcess(e.getMessage());
    } catch (ExecutionException e) {
      failDebugProcess(e.getMessage());
    }
  }

  @Nullable
  @Override
  protected ProcessHandler doGetProcessHandler() {
    return myErlangProcessHandler;
  }

  void addBreakpoint(XLineBreakpoint<ErlangLineBreakpointProperties> breakpoint) {
    ErlangSourcePosition breakpointPosition = getErlangSourcePosition(breakpoint);
    if (breakpointPosition == null) return;
    myPositionToLineBreakpointMap.put(breakpointPosition, breakpoint);
    myDebuggerNode.setBreakpoint(breakpointPosition.getErlangModuleName(), breakpointPosition.getLine());
  }

  void removeBreakpoint(XLineBreakpoint<ErlangLineBreakpointProperties> breakpoint, @SuppressWarnings("UnusedParameters") boolean temporary) {
    ErlangSourcePosition breakpointPosition = getErlangSourcePosition(breakpoint);
    if (breakpointPosition == null) return;
    myPositionToLineBreakpointMap.remove(breakpointPosition);
    myDebuggerNode.removeBreakpoint(breakpointPosition.getErlangModuleName(), breakpointPosition.getLine());
  }

  @Nullable
  private ErlangSourcePosition getErlangSourcePosition(XLineBreakpoint<ErlangLineBreakpointProperties> breakpoint) {
    Project project = myExecutionEnvironment.getProject();
    XSourcePosition sourcePosition = breakpoint.getSourcePosition();
    return sourcePosition != null ? new ErlangSourcePosition(project, sourcePosition) : null;
  }

  private void failDebugProcess(String message) {
    getSession().reportMessage("Failed to start debugger. Reason: " + message, MessageType.ERROR);
    getSession().stop();
  }

  private void initErlangDebuggerNode() throws ErlangDebuggerNodeException {
    myDebuggerNode.startNode();
  }

  private ErlangRunConfigurationBase<?> getRunConfiguration() {
    ErlangRunConfigurationBase<?> runConfig = (ErlangRunConfigurationBase) getSession().getRunProfile();
    assert runConfig != null;
    return runConfig;
  }

  private ErlangRunningState createRunningState() throws ExecutionException {
    return getRunConfiguration().createRunningState(myExecutionEnvironment);
  }

  private void runDebugTarget() throws ExecutionException {
    ErlangRunningState runningState = createRunningState();
    Sdk sdk = ModuleUtilCore.getSdk(runningState.getModule(), ErlangModuleExtension.class);
    String sdkHome = sdk != null ? sdk.getHomePath() : null;
    assert sdkHome != null;
    File erl = ErlangSdkType.getTopLevelExecutable(sdkHome);

    GeneralCommandLine commandLine = new GeneralCommandLine();
    commandLine.setExePath(erl.getPath());
    commandLine.setWorkDirectory(runningState.getModule().getProject().getBasePath());
    setUpErlangDebuggerCodePath(commandLine);
    commandLine.addParameters(runningState.getCodePath());
    commandLine.addParameters("-sname", "test_node" + System.currentTimeMillis());
    commandLine.addParameters("-run", "debugnode", "main", myDebuggerNode.getName(), myDebuggerNode.getMessageBoxName(), "-noshell");
    commandLine.addParameters("-s", "init", "stop");
    Process process = commandLine.createProcess();
    myErlangProcessHandler = new OSProcessHandler(process);
    getSession().getConsoleView().attachToProcess(myErlangProcessHandler);
    myErlangProcessHandler.startNotify();
    if (runningState instanceof ErlangRemoteDebugRunningState) {
      ErlangRemoteDebugRunConfiguration runConfiguration = (ErlangRemoteDebugRunConfiguration) getRunConfiguration();
      if (StringUtil.isEmptyOrSpaces(runConfiguration.getErlangNode())) {
        throw new ExecutionException("Bad run configuration: remote Erlang node is not specified.");
      }
      myDebuggerNode.debugRemoteNode(runConfiguration.getErlangNode(), runConfiguration.getCookie());
    }
    else {
      ErlangRunningState.ErlangEntryPoint entryPoint = runningState.getDebugEntryPoint();
      myDebuggerNode.runDebugger(entryPoint.getModuleName(), entryPoint.getFunctionName(), entryPoint.getArgsList());
    }
  }

  private static void setUpErlangDebuggerCodePath(GeneralCommandLine commandLine) throws ExecutionException {
    try {
      final String[] beams = {"debugnode.beam", "remote_debugger_listener.beam", "remote_debugger_notifier.beam"};
      File tempDirectory = FileUtil.createTempDirectory("intellij_erlang_debugger_", null);
      for (String beam : beams) {
        copyBeamTo(beam, tempDirectory);
      }
      commandLine.addParameters("-pa", tempDirectory.getPath());
    } catch (IOException e) {
      throw new ExecutionException("Failed to setup debugger environment", e);
    }
  }

  private static void copyBeamTo(String beamName, File directory) throws IOException {
    URL beamUrl = ResourceUtil.getResource(ErlangXDebugProcess.class, "/debugger/beams", beamName);
    if (beamUrl == null) {
      throw new IOException("Failed to locate debugger module: " + beamName);
    }
    BufferedInputStream inputStream = new BufferedInputStream(URLUtil.openStream(beamUrl));
    try {
      BufferedOutputStream outputStream = new BufferedOutputStream(new FileOutputStream(new File(directory, beamName)));
      try {
        FileUtil.copy(inputStream, outputStream);
      } finally {
        outputStream.close();
      }
    } finally {
      inputStream.close();
    }
  }
}
