package de.tum.cit.aet.artemis.programming.repository;

import static de.tum.cit.aet.artemis.core.config.Constants.PROFILE_CORE;

import java.util.Collection;
import java.util.List;

import org.springframework.context.annotation.Lazy;
import org.springframework.context.annotation.Profile;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import de.tum.cit.aet.artemis.core.repository.base.ArtemisJpaRepository;
import de.tum.cit.aet.artemis.programming.domain.UserStoryTask;
import de.tum.cit.aet.artemis.programming.dto.UserStoryEffortDTO;
import de.tum.cit.aet.artemis.programming.dto.UserStoryEffortStatusDTO;

/**
 * Spring Data JPA repository for {@link UserStoryTask}, the tasks a participant creates for themself while working
 * on a {@code UserStoryExercise}.
 */
@Profile(PROFILE_CORE)
@Lazy
@Repository
public interface UserStoryTaskRepository extends ArtemisJpaRepository<UserStoryTask, Long> {

    /**
     * Every task on one participation's board, in the participant's own manual order.
     *
     * @param participationId the id of the participation
     * @return the tasks created on that participation's board
     */
    List<UserStoryTask> findAllByParticipationIdOrderByOrderIndexAsc(long participationId);

    /**
     * Every task on several participations' boards at once, each board in the participant's own manual order. Used by
     * the milestone assessment to show a tutor one student's boards for all stories of a group in a single query.
     *
     * @param participationIds the ids of the participations
     * @return the tasks created on those participations' boards
     */
    List<UserStoryTask> findAllByParticipationIdInOrderByOrderIndexAsc(Collection<Long> participationIds);

    /**
     * How many tasks are already on a participation's board - used to append a newly created one at the end.
     *
     * @param participationId the id of the participation
     * @return the number of tasks on that participation's board
     */
    long countByParticipationId(long participationId);

    /**
     * The story-level effort for one participation's board: the sum of every task's estimated effort, and the sum of
     * whatever actual effort has been logged so far.
     * <p>
     * {@code estimatedEffortHours} is a required field on every task, so summing it is never {@code null} - it is
     * {@code 0} for a board with no tasks yet. {@code actualEffortHours} is optional per task, and {@code SUM} already
     * ignores {@code NULL} rows and itself returns {@code NULL} when every task's value is unset (or there are no
     * tasks at all), which is exactly "nothing logged yet" - no extra handling needed to tell that apart from a real
     * {@code 0}.
     *
     * @param participationId the id of the participation
     * @return the summed pair
     */
    @Query("""
            SELECT new de.tum.cit.aet.artemis.programming.dto.UserStoryEffortDTO(COALESCE(SUM(task.estimatedEffortHours), 0.0), SUM(task.actualEffortHours))
            FROM UserStoryTask task
            WHERE task.participation.id = :participationId
            """)
    UserStoryEffortDTO sumEffortByParticipationId(@Param("participationId") long participationId);

    /**
     * Every user story in the course that the requesting participant has started, with the story-level effort summed
     * from its board.
     * <p>
     * One query for the whole course overview: computing the sums as correlated subqueries per participation avoids a
     * join against {@code UserStoryTask} that would otherwise require a {@code GROUP BY} over every other selected
     * column.
     * <p>
     * Matches the participant through either an individual participation or team membership.
     *
     * @param courseId the course whose stories to report on
     * @param login    the login of the participating student
     * @return one entry per started story
     */
    @Query("""
            SELECT new de.tum.cit.aet.artemis.programming.dto.UserStoryEffortStatusDTO(
                participation.exercise.id,
                COALESCE((SELECT SUM(task.estimatedEffortHours) FROM UserStoryTask task WHERE task.participation.id = participation.id), 0.0),
                (SELECT SUM(task.actualEffortHours) FROM UserStoryTask task WHERE task.participation.id = participation.id)
            )
            FROM StudentParticipation participation
                LEFT JOIN participation.team.students teamStudent
            WHERE participation.exercise.course.id = :courseId
                AND TYPE(participation.exercise) = UserStoryExercise
                AND (participation.student.login = :login OR teamStudent.login = :login)
            """)
    List<UserStoryEffortStatusDTO> findAllStartedStoriesByCourseIdAndStudentLogin(@Param("courseId") long courseId, @Param("login") String login);
}
