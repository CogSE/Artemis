package de.tum.cit.aet.artemis.programming.service;

import static de.tum.cit.aet.artemis.core.config.Constants.PROFILE_CORE;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import de.tum.cit.aet.artemis.account.domain.User;
import de.tum.cit.aet.artemis.exercise.domain.Exercise;
import de.tum.cit.aet.artemis.exercise.domain.participation.Participation;
import de.tum.cit.aet.artemis.exercise.domain.participation.StudentParticipation;
import de.tum.cit.aet.artemis.exercise.repository.ExerciseRepository;
import de.tum.cit.aet.artemis.exercise.repository.MilestoneExerciseGroupRepository;
import de.tum.cit.aet.artemis.exercise.repository.StudentParticipationRepository;
import de.tum.cit.aet.artemis.exercise.service.ParticipationService;
import de.tum.cit.aet.artemis.programming.domain.ProgrammingExercise;
import de.tum.cit.aet.artemis.programming.domain.UserStoryExercise;
import de.tum.cit.aet.artemis.quiz.domain.QuizExercise;

/**
 * Keeps the individual due dates of a milestone group's members in step with the group's milestone.
 * <p>
 * The student's participation in the {@link de.tum.cit.aet.artemis.programming.domain.MilestoneExercise} is the single
 * owner of their individual due date. Every member of the group - user stories, but also text, modeling, file upload or
 * other programming exercises - still judges its own deadline by its own participation's due date: whether a result is
 * rated, whether a submission is still accepted, whether a task board is frozen. Without the copy made here, an extension
 * granted on the milestone would leave every member treating the student as late.
 * <p>
 * Participations are matched by student, not by repository: most members have no repository at all. Practice and test
 * run participations have no individual due date and are left alone, and so are team participations, which are not
 * part of the milestone's per-student scheme. Scheduling is left to the caller, since the programming scheduler itself
 * depends on services this one would otherwise pull in.
 */
@Profile(PROFILE_CORE)
@Lazy
@Service
public class MilestoneIndividualDueDateService {

    private static final Logger log = LoggerFactory.getLogger(MilestoneIndividualDueDateService.class);

    private final MilestoneExerciseGroupRepository milestoneExerciseGroupRepository;

    private final StudentParticipationRepository studentParticipationRepository;

    private final ParticipationService participationService;

    private final ExerciseRepository exerciseRepository;

    public MilestoneIndividualDueDateService(MilestoneExerciseGroupRepository milestoneExerciseGroupRepository, StudentParticipationRepository studentParticipationRepository,
            ParticipationService participationService, ExerciseRepository exerciseRepository) {
        this.milestoneExerciseGroupRepository = milestoneExerciseGroupRepository;
        this.studentParticipationRepository = studentParticipationRepository;
        this.participationService = participationService;
        this.exerciseRepository = exerciseRepository;
    }

    /**
     * Starts every member of the milestone's group that a student with an extension has not started yet, for each given
     * milestone participation that carries an individual due date.
     * <p>
     * An individual due date lives on a participation, so without one a member shows the student the regular due date
     * and refuses a start once that has passed. Starting the member here, with the same call the student's own start
     * makes, gives it a participation that takes over the milestone's date (see
     * {@code ParticipationService.takeOverMilestoneIndividualDueDate}). Its initial submission stays unsubmitted, so it
     * does not reach the assessment queue before the student hands something in.
     * <p>
     * User stories are left out: their participations come with the milestone's repository. So are team-mode members,
     * which are not part of the milestone's per-student scheme, and quizzes, which have no individual due dates. A member
     * that fails to start is logged and skipped, so the extension is still granted on the others.
     *
     * @param milestoneExerciseId     the id of the group's milestone exercise
     * @param milestoneParticipations the milestone participations whose students were just granted an extension
     */
    public void createMissingMemberParticipations(long milestoneExerciseId, Collection<? extends Participation> milestoneParticipations) {
        Map<Long, User> studentsWithExtension = new HashMap<>();
        for (Participation milestoneParticipation : milestoneParticipations) {
            if (milestoneParticipation instanceof StudentParticipation studentParticipation && !studentParticipation.isPracticeMode()
                    && studentParticipation.getIndividualDueDate() != null) {
                studentParticipation.getStudent().ifPresent(user -> studentsWithExtension.put(user.getId(), user));
            }
        }
        if (studentsWithExtension.isEmpty()) {
            return;
        }
        milestoneExerciseGroupRepository.findByMilestoneExerciseIdWithExercises(milestoneExerciseId).ifPresent(group -> {
            for (Exercise member : group.getExercises()) {
                if (member instanceof UserStoryExercise || member instanceof QuizExercise || member.isTeamMode()) {
                    continue;
                }
                Set<Long> startedStudentIds = new HashSet<>();
                for (StudentParticipation existing : studentParticipationRepository.findGradedByExerciseIdAndStudentIdInWithStudent(member.getId(),
                        studentsWithExtension.keySet())) {
                    existing.getStudent().ifPresent(user -> startedStudentIds.add(user.getId()));
                }
                for (User student : studentsWithExtension.values()) {
                    if (!startedStudentIds.contains(student.getId())) {
                        startMember(member.getId(), student);
                    }
                }
            }
        });
    }

    private void startMember(long memberExerciseId, User student) {
        try {
            // Loaded fresh, as the start endpoint does: the group's members come without what a start reads.
            participationService.startExercise(exerciseRepository.findByIdElseThrow(memberExerciseId), student, true);
        }
        catch (Exception e) {
            log.error("Failed to start milestone member {} for student {} to carry their individual due date", memberExerciseId, student.getLogin(), e);
        }
    }

    /**
     * Copies the individual due date of each given milestone participation onto the same student's graded participation
     * in every member of the milestone's group, clearing it there where the milestone participation has none.
     *
     * @param milestoneExerciseId     the id of the group's milestone exercise
     * @param milestoneParticipations the milestone participations whose individual due date the members should take
     * @return the ids of the programming members whose participations changed, which the caller reschedules
     */
    public Set<Long> syncIndividualDueDatesToMembers(long milestoneExerciseId, Collection<? extends Participation> milestoneParticipations) {
        Map<Long, ZonedDateTime> dueDateByStudentId = new HashMap<>();
        for (Participation milestoneParticipation : milestoneParticipations) {
            if (milestoneParticipation instanceof StudentParticipation studentParticipation && !studentParticipation.isPracticeMode()) {
                Optional<User> student = studentParticipation.getStudent();
                // A HashMap rather than Collectors.toMap: the value is null wherever the milestone has no individual due date.
                student.ifPresent(user -> dueDateByStudentId.put(user.getId(), studentParticipation.getIndividualDueDate()));
            }
        }
        Set<Long> changedProgrammingMemberIds = new HashSet<>();
        if (dueDateByStudentId.isEmpty()) {
            return changedProgrammingMemberIds;
        }
        milestoneExerciseGroupRepository.findByMilestoneExerciseIdWithExercises(milestoneExerciseId).ifPresent(group -> {
            List<StudentParticipation> changed = new ArrayList<>();
            for (Exercise member : group.getExercises()) {
                if (member.isTeamMode()) {
                    continue;
                }
                for (StudentParticipation memberParticipation : studentParticipationRepository.findGradedByExerciseIdAndStudentIdInWithStudent(member.getId(),
                        dueDateByStudentId.keySet())) {
                    ZonedDateTime milestoneDueDate = memberParticipation.getStudent().map(user -> dueDateByStudentId.get(user.getId())).orElse(null);
                    if (!isSameInstant(memberParticipation.getIndividualDueDate(), milestoneDueDate)) {
                        memberParticipation.setIndividualDueDate(milestoneDueDate);
                        changed.add(memberParticipation);
                        if (member instanceof ProgrammingExercise) {
                            changedProgrammingMemberIds.add(member.getId());
                        }
                    }
                }
            }
            studentParticipationRepository.saveAll(changed);
        });
        return changedProgrammingMemberIds;
    }

    /**
     * Re-copies the individual due date of every student's milestone participation onto the group's members. Used when
     * the milestone's own dates changed, which may have cleared individual due dates that fell before the new due date,
     * and to repair members that drifted out of step.
     *
     * @param milestoneExerciseId the id of the group's milestone exercise
     * @return the ids of the programming members whose participations changed, which the caller reschedules
     */
    public Set<Long> syncAllIndividualDueDatesToMembers(long milestoneExerciseId) {
        return syncIndividualDueDatesToMembers(milestoneExerciseId, studentParticipationRepository.findGradedIndividualByExerciseIdWithStudent(milestoneExerciseId));
    }

    private static boolean isSameInstant(@Nullable ZonedDateTime first, @Nullable ZonedDateTime second) {
        return first == null ? second == null : second != null && first.isEqual(second);
    }
}
