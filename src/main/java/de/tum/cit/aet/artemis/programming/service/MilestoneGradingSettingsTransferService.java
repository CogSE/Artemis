package de.tum.cit.aet.artemis.programming.service;

import static de.tum.cit.aet.artemis.core.config.Constants.PROFILE_CORE;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import de.tum.cit.aet.artemis.programming.domain.ProgrammingExercise;
import de.tum.cit.aet.artemis.programming.domain.ProgrammingExerciseTestCase;
import de.tum.cit.aet.artemis.programming.domain.StaticCodeAnalysisCategory;
import de.tum.cit.aet.artemis.programming.dto.MilestoneStaticCodeAnalysisCategoryExportDTO;
import de.tum.cit.aet.artemis.programming.dto.MilestoneTestCaseExportDTO;
import de.tum.cit.aet.artemis.programming.repository.ProgrammingExerciseTestCaseRepository;
import de.tum.cit.aet.artemis.programming.repository.StaticCodeAnalysisCategoryRepository;

/**
 * Carries the grading settings of a milestone exercise through a milestone export and import: the weights of its test
 * cases and the settings of its static code analysis categories. The programming exercise export the milestone is
 * archived with leaves both out, so an imported milestone would otherwise start with the defaults.
 * <p>
 * Test cases only come into existence when the first build of the solution reports them, which may be before or after
 * the import applies the settings. Both orders end up the same:
 * <ul>
 * <li>a test case the build already created is updated in place,</li>
 * <li>a test case the build has not created yet is created here, active and with the exported settings. The build
 * finds it by name and leaves it as it is, and would deactivate it if the tests no longer contained it.</li>
 * </ul>
 * The user stories copy these settings from the milestone whenever they sync their test cases (see
 * {@link UserStoryExerciseService#syncTestCasesFromMilestone}), so applying them to the milestone before the user
 * stories are created is all they need.
 */
@Profile(PROFILE_CORE)
@Lazy
@Service
public class MilestoneGradingSettingsTransferService {

    private static final Logger log = LoggerFactory.getLogger(MilestoneGradingSettingsTransferService.class);

    private final ProgrammingExerciseTestCaseRepository testCaseRepository;

    private final StaticCodeAnalysisCategoryRepository staticCodeAnalysisCategoryRepository;

    public MilestoneGradingSettingsTransferService(ProgrammingExerciseTestCaseRepository testCaseRepository,
            StaticCodeAnalysisCategoryRepository staticCodeAnalysisCategoryRepository) {
        this.testCaseRepository = testCaseRepository;
        this.staticCodeAnalysisCategoryRepository = staticCodeAnalysisCategoryRepository;
    }

    /**
     * Describes the grading settings of every test case of the milestone exercise, ordered by name.
     *
     * @param milestoneExerciseId the id of the exported milestone exercise
     * @return the exportable test case settings
     */
    public List<MilestoneTestCaseExportDTO> exportTestCases(long milestoneExerciseId) {
        return testCaseRepository.findByExerciseId(milestoneExerciseId).stream().sorted(Comparator.comparing(ProgrammingExerciseTestCase::getTestName))
                .map(MilestoneTestCaseExportDTO::of).toList();
    }

    /**
     * Describes the settings of every static code analysis category of the milestone exercise, ordered by name.
     *
     * @param milestoneExerciseId the id of the exported milestone exercise
     * @return the exportable category settings
     */
    public List<MilestoneStaticCodeAnalysisCategoryExportDTO> exportCategories(long milestoneExerciseId) {
        return staticCodeAnalysisCategoryRepository.findByExerciseId(milestoneExerciseId).stream().sorted(Comparator.comparing(StaticCodeAnalysisCategory::getName))
                .map(MilestoneStaticCodeAnalysisCategoryExportDTO::of).toList();
    }

    /**
     * Applies the exported category settings to the imported milestone's categories, matched by name. The import has
     * created the language's default categories already; an exported category the defaults no longer know is skipped.
     *
     * @param milestoneExercise the imported milestone exercise
     * @param exported          the exported category settings, or {@code null} for an archive without any
     */
    public void applyCategories(ProgrammingExercise milestoneExercise, @Nullable List<MilestoneStaticCodeAnalysisCategoryExportDTO> exported) {
        if (exported == null || exported.isEmpty()) {
            return;
        }
        Map<String, StaticCodeAnalysisCategory> categoriesByName = staticCodeAnalysisCategoryRepository.findByExerciseId(milestoneExercise.getId()).stream()
                .collect(Collectors.toMap(StaticCodeAnalysisCategory::getName, Function.identity(), (first, second) -> first));
        List<StaticCodeAnalysisCategory> changed = new ArrayList<>();
        for (MilestoneStaticCodeAnalysisCategoryExportDTO exportedCategory : exported) {
            StaticCodeAnalysisCategory category = categoriesByName.get(exportedCategory.name());
            if (category == null) {
                log.info("Skipping the exported static code analysis category {}: milestone exercise {} has no category of that name", exportedCategory.name(),
                        milestoneExercise.getId());
                continue;
            }
            category.setPenalty(exportedCategory.penalty());
            category.setMaxPenalty(exportedCategory.maxPenalty());
            if (exportedCategory.state() != null) {
                category.setState(exportedCategory.state());
            }
            changed.add(category);
        }
        staticCodeAnalysisCategoryRepository.saveAll(changed);
    }

    /**
     * Applies the exported test case settings to the imported milestone, creating the test cases the first build has
     * not reported yet (see the class documentation for why both orders end up the same).
     *
     * @param milestoneExercise the imported milestone exercise
     * @param exported          the exported test case settings, or {@code null} for an archive without any
     */
    public void applyTestCases(ProgrammingExercise milestoneExercise, @Nullable List<MilestoneTestCaseExportDTO> exported) {
        if (exported == null || exported.isEmpty()) {
            return;
        }
        Set<ProgrammingExerciseTestCase> existing = testCaseRepository.findByExerciseId(milestoneExercise.getId());
        for (MilestoneTestCaseExportDTO exportedTestCase : exported) {
            Optional<ProgrammingExerciseTestCase> match = findByName(existing, exportedTestCase.testName());
            ProgrammingExerciseTestCase testCase = match.orElseGet(() -> newTestCase(milestoneExercise, exportedTestCase.testName()));
            applySettings(testCase, exportedTestCase);
            try {
                testCaseRepository.save(testCase);
            }
            catch (DataIntegrityViolationException concurrentInsert) {
                // The first build reported the test case between the lookup above and this insert; the unique index on
                // (test_name, exercise_id) rejected the second row. Update the build's row instead.
                ProgrammingExerciseTestCase created = findByName(testCaseRepository.findByExerciseId(milestoneExercise.getId()), exportedTestCase.testName())
                        .orElseThrow(() -> concurrentInsert);
                applySettings(created, exportedTestCase);
                testCaseRepository.save(created);
            }
        }
    }

    private static ProgrammingExerciseTestCase newTestCase(ProgrammingExercise milestoneExercise, String testName) {
        ProgrammingExerciseTestCase testCase = new ProgrammingExerciseTestCase();
        testCase.setTestName(testName);
        testCase.setExercise(milestoneExercise);
        testCase.setActive(true);
        // What the build would give a test case it has never seen; the exported visibility overrides it below.
        testCase.setVisibility(milestoneExercise.getDefaultTestCaseVisibility());
        return testCase;
    }

    private static void applySettings(ProgrammingExerciseTestCase testCase, MilestoneTestCaseExportDTO exported) {
        testCase.setWeight(exported.weight() == null ? 1.0 : exported.weight());
        testCase.setBonusMultiplier(exported.bonusMultiplier() == null ? 1.0 : exported.bonusMultiplier());
        testCase.setBonusPoints(exported.bonusPoints() == null ? 0.0 : exported.bonusPoints());
        if (exported.visibility() != null) {
            testCase.setVisibility(exported.visibility());
        }
    }

    /** Matches like the build does: test names are compared ignoring case. */
    private static Optional<ProgrammingExerciseTestCase> findByName(Set<ProgrammingExerciseTestCase> testCases, String testName) {
        return testCases.stream().filter(testCase -> testName.equalsIgnoreCase(testCase.getTestName())).findFirst();
    }
}
