package de.tum.cit.aet.artemis.exercise.dto;

import java.util.List;

import org.jspecify.annotations.Nullable;

import com.fasterxml.jackson.annotation.JsonInclude;

import de.tum.cit.aet.artemis.exercise.domain.ExerciseType;
import de.tum.cit.aet.artemis.programming.dto.UserStoryTaskDTO;

/**
 * One exercise of a milestone group as it stands for a single student, which is both a tab on the milestone assessment
 * page and a cell on the milestone assessment dashboard.
 * <p>
 * Most members are {@code UserStoryExercise}s, which share the milestone's repository and its build, so what differs
 * between them is not the code but the grading: each story has its own participation, its own submission row for the
 * same commit, and its own result. A group may also hold text, modeling, file upload and quiz exercises, which are
 * graded like anywhere else and only share the group's timeline.
 *
 * @param exerciseId      the exercise's id
 * @param title           the exercise's title, used as the tab label
 * @param exerciseType    the exercise's type, which decides the assessment editor the page mounts for it
 * @param userStory       whether the exercise is a user story; needed on top of the type, since a user story is a
 *                            programming exercise
 * @param maxPoints       the points the exercise is worth
 * @param participationId the student's participation in this exercise, or {@code null} if they never started it
 * @param submissionId    the latest submission of that participation, or {@code null} if there is none yet - this is
 *                            what the assessment editor is opened on
 * @param latestScore     the score of the latest result, or {@code null} if the exercise has none yet
 * @param assessorLogin   the login of the tutor who assessed it, or {@code null} if nobody has
 * @param assessed        whether a manual assessment has been submitted for this exercise
 * @param tasks           the tasks the student (or their team) broke the user story down into, in their own board order,
 *                            so the tutor can see how the story was planned; empty for any other exercise, for a story
 *                            without tasks, and on the dashboard, which does not show them
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record MilestoneAssessmentExerciseDTO(long exerciseId, String title, ExerciseType exerciseType, boolean userStory, @Nullable Double maxPoints,
        @Nullable Long participationId, @Nullable Long submissionId, @Nullable Double latestScore, @Nullable String assessorLogin, boolean assessed, List<UserStoryTaskDTO> tasks) {
}
