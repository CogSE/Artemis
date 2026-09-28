package de.tum.cit.aet.artemis.programming.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import de.tum.cit.aet.artemis.assessment.domain.CategoryState;
import de.tum.cit.aet.artemis.assessment.domain.Visibility;
import de.tum.cit.aet.artemis.programming.domain.MilestoneExercise;
import de.tum.cit.aet.artemis.programming.domain.ProgrammingExerciseTestCase;
import de.tum.cit.aet.artemis.programming.domain.StaticCodeAnalysisCategory;
import de.tum.cit.aet.artemis.programming.dto.MilestoneStaticCodeAnalysisCategoryExportDTO;
import de.tum.cit.aet.artemis.programming.dto.MilestoneTestCaseExportDTO;
import de.tum.cit.aet.artemis.programming.repository.StaticCodeAnalysisCategoryRepository;
import de.tum.cit.aet.artemis.programming.test_repository.ProgrammingExerciseTestCaseTestRepository;

@ExtendWith(MockitoExtension.class)
class MilestoneGradingSettingsTransferServiceTest {

    @Mock
    private ProgrammingExerciseTestCaseTestRepository testCaseRepository;

    @Mock
    private StaticCodeAnalysisCategoryRepository categoryRepository;

    private MilestoneGradingSettingsTransferService service;

    private final MilestoneExercise milestone = new MilestoneExercise();

    @BeforeEach
    void setUp() {
        service = new MilestoneGradingSettingsTransferService(testCaseRepository, categoryRepository);
        milestone.setId(10L);
    }

    private static ProgrammingExerciseTestCase testCase(long id, String name, double weight, boolean active) {
        ProgrammingExerciseTestCase testCase = new ProgrammingExerciseTestCase();
        testCase.setId(id);
        testCase.setTestName(name);
        testCase.setWeight(weight);
        testCase.setActive(active);
        return testCase;
    }

    @Test
    void exportDescribesTestCasesAndCategoriesByName() {
        when(testCaseRepository.findByExerciseId(10L)).thenReturn(Set.of(testCase(2L, "testB", 2.0, true), testCase(1L, "testA", 5.0, true)));
        StaticCodeAnalysisCategory category = new StaticCodeAnalysisCategory();
        category.setName("Bad Practice");
        category.setPenalty(3.0);
        category.setState(CategoryState.GRADED);
        when(categoryRepository.findByExerciseId(10L)).thenReturn(Set.of(category));

        assertThat(service.exportTestCases(10L)).extracting(MilestoneTestCaseExportDTO::testName, MilestoneTestCaseExportDTO::weight).containsExactly(tuple("testA", 5.0),
                tuple("testB", 2.0));
        assertThat(service.exportCategories(10L)).singleElement().satisfies(exported -> {
            assertThat(exported.penalty()).isEqualTo(3.0);
            assertThat(exported.state()).isEqualTo(CategoryState.GRADED);
        });
    }

    @Test
    void categoriesAreMatchedByNameAndUnknownOnesSkipped() {
        StaticCodeAnalysisCategory badPractice = new StaticCodeAnalysisCategory();
        badPractice.setName("Bad Practice");
        badPractice.setPenalty(1.0);
        badPractice.setState(CategoryState.FEEDBACK);
        when(categoryRepository.findByExerciseId(10L)).thenReturn(Set.of(badPractice));

        service.applyCategories(milestone, List.of(new MilestoneStaticCodeAnalysisCategoryExportDTO("Bad Practice", 4.0, 12.0, CategoryState.GRADED),
                new MilestoneStaticCodeAnalysisCategoryExportDTO("Removed Category", 1.0, 1.0, CategoryState.GRADED)));

        assertThat(badPractice.getPenalty()).isEqualTo(4.0);
        assertThat(badPractice.getMaxPenalty()).isEqualTo(12.0);
        assertThat(badPractice.getState()).isEqualTo(CategoryState.GRADED);
        verify(categoryRepository).saveAll(List.of(badPractice));
    }

    @Test
    void anExistingTestCaseIsUpdatedAndAMissingOneCreatedActive() {
        ProgrammingExerciseTestCase reported = testCase(1L, "testLogin", 1.0, true);
        when(testCaseRepository.findByExerciseId(10L)).thenReturn(Set.of(reported));

        service.applyTestCases(milestone, List.of(new MilestoneTestCaseExportDTO("TESTLOGIN", 3.0, 2.0, 1.0, Visibility.AFTER_DUE_DATE),
                new MilestoneTestCaseExportDTO("testLogout", 5.0, null, null, null)));

        assertThat(reported.getWeight()).isEqualTo(3.0);
        assertThat(reported.getBonusMultiplier()).isEqualTo(2.0);
        assertThat(reported.getVisibility()).isEqualTo(Visibility.AFTER_DUE_DATE);
        ArgumentCaptor<ProgrammingExerciseTestCase> saved = ArgumentCaptor.forClass(ProgrammingExerciseTestCase.class);
        verify(testCaseRepository, times(2)).save(saved.capture());
        ProgrammingExerciseTestCase created = saved.getAllValues().get(1);
        assertThat(created.getTestName()).isEqualTo("testLogout");
        assertThat(created.getWeight()).isEqualTo(5.0);
        assertThat(created.isActive()).isTrue();
        assertThat(created.getExercise()).isSameAs(milestone);
    }

    @Test
    void aTestCaseTheBuildInsertedConcurrentlyIsUpdatedInstead() {
        ProgrammingExerciseTestCase fromBuild = testCase(7L, "testLogin", 1.0, true);
        when(testCaseRepository.findByExerciseId(10L)).thenReturn(Set.of()).thenReturn(Set.of(fromBuild));
        when(testCaseRepository.save(any(ProgrammingExerciseTestCase.class))).thenThrow(new DataIntegrityViolationException("duplicate")).thenReturn(fromBuild);

        service.applyTestCases(milestone, List.of(new MilestoneTestCaseExportDTO("testLogin", 4.0, 1.0, 0.0, Visibility.ALWAYS)));

        assertThat(fromBuild.getWeight()).isEqualTo(4.0);
        verify(testCaseRepository, times(2)).save(any(ProgrammingExerciseTestCase.class));
    }

    @Test
    void anArchiveWithoutSettingsChangesNothing() {
        service.applyTestCases(milestone, null);
        service.applyCategories(milestone, List.of());

        verify(testCaseRepository, never()).findByExerciseId(10L);
        verify(categoryRepository, never()).findByExerciseId(10L);
    }
}
