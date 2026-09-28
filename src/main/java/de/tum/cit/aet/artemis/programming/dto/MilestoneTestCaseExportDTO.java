package de.tum.cit.aet.artemis.programming.dto;

import org.jspecify.annotations.Nullable;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import de.tum.cit.aet.artemis.assessment.domain.Visibility;
import de.tum.cit.aet.artemis.programming.domain.ProgrammingExerciseTestCase;

/**
 * The grading settings of one test case of an exported milestone exercise (see {@link MilestoneExportDetailsDTO}).
 * The test itself travels as code in the milestone's tests repository; this carries what an instructor configured for it.
 *
 * @param testName        the name of the test, which identifies it across instances
 * @param weight          the weight of the test
 * @param bonusMultiplier the bonus multiplier of the test
 * @param bonusPoints     the bonus points of the test
 * @param visibility      when students see the result of the test
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record MilestoneTestCaseExportDTO(String testName, @Nullable Double weight, @Nullable Double bonusMultiplier, @Nullable Double bonusPoints,
        @Nullable Visibility visibility) {

    /**
     * Describes a test case for the export.
     *
     * @param testCase the test case of the milestone exercise
     * @return the exportable grading settings of the test case
     */
    public static MilestoneTestCaseExportDTO of(ProgrammingExerciseTestCase testCase) {
        return new MilestoneTestCaseExportDTO(testCase.getTestName(), testCase.getWeight(), testCase.getBonusMultiplier(), testCase.getBonusPoints(), testCase.getVisibility());
    }
}
