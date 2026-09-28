package de.tum.cit.aet.artemis.programming.dto;

import java.util.List;
import java.util.Set;

import org.jspecify.annotations.Nullable;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import de.tum.cit.aet.artemis.assessment.domain.AssessmentType;
import de.tum.cit.aet.artemis.assessment.dto.GradingCriterionDTO;
import de.tum.cit.aet.artemis.exercise.domain.DifficultyLevel;
import de.tum.cit.aet.artemis.exercise.domain.ExerciseMode;
import de.tum.cit.aet.artemis.exercise.domain.ExerciseType;
import de.tum.cit.aet.artemis.exercise.domain.IncludedInOverallScore;
import de.tum.cit.aet.artemis.exercise.dto.TeamAssignmentConfigDTO;
import de.tum.cit.aet.artemis.quiz.dto.exercise.QuizExerciseCreateDTO;

/**
 * An exercise of an exported milestone exercise group that is not a user story: a quiz, text, modeling or file upload
 * exercise (see {@link MilestoneExportDetailsDTO}).
 * <p>
 * Text, modeling and file upload exercises are described by the fields shared by every exercise plus the few that
 * belong to their type. A quiz is described by the payload of the regular quiz creation, {@link QuizExerciseCreateDTO},
 * which already carries its questions; the images of its drag and drop questions travel in the archive next to it and
 * are named in {@code quizFiles}. Dates, competency links and example submissions are not exported: the timeline is the
 * group's, competencies belong to the source course, and example submissions are assessments of the source course.
 *
 * @param type                                   the exercise type
 * @param title                                  the title
 * @param shortName                              the short name, if any
 * @param problemStatement                       the problem statement
 * @param categories                             the exercise categories as JSON-encoded strings
 * @param difficulty                             the difficulty level
 * @param mode                                   individual or team mode
 * @param teamAssignmentConfig                   the team sizes of a team exercise, without id
 * @param maxPoints                              the achievable points
 * @param bonusPoints                            the achievable bonus points
 * @param assessmentType                         automatic, semi-automatic or manual assessment
 * @param includedInOverallScore                 how the exercise counts towards the course score
 * @param allowComplaintsForAutomaticAssessments whether complaints are allowed for automatic assessments
 * @param presentationScoreEnabled               whether the presentation score is enabled
 * @param secondCorrectionEnabled                whether a second correction round is enabled
 * @param gradingInstructions                    the unstructured grading instructions
 * @param gradingCriteria                        the structured grading criteria, without ids
 * @param exampleSolution                        the example solution of a text or file upload exercise
 * @param diagramType                            the diagram type of a modeling exercise
 * @param exampleSolutionModel                   the example solution model of a modeling exercise
 * @param exampleSolutionExplanation             the example solution explanation of a modeling exercise
 * @param filePattern                            the accepted file pattern of a file upload exercise
 * @param quiz                                   the creation payload of a quiz exercise, with its questions
 * @param quizFiles                              the archive paths of the drag and drop images of a quiz exercise
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record MilestoneMemberExportDTO(ExerciseType type, String title, @Nullable String shortName, @Nullable String problemStatement, @Nullable Set<String> categories,
        @Nullable DifficultyLevel difficulty, @Nullable ExerciseMode mode, @Nullable TeamAssignmentConfigDTO teamAssignmentConfig, @Nullable Double maxPoints,
        @Nullable Double bonusPoints, @Nullable AssessmentType assessmentType, @Nullable IncludedInOverallScore includedInOverallScore,
        @Nullable Boolean allowComplaintsForAutomaticAssessments, @Nullable Boolean presentationScoreEnabled, @Nullable Boolean secondCorrectionEnabled,
        @Nullable String gradingInstructions, @Nullable List<GradingCriterionDTO> gradingCriteria, @Nullable String exampleSolution, @Nullable String diagramType,
        @Nullable String exampleSolutionModel, @Nullable String exampleSolutionExplanation, @Nullable String filePattern, @Nullable QuizExerciseCreateDTO quiz,
        @Nullable List<String> quizFiles) {
}
