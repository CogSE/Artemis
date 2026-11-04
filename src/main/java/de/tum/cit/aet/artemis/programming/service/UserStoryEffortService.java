package de.tum.cit.aet.artemis.programming.service;

import static de.tum.cit.aet.artemis.core.config.Constants.PROFILE_CORE;

import java.util.List;
import java.util.Optional;

import org.springframework.context.annotation.Lazy;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import de.tum.cit.aet.artemis.account.domain.User;
import de.tum.cit.aet.artemis.core.exception.BadRequestAlertException;
import de.tum.cit.aet.artemis.exercise.domain.Exercise;
import de.tum.cit.aet.artemis.exercise.domain.participation.StudentParticipation;
import de.tum.cit.aet.artemis.exercise.repository.ExerciseRepository;
import de.tum.cit.aet.artemis.exercise.service.ParticipationAuthorizationCheckService;
import de.tum.cit.aet.artemis.exercise.service.ParticipationService;
import de.tum.cit.aet.artemis.programming.domain.UserStoryExercise;
import de.tum.cit.aet.artemis.programming.dto.UserStoryEffortDTO;
import de.tum.cit.aet.artemis.programming.dto.UserStoryEffortStatusDTO;
import de.tum.cit.aet.artemis.programming.repository.UserStoryTaskRepository;

/**
 * Reads the effort a participant has reported for a {@link UserStoryExercise}: the sum of their board's tasks'
 * estimated and actual effort (see {@code UserStoryTaskRepository#sumEffortByParticipationId}).
 * <p>
 * Read-only - a participant no longer reports this pair by hand, so there is nothing here to write.
 */
@Profile(PROFILE_CORE)
@Lazy
@Service
public class UserStoryEffortService {

    private static final String ENTITY_NAME = "userStoryEffort";

    private final UserStoryTaskRepository userStoryTaskRepository;

    private final ExerciseRepository exerciseRepository;

    private final ParticipationService participationService;

    private final ParticipationAuthorizationCheckService participationAuthorizationCheckService;

    public UserStoryEffortService(UserStoryTaskRepository userStoryTaskRepository, ExerciseRepository exerciseRepository, ParticipationService participationService,
            ParticipationAuthorizationCheckService participationAuthorizationCheckService) {
        this.userStoryTaskRepository = userStoryTaskRepository;
        this.exerciseRepository = exerciseRepository;
        this.participationService = participationService;
        this.participationAuthorizationCheckService = participationAuthorizationCheckService;
    }

    /**
     * The effort summed from the user's board for the story.
     *
     * @param exerciseId the id of the user story exercise
     * @param user       the requesting user
     * @return the summed pair; nothing estimated and nothing logged if the user never started the story graded - a
     *         student who only practices it has no board to sum, and that must not be an error
     */
    public UserStoryEffortDTO findForUser(long exerciseId, User user) {
        return findOwnGradedParticipation(exerciseId, user).map(participation -> userStoryTaskRepository.sumEffortByParticipationId(participation.getId()))
                .orElseGet(() -> new UserStoryEffortDTO(0.0, null));
    }

    /**
     * Every user story in the course the user has started, with the effort summed from its board. Serves the
     * exercise overview's "no tasks yet" marker in one request.
     *
     * @param courseId the course to report on
     * @param user     the requesting user
     * @return one entry per started story
     */
    public List<UserStoryEffortStatusDTO> findAllForCourse(long courseId, User user) {
        return userStoryTaskRepository.findAllStartedStoriesByCourseIdAndStudentLogin(courseId, user.getLogin());
    }

    /**
     * Finds the user's own graded participation the effort is reported on, rejecting any exercise that is not a user
     * story exercise.
     *
     * @param exerciseId the id of the exercise, which must be a {@link UserStoryExercise}
     * @param user       the requesting user
     * @return the user's (or their team's) graded participation in that exercise, or empty if there is none
     */
    private Optional<StudentParticipation> findOwnGradedParticipation(long exerciseId, User user) {
        Exercise exercise = exerciseRepository.findByIdElseThrow(exerciseId);
        if (!(exercise instanceof UserStoryExercise)) {
            throw new BadRequestAlertException("Effort can only be reported for a user story exercise", ENTITY_NAME, "notUserStoryExercise");
        }
        // The graded participation: a practice participation has no board of its own. Team-aware: for a team exercise this
        // resolves the team's single participation, so its members share one pair.
        Optional<StudentParticipation> participation = participationService.findOneGradedByExerciseAndStudentAnyState(exercise, user);
        // Belt and braces: the lookup above is already scoped to this user, so this only ever fires if that changes.
        participation.ifPresent(participationAuthorizationCheckService::checkCanAccessParticipationElseThrow);
        return participation;
    }
}
