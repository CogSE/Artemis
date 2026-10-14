package de.tum.cit.aet.artemis.programming.service;

import static de.tum.cit.aet.artemis.core.config.Constants.PROFILE_CORE;

import java.util.List;

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
     * @return the summed pair
     */
    public UserStoryEffortDTO findForUser(long exerciseId, User user) {
        StudentParticipation participation = resolveOwnParticipationElseThrow(exerciseId, user);
        return userStoryTaskRepository.sumEffortByParticipationId(participation.getId());
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
     * Resolves the participation the user reports on, rejecting anything that is not the user's own participation in
     * a user story exercise.
     *
     * @param exerciseId the id of the exercise, which must be a {@link UserStoryExercise}
     * @param user       the requesting user
     * @return the user's (or their team's) participation in that exercise
     */
    private StudentParticipation resolveOwnParticipationElseThrow(long exerciseId, User user) {
        Exercise exercise = exerciseRepository.findByIdElseThrow(exerciseId);
        if (!(exercise instanceof UserStoryExercise)) {
            throw new BadRequestAlertException("Effort can only be reported for a user story exercise", ENTITY_NAME, "notUserStoryExercise");
        }
        // Team-aware: for a team exercise this resolves the team's single participation, so its members share one pair.
        StudentParticipation participation = participationService.findOneByExerciseAndStudentAnyState(exercise, user)
                .orElseThrow(() -> new BadRequestAlertException("The exercise has to be started before its effort can be reported", ENTITY_NAME, "participationMissing"));
        // Belt and braces: the lookup above is already scoped to this user, so this only ever fires if that changes.
        participationAuthorizationCheckService.checkCanAccessParticipationElseThrow(participation);
        return participation;
    }
}
