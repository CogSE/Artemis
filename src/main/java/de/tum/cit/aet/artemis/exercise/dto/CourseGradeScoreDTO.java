package de.tum.cit.aet.artemis.exercise.dto;

import jakarta.validation.constraints.NotNull;

import org.jspecify.annotations.Nullable;

import com.fasterxml.jackson.annotation.JsonInclude;

import de.tum.cit.aet.artemis.exercise.domain.Exercise;
import de.tum.cit.aet.artemis.exercise.domain.ExerciseType;

/**
 * A student's score on one exercise, projected straight from the database for the score calculation.
 *
 * @param participationId   the participation the score belongs to
 * @param userId            the student
 * @param exerciseId        the exercise
 * @param score             the achieved score in percent
 * @param rated             whether the result counts towards the grade; the course statistics distinguish rated from
 *                              unrated results, and the score calculation only counts rated ones
 * @param presentationScore the presentation score recorded on the participation, if any
 * @param type              the exercise type, which the per-type score breakdown groups by
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record CourseGradeScoreDTO(long participationId, long userId, long exerciseId, double score, @Nullable Boolean rated, @Nullable Double presentationScore,
        @NotNull ExerciseType type) {

    /**
     * JPQL constructor accepting the entity class produced by Hibernate's {@code TYPE(...)} function, so the milestone
     * subtypes are classified like every other projection (see {@link ExerciseType#getExerciseTypeFromClass}) instead of
     * by a {@code CASE} that only matches the exact discriminator.
     */
    public CourseGradeScoreDTO(long participationId, long userId, long exerciseId, double score, @Nullable Boolean rated, @Nullable Double presentationScore,
            Class<? extends Exercise> type) {
        this(participationId, userId, exerciseId, score, rated, presentationScore, ExerciseType.getExerciseTypeFromClass(type));
    }
}
