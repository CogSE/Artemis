package de.tum.cit.aet.artemis.buildagent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

import com.github.dockerjava.api.model.HostConfig;

import de.tum.cit.aet.artemis.core.config.ProgrammingLanguageConfiguration;

class BuildAgentConfigurationHostConfigTest {

    private final ProgrammingLanguageConfiguration programmingLanguageConfiguration = mock(ProgrammingLanguageConfiguration.class);

    private final BuildAgentConfiguration configuration = new BuildAgentConfiguration(programmingLanguageConfiguration);

    @BeforeEach
    void setUp() {
        // getDefaultDockerFlags() returns the values quoted, as they are passed on the docker command line
        when(programmingLanguageConfiguration.getDefaultDockerFlags())
                .thenReturn(List.of("--cpus", "\"2\"", "--memory", "\"2g\"", "--memory-swap", "\"2g\"", "--pids-limit", "\"1000\""));
    }

    @Test
    void tmpfsOptionsMountTmpfsOverWorkingDirectory() {
        ReflectionTestUtils.setField(configuration, "buildContainerTmpfsOptions", " rw,exec,size=2g ");

        HostConfig hostConfig = configuration.hostConfig();

        assertThat(hostConfig.getTmpFs()).containsExactlyEntriesOf(Map.of("/var/tmp", "rw,exec,size=2g"));
        assertThat(hostConfig.getCpuQuota()).isEqualTo(200000L);
        // Finished containers are removed by the build agent while it is idle, not by the container engine when they stop
        assertThat(hostConfig.getAutoRemove()).isFalse();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = { "  " })
    void missingTmpfsOptionsMountNoTmpfs(String options) {
        ReflectionTestUtils.setField(configuration, "buildContainerTmpfsOptions", options);

        assertThat(configuration.hostConfig().getTmpFs()).isNull();
    }
}
