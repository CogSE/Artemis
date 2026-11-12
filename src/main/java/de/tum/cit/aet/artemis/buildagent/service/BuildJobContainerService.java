package de.tum.cit.aet.artemis.buildagent.service;

import static de.tum.cit.aet.artemis.core.config.Constants.LOCAL_CI_DOCKER_CONTAINER_WORKING_DIRECTORY;
import static de.tum.cit.aet.artemis.core.config.Constants.PROFILE_BUILDAGENT;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.command.ExecCreateCmdResponse;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.command.InspectExecResponse;
import com.github.dockerjava.api.exception.ConflictException;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.exception.NotModifiedException;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.HostConfig;

import de.tum.cit.aet.artemis.buildagent.BuildAgentConfiguration;
import de.tum.cit.aet.artemis.buildagent.dto.BuildLogDTO;
import de.tum.cit.aet.artemis.buildagent.dto.DockerRunConfig;
import de.tum.cit.aet.artemis.localci.exception.LocalCIException;

/**
 * Service responsible for managing Docker container lifecycle and file operations during build job execution.
 * <p>
 * This service handles all Docker container interactions for the local CI system, including:
 * <ul>
 * <li>Creating and configuring containers with appropriate resource limits and environment variables</li>
 * <li>Starting containers and executing build scripts within them</li>
 * <li>Copying repository files into containers (via tar archives)</li>
 * <li>Retrieving build results from containers</li>
 * <li>Stopping and cleaning up containers (gracefully or forcefully)</li>
 * </ul>
 * <p>
 * <b>Architecture:</b> This service works in conjunction with:
 * <ul>
 * <li>{@link BuildJobExecutionService} - orchestrates the overall build job flow and calls this service for container operations</li>
 * <li>{@link BuildAgentConfiguration} - provides Docker client and host configuration</li>
 * <li>{@link BuildLogsMap} - stores build logs that are captured from container output</li>
 * </ul>
 * <p>
 * <b>Container Lifecycle:</b>
 * <ol>
 * <li>{@link #configureContainer} - creates container with resource limits and environment variables</li>
 * <li>{@link #startContainer} - starts the container</li>
 * <li>{@link #copyInputArchiveToContainer} - copies repositories and the build script into container</li>
 * <li>{@link #runScriptInContainer} - executes the build script</li>
 * <li>{@link #getArchiveFromContainer} - retrieves build results</li>
 * <li>{@link #stopContainer} or {@link #stopUnresponsiveContainer} - stops and removes container</li>
 * </ol>
 * <p>
 * <b>Retry Logic:</b> Archive operations (upload, retrieval) include retry logic with exponential
 * backoff to handle transient failures. See {@link #executeWithRetry} for details.
 *
 * @see BuildJobExecutionService
 * @see BuildAgentConfiguration
 */
@Lazy(false)
@Service
@Profile(PROFILE_BUILDAGENT)
@ConditionalOnProperty(prefix = "artemis.continuous-integration", name = "build-runner", havingValue = "docker", matchIfMissing = true)
public class BuildJobContainerService {

    private static final Logger log = LoggerFactory.getLogger(BuildJobContainerService.class);

    /** The characters a path inside a build container may consist of. */
    private static final Pattern SAFE_CONTAINER_PATH = Pattern.compile("[a-zA-Z0-9_*./-]+");

    /**
     * Timeout in minutes for Docker exec setup commands (mkdir, chmod, cp, etc.).
     * If a setup command does not complete within this time, an exception is thrown.
     */
    private static final int DOCKER_SETUP_TIMEOUT_MINUTES = 5;

    /**
     * Timeout in minutes for the build script execution. This is intentionally longer than setup commands
     * because build scripts may legitimately run for extended periods. The actual build timeout is enforced
     * by the outer {@code future.get(buildJobTimeoutSeconds, ...)} in {@link BuildJobManagementService};
     * this timeout serves only as a safety net to prevent permanently stuck threads if the outer timeout
     * mechanism fails.
     */
    private static final int DOCKER_BUILD_SCRIPT_TIMEOUT_MINUTES = 20;

    /**
     * Maximum number of retry attempts for tar archive operations (creation and retrieval).
     * Operations will be retried with exponential backoff before failing permanently.
     */
    private static final int MAX_TAR_OPERATION_RETRIES = 3;

    /**
     * Base delay in milliseconds for retry backoff. Actual delays are: 100ms, 200ms, 400ms (exponential).
     */
    private static final int TAR_RETRY_BASE_DELAY_MS = 100;

    /** Signal a build container is stopped with. Build containers hold nothing worth a graceful shutdown. */
    static final String BUILD_CONTAINER_STOP_SIGNAL = "SIGKILL";

    /**
     * The main process of a build container: wait until the stop file appears. It ends on SIGTERM too, because as PID 1 the shell would otherwise ignore it.
     */
    static final String MAIN_PROCESS_COMMAND = "trap 'exit 0' TERM; while [ ! -f " + LOCAL_CI_DOCKER_CONTAINER_WORKING_DIRECTORY + "/stop_container.txt ]; do sleep 0.5; done";

    private final BuildAgentConfiguration buildAgentConfiguration;

    private final BuildLogsMap buildLogsMap;

    @Value("${artemis.continuous-integration.proxies.use-system-proxy:false}")
    private boolean useSystemProxy;

    @Value("${artemis.continuous-integration.proxies.default.http-proxy:}")
    private String httpProxy;

    @Value("${artemis.continuous-integration.proxies.default.https-proxy:}")
    private String httpsProxy;

    @Value("${artemis.continuous-integration.proxies.default.no-proxy:}")
    private String noProxy;

    @Value("${artemis.continuous-integration.container-flags-limit.max-cpu-count:0}")
    private int maxCpuCount;

    @Value("${artemis.continuous-integration.container-flags-limit.max-memory:0}")
    private int maxMemory;

    @Value("${artemis.continuous-integration.container-flags-limit.max-memory-swap:0}")
    private int maxMemorySwap;

    /**
     * How many finished build containers may wait for removal while build jobs keep running. Above this, all of them are removed at once, see
     * {@link #removeFinishedContainers()}.
     */
    @Value("${artemis.continuous-integration.container-cleanup.max-finished-containers:20}")
    private int maxFinishedContainers;

    /** How often removing a finished container may fail before it is left to the scheduled container cleanup. */
    private static final int MAX_REMOVAL_ATTEMPTS = 5;

    /**
     * Finished build containers that still have to be removed, with the number of failed removal attempts so far.
     * <p>
     * Build containers are not removed automatically when they stop. Removing a container deletes its files while the container engine holds its storage locks,
     * and with Podman that blocks every container create, start and image inspect of the build jobs starting at the same time. The containers are collected here
     * instead and removed by {@link #removeFinishedContainers()} once no build job is running.
     */
    private final Map<String, Integer> finishedContainers = new ConcurrentHashMap<>();

    public BuildJobContainerService(BuildAgentConfiguration buildAgentConfiguration, BuildLogsMap buildLogsMap) {
        this.buildAgentConfiguration = buildAgentConfiguration;
        this.buildLogsMap = buildLogsMap;
    }

    /**
     * Configure a container with the Docker image, the container name, optional proxy config variables, and set the command that runs when the container starts.
     * <p>
     * If a container with the same name already exists, it is removed and the container is created again. This happens when:
     * <ul>
     * <li>A previous build job was interrupted and the container wasn't cleaned up</li>
     * <li>A re-queued job (after agent disconnect) is picked up by the same or different agent</li>
     * </ul>
     * The existing container is only looked for after the create call failed, so the usual case costs a single Docker call.
     *
     * @param containerName the name of the container to be created
     * @param image         the Docker image to use for the container
     * @param runConfig     the run config container the docker flags
     * @return {@link CreateContainerResponse} that can be used to start the container
     */
    public CreateContainerResponse configureContainer(String containerName, String image, DockerRunConfig runConfig) {
        if (!buildAgentConfiguration.isDockerAvailable()) {
            throw new LocalCIException("Docker is not available. Cannot configure container " + containerName);
        }
        // get all record fields here because we need them more than once
        List<String> exerciseEnvVars = runConfig.env();
        String network = runConfig.network();
        int cpuCount = runConfig.cpuCount();
        int memory = runConfig.memory();
        int memorySwap = runConfig.memorySwap();

        List<String> envVars = new ArrayList<>();
        if (useSystemProxy) {
            envVars.add("HTTP_PROXY=" + httpProxy);
            envVars.add("HTTPS_PROXY=" + httpsProxy);
            envVars.add("NO_PROXY=" + noProxy);
        }
        if (exerciseEnvVars != null && !exerciseEnvVars.isEmpty()) {
            envVars.addAll(exerciseEnvVars);
        }

        HostConfig defaultHostConfig = buildAgentConfiguration.hostConfig();
        HostConfig customHostConfig = defaultHostConfig;
        if (network != null || cpuCount > 0 || memory > 0 || memorySwap > 0) {
            // Use provided values if they are greater than 0 and less than the maximum values, otherwise use either the maximum values or the default values from the host config.
            long adjustedCpuCount = (cpuCount > 0) ? ((maxCpuCount > 0) ? Math.min(cpuCount, maxCpuCount) : cpuCount)
                    : (defaultHostConfig.getCpuQuota() / defaultHostConfig.getCpuPeriod());

            long adjustedMemory = (memory > 0)
                    ? ((maxMemory > 0) ? Math.min(convertMemoryFromMBToBytes(memory), convertMemoryFromMBToBytes(maxMemory)) : convertMemoryFromMBToBytes(memory))
                    : defaultHostConfig.getMemory();

            long adjustedMemorySwap = (memorySwap > 0)
                    ? ((maxMemorySwap > 0) ? Math.min(convertMemoryFromMBToBytes(memorySwap), convertMemoryFromMBToBytes(maxMemorySwap)) : convertMemoryFromMBToBytes(memorySwap))
                    : defaultHostConfig.getMemorySwap();

            customHostConfig = copyAndAdjustHostConfig(defaultHostConfig, network, adjustedCpuCount, adjustedMemory, adjustedMemorySwap);
        }

        log.debug("Set docker network to {}", customHostConfig.getNetworkMode());

        try {
            return createContainer(containerName, image, customHostConfig, envVars);
        }
        catch (ConflictException e) {
            // The name is taken. This happens when a job is re-queued after an agent disconnect (same job ID = same container name).
            // Looking for such a container before every create would cost a Docker call on each build, so it is only done here.
            log.warn("A container named '{}' already exists. Removing it and creating the container again.", containerName);
            removeExistingContainer(containerName);
            return createContainer(containerName, image, customHostConfig, envVars);
        }
    }

    private CreateContainerResponse createContainer(String containerName, String image, HostConfig hostConfig, List<String> envVars) {
        try (final var createCommand = buildAgentConfiguration.getDockerClient().createContainerCmd(image)) {
            return createCommand.withName(containerName).withHostConfig(hostConfig).withEnv(envVars)
                    // Build containers are thrown away, so nothing in them needs a graceful shutdown. Without this, stopping or force-removing a container sends SIGTERM,
                    // which the main process below ignores (it runs as PID 1), and the container engine waits its full stop timeout before sending SIGKILL.
                    .withStopSignal(BUILD_CONTAINER_STOP_SIGNAL).withStopTimeout(0)
                    // Command to run when the container starts. This is the command that will be executed in the container's main process, which runs in the foreground and blocks
                    // the container from exiting until it finishes.
                    // It waits until the script that is running the tests (see below execCreateCmdResponse) is completed, and until the result files are extracted which is
                    // indicated by the creation of a file "stop_container.txt" in the container's root directory.
                    // The trap makes the loop end on SIGTERM as well, for a stop that does not use the stop signal configured above.
                    .withEntrypoint().withCmd("sh", "-c", MAIN_PROCESS_COMMAND)
                    // .withCmd("tail", "-f", "/dev/null") // Activate for debugging purposes instead of the above command to get a running container that you can peek into using
                    // "docker exec -it <container-id> /bin/bash".
                    .exec();
        }
    }

    private HostConfig copyAndAdjustHostConfig(HostConfig defaultHostConfig, String network, long cpuCount, long memory, long memorySwap) {
        long cpuPeriod = defaultHostConfig.getCpuPeriod();
        HostConfig host = HostConfig.newHostConfig().withCpuQuota(cpuCount * cpuPeriod).withCpuPeriod(cpuPeriod).withMemory(memory).withMemorySwap(memorySwap)
                .withPidsLimit(defaultHostConfig.getPidsLimit()).withAutoRemove(false);
        // Keep the working directory tmpfs of the default configuration; only the resource limits and the network are adjusted per exercise.
        if (defaultHostConfig.getTmpFs() != null) {
            host.withTmpFs(defaultHostConfig.getTmpFs());
        }
        if (network != null && !network.trim().isBlank()) {
            host.withNetworkMode(network);
        }
        return host;
    }

    private long convertMemoryFromMBToBytes(long memory) {
        return memory * 1024L * 1024L;
    }

    /**
     * Start the container with the given ID.
     *
     * @param containerId the ID of the container to be started
     */
    public void startContainer(String containerId) {
        if (!buildAgentConfiguration.isDockerAvailable()) {
            throw new LocalCIException("Docker is not available. Cannot start container " + containerId);
        }
        try (final var startCommand = buildAgentConfiguration.getDockerClient().startContainerCmd(containerId)) {
            startCommand.exec();
        }
    }

    /**
     * Run the script in the container and wait for it to finish before returning.
     *
     * @param containerId the id of the container in which the script should be run
     * @param buildJobId  the id of the build job that is currently being executed
     * @return the exit code of the build script (0 = success, non-zero = failure)
     */
    public int runScriptInContainer(String containerId, String buildJobId) {
        log.info("Started running the build script for build job in container with id {}", containerId);
        // The build script is executed as an additional process inside the container (docker exec), independent of the container's main process.
        // The call blocks until the script finishes, so it is safe to extract results after it returns.
        // forceRoot=false: the build script runs as the container's default user (not root) for security.
        return executeDockerCommand(containerId, buildJobId, false, DOCKER_BUILD_SCRIPT_TIMEOUT_MINUTES, "bash", LOCAL_CI_DOCKER_CONTAINER_WORKING_DIRECTORY + "/script.sh");
    }

    /**
     * Moves the generated result files to a specified directory so it can easily be retrieved
     *
     * @param containerId     the id of the container which generated the files
     * @param sourcePaths     the list of paths in the container where the generated files can be found
     * @param destinationPath the path of the directory where the files shall be moved
     */
    public void moveResultsToSpecifiedDirectory(String containerId, List<String> sourcePaths, String destinationPath) {
        checkPath(destinationPath);
        String command = "shopt -s globstar && mkdir -p " + destinationPath;
        executeDockerCommand(containerId, null, true, "bash", "-c", command);

        for (String sourcePath : sourcePaths) {
            checkPath(sourcePath);
            command = "shopt -s globstar && mv " + sourcePath + " " + destinationPath;
            executeDockerCommand(containerId, null, true, "bash", "-c", command);
        }
    }

    /**
     * Retrieve an archive from a running Docker container with retry logic.
     *
     * @param containerId the id of the container.
     * @param path        the path to the file or directory to be retrieved.
     * @param buildJobId  the build job ID for logging purposes (can be null).
     * @return a {@link TarArchiveInputStream} that can be used to read the archive.
     * @throws NotFoundException if the path does not exist in the container after all retries.
     */
    public TarArchiveInputStream getArchiveFromContainer(String containerId, String path, String buildJobId) throws NotFoundException {
        log.debug("Retrieving archive from container {} at path {}", containerId, path);

        try {
            return executeWithRetry(() -> {
                try (final var copyArchiveCommand = buildAgentConfiguration.getDockerClient().copyArchiveFromContainerCmd(containerId, path)) {
                    InputStream archiveStream = copyArchiveCommand.exec();
                    if (archiveStream == null) {
                        throw new IOException("Failed to retrieve archive from container " + containerId + " at path " + path + ": exec() returned null InputStream");
                    }
                    TarArchiveInputStream result;
                    try {
                        result = new TarArchiveInputStream(archiveStream);
                    }
                    catch (Exception e) {
                        // Ensure archiveStream is closed if TarArchiveInputStream constructor fails
                        try {
                            archiveStream.close();
                        }
                        catch (IOException closeException) {
                            e.addSuppressed(closeException);
                        }
                        throw e;
                    }
                    log.debug("Successfully retrieved archive from container {} at path {}", containerId, path);
                    return result;
                }
                catch (NotFoundException e) {
                    // Don't retry NotFoundException - the path genuinely doesn't exist
                    throw e;
                }
                catch (RuntimeException e) {
                    // Wrap runtime exceptions (e.g., Docker connectivity issues) for retry handling
                    throw new IOException("Failed to retrieve archive from container: " + e.getMessage(), e);
                }
            }, "Retrieve archive from container " + containerId, buildJobId);
        }
        catch (NotFoundException e) {
            // Re-throw NotFoundException as-is (not wrapped in retry)
            throw e;
        }
        catch (IOException e) {
            // Check if the underlying cause was NotFoundException
            if (e.getCause() instanceof NotFoundException nfe) {
                throw nfe;
            }
            String errorMessage = "Could not retrieve archive from container " + containerId + " at path " + path + " after " + MAX_TAR_OPERATION_RETRIES + " attempts";
            if (DockerUtil.isDockerNotAvailable(e)) {
                log.warn("Docker is not available. {}: {}", errorMessage, e.getMessage());
            }
            else {
                log.error(errorMessage, e);
            }
            if (buildJobId != null) {
                buildLogsMap.appendBuildLogEntry(buildJobId,
                        "Failed to retrieve build results after multiple attempts. This is an infrastructure issue, not a problem with your code. Please try rerunning your build.");
            }
            throw new LocalCIException(errorMessage, e);
        }
    }

    /**
     * Stops the container with the given name by creating a file "stop_container.txt" in its root directory.
     * The container must be created in such a way that it waits for this file to appear and then stops running.
     * The stopped container is not removed right away but handed to {@link #scheduleRemoval(String)}.
     *
     * @param containerName The name of the container to stop. Cannot use the container ID, because this method might have to be called from the main thread (not the thread started
     *                          for the build job) where the container ID is not available.
     */
    public void stopContainer(String containerName) {
        Optional<ContainerLookup> container = findContainerByName(containerName);
        if (container.isEmpty()) {
            return;
        }

        String containerId = container.get().id();
        // Whether it stops now or has already stopped, the container is done and only has to be removed.
        scheduleRemoval(containerId);

        // Only a running container can react to the stop file.
        if (!container.get().isRunning()) {
            return;
        }

        log.info("Stopping container with id {}", containerId);

        // Create a file "stop_container.txt" in the root directory of the container to indicate that the test results have been extracted or that the container should be stopped
        // for some other reason.
        // The container's main process is waiting for this file to appear and then stops the main process, thus stopping and removing the container.
        try {
            executeDockerCommandWithoutAwaitingResponse(containerId, "touch", LOCAL_CI_DOCKER_CONTAINER_WORKING_DIRECTORY + "/stop_container.txt");
        }
        catch (Exception e) {
            // The container may have been stopped/removed between the state check and this exec (TOCTOU race).
            // This is expected in concurrent scenarios and not an error.
            log.warn("Could not send stop signal to container {}, it may have already stopped: {}", containerId, e.getMessage());
        }
    }

    /**
     * Kills a container in case a build job has failed, timed out or the container is unresponsive.
     * Adding a file "stop_container.txt" like in {@link #stopContainer(String)} might not work for unresponsive containers, thus the container is killed with
     * {@link DockerClient#killContainerCmd(String)}. It is killed right away instead of being stopped first: a build container holds nothing that needs a graceful
     * shutdown, and a stop only waited for its timeout, because the container's main process ignores SIGTERM.
     * <p>
     * The container is then handed to {@link #scheduleRemoval(String)}, also when it was not running (exited, or created but never started) or could not be
     * killed: the removal is forced, so it ends such a container as well.
     * <p>
     * Runs to the end even when the calling thread has been interrupted, because it is reached during cancellation and
     * stopping the container is exactly the work that still has to happen.
     *
     * @param containerId The ID of the container to kill.
     */
    public void stopUnresponsiveContainer(String containerId) {
        try (final var executor = Executors.newSingleThreadExecutor()) {
            log.warn("Killing unresponsive container with id {}", containerId);
            try {
                Future<Void> future = executor.submit(() -> {
                    try (final var killCommand = buildAgentConfiguration.getDockerClient().killContainerCmd(containerId)) {
                        killCommand.exec();
                        return null;  // Return type to match Future<Void>
                    }
                });
                future.get(10, TimeUnit.SECONDS);
                scheduleRemoval(containerId);
            }
            catch (Exception e) {
                // The interrupt status is deliberately left cleared here, which is what java:S2142 would ask to
                // restore. This method is reached during cancellation from callers that have already restored the flag
                // themselves - BuildJobManagementService.awaitTermination and the pause grace period in
                // SharedQueueProcessingService both do - and every Docker call below waits on a Future, which fails
                // immediately while the flag is set. Restoring it would abandon a running container per cancelled job
                // and leave the students' build scripts burning CPU until the hourly container cleanup reaps them.

                // e is an ExecutionException if the kill itself failed; we are interested in the underlying cause then
                Throwable cause = e instanceof ExecutionException ? e.getCause() : e;
                if (cause instanceof NotFoundException) {
                    log.debug("Container with id {} is already gone.", containerId);
                    return;
                }
                if (cause instanceof NotModifiedException || cause instanceof ConflictException) {
                    // The container is not running: it already exited, or it is stuck in the created state.
                    log.info("Container with id {} is not running. It will be removed.", containerId);
                }
                else {
                    log.error("Failed to kill container with id {}. It will be removed by force.", containerId, e);
                }
                scheduleRemoval(containerId);
            }
        }
    }

    /**
     * Hands a finished build container over for removal. It is removed by {@link #removeFinishedContainers()}, normally once no build job is running.
     *
     * @param containerId the ID of the container to remove
     */
    public void scheduleRemoval(String containerId) {
        if (containerId != null) {
            finishedContainers.putIfAbsent(containerId, 0);
        }
    }

    /**
     * Removes the finished build containers handed over by {@link #scheduleRemoval(String)}.
     * <p>
     * Removing a container deletes its files while the container engine holds its storage locks, which blocks the container creates, starts and image inspects
     * of build jobs starting at the same time. So containers are only removed while this build agent runs no build job, one at a time, checking again before each
     * one, so a job that arrives in between waits for at most one removal.
     * <p>
     * If more than {@code container-cleanup.max-finished-containers} containers are waiting, the agent has not been idle for a long time and the containers would
     * keep piling up on disk. Then all of them are removed at once, although build jobs are running.
     */
    public void removeFinishedContainers() {
        if (finishedContainers.isEmpty() || !buildAgentConfiguration.isDockerAvailable()) {
            return;
        }
        boolean removeAll = finishedContainers.size() > maxFinishedContainers;
        if (removeAll) {
            log.info("{} finished build containers are waiting for removal, more than the limit of {}. Removing all of them now, although build jobs may be running.",
                    finishedContainers.size(), maxFinishedContainers);
        }
        for (String containerId : List.copyOf(finishedContainers.keySet())) {
            if (!removeAll && !isBuildAgentIdle()) {
                log.debug("Build jobs are running. Postponing the removal of {} finished build containers.", finishedContainers.size());
                return;
            }
            removeFinishedContainer(containerId);
        }
    }

    /**
     * @return the number of finished build containers that are waiting for removal
     */
    public int getNumberOfFinishedContainers() {
        return finishedContainers.size();
    }

    /**
     * Whether this build agent currently runs no build job. A job runs on the build executor from the moment it starts until its container is stopped.
     */
    private boolean isBuildAgentIdle() {
        ThreadPoolExecutor buildExecutor = buildAgentConfiguration.getBuildExecutor();
        return buildExecutor == null || (buildExecutor.getActiveCount() == 0 && buildExecutor.getQueue().isEmpty());
    }

    private void removeFinishedContainer(String containerId) {
        long start = System.nanoTime();
        try (final var removeCommand = buildAgentConfiguration.getDockerClient().removeContainerCmd(containerId).withForce(true)) {
            removeCommand.exec();
            finishedContainers.remove(containerId);
            log.debug("Removed finished build container {} in {} ms", containerId, elapsedMillis(start));
        }
        catch (NotFoundException e) {
            finishedContainers.remove(containerId);
        }
        catch (Exception e) {
            int attempts = finishedContainers.merge(containerId, 1, Integer::sum);
            if (attempts >= MAX_REMOVAL_ATTEMPTS) {
                // The scheduled container cleanup will find it again if it is still there
                finishedContainers.remove(containerId);
                log.error("Giving up removing finished build container {} after {} attempts: {}", containerId, attempts, e.getMessage());
            }
            else {
                log.warn("Could not remove finished build container {} (attempt {} of {}), trying again later: {}", containerId, attempts, MAX_REMOVAL_ATTEMPTS, e.getMessage());
            }
        }
    }

    /**
     * Removes an existing container with the given name if it exists.
     * This is used when creating a container failed because the name is taken, which can happen when a job is re-queued after agent disconnect
     * (the job ID and thus container name remain the same).
     * <p>
     * The container is force-removed right away instead of being handed to {@link #scheduleRemoval(String)}: the new container can only be created once the
     * name is free.
     *
     * @param containerName The name of the container to remove if it exists.
     */
    private void removeExistingContainer(String containerName) {
        Optional<ContainerLookup> existingContainer = findContainerByName(containerName);
        if (existingContainer.isEmpty()) {
            return;
        }
        String containerId = existingContainer.get().id();
        log.warn("Found existing container with name '{}' (id: {}). Removing it before creating new container.", containerName, containerId);
        try (final var removeCommand = buildAgentConfiguration.getDockerClient().removeContainerCmd(containerId).withForce(true)) {
            removeCommand.exec();
        }
        catch (NotFoundException e) {
            log.debug("Container {} was removed in the meantime", containerId);
        }
        finishedContainers.remove(containerId);
    }

    /**
     * Get the ID of a container by its name, if the container still belongs to a running build job.
     * <p>
     * A container counts as such while it has been created but not started yet, or is running. An exited container does not: its build is over, whether or not the
     * container itself has been removed yet.
     *
     * @param containerName The name of the container.
     * @return The ID of the container, or null if no active container with the given name was found.
     */
    public String getIDOfRunningContainer(String containerName) {
        return findContainerByName(containerName).filter(ContainerLookup::isActive).map(ContainerLookup::id).orElse(null);
    }

    /**
     * Functional interface for operations that can throw IOException and need retry logic.
     *
     * @param <T> the return type of the operation
     */
    @FunctionalInterface
    private interface IOOperation<T> {

        T execute() throws IOException;
    }

    /**
     * Executes an IO operation with retry logic and exponential backoff.
     * This method will retry the operation up to MAX_TAR_OPERATION_RETRIES times with delays of 100ms, 200ms, 400ms.
     *
     * @param <T>           the return type of the operation
     * @param operation     the operation to execute
     * @param operationName a descriptive name for the operation (used in logging)
     * @param buildJobId    the build job ID for logging to build logs (can be null)
     * @return the result of the operation
     * @throws IOException if all retry attempts fail
     */
    private <T> T executeWithRetry(IOOperation<T> operation, String operationName, String buildJobId) throws IOException {
        IOException lastException = null;

        for (int attempt = 1; attempt <= MAX_TAR_OPERATION_RETRIES; attempt++) {
            try {
                T result = operation.execute();
                if (attempt > 1) {
                    log.info("{} succeeded on attempt {} of {}", operationName, attempt, MAX_TAR_OPERATION_RETRIES);
                    if (buildJobId != null) {
                        buildLogsMap.appendBuildLogEntry(buildJobId, operationName + " succeeded after " + attempt + " attempt(s).");
                    }
                }
                return result;
            }
            catch (IOException e) {
                lastException = e;
                log.warn("{} failed on attempt {} of {}: {} - {}", operationName, attempt, MAX_TAR_OPERATION_RETRIES, e.getClass().getSimpleName(), e.getMessage());

                if (attempt < MAX_TAR_OPERATION_RETRIES) {
                    int delayMs = TAR_RETRY_BASE_DELAY_MS * (1 << (attempt - 1)); // Exponential backoff: 100, 200, 400
                    log.debug("Retrying {} in {}ms...", operationName, delayMs);
                    if (buildJobId != null) {
                        buildLogsMap.appendBuildLogEntry(buildJobId, "Retrying " + operationName + " (attempt " + (attempt + 1) + " of " + MAX_TAR_OPERATION_RETRIES + ")...");
                    }
                    try {
                        Thread.sleep(delayMs);
                    }
                    catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new IOException("Interrupted while waiting to retry " + operationName, ie);
                    }
                }
            }
        }

        // All retries exhausted — callers handle Docker-unavailability vs. generic error logging
        throw lastException;
    }

    /**
     * Copies the build input archive into the container's working directory with a single upload.
     * <p>
     * The archive already contains every repository at its final checkout path and the executable build script
     * (see {@link de.tum.cit.aet.artemis.buildagent.service.runner.BuildInputArchiveService}), so no setup command has to run inside the container.
     * Each such command is a separate exec, which is expensive on Podman. The upload is retried to handle transient failures (e.g. Docker connectivity issues).
     *
     * @param containerId  the Docker container ID to copy the archive into
     * @param inputArchive the tar archive whose paths are relative to the container's working directory
     * @param buildJobId   the build job ID for logging purposes
     * @throws LocalCIException if the copy operation fails after all retry attempts
     */
    public void copyInputArchiveToContainer(String containerId, Path inputArchive, String buildJobId) {
        log.debug("Copying input archive {} to container {}", inputArchive, containerId);

        try {
            executeWithRetry(() -> {
                try (final var uploadStream = Files.newInputStream(inputArchive);
                        final var copyToContainerCommand = buildAgentConfiguration.getDockerClient().copyArchiveToContainerCmd(containerId)
                                .withRemotePath(LOCAL_CI_DOCKER_CONTAINER_WORKING_DIRECTORY).withTarInputStream(uploadStream)) {
                    long uploadStart = System.nanoTime();
                    copyToContainerCommand.exec();
                    log.debug("Successfully copied input archive to container {} in {} ms", containerId, elapsedMillis(uploadStart));
                }
                catch (RuntimeException e) {
                    // Wrap runtime exceptions (e.g., Docker connectivity issues) for retry handling
                    throw new IOException("Failed to copy archive to container: " + e.getMessage(), e);
                }
                return null;
            }, "Copy to container " + containerId, buildJobId);
        }
        catch (IOException e) {
            String errorMessage = "Could not copy input archive " + inputArchive + " to container " + containerId + " after " + MAX_TAR_OPERATION_RETRIES + " attempts";
            if (DockerUtil.isDockerNotAvailable(e)) {
                log.warn("Docker is not available. {}: {}", errorMessage, e.getMessage());
            }
            else {
                log.error(errorMessage, e);
            }
            buildLogsMap.appendBuildLogEntry(buildJobId,
                    "Failed to copy files to build container after multiple attempts. This is an infrastructure issue, not a problem with your code. Please try rerunning your build.");
            throw new LocalCIException(errorMessage, e);
        }
    }

    /**
     * Executes a command in a Docker container without waiting for it to complete (fire-and-forget).
     * <p>
     * This is used for commands where we don't need the result, such as creating the stop signal file.
     * The command is executed in detached mode.
     *
     * @param containerId the Docker container ID
     * @param command     the command and arguments to execute
     */
    private void executeDockerCommandWithoutAwaitingResponse(String containerId, String... command) {
        DockerClient dockerClient = buildAgentConfiguration.getDockerClient();
        try (final var createCommand = dockerClient.execCreateCmd(containerId).withCmd(command)) {
            final var createCommandResponse = createCommand.exec();
            dockerClient.execStartCmd(createCommandResponse.getId()).withDetach(true).exec(new ResultCallback.Adapter<>());
        }
    }

    /**
     * Executes a command in a Docker container and waits for it to complete.
     * <p>
     * This method is the core Docker command execution mechanism. It always attaches to stdout/stderr
     * and runs synchronously (non-detached), ensuring each command completes before control returns.
     * This prevents race conditions where subsequent commands or the build script could start before
     * container setup operations (mkdir, cp, chmod) have finished.
     * <p>
     * If {@code buildJobId} is provided, command output is captured and stored in the build logs
     * via {@link BuildLogsMap}.
     *
     * @param containerId the Docker container ID
     * @param buildJobId  the build job ID for logging (can be null if output capture not needed)
     * @param forceRoot   if true, execute the command as root user
     * @param command     the command and arguments to execute
     * @return the exit code of the command (0 = success, non-zero = failure)
     * @throws LocalCIException if the command execution fails or is interrupted
     */
    private int executeDockerCommand(String containerId, String buildJobId, boolean forceRoot, String... command) {
        return executeDockerCommand(containerId, buildJobId, forceRoot, DOCKER_SETUP_TIMEOUT_MINUTES, command);
    }

    private int executeDockerCommand(String containerId, String buildJobId, boolean forceRoot, int timeoutMinutes, String... command) {
        DockerClient dockerClient = buildAgentConfiguration.getDockerClient();
        long execStart = System.nanoTime();
        try (var execCreateCommandTemp = dockerClient.execCreateCmd(containerId).withAttachStdout(true).withAttachStderr(true).withCmd(command)) {
            final var execCreateCommand = forceRoot ? execCreateCommandTemp.withUser("root") : execCreateCommandTemp;
            ExecCreateCmdResponse execCreateCmdResponse = execCreateCommand.exec();
            final String execId = execCreateCmdResponse.getId();
            final CountDownLatch latch = new CountDownLatch(1);
            final AtomicReference<Throwable> errorRef = new AtomicReference<>();
            try {
                // IMPORTANT: withDetach(false) is critical here. It ensures the Docker daemon keeps the HTTP response stream open
                // until the exec process finishes inside the container, so onComplete/onError fires only after the command has
                // actually completed. Without this (i.e. withDetach(true)), the call returns immediately while the process may still
                // be running in the container, causing race conditions where subsequent commands (e.g. the build script) start before
                // setup commands (mkdir, chmod, cp) have finished.
                dockerClient.execStartCmd(execId).withDetach(false).exec(new ResultCallback.Adapter<>() {

                    @Override
                    public void onNext(Frame item) {
                        String payload = new String(item.getPayload());
                        String[] logLines = splitBehindNewLines(payload);
                        ZonedDateTime now = ZonedDateTime.now();

                        if (buildJobId == null) {
                            return;
                        }
                        for (String line : logLines) {
                            if (line.isEmpty()) {
                                continue;
                            }
                            BuildLogDTO buildLogEntry = new BuildLogDTO(now, line);
                            buildLogsMap.appendBuildLogEntry(buildJobId, buildLogEntry);
                        }
                    }

                    @Override
                    public void onComplete() {
                        latch.countDown();
                    }

                    @Override
                    public void onError(Throwable throwable) {
                        log.error("Error while executing Docker command: {} on container {}", String.join(" ", command), containerId, throwable);
                        errorRef.set(throwable);
                        latch.countDown();
                    }
                });
            }
            catch (ConflictException e) {
                throw new LocalCIException("Could not execute Docker command: " + String.join(" ", command), e);
            }

            try {
                boolean completed = latch.await(timeoutMinutes, TimeUnit.MINUTES);
                if (!completed) {
                    throw new LocalCIException("Docker command timed out after " + timeoutMinutes + " minutes: " + String.join(" ", command));
                }
            }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new LocalCIException("Interrupted while executing Docker command: " + String.join(" ", command), e);
            }

            Throwable execError = errorRef.get();
            if (execError != null) {
                throw new LocalCIException("Docker command failed: " + String.join(" ", command), execError);
            }

            try (var inspectCmd = dockerClient.inspectExecCmd(execId)) {
                InspectExecResponse inspectResponse = inspectCmd.exec();
                Long exitCode = inspectResponse.getExitCodeLong();
                log.debug("Docker command '{}' in container {} took {} ms", String.join(" ", command), containerId, elapsedMillis(execStart));
                return (exitCode != null) ? exitCode.intValue() : -1;
            }
        }
    }

    /**
     * Validates a file path to prevent path traversal attacks and injection.
     * <p>
     * Rejects paths that:
     * <ul>
     * <li>Are null</li>
     * <li>Contain ".." (parent directory traversal)</li>
     * <li>Contain characters outside the allowed set (alphanumeric, underscore, asterisk, dot, slash, hyphen)</li>
     * </ul>
     *
     * @param path the path to validate
     * @throws LocalCIException if the path is invalid or potentially malicious
     */
    private void checkPath(String path) {
        if (path == null || path.contains("..") || !SAFE_CONTAINER_PATH.matcher(path).matches()) {
            throw new LocalCIException("Invalid path: " + path);
        }
    }

    /**
     * The ID and state of a build container, as far as the callers of {@link #findContainerByName} need them.
     *
     * @param id    the container ID
     * @param state the container state as reported by Docker, e.g. {@code created}, {@code running} or {@code exited}; null if unknown
     */
    private record ContainerLookup(String id, String state) {

        /** States in which the container still belongs to a running build job. */
        private static final Set<String> ACTIVE_STATES = Set.of("created", "running", "paused", "restarting");

        boolean isRunning() {
            return "running".equals(state);
        }

        boolean isActive() {
            return state != null && ACTIVE_STATES.contains(state);
        }
    }

    /**
     * Finds a Docker container by its name.
     * <p>
     * The container is inspected by name instead of being picked out of a list of all containers. Listing makes the container engine look at every container and,
     * with Podman, wait for each container's lock, so a single container being removed held up every lookup. Inspecting only touches the one container.
     * Returns an empty Optional if the container is not found or if Docker is not available.
     *
     * @param containerName the container name
     * @return the ID and state of the container if found
     */
    private Optional<ContainerLookup> findContainerByName(String containerName) {
        // When Docker is temporarily unavailable, we skip the container lookup.
        // Any containers left behind will be cleaned up by the scheduled cleanUpContainers() task
        // in BuildAgentDockerService once Docker becomes available again.
        if (!buildAgentConfiguration.isDockerAvailable()) {
            log.debug("Docker is not available. Cannot get container for name {}", containerName);
            return Optional.empty();
        }
        try (final var inspectCommand = buildAgentConfiguration.getDockerClient().inspectContainerCmd(containerName)) {
            InspectContainerResponse container = inspectCommand.exec();
            String state = container.getState() != null ? container.getState().getStatus() : null;
            return Optional.of(new ContainerLookup(container.getId(), state));
        }
        catch (NotFoundException e) {
            return Optional.empty();
        }
        catch (Exception ex) {
            if (DockerUtil.isDockerNotAvailable(ex)) {
                log.debug("Docker is not available: {}", ex.getMessage());
            }
            else {
                log.error("Failed to get container for name {}: {}", containerName, ex.getMessage());
            }
            return Optional.empty();
        }
    }

    private static long elapsedMillis(long startNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
    }

    /**
     * Splits a string by newlines while preserving the newline characters at the end of each segment.
     * <p>
     * Uses a look-behind regex pattern to split after (not before) newline characters.
     * This preserves the newlines in the output, which is useful for log formatting.
     *
     * @param input the string to split
     * @return an array of strings, each potentially ending with a newline character
     */
    private String[] splitBehindNewLines(String input) {
        String newlineLookBehindPattern = "(?<=\\R)";
        return input.split(newlineLookBehindPattern);
    }
}
