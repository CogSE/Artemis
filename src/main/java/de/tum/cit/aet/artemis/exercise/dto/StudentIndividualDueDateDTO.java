package de.tum.cit.aet.artemis.exercise.dto;

import java.time.ZonedDateTime;

import jakarta.validation.constraints.NotNull;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * The individual due date an instructor grants one student, whether or not the student has started the exercise.
 *
 * @param individualDueDate the new individual due date; it has to lie after the exercise's regular due date
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record StudentIndividualDueDateDTO(@NotNull ZonedDateTime individualDueDate) {
}
