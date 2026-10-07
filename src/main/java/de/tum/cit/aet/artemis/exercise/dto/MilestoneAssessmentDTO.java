package de.tum.cit.aet.artemis.exercise.dto;

import java.util.List;

import org.jspecify.annotations.Nullable;

import com.fasterxml.jackson.annotation.JsonInclude;

import de.tum.cit.aet.artemis.programming.dto.ResultDTO;

/**
 * Everything the milestone assessment page needs for one student, in one request: the group-level information its first
 * tab shows, and the exercises its remaining tabs grade.
 * <p>
 * The milestone's own result is what carries the group's static code analysis feedback - the fan-out deliberately copies
 * only test case feedback down to the stories, and every story has static code analysis switched off, so this is the
 * only place those issues exist. It is sent as an ordinary {@link ResultDTO} so the client can reuse the same component
 * the student-facing group page renders them with.
 *
 * @param milestoneExerciseId          the group's anchor milestone exercise
 * @param milestoneTitle               its title
 * @param problemStatement             the milestone's problem statement, which is the brief the shared repository was
 *                                         written against; {@code null} when the instructor left it empty
 * @param staticCodeAnalysisEnabled    whether static code analysis runs for this group at all
 * @param maxStaticCodeAnalysisPenalty the cap on the penalty, as a percentage of the milestone's points, or {@code null}
 *                                         when the instructor set none
 * @param milestoneMaxPoints           the milestone's points, which the server keeps equal to the sum of the stories'
 *                                         and which the penalty cap is a percentage of
 * @param milestoneParticipationId     the student's milestone participation, which the problem statement's tasks are
 *                                         judged against; {@code null} if they never started the milestone
 * @param milestoneResult              the student's latest milestone result with its synthesized static code analysis
 *                                         feedback, or {@code null} before their first build
 * @param exercises                    the group's exercises, user stories first, one tab each
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record MilestoneAssessmentDTO(long milestoneExerciseId, String milestoneTitle, @Nullable String problemStatement, boolean staticCodeAnalysisEnabled,
        @Nullable Integer maxStaticCodeAnalysisPenalty, @Nullable Double milestoneMaxPoints, @Nullable Long milestoneParticipationId, @Nullable ResultDTO milestoneResult,
        List<MilestoneAssessmentExerciseDTO> exercises) {
}
