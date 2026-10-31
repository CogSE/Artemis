package de.tum.cit.aet.artemis.programming.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import de.tum.cit.aet.artemis.core.service.distributed.api.DistributedDataProvider;
import de.tum.cit.aet.artemis.core.service.messaging.InstanceMessageSendService;
import de.tum.cit.aet.artemis.exercise.repository.MilestoneExerciseGroupRepository;
import de.tum.cit.aet.artemis.exercise.service.ParticipationDeletionService;
import de.tum.cit.aet.artemis.localci.service.ci.ContinuousIntegrationService;
import de.tum.cit.aet.artemis.localvc.service.RepositoryVcsAccessTokenService;
import de.tum.cit.aet.artemis.programming.domain.MilestoneExercise;
import de.tum.cit.aet.artemis.programming.domain.ProgrammingExercise;
import de.tum.cit.aet.artemis.programming.domain.UserStoryExercise;
import de.tum.cit.aet.artemis.programming.test_repository.ProgrammingExerciseTaskTestRepository;
import de.tum.cit.aet.artemis.programming.test_repository.ProgrammingExerciseTestRepository;

/**
 * A user story stores the repository URIs of its milestone, so deleting it must leave those repositories in place.
 */
@ExtendWith(MockitoExtension.class)
class ProgrammingExerciseDeletionServiceTest {

    private static final long EXERCISE_ID = 1L;

    private static final long MILESTONE_EXERCISE_ID = 2L;

    @Mock
    private ProgrammingExerciseRepositoryService programmingExerciseRepositoryService;

    @Mock
    private ProgrammingExerciseTestRepository programmingExerciseRepository;

    @Mock
    private ParticipationDeletionService participationDeletionService;

    @Mock
    private ContinuousIntegrationService continuousIntegrationService;

    @Mock
    private InstanceMessageSendService instanceMessageSendService;

    @Mock
    private ProgrammingExerciseTaskTestRepository programmingExerciseTaskRepository;

    @Mock
    private RepositoryVcsAccessTokenService repositoryVcsAccessTokenService;

    @Mock
    private DistributedDataProvider distributedDataProvider;

    @Mock
    private MilestoneExerciseGroupRepository milestoneExerciseGroupRepository;

    @Mock
    private MilestoneExercisePointsService milestoneExercisePointsService;

    private ProgrammingExerciseDeletionService programmingExerciseDeletionService;

    @BeforeEach
    void setUp() {
        programmingExerciseDeletionService = new ProgrammingExerciseDeletionService(programmingExerciseRepositoryService, programmingExerciseRepository,
                participationDeletionService, Optional.of(continuousIntegrationService), instanceMessageSendService, programmingExerciseTaskRepository,
                repositoryVcsAccessTokenService, Optional.empty(), distributedDataProvider, milestoneExerciseGroupRepository, milestoneExercisePointsService);
    }

    @ParameterizedTest
    @ValueSource(booleans = { true, false })
    void deletingAUserStoryKeepsTheRepositoriesOfItsMilestone(boolean deleteBaseReposBuildPlans) {
        stubExercise(new UserStoryExercise());
        when(milestoneExerciseGroupRepository.findMilestoneExerciseIdByUserStoryExerciseId(EXERCISE_ID)).thenReturn(Optional.of(MILESTONE_EXERCISE_ID));

        programmingExerciseDeletionService.delete(EXERCISE_ID, deleteBaseReposBuildPlans);

        verify(programmingExerciseRepositoryService, never()).deleteRepositories(any());
        verify(programmingExerciseRepositoryService, never()).deleteLocalRepoCopies(any());
        verify(programmingExerciseRepository).deleteById(EXERCISE_ID);
        verify(milestoneExercisePointsService).syncMaxPoints(MILESTONE_EXERCISE_ID);
    }

    @Test
    void deletingAMilestoneDeletesItsRepositories() {
        MilestoneExercise milestoneExercise = new MilestoneExercise();
        stubExercise(milestoneExercise);

        programmingExerciseDeletionService.delete(EXERCISE_ID, true);

        verify(programmingExerciseRepositoryService).deleteRepositories(milestoneExercise);
        verify(programmingExerciseRepositoryService).deleteLocalRepoCopies(milestoneExercise);
        verify(programmingExerciseRepository).deleteById(EXERCISE_ID);
    }

    private void stubExercise(ProgrammingExercise exercise) {
        exercise.setId(EXERCISE_ID);
        when(programmingExerciseRepository.findWithTemplateAndSolutionParticipationCategoriesById(EXERCISE_ID)).thenReturn(Optional.of(exercise));
        when(programmingExerciseRepository.findByIdWithStudentParticipationsAndSubmissionsElseThrow(EXERCISE_ID)).thenReturn(exercise);
    }
}
