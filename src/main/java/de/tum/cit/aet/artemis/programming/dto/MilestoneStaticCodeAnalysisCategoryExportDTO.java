package de.tum.cit.aet.artemis.programming.dto;

import org.jspecify.annotations.Nullable;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import de.tum.cit.aet.artemis.assessment.domain.CategoryState;
import de.tum.cit.aet.artemis.programming.domain.StaticCodeAnalysisCategory;

/**
 * The settings of one static code analysis category of an exported milestone exercise (see
 * {@link MilestoneExportDetailsDTO}).
 *
 * @param name       the name of the category, which identifies it across instances
 * @param penalty    the penalty per issue in the category
 * @param maxPenalty the maximum penalty of the category
 * @param state      whether the category is graded, only shown as feedback, or inactive
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record MilestoneStaticCodeAnalysisCategoryExportDTO(String name, @Nullable Double penalty, @Nullable Double maxPenalty, @Nullable CategoryState state) {

    /**
     * Describes a category for the export.
     *
     * @param category the category of the milestone exercise
     * @return the exportable settings of the category
     */
    public static MilestoneStaticCodeAnalysisCategoryExportDTO of(StaticCodeAnalysisCategory category) {
        return new MilestoneStaticCodeAnalysisCategoryExportDTO(category.getName(), category.getPenalty(), category.getMaxPenalty(), category.getState());
    }
}
