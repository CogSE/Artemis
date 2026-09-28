package de.tum.cit.aet.artemis.programming.dto;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.jspecify.annotations.Nullable;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import de.tum.cit.aet.artemis.assessment.domain.AssessmentType;
import de.tum.cit.aet.artemis.assessment.dto.GradingCriterionDTO;
import de.tum.cit.aet.artemis.exercise.domain.DifficultyLevel;
import de.tum.cit.aet.artemis.exercise.domain.ExerciseMode;
import de.tum.cit.aet.artemis.exercise.dto.CreateUserStoryExerciseDTO;
import de.tum.cit.aet.artemis.programming.domain.UserStoryExercise;

/**
 * One user story of an exported milestone exercise group (see {@link MilestoneExportDetailsDTO}).
 * <p>
 * Carries exactly what a user story owns for itself - the same fields {@link CreateUserStoryExerciseDTO} accepts -
 * so that the import can recreate it through the regular user story creation path. Everything else (repositories,
 * build configuration, timeline, test cases) is the milestone's and travels with the milestone's own export.
 * Competency links are left out on purpose: competencies belong to a course and do not exist in the target one.
 * <p>
 * The grading criteria are a list, not a set: the export strips their ids, and two value-identical criteria would
 * otherwise collapse into one while the manifest is read.
 *
 * @param title                                  the title of the user story
 * @param shortName                              the short name of the user story
 * @param problemStatement                       the problem statement, with test references written as test names
 * @param categories                             the exercise categories as JSON-encoded strings
 * @param difficulty                             the difficulty level
 * @param mode                                   individual or team mode
 * @param maxPoints                              the achievable points
 * @param bonusPoints                            the achievable bonus points
 * @param assessmentType                         automatic, semi-automatic or manual assessment
 * @param allowComplaintsForAutomaticAssessments whether complaints are allowed for automatic assessments
 * @param presentationScoreEnabled               whether the presentation score is enabled
 * @param secondCorrectionEnabled                whether a second correction round is enabled
 * @param gradingInstructions                    the unstructured grading instructions
 * @param gradingCriteria                        the structured grading criteria, without ids
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record MilestoneUserStoryExportDTO(String title, String shortName, @Nullable String problemStatement, @Nullable Set<String> categories, @Nullable DifficultyLevel difficulty,
        @Nullable ExerciseMode mode, Double maxPoints, @Nullable Double bonusPoints, @Nullable AssessmentType assessmentType,
        @Nullable Boolean allowComplaintsForAutomaticAssessments, @Nullable Boolean presentationScoreEnabled, @Nullable Boolean secondCorrectionEnabled,
        @Nullable String gradingInstructions, @Nullable List<GradingCriterionDTO> gradingCriteria) {

    /**
     * Describes a user story for the export. The caller has already replaced the test ids in its problem statement
     * with test names, since the ids mean nothing on the importing side.
     *
     * @param exercise the user story, with its categories and grading criteria loaded
     * @return the exportable description of the user story
     */
    public static MilestoneUserStoryExportDTO of(UserStoryExercise exercise) {
        List<GradingCriterionDTO> criteria = exercise.getGradingCriteria() == null ? List.of()
                : exercise.getGradingCriteria().stream().map(GradingCriterionDTO::of).map(GradingCriterionDTO::withoutIds).toList();
        return new MilestoneUserStoryExportDTO(exercise.getTitle(), exercise.getShortName(), exercise.getProblemStatement(), exercise.getCategories(), exercise.getDifficulty(),
                exercise.getMode(), exercise.getMaxPoints(), exercise.getBonusPoints(), exercise.getAssessmentType(), exercise.getAllowComplaintsForAutomaticAssessments(),
                exercise.getPresentationScoreEnabled(), exercise.getSecondCorrectionEnabled(), exercise.getGradingInstructions(), criteria);
    }

    /**
     * Builds the creation request for this user story in the importing course.
     *
     * @param newTitle     the title to create it with, which may differ from the exported one to stay unique
     * @param newShortName the short name to create it with, which may differ from the exported one to stay unique
     * @return the payload for {@code MilestoneExerciseService.createUserStoryExercise}
     */
    public CreateUserStoryExerciseDTO toCreateDTO(String newTitle, String newShortName) {
        // The channel name is left out: it is derived from the (possibly renamed) title, which keeps it unique as well.
        return new CreateUserStoryExerciseDTO(newTitle, newShortName, null, problemStatement, categories, difficulty, mode, maxPoints == null ? 0.0 : maxPoints, bonusPoints,
                assessmentType, allowComplaintsForAutomaticAssessments, presentationScoreEnabled, secondCorrectionEnabled, gradingInstructions,
                gradingCriteria == null ? null : new HashSet<>(gradingCriteria), null);
    }
}
