package de.tum.cit.aet.artemis.programming.dto;

import java.util.List;

import org.jspecify.annotations.Nullable;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * The manifest of an exported milestone exercise group, written as {@code Milestone-Details.json} at the root of the
 * milestone archive.
 * <p>
 * The archive bundles the group's anchor {@code MilestoneExercise} as a regular programming exercise export (a nested
 * zip that owns the shared template, solution and test repositories) next to this manifest, which describes the
 * group's {@code UserStoryExercise}s. User stories have no repositories of their own, so the manifest is all of them.
 * The group's other members (quiz, text, modeling and file upload exercises) are described in the manifest as well, and
 * so are the grading settings of the milestone exercise that the programming exercise export leaves out: the weights of
 * its test cases and the settings of its static code analysis categories.
 *
 * @param formatVersion                the version of the archive layout, raised on an incompatible change
 * @param groupTitle                   the title of the exported group
 * @param milestoneArchive             the file name of the nested programming exercise export of the anchor milestone exercise
 * @param userStories                  the group's user stories, in creation order
 * @param otherExercises               the group's members that are not user stories, in creation order
 * @param testCases                    the grading settings of the milestone exercise's test cases
 * @param staticCodeAnalysisCategories the settings of the milestone exercise's static code analysis categories
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record MilestoneExportDetailsDTO(int formatVersion, String groupTitle, String milestoneArchive, @Nullable List<MilestoneUserStoryExportDTO> userStories,
        @Nullable List<MilestoneMemberExportDTO> otherExercises, @Nullable List<MilestoneTestCaseExportDTO> testCases,
        @Nullable List<MilestoneStaticCodeAnalysisCategoryExportDTO> staticCodeAnalysisCategories) {
}
