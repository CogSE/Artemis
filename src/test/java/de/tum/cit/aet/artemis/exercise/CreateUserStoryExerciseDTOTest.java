package de.tum.cit.aet.artemis.exercise;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import tools.jackson.databind.json.JsonMapper;

import de.tum.cit.aet.artemis.assessment.domain.AssessmentType;
import de.tum.cit.aet.artemis.assessment.dto.GradingCriterionDTO;
import de.tum.cit.aet.artemis.core.util.JsonObjectMapper;
import de.tum.cit.aet.artemis.exercise.domain.DifficultyLevel;
import de.tum.cit.aet.artemis.exercise.domain.ExerciseMode;
import de.tum.cit.aet.artemis.exercise.domain.IncludedInOverallScore;
import de.tum.cit.aet.artemis.exercise.dto.CreateUserStoryExerciseDTO;
import de.tum.cit.aet.artemis.programming.domain.UserStoryExercise;

/**
 * The request contract of {@code POST .../milestone-exercise-groups/{groupId}/user-story-exercises}.
 * <p>
 * A user story owns only its own title, grading and problem statement; its Language/Version-Control settings,
 * repositories, build config and timeline are the group's milestone exercise's. Before this payload was a DTO the
 * endpoint bound the whole {@link UserStoryExercise} entity, so a client could submit all of them and have them
 * silently overwritten - or, for the fields nothing overwrote, persisted. These tests pin that the payload simply has
 * nowhere to put them.
 */
class CreateUserStoryExerciseDTOTest {

    private static final JsonMapper OBJECT_MAPPER = JsonObjectMapper.get();

    @Test
    void deserializationDropsEverythingTheMilestoneGroupOwns() throws Exception {
        String body = """
                {
                    "title": "Render the board",
                    "shortName": "board",
                    "maxPoints": 5.0,
                    "programmingLanguage": "PYTHON",
                    "projectType": "PLAIN_GRADLE",
                    "packageName": "de.tum.smuggled",
                    "staticCodeAnalysisEnabled": true,
                    "allowOfflineIde": true,
                    "includedInOverallScore": "NOT_INCLUDED",
                    "dueDate": "2030-01-01T00:00:00Z",
                    "testRepositoryUri": "https://example.org/smuggled.git",
                    "templateParticipation": { "id": 1 },
                    "testCases": [ { "testName": "smuggled" } ],
                    "projectKey": "SMUGGLED"
                }
                """;

        CreateUserStoryExerciseDTO dto = OBJECT_MAPPER.readValue(body, CreateUserStoryExerciseDTO.class);

        assertThat(dto.title()).isEqualTo("Render the board");
        assertThat(dto.shortName()).isEqualTo("board");
        assertThat(dto.maxPoints()).isEqualTo(5.0);

        // None of the group-owned settings above survive: the record has no component for any of them, so the exercise
        // built from this payload carries the defaults the service then replaces with the group's configuration.
        UserStoryExercise exercise = dto.toUserStoryExercise();
        assertThat(exercise.getProgrammingLanguage()).isNull();
        assertThat(exercise.getProjectType()).isNull();
        assertThat(exercise.getPackageName()).isNull();
        assertThat(exercise.isStaticCodeAnalysisEnabled()).isNull();
        assertThat(exercise.isAllowOfflineIde()).isNull();
        // The submitted NOT_INCLUDED is gone; what is left is the entity's own default, which the service pins to the
        // same value anyway - a user story's points always count, through its group.
        assertThat(exercise.getIncludedInOverallScore()).isEqualTo(IncludedInOverallScore.INCLUDED_COMPLETELY);
        assertThat(exercise.getDueDate()).isNull();
        assertThat(exercise.getTestRepositoryUri()).isNull();
        assertThat(exercise.getTemplateParticipation()).isNull();
        assertThat(exercise.getProjectKey()).isNull();
        assertThat(exercise.getTestCases()).isNullOrEmpty();
        assertThat(exercise.getId()).isNull();
    }

    @Test
    void toUserStoryExerciseMapsTheSettingsAUserStoryOwns() {
        CreateUserStoryExerciseDTO dto = new CreateUserStoryExerciseDTO("Render the board", "board", "board-channel", "Draw it", Set.of("{\"category\":\"ui\"}"),
                DifficultyLevel.MEDIUM, ExerciseMode.INDIVIDUAL, 5.0, 1.0, AssessmentType.AUTOMATIC, true, false, true, "Be fair",
                Set.of(new GradingCriterionDTO(null, "Style", null)), null, null);

        UserStoryExercise exercise = dto.toUserStoryExercise();

        assertThat(exercise.getTitle()).isEqualTo("Render the board");
        assertThat(exercise.getShortName()).isEqualTo("board");
        assertThat(exercise.getChannelName()).isEqualTo("board-channel");
        assertThat(exercise.getProblemStatement()).isEqualTo("Draw it");
        assertThat(exercise.getCategories()).containsExactly("{\"category\":\"ui\"}");
        assertThat(exercise.getDifficulty()).isEqualTo(DifficultyLevel.MEDIUM);
        assertThat(exercise.getMode()).isEqualTo(ExerciseMode.INDIVIDUAL);
        assertThat(exercise.getMaxPoints()).isEqualTo(5.0);
        assertThat(exercise.getBonusPoints()).isEqualTo(1.0);
        assertThat(exercise.getAssessmentType()).isEqualTo(AssessmentType.AUTOMATIC);
        assertThat(exercise.getAllowComplaintsForAutomaticAssessments()).isTrue();
        assertThat(exercise.getPresentationScoreEnabled()).isFalse();
        assertThat(exercise.getSecondCorrectionEnabled()).isTrue();
        assertThat(exercise.getGradingInstructions()).isEqualTo("Be fair");
        // setGradingCriteria reconnects the back-reference, so the criteria cascade with the exercise's own save.
        assertThat(exercise.getGradingCriteria()).singleElement().satisfies(criterion -> {
            assertThat(criterion.getTitle()).isEqualTo("Style");
            assertThat(criterion.getExercise()).isSameAs(exercise);
        });
    }

    /**
     * Showing test progress instead of the score only makes sense while a tutor's assessment is outstanding, so only a
     * manually assessed user story keeps the flag; any other assessment type drops it rather than storing a dead setting.
     */
    @ParameterizedTest
    @EnumSource(AssessmentType.class)
    void showTestProgressAsScoreIsOnlyKeptWithManualAssessment(AssessmentType assessmentType) {
        CreateUserStoryExerciseDTO dto = new CreateUserStoryExerciseDTO("Render the board", "board", null, null, null, null, null, 5.0, null, assessmentType, null, null, null,
                null, null, null, true);

        assertThat(dto.toUserStoryExercise().isShowTestProgressAsScore()).isEqualTo(assessmentType == AssessmentType.SEMI_AUTOMATIC);
    }

    /**
     * A user story is created from a form where most fields are optional, so an omitted flag must not become {@code null}
     * on a primitive-backed column - and an omitted mode must not leave the exercise without one.
     */
    @Test
    void omittedOptionalSettingsFallBackToTheirDefaults() throws Exception {
        CreateUserStoryExerciseDTO dto = OBJECT_MAPPER.readValue("""
                { "title": "Render the board", "shortName": "board", "maxPoints": 5.0 }
                """, CreateUserStoryExerciseDTO.class);

        UserStoryExercise exercise = dto.toUserStoryExercise();

        assertThat(exercise.getMode()).isEqualTo(ExerciseMode.INDIVIDUAL);
        assertThat(exercise.getBonusPoints()).isZero();
        assertThat(exercise.getAllowComplaintsForAutomaticAssessments()).isFalse();
        assertThat(exercise.getPresentationScoreEnabled()).isFalse();
        assertThat(exercise.getSecondCorrectionEnabled()).isFalse();
        assertThat(exercise.getCategories()).isEmpty();
        assertThat(exercise.getGradingCriteria()).isEmpty();
    }
}
