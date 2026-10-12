package de.tum.cit.aet.artemis.exercise.repository;

import static de.tum.cit.aet.artemis.core.config.Constants.PROFILE_CORE;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.springframework.context.annotation.Lazy;
import org.springframework.context.annotation.Profile;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import de.tum.cit.aet.artemis.calendar.dto.NonQuizExerciseCalendarEventDTO;
import de.tum.cit.aet.artemis.core.exception.EntityNotFoundException;
import de.tum.cit.aet.artemis.core.repository.base.ArtemisJpaRepository;
import de.tum.cit.aet.artemis.exercise.domain.MilestoneExerciseGroup;
import de.tum.cit.aet.artemis.exercise.dto.MilestoneGroupAnchorDTO;
import de.tum.cit.aet.artemis.exercise.dto.MilestoneScoreTargetDTO;
import de.tum.cit.aet.artemis.programming.domain.MilestoneExercise;

/**
 * Spring Data JPA repository for {@link MilestoneExerciseGroup}, deliberately separate from
 * {@link ExerciseVariantGroupRepository}.
 * <p>
 * Every query here is rooted at {@code MilestoneExerciseGroup} rather than at the {@code ExerciseVariantGroup} base type.
 * That is the whole point of a dedicated repository: {@link MilestoneExerciseGroup#getMilestoneExercise()} is declared on
 * the subtype, and reaching it from the base type would need {@code TREAT(evg AS MilestoneExerciseGroup).milestoneExercise},
 * which restricts the <em>query</em> to that subtype instead of only the join - even in a {@code LEFT JOIN FETCH}. Rooting
 * at the subtype fetches the anchor with an ordinary join and leaves the variant-group queries untouched.
 * <p>
 * The {@code Course → ExerciseVariantGroup} relationship is unidirectional (the {@code course_id} foreign key lives on the
 * {@code exercise_variant_group} table but is owned by the {@code Course} collection), so the group has no {@code course}
 * attribute and course-scoped lookups go through a subquery over that collection.
 */
@Profile(PROFILE_CORE)
@Lazy
@Repository
public interface MilestoneExerciseGroupRepository extends ArtemisJpaRepository<MilestoneExerciseGroup, Long> {

    /**
     * Loads all milestone groups of a course, each with its members and its anchor milestone exercise.
     * <p>
     * The anchor fetch is required, not an optimization: the group's timeline getters delegate to it and
     * {@code spring.jpa.open-in-view} is disabled, so an unfetched proxy would read as "no dates".
     *
     * @param courseId the id of the course whose milestone groups to load
     * @return the course's milestone groups
     */
    @Query("""
            SELECT DISTINCT g
            FROM MilestoneExerciseGroup g
                LEFT JOIN FETCH g.exercises
                LEFT JOIN FETCH g.milestoneExercise
            WHERE g.id IN (
                SELECT evg.id
                FROM Course c
                    JOIN c.exerciseVariantGroups evg
                WHERE c.id = :courseId
            )
            """)
    List<MilestoneExerciseGroup> findAllByCourseId(@Param("courseId") Long courseId);

    /**
     * Fetches the data needed to derive calendar events for every milestone group of a course, one {@link NonQuizExerciseCalendarEventDTO} per group.
     * <p>
     * Keyed by the anchor {@link MilestoneExercise}'s id, not the group's own id: the generated calendar event ids
     * (e.g. {@code "exerciseDueEvent-" + originEntityId}) must not collide with an unrelated exercise's id, and the
     * group id comes from the unrelated {@code exercise_variant_group} sequence while the anchor's id shares the
     * {@code Exercise} table's id space with every other exercise a course's calendar events are derived from.
     * <p>
     * The dates are read off the anchor rather than off any column of the group itself - a milestone group has none of
     * its own; its timeline getters delegate to {@link MilestoneExerciseGroup#getMilestoneExercise()} (see
     * {@link MilestoneExerciseGroup}) - while the title is read off the group rather than the anchor, because the
     * anchor is never rendered to students; the group is what they see (see {@code CourseOverviewExerciseService}).
     *
     * @param courseId the id of the course whose milestone groups to fetch calendar event data for
     * @return one DTO per milestone group of the course that has an anchor exercise
     */
    @Query("""
            SELECT NEW de.tum.cit.aet.artemis.calendar.dto.NonQuizExerciseCalendarEventDTO(
                g.milestoneExercise.id,
                de.tum.cit.aet.artemis.calendar.util.CalendarEventType.PROGRAMMING_EXERCISE,
                g.title,
                g.milestoneExercise.releaseDate,
                g.milestoneExercise.startDate,
                g.milestoneExercise.dueDate,
                g.milestoneExercise.assessmentDueDate
            )
            FROM MilestoneExerciseGroup g
            WHERE g.milestoneExercise IS NOT NULL
                AND g.id IN (
                    SELECT evg.id
                    FROM Course c
                        JOIN c.exerciseVariantGroups evg
                    WHERE c.id = :courseId
                )
            """)
    Set<NonQuizExerciseCalendarEventDTO> getCalendarEventDTOsForCourseId(@Param("courseId") long courseId);

    /**
     * Single-group counterpart of {@link #findAllByCourseId}. Empty for an ExerciseVariantGroup that is not a milestone group, which is served by
     * {@link ExerciseVariantGroupRepository} instead.
     *
     * @param groupId  the id of the milestone group to load
     * @param courseId the id of the course the group must belong to
     * @return the matching milestone group with its members and anchor initialized, or empty
     */
    @Query("""
            SELECT DISTINCT g
            FROM MilestoneExerciseGroup g
                LEFT JOIN FETCH g.exercises
                LEFT JOIN FETCH g.milestoneExercise
            WHERE g.id = :groupId
                AND g.id IN (
                    SELECT evg.id
                    FROM Course c
                        JOIN c.exerciseVariantGroups evg
                    WHERE c.id = :courseId
                )
            """)
    Optional<MilestoneExerciseGroup> findByIdAndCourseId(@Param("groupId") Long groupId, @Param("courseId") Long courseId);

    default MilestoneExerciseGroup findByIdAndCourseIdElseThrow(Long groupId, Long courseId) throws EntityNotFoundException {
        return getValueElseThrow(findByIdAndCourseId(groupId, courseId), groupId);
    }

    /**
     * Like {@link #findByIdAndCourseId}, but additionally fetches the anchor exercise's own {@code templateParticipation}
     * and {@code solutionParticipation} (each a further {@code LAZY @OneToOne}) - needed by the call sites that copy the
     * anchor's repository URIs onto a member exercise (its build config is a row of its own, read separately), since
     * {@code spring.jpa.open-in-view} is disabled and each would otherwise be an uninitialized proxy by then.
     *
     * @param groupId  the id of the milestone group to load
     * @param courseId the id of the course the group must belong to
     * @return the matching milestone group with its fully hydrated anchor exercise, or empty
     */
    @Query("""
            SELECT DISTINCT g
            FROM MilestoneExerciseGroup g
                LEFT JOIN FETCH g.exercises
                LEFT JOIN FETCH g.milestoneExercise me
                LEFT JOIN FETCH me.templateParticipation
                LEFT JOIN FETCH me.solutionParticipation
            WHERE g.id = :groupId
                AND g.id IN (
                    SELECT evg.id
                    FROM Course c
                        JOIN c.exerciseVariantGroups evg
                    WHERE c.id = :courseId
                )
            """)
    Optional<MilestoneExerciseGroup> findByIdAndCourseIdWithDetails(@Param("groupId") Long groupId, @Param("courseId") Long courseId);

    default MilestoneExerciseGroup findByIdAndCourseIdWithDetailsElseThrow(Long groupId, Long courseId) throws EntityNotFoundException {
        return getValueElseThrow(findByIdAndCourseIdWithDetails(groupId, courseId), groupId);
    }

    /**
     * Like {@link #findByIdAndCourseId}, but without the members. Used for deletion: pulling the members into the
     * persistence context would make Hibernate's flush fail with a {@code TransientPropertyValueException} (the managed
     * exercises would still reference the removed group).
     *
     * @param groupId  the id of the milestone group to load
     * @param courseId the id of the course the group must belong to
     * @return the matching milestone group without its members, or empty
     */
    @Query("""
            SELECT g
            FROM MilestoneExerciseGroup g
            WHERE g.id = :groupId
                AND g.id IN (
                    SELECT evg.id
                    FROM Course c
                        JOIN c.exerciseVariantGroups evg
                    WHERE c.id = :courseId
                )
            """)
    Optional<MilestoneExerciseGroup> findByIdAndCourseIdWithoutExercises(@Param("groupId") Long groupId, @Param("courseId") Long courseId);

    default MilestoneExerciseGroup findByIdAndCourseIdWithoutExercisesElseThrow(Long groupId, Long courseId) throws EntityNotFoundException {
        return getValueElseThrow(findByIdAndCourseIdWithoutExercises(groupId, courseId), groupId);
    }

    /**
     * Resolves a group's anchor {@link MilestoneExercise} id without loading any entity - a scalar projection, so it
     * carries none of the lazy-proxy/session-lifetime risk that loading the full group or exercise would.
     *
     * @param groupId the id of the group to resolve the anchor exercise id for
     * @return the milestone exercise id, or empty if the group does not exist or has no anchor
     */
    @Query("""
            SELECT g.milestoneExercise.id
            FROM MilestoneExerciseGroup g
            WHERE g.id = :groupId
            """)
    Optional<Long> findMilestoneExerciseIdByGroupId(@Param("groupId") Long groupId);

    /**
     * Resolves a {@code MilestoneExerciseGroup}'s anchor {@code milestoneExercise}'s problem statement, which doubles as
     * the group's description in the student group view. A scalar projection for the same reason as
     * {@link #findMilestoneExerciseIdByGroupId}, and deliberately not {@link #findMilestoneExerciseByGroupId}: that one
     * additionally fetches the build config and the template/solution participations, far more than a text blurb needs.
     *
     * @param groupId the id of the group to resolve the anchor milestone exercise's problem statement for
     * @return the problem statement, or empty if the group isn't a milestone group, doesn't exist, or has none set
     */
    @Query("""
            SELECT g.milestoneExercise.problemStatement
            FROM MilestoneExerciseGroup g
            WHERE g.id = :groupId
            """)
    Optional<String> findMilestoneProblemStatementByGroupId(@Param("groupId") Long groupId);

    /**
     * Resolves a {@code MilestoneExerciseGroup}'s anchor {@code milestoneExercise}, fully hydrated (its
     * {@code templateParticipation} and {@code solutionParticipation}, each a further {@code LAZY} association). Used to hydrate an already-loaded {@code UserStoryExercise}'s
     * {@code exerciseVariantGroup} before it
     * is serialized (e.g. {@code ProgrammingExerciseRetrievalResource.getProgrammingExercise}): {@code MilestoneExerciseGroup}'s
     * timeline getters ({@code getReleaseDate()}, {@code getDueDate()}, etc. - see {@link MilestoneExerciseGroup}) all
     * delegate to {@code milestoneExercise}, so those getters throw {@code LazyInitializationException} once the loading
     * session has closed (open-in-view is disabled) unless it was fetched - regardless of whether {@code milestoneExercise}
     * itself is ever serialized as its own JSON property.
     *
     * @param groupId the id of the group to resolve the hydrated anchor milestone exercise for
     * @return the milestone exercise, or empty if the group isn't a milestone group (or doesn't exist)
     */
    @Query("""
            SELECT me
            FROM MilestoneExerciseGroup g
                JOIN g.milestoneExercise me
                LEFT JOIN FETCH me.templateParticipation
                LEFT JOIN FETCH me.solutionParticipation
            WHERE g.id = :groupId
            """)
    Optional<MilestoneExercise> findMilestoneExerciseByGroupId(@Param("groupId") Long groupId);

    /**
     * Resolves a {@code MilestoneExerciseGroup} from its anchor {@code milestoneExercise}'s id, with the group's
     * {@code exercises} eagerly fetched. The milestone exercise itself is never a member of that collection and never
     * has its own {@code exerciseVariantGroup} set (only member {@code UserStoryExercise}s do - see
     * {@link MilestoneExerciseGroup}), so {@code ExerciseVariantGroupRepository.findByExerciseId} /
     * {@code findByExerciseIdWithExercises} - which both resolve via {@code Exercise.exerciseVariantGroup} - always
     * return empty for a milestone exercise id; this query instead goes through the group's own
     * {@code milestoneExercise} reference, the only FK that actually links a milestone exercise to its group.
     *
     * @param milestoneExerciseId the id of the milestone exercise to resolve the owning group for
     * @return the group with its exercises initialized, or empty if the exercise isn't a milestone exercise (or doesn't exist)
     */
    @Query("""
            SELECT DISTINCT g
            FROM MilestoneExerciseGroup g
                LEFT JOIN FETCH g.exercises
            WHERE g.milestoneExercise.id = :milestoneExerciseId
            """)
    Optional<MilestoneExerciseGroup> findByMilestoneExerciseIdWithExercises(@Param("milestoneExerciseId") long milestoneExerciseId);

    /**
     * Loads a milestone group with its anchor {@code milestoneExercise}, which holds the group's whole timeline: the
     * group's date getters delegate to the anchor and read as {@code null} while it is an unfetched proxy (see
     * {@link MilestoneExerciseGroup}). Needed wherever those dates are copied onto a member.
     *
     * @param groupId the id of the milestone group to load
     * @return the group with its anchor initialized, or empty if there is no milestone group with that id
     */
    @Query("""
            SELECT g
            FROM MilestoneExerciseGroup g
                LEFT JOIN FETCH g.milestoneExercise
            WHERE g.id = :groupId
            """)
    Optional<MilestoneExerciseGroup> findByIdWithMilestoneExercise(@Param("groupId") long groupId);

    /**
     * Sums the {@code maxPoints} of a group's {@code UserStoryExercise} members, which is what the group's anchor
     * {@code MilestoneExercise} carries as its own {@code maxPoints}: the milestone is the only scored exercise of the
     * group, and its points are the group's points (see {@code MilestoneExerciseService.syncMilestoneMaxPoints}).
     * <p>
     * Restricted to {@code UserStoryExercise} on purpose - a milestone group only ever holds those, and the type filter
     * keeps the sum correct should anything else ever be assigned to one.
     *
     * @param milestoneExerciseId the id of the milestone exercise whose group's member points to sum
     * @return the summed points, or empty if the group has no members yet
     */
    @Query("""
            SELECT SUM(e.maxPoints)
            FROM MilestoneExerciseGroup g
                JOIN g.exercises e
            WHERE g.milestoneExercise.id = :milestoneExerciseId
                AND TYPE(e) = UserStoryExercise
            """)
    Optional<Double> sumUserStoryMaxPointsByMilestoneExerciseId(@Param("milestoneExerciseId") long milestoneExerciseId);

    /**
     * Finds every (milestone exercise, student) pair whose {@code UserStoryExercise} results were modified after the
     * given instant - the fallback sweep of {@code MilestoneScoreScheduleService}, for events lost to a restart or a
     * broker hiccup.
     * <p>
     * Restricted to individual participations: a milestone group is individual-participation only (see
     * {@code ParticipationService}), so a team participation could never contribute to a milestone aggregate anyway.
     *
     * @param modifiedAfter only results modified strictly after this instant are considered
     * @return the pairs whose aggregated milestone score may be out of date
     */
    @Query("""
            SELECT DISTINCT new de.tum.cit.aet.artemis.exercise.dto.MilestoneScoreTargetDTO(g.milestoneExercise.id, p.student.id)
            FROM Result r
                JOIN r.submission s
                JOIN TREAT(s.participation AS StudentParticipation) p
                JOIN MilestoneExerciseGroup g ON p.exercise.exerciseVariantGroup.id = g.id
            WHERE TYPE(p.exercise) = UserStoryExercise
                AND p.student.id IS NOT NULL
                AND r.lastModifiedDate > :modifiedAfter
            """)
    List<MilestoneScoreTargetDTO> findMilestoneScoreTargetsForUserStoryResultsModifiedAfter(@Param("modifiedAfter") Instant modifiedAfter);

    /**
     * Resolves the id of the {@code MilestoneExercise} anchoring the group a user story belongs to.
     * <p>
     * A scalar projection because the caller ({@code MilestoneScoreScheduleService}) runs this once per user story result
     * event, purely to turn a per-story event into a per-milestone one so the recomputation can be debounced across a
     * whole fan-out.
     *
     * @param userStoryExerciseId the id of the user story exercise
     * @return the owning milestone exercise's id, or empty if the exercise has no milestone group
     */
    @Query("""
            SELECT g.milestoneExercise.id
            FROM MilestoneExerciseGroup g
                JOIN g.exercises e
            WHERE e.id = :userStoryExerciseId
            """)
    Optional<Long> findMilestoneExerciseIdByUserStoryExerciseId(@Param("userStoryExerciseId") long userStoryExerciseId);

    /**
     * Resolves the anchor {@code MilestoneExercise} of every milestone group among the given group ids, as a scalar
     * (anchor exercise id, group id) pair.
     * <p>
     * The score calculation needs this link and cannot derive it from the exercises it works with: a
     * {@code MilestoneExercise} has no {@code exerciseVariantGroup} of its own, and reading it off an already-loaded
     * {@link MilestoneExerciseGroup} would mean dereferencing the {@code LAZY} {@code milestoneExercise} association,
     * which silently reads as "not set" once the loading session has closed (see {@link MilestoneExerciseGroup}).
     * <p>
     * Ids that are not milestone groups simply produce no row, so callers can pass every variant group id they hold.
     *
     * @param groupIds the variant group ids to resolve anchors for
     * @return one pair per milestone group among {@code groupIds} that has an anchor
     */
    @Query("""
            SELECT NEW de.tum.cit.aet.artemis.exercise.dto.MilestoneGroupAnchorDTO(g.milestoneExercise.id, g.id)
            FROM MilestoneExerciseGroup g
            WHERE g.id IN :groupIds
                AND g.milestoneExercise IS NOT NULL
            """)
    List<MilestoneGroupAnchorDTO> findAnchorsByGroupIds(@Param("groupIds") Collection<Long> groupIds);

    /**
     * @return the ids of every anchor {@code MilestoneExercise}, one per milestone group that has one
     */
    @Query("""
            SELECT g.milestoneExercise.id
            FROM MilestoneExerciseGroup g
            WHERE g.milestoneExercise IS NOT NULL
            """)
    List<Long> findAllMilestoneExerciseIds();

    /**
     * Counts a group's members without loading them, for the "cannot delete a non-empty milestone group" guard. The
     * anchor exercise is never itself a member of that collection, so no exclusion is needed here.
     *
     * @param groupId the id of the group whose members to count
     * @return the number of exercises currently assigned to the group
     */
    @Query("""
            SELECT COUNT(e)
            FROM Exercise e
            WHERE e.exerciseVariantGroup.id = :groupId
            """)
    long countExercisesByGroupId(@Param("groupId") Long groupId);
}
