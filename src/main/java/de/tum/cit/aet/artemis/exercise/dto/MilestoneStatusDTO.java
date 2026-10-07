package de.tum.cit.aet.artemis.exercise.dto;

import org.jspecify.annotations.Nullable;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * The requesting student's view of the {@code MilestoneExercise} anchoring a {@code MilestoneExerciseGroup}. The milestone
 * itself is never shown to students (see {@code MilestoneExercise.isVisibleToStudents}), so this is the only way the group
 * view can reach its problem statement and the student's participation in it. The milestone's id is not repeated here:
 * clients already have it from the group reference ({@link ExerciseVariantGroupReferenceDTO#milestoneExerciseId()}).
 *
 * @param participationId  the id of the student's participation in the milestone, or {@code null} if not started yet
 * @param problemStatement the milestone exercise's problem statement, which doubles as the group's description in the
 *                             student group view; {@code null} when the instructor left it empty
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record MilestoneStatusDTO(@Nullable Long participationId, @Nullable String problemStatement) {
}
