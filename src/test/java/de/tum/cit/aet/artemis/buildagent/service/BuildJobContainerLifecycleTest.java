package de.tum.cit.aet.artemis.buildagent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.CopyArchiveFromContainerCmd;
import com.github.dockerjava.api.command.CreateContainerCmd;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.command.ExecCreateCmd;
import com.github.dockerjava.api.command.ExecCreateCmdResponse;
import com.github.dockerjava.api.command.ExecStartCmd;
import com.github.dockerjava.api.command.InspectContainerCmd;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.command.InspectExecCmd;
import com.github.dockerjava.api.command.InspectExecResponse;
import com.github.dockerjava.api.command.KillContainerCmd;
import com.github.dockerjava.api.command.RemoveContainerCmd;
import com.github.dockerjava.api.exception.ConflictException;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.exception.NotModifiedException;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.StreamType;

import de.tum.cit.aet.artemis.buildagent.BuildAgentConfiguration;
import de.tum.cit.aet.artemis.buildagent.dto.BuildLogDTO;
import de.tum.cit.aet.artemis.buildagent.dto.DockerRunConfig;
import de.tum.cit.aet.artemis.localci.exception.LocalCIException;

class BuildJobContainerLifecycleTest {

    private final BuildAgentConfiguration configuration = mock(BuildAgentConfiguration.class);

    private final DockerClient docker = mock(DockerClient.class);

    private final BuildLogsMap logs = mock(BuildLogsMap.class);

    private final BuildJobContainerService service = new BuildJobContainerService(configuration, logs);

    private final InspectContainerCmd inspect = mock(InspectContainerCmd.class);

    private final CreateContainerCmd create = mock(CreateContainerCmd.class, RETURNS_SELF);

    @BeforeEach
    void setUp() {
        when(configuration.getDockerClient()).thenReturn(docker);
        when(configuration.isDockerAvailable()).thenReturn(true);
        when(docker.inspectContainerCmd("build")).thenReturn(inspect);
        when(inspect.exec()).thenThrow(new NotFoundException("no such container"));
        when(docker.createContainerCmd("image")).thenReturn(create);
        ReflectionTestUtils.setField(service, "maxFinishedContainers", 20);
        when(configuration.hostConfig())
                .thenReturn(HostConfig.newHostConfig().withCpuPeriod(100000L).withCpuQuota(200000L).withMemory(1024L).withMemorySwap(2048L).withPidsLimit(100L));
    }

    @Test
    void requestIncludesProxyAndExerciseEnvironmentWithUncappedResources() {
        ReflectionTestUtils.setField(service, "useSystemProxy", true);
        ReflectionTestUtils.setField(service, "httpProxy", "http://proxy");
        ReflectionTestUtils.setField(service, "httpsProxy", "https://proxy");
        ReflectionTestUtils.setField(service, "noProxy", "localhost");
        CreateContainerResponse response = mock(CreateContainerResponse.class);
        when(create.exec()).thenReturn(response);
        assertThat(service.configureContainer("build", "image", new DockerRunConfig(List.of("CUSTOM=value"), "network", 3, 10, 20))).isSameAs(response);
        var host = ArgumentCaptor.forClass(HostConfig.class);
        verify(create).withHostConfig(host.capture());
        assertThat(host.getValue().getCpuQuota()).isEqualTo(300000L);
        assertThat(host.getValue().getMemory()).isEqualTo(10 * 1024L * 1024L);
        assertThat(host.getValue().getMemorySwap()).isEqualTo(20 * 1024L * 1024L);
        assertThat(host.getValue().getNetworkMode()).isEqualTo("network");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> environment = ArgumentCaptor.forClass(List.class);
        verify(create).withEnv(environment.capture());
        assertThat(environment.getValue()).containsExactly("HTTP_PROXY=http://proxy", "HTTPS_PROXY=https://proxy", "NO_PROXY=localhost", "CUSTOM=value");
    }

    @Test
    void exerciseResourceRequestKeepsWorkingDirectoryTmpfs() {
        when(configuration.hostConfig()).thenReturn(HostConfig.newHostConfig().withCpuPeriod(100000L).withCpuQuota(200000L).withMemory(1024L).withMemorySwap(2048L)
                .withPidsLimit(100L).withTmpFs(Map.of("/var/tmp", "rw,exec,size=2g")));

        service.configureContainer("build", "image", new DockerRunConfig(null, null, 4, 4096, 4096));

        var host = ArgumentCaptor.forClass(HostConfig.class);
        verify(create).withHostConfig(host.capture());
        assertThat(host.getValue().getCpuQuota()).isEqualTo(400000L);
        assertThat(host.getValue().getTmpFs()).containsExactlyEntriesOf(Map.of("/var/tmp", "rw,exec,size=2g"));
    }

    @Test
    void exerciseResourceRequestWithoutTmpfsDoesNotAddOne() {
        service.configureContainer("build", "image", new DockerRunConfig(null, null, 4, 4096, 4096));

        var host = ArgumentCaptor.forClass(HostConfig.class);
        verify(create).withHostConfig(host.capture());
        assertThat(host.getValue().getTmpFs()).isNull();
    }

    @Test
    void defaultResourceRequestKeepsHostConfigurationAndHandlesMissingExerciseVariables() {
        HostConfig defaultHost = configuration.hostConfig();
        service.configureContainer("build", "image", new DockerRunConfig(null, null, 0, 0, 0));
        verify(create).withHostConfig(defaultHost);
        verify(create).withEnv(List.of());
        when(configuration.isDockerAvailable()).thenReturn(false);
        assertThatThrownBy(() -> service.configureContainer("build", "image", new DockerRunConfig(null, null, 0, 0, 0))).isInstanceOf(LocalCIException.class)
                .hasMessageContaining("Docker is not available");
        assertThatThrownBy(() -> service.startContainer("build")).isInstanceOf(LocalCIException.class).hasMessageContaining("Cannot start container build");
        assertThat(service.getIDOfRunningContainer("build")).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = { "created", "running" })
    void lookupByNameReportsCreatedAndRunningContainersAsActive(String state) {
        doReturn(inspected("matching", state)).when(inspect).exec();
        assertThat(service.getIDOfRunningContainer("build")).isEqualTo("matching");
        verify(docker, never()).listContainersCmd();
    }

    @Test
    void lookupByNameTreatsExitedAndFailingLookupsAsInactive() {
        doReturn(inspected("matching", "exited")).when(inspect).exec();
        assertThat(service.getIDOfRunningContainer("build")).isNull();
        // An exited container can no longer react to the stop file, so no exec is sent
        service.stopContainer("build");
        verify(docker, never()).execCreateCmd(any());
        doThrow(new IllegalStateException("unexpected daemon failure")).when(inspect).exec();
        assertThat(service.getIDOfRunningContainer("build")).isNull();
        doThrow(new RuntimeException("Connection refused")).when(inspect).exec();
        assertThat(service.getIDOfRunningContainer("build")).isNull();
        verify(docker, never()).listContainersCmd();
    }

    @Test
    void createWithFreeNameDoesNotLookForExistingContainer() {
        service.configureContainer("build", "image", new DockerRunConfig(null, null, 0, 0, 0));
        verify(create).exec();
        verify(docker, never()).inspectContainerCmd(any());
        verify(docker, never()).removeContainerCmd(any());
    }

    @Test
    void createWithTakenNameRemovesExistingContainerAndCreatesAgain() {
        CreateContainerResponse response = mock(CreateContainerResponse.class);
        when(create.exec()).thenThrow(new ConflictException("name already in use")).thenReturn(response);
        doReturn(inspected("old", "exited")).when(inspect).exec();
        RemoveContainerCmd remove = mock(RemoveContainerCmd.class, RETURNS_SELF);
        when(docker.removeContainerCmd("old")).thenReturn(remove);

        assertThat(service.configureContainer("build", "image", new DockerRunConfig(null, null, 0, 0, 0))).isSameAs(response);

        verify(remove).withForce(true);
        verify(remove).exec();
        verify(create, times(2)).exec();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = { "../results", "results/$(command)", "results with spaces" })
    void rejectsUnsafePathsBeforeExecutingDockerCommands(String path) {
        assertThatThrownBy(() -> service.moveResultsToSpecifiedDirectory("build", List.of(), path)).isInstanceOf(LocalCIException.class).hasMessageContaining("Invalid path:");
        verify(docker, never()).execCreateCmd(any());
    }

    @Test
    void archiveRetrievalRetriesTransientNullStreamThenReturnsUsableArchive() throws Exception {
        CopyArchiveFromContainerCmd copy = mock(CopyArchiveFromContainerCmd.class);
        when(docker.copyArchiveFromContainerCmd("build", "/results")).thenReturn(copy);
        when(copy.exec()).thenReturn((InputStream) null).thenReturn(new ByteArrayInputStream(new byte[1024]));
        try (var archive = service.getArchiveFromContainer("build", "/results", "job")) {
            assertThat(archive.getNextEntry()).isNull();
        }
        verify(copy, times(2)).exec();
        verify(logs).appendBuildLogEntry("job", "Retrieve archive from container build succeeded after 2 attempt(s).");
    }

    @Test
    void missingArchiveFailsImmediatelyAndPersistentConnectivityFailureExhaustsRetries() {
        CopyArchiveFromContainerCmd copy = mock(CopyArchiveFromContainerCmd.class);
        when(docker.copyArchiveFromContainerCmd("build", "/results")).thenReturn(copy);
        when(copy.exec()).thenThrow(new NotFoundException("missing"));
        assertThatThrownBy(() -> service.getArchiveFromContainer("build", "/results", null)).isInstanceOf(NotFoundException.class);
        verify(copy).exec();
        doThrow(new IllegalStateException("Connection refused")).when(copy).exec();
        assertThatThrownBy(() -> service.getArchiveFromContainer("build", "/results", "job")).isInstanceOf(LocalCIException.class).hasMessageContaining("after 3 attempts");
        verify(copy, times(4)).exec();
        verify(logs).appendBuildLogEntry("job",
                "Failed to retrieve build results after multiple attempts. This is an infrastructure issue, not a problem with your code. Please try rerunning your build.");
    }

    @Test
    void scriptLogsPreserveLinesAndMissingExitCodeReturnsMinusOne() {
        ExecStartCmd start = execSetup();
        when(start.exec(any())).thenAnswer(call -> {
            ResultCallback<Frame> callback = call.getArgument(0);
            callback.onNext(new Frame(StreamType.STDOUT, "first\nsecond\n".getBytes(StandardCharsets.UTF_8)));
            callback.onNext(new Frame(StreamType.STDOUT, new byte[0]));
            callback.onComplete();
            return null;
        });
        assertThat(service.runScriptInContainer("build", "job")).isEqualTo(-1);
        var lines = ArgumentCaptor.forClass(BuildLogDTO.class);
        verify(logs, times(2)).appendBuildLogEntry(eq("job"), lines.capture());
        assertThat(lines.getAllValues()).extracting(BuildLogDTO::log).containsExactly("first\n", "second\n");
    }

    @Test
    void scriptCallbackErrorIsReportedWithoutInspectingExitCode() {
        ExecStartCmd start = execSetup();
        when(start.exec(any())).thenAnswer(call -> {
            ResultCallback<?> callback = call.getArgument(0);
            callback.onError(new IllegalStateException("stream failed"));
            return null;
        });
        assertThatThrownBy(() -> service.runScriptInContainer("build", "job")).isInstanceOf(LocalCIException.class).hasMessageContaining("Docker command failed");
    }

    @Test
    void unresponsiveContainerIsKilledWithoutGracefulStopAndRemovedLater() {
        KillContainerCmd kill = mock(KillContainerCmd.class);
        when(docker.killContainerCmd("id")).thenReturn(kill);

        assertThatCode(() -> service.stopUnresponsiveContainer("id")).doesNotThrowAnyException();

        verify(kill).exec();
        verify(docker, never()).stopContainerCmd(any());
        verify(docker, never()).removeContainerCmd(any());
        assertThat(service.getNumberOfFinishedContainers()).isOne();
    }

    @ParameterizedTest
    @ValueSource(strings = { "missing", "not-running", "conflict", "other" })
    void unresponsiveContainerThatCannotBeKilledIsScheduledForRemovalUnlessGone(String failure) {
        KillContainerCmd kill = mock(KillContainerCmd.class);
        when(docker.killContainerCmd("id")).thenReturn(kill);
        RuntimeException exception = switch (failure) {
            case "missing" -> new NotFoundException("already removed");
            case "not-running" -> new NotModifiedException("already stopped");
            case "conflict" -> new ConflictException("container is not running");
            default -> new IllegalStateException("daemon failed");
        };
        when(kill.exec()).thenThrow(exception);

        assertThatCode(() -> service.stopUnresponsiveContainer("id")).doesNotThrowAnyException();

        assertThat(service.getNumberOfFinishedContainers()).isEqualTo(failure.equals("missing") ? 0 : 1);
        verify(docker, never()).removeContainerCmd(any());
        verify(docker, never()).stopContainerCmd(any());
    }

    @Test
    void stoppedContainerIsScheduledForRemoval() {
        doReturn(inspected("running-id", "running")).when(inspect).exec();
        // The stop file exec itself is not mocked; a failing exec only logs a warning, the container is scheduled for removal before it
        service.stopContainer("build");
        assertThat(service.getNumberOfFinishedContainers()).isOne();
    }

    @Test
    void idleAgentRemovesFinishedContainersOneByOneAndStopsWhenAJobStarts() {
        ThreadPoolExecutor buildExecutor = mock(ThreadPoolExecutor.class);
        when(buildExecutor.getQueue()).thenReturn(new LinkedBlockingQueue<>());
        // idle for the first container, then a build job starts
        when(buildExecutor.getActiveCount()).thenReturn(0, 1);
        when(configuration.getBuildExecutor()).thenReturn(buildExecutor);
        RemoveContainerCmd remove = mock(RemoveContainerCmd.class, RETURNS_SELF);
        when(docker.removeContainerCmd(any())).thenReturn(remove);
        service.scheduleRemoval("first");
        service.scheduleRemoval("second");

        service.removeFinishedContainers();

        verify(remove).withForce(true);
        verify(remove).exec();
        assertThat(service.getNumberOfFinishedContainers()).isOne();
    }

    @Test
    void busyAgentPostponesRemovalUntilLimitIsExceededThenRemovesAll() {
        ThreadPoolExecutor buildExecutor = mock(ThreadPoolExecutor.class);
        when(buildExecutor.getActiveCount()).thenReturn(2);
        when(configuration.getBuildExecutor()).thenReturn(buildExecutor);
        RemoveContainerCmd remove = mock(RemoveContainerCmd.class, RETURNS_SELF);
        when(docker.removeContainerCmd(any())).thenReturn(remove);
        ReflectionTestUtils.setField(service, "maxFinishedContainers", 2);
        service.scheduleRemoval("first");
        service.scheduleRemoval("second");

        service.removeFinishedContainers();
        verify(remove, never()).exec();
        assertThat(service.getNumberOfFinishedContainers()).isEqualTo(2);

        service.scheduleRemoval("third");
        service.removeFinishedContainers();
        verify(remove, times(3)).exec();
        assertThat(service.getNumberOfFinishedContainers()).isZero();
    }

    @Test
    void failedRemovalIsRetriedAndGivenUpAfterFiveAttemptsWhileMissingContainerIsDropped() {
        RemoveContainerCmd failing = mock(RemoveContainerCmd.class, RETURNS_SELF);
        RemoveContainerCmd missing = mock(RemoveContainerCmd.class, RETURNS_SELF);
        when(docker.removeContainerCmd("failing")).thenReturn(failing);
        when(docker.removeContainerCmd("missing")).thenReturn(missing);
        doThrow(new IllegalStateException("removal in progress")).when(failing).exec();
        doThrow(new NotFoundException("already removed")).when(missing).exec();
        service.scheduleRemoval("failing");
        service.scheduleRemoval("missing");

        service.removeFinishedContainers();
        assertThat(service.getNumberOfFinishedContainers()).isOne();
        for (int attempt = 2; attempt <= 5; attempt++) {
            service.removeFinishedContainers();
        }

        verify(failing, times(5)).exec();
        assertThat(service.getNumberOfFinishedContainers()).isZero();
    }

    @Test
    void conflictingContainerIsRemovedRightAwayAndLeavesTheRemovalQueue() {
        CreateContainerResponse response = mock(CreateContainerResponse.class);
        when(create.exec()).thenThrow(new ConflictException("name already in use")).thenReturn(response);
        doReturn(inspected("old", "exited")).when(inspect).exec();
        RemoveContainerCmd remove = mock(RemoveContainerCmd.class, RETURNS_SELF);
        when(docker.removeContainerCmd("old")).thenReturn(remove);
        service.scheduleRemoval("old");

        service.configureContainer("build", "image", new DockerRunConfig(null, null, 0, 0, 0));

        verify(remove).exec();
        assertThat(service.getNumberOfFinishedContainers()).isZero();
    }

    @Test
    void containersAreCreatedToDieOnStopWithoutWaiting() {
        service.configureContainer("build", "image", new DockerRunConfig(null, null, 0, 0, 0));

        verify(create).withStopSignal("SIGKILL");
        verify(create).withStopTimeout(0);
        verify(create).withCmd("sh", "-c", "trap 'exit 0' TERM; while [ ! -f /var/tmp/stop_container.txt ]; do sleep 0.5; done");
    }

    private ExecStartCmd execSetup() {
        ExecCreateCmd createExec = mock(ExecCreateCmd.class, RETURNS_SELF);
        ExecCreateCmdResponse created = mock(ExecCreateCmdResponse.class);
        when(created.getId()).thenReturn("exec-id");
        when(docker.execCreateCmd("build")).thenReturn(createExec);
        when(createExec.exec()).thenReturn(created);
        ExecStartCmd start = mock(ExecStartCmd.class, RETURNS_SELF);
        when(docker.execStartCmd("exec-id")).thenReturn(start);
        InspectExecCmd inspect = mock(InspectExecCmd.class);
        InspectExecResponse inspected = mock(InspectExecResponse.class);
        when(docker.inspectExecCmd("exec-id")).thenReturn(inspect);
        when(inspect.exec()).thenReturn(inspected);
        when(inspected.getExitCodeLong()).thenReturn(null);
        return start;
    }

    private InspectContainerResponse inspected(String id, String state) {
        InspectContainerResponse container = mock(InspectContainerResponse.class, RETURNS_DEEP_STUBS);
        when(container.getId()).thenReturn(id);
        when(container.getState().getStatus()).thenReturn(state);
        return container;
    }
}
