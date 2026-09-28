package de.tum.cit.aet.artemis.programming.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.apache.commons.io.FileUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.multipart.MultipartFile;

import de.tum.cit.aet.artemis.assessment.dto.GradingCriterionDTO;
import de.tum.cit.aet.artemis.assessment.dto.GradingInstructionDTO;
import de.tum.cit.aet.artemis.assessment.repository.GradingCriterionRepository;
import de.tum.cit.aet.artemis.core.util.FilePathConverter;
import de.tum.cit.aet.artemis.course.domain.Course;
import de.tum.cit.aet.artemis.exercise.domain.ExerciseMode;
import de.tum.cit.aet.artemis.exercise.domain.ExerciseType;
import de.tum.cit.aet.artemis.exercise.domain.IncludedInOverallScore;
import de.tum.cit.aet.artemis.exercise.domain.MilestoneExerciseGroup;
import de.tum.cit.aet.artemis.exercise.repository.ExerciseTestRepository;
import de.tum.cit.aet.artemis.exercise.service.ExerciseDeletionService;
import de.tum.cit.aet.artemis.exercise.service.ExerciseVariantGroupService;
import de.tum.cit.aet.artemis.fileupload.api.FileUploadImportApi;
import de.tum.cit.aet.artemis.modeling.api.ModelingExerciseImportApi;
import de.tum.cit.aet.artemis.modeling.domain.DiagramType;
import de.tum.cit.aet.artemis.modeling.domain.ModelingExercise;
import de.tum.cit.aet.artemis.programming.dto.MilestoneMemberExportDTO;
import de.tum.cit.aet.artemis.quiz.domain.QuizExercise;
import de.tum.cit.aet.artemis.quiz.domain.QuizMode;
import de.tum.cit.aet.artemis.quiz.domain.ScoringType;
import de.tum.cit.aet.artemis.quiz.dto.exercise.QuizExerciseCreateDTO;
import de.tum.cit.aet.artemis.quiz.dto.question.create.DragAndDropMappingCreateDTO;
import de.tum.cit.aet.artemis.quiz.dto.question.create.DragAndDropQuestionCreateDTO;
import de.tum.cit.aet.artemis.quiz.dto.question.create.DragItemCreateDTO;
import de.tum.cit.aet.artemis.quiz.dto.question.create.DropLocationCreateDTO;
import de.tum.cit.aet.artemis.quiz.service.QuizExerciseService;
import de.tum.cit.aet.artemis.quiz.test_repository.QuizExerciseTestRepository;
import de.tum.cit.aet.artemis.text.api.TextExerciseImportApi;
import de.tum.cit.aet.artemis.text.domain.TextExercise;

@ExtendWith(MockitoExtension.class)
class MilestoneMemberImportExportServiceTest {

    @Mock
    private ExerciseTestRepository exerciseRepository;

    @Mock
    private GradingCriterionRepository gradingCriterionRepository;

    @Mock
    private QuizExerciseTestRepository quizExerciseRepository;

    @Mock
    private QuizExerciseService quizExerciseService;

    @Mock
    private TextExerciseImportApi textExerciseImportApi;

    @Mock
    private ModelingExerciseImportApi modelingExerciseImportApi;

    @Mock
    private ExerciseVariantGroupService exerciseVariantGroupService;

    @Mock
    private ExerciseDeletionService exerciseDeletionService;

    @TempDir
    private Path tempDir;

    private MilestoneMemberImportExportService service;

    private final Course course = new Course();

    private final MilestoneExerciseGroup group = new MilestoneExerciseGroup();

    private Path originalFileUploadPath;

    @BeforeEach
    void setUp() {
        // The quiz import looks up stored drag and drop images below the upload root, so it points at this test's own
        // directory, where none exist yet; the process-wide value is put back afterwards.
        originalFileUploadPath = FilePathConverter.getFileUploadPath();
        FilePathConverter.setFileUploadPath(tempDir.resolve("uploads"));
        service = new MilestoneMemberImportExportService(exerciseRepository, gradingCriterionRepository, quizExerciseRepository, quizExerciseService,
                Optional.of(textExerciseImportApi), Optional.of(modelingExerciseImportApi), Optional.<FileUploadImportApi>empty(), exerciseVariantGroupService,
                exerciseDeletionService);
        course.setId(1L);
    }

    @AfterEach
    void restoreFileUploadPath() {
        if (originalFileUploadPath != null) {
            FilePathConverter.setFileUploadPath(originalFileUploadPath);
        }
    }

    @Test
    void exportDescribesATextExerciseWithoutIds() {
        TextExercise essay = new TextExercise();
        essay.setId(7L);
        essay.setTitle("Essay");
        essay.setExampleSolution("Solution");
        essay.setCategories(Set.of("{\"category\":\"homework\"}"));
        essay.setMaxPoints(10.0);
        when(exerciseRepository.findByIdWithCategoriesAndTeamAssignmentConfigElseThrow(7L)).thenReturn(essay);
        when(gradingCriterionRepository.findByExerciseIdWithEagerGradingCriteria(7L)).thenReturn(Set.of());

        MilestoneMemberExportDTO exported = service.exportMember(essay, tempDir);

        assertThat(exported.type()).isEqualTo(ExerciseType.TEXT);
        assertThat(exported.title()).isEqualTo("Essay");
        assertThat(exported.exampleSolution()).isEqualTo("Solution");
        assertThat(exported.categories()).containsExactly("{\"category\":\"homework\"}");
        assertThat(exported.quiz()).isNull();
    }

    @Test
    void importCreatesATextExerciseThroughItsModuleAndAddsItToTheGroup() throws Exception {
        var criterion = new GradingCriterionDTO(null, "Structure", List.of(new GradingInstructionDTO(null, 1.0, "good", "clear structure", "Well done", 0)));
        var member = new MilestoneMemberExportDTO(ExerciseType.TEXT, "Essay", "essay", "Write", Set.of(), null, ExerciseMode.INDIVIDUAL, null, 10.0, 1.0, null,
                IncludedInOverallScore.INCLUDED_AS_BONUS, true, null, null, "Be fair", List.of(criterion), "Solution", null, null, null, null, null, null);
        TextExercise created = new TextExercise();
        when(textExerciseImportApi.importTextExercise(any(TextExercise.class), any(TextExercise.class))).thenReturn(created);

        assertThat(service.importMember(member, group, course, tempDir)).contains(created);

        ArgumentCaptor<TextExercise> newExercise = ArgumentCaptor.forClass(TextExercise.class);
        verify(textExerciseImportApi).importTextExercise(newExercise.capture(), any(TextExercise.class));
        assertThat(newExercise.getValue().getCourseViaExerciseGroupOrCourseMember()).isSameAs(course);
        assertThat(newExercise.getValue().getTitle()).isEqualTo("Essay");
        assertThat(newExercise.getValue().getExampleSolution()).isEqualTo("Solution");
        assertThat(newExercise.getValue().getIncludedInOverallScore()).isEqualTo(IncludedInOverallScore.INCLUDED_AS_BONUS);
        assertThat(newExercise.getValue().getAllowComplaintsForAutomaticAssessments()).isTrue();
        assertThat(newExercise.getValue().getGradingCriteria()).singleElement().satisfies(entity -> assertThat(entity.getTitle()).isEqualTo("Structure"));
        verify(exerciseVariantGroupService).assignToGroup(created, group);
    }

    @Test
    void importCreatesAModelingExerciseWithItsDiagramType() throws Exception {
        var member = new MilestoneMemberExportDTO(ExerciseType.MODELING, "Model", null, null, null, null, null, null, 5.0, null, null, null, null, null, null, null, null, null,
                DiagramType.ClassDiagram.name(), "{}", "Explanation", null, null, null);
        when(modelingExerciseImportApi.importModelingExercise(any(ModelingExercise.class), any(ModelingExercise.class))).thenAnswer(invocation -> invocation.getArgument(0));

        var created = service.importMember(member, group, course, tempDir);

        assertThat(created).get().isInstanceOfSatisfying(ModelingExercise.class, modeling -> {
            assertThat(modeling.getDiagramType()).isEqualTo(DiagramType.ClassDiagram);
            assertThat(modeling.getExampleSolutionModel()).isEqualTo("{}");
        });
    }

    @Test
    void aMemberOfADisabledModuleIsSkipped() throws Exception {
        var member = new MilestoneMemberExportDTO(ExerciseType.FILE_UPLOAD, "Upload", null, null, null, null, null, null, 5.0, null, null, null, null, null, null, null, null, null,
                null, null, null, "pdf", null, null);

        assertThat(service.importMember(member, group, course, tempDir)).isEmpty();
    }

    @Test
    void importCreatesAQuizAndUploadsTheExportedDragAndDropImages() throws Exception {
        String pictureName = "DragItem_milestone_import_test_picture.png";
        FileUtils.writeStringToFile(tempDir.resolve(MilestoneMemberImportExportService.QUIZ_FILES_DIRECTORY).resolve("drag-item").resolve(pictureName).toFile(), "png",
                StandardCharsets.UTF_8);
        var question = new DragAndDropQuestionCreateDTO("Match", "text", null, null, 2.0, ScoringType.ALL_OR_NOTHING, false, null,
                List.of(new DropLocationCreateDTO(1L, 0.0, 0.0, 10.0, 10.0)), List.of(new DragItemCreateDTO(2L, null, pictureName)),
                List.of(new DragAndDropMappingCreateDTO(2L, 1L)));
        var quiz = new QuizExerciseCreateDTO("Quiz", null, null, null, null, ExerciseMode.INDIVIDUAL, IncludedInOverallScore.INCLUDED_COMPLETELY, null, null, null, false,
                QuizMode.INDIVIDUAL, 60, null, List.of(question));
        var member = new MilestoneMemberExportDTO(ExerciseType.QUIZ, "Quiz", null, null, null, null, null, null, 2.0, null, null, null, null, null, null, null, null, null, null,
                null, null, null, quiz, List.of());
        QuizExercise created = new QuizExercise();
        when(quizExerciseService.createQuizExercise(any(QuizExercise.class), anyList(), eq(false), isNull())).thenReturn(created);

        assertThat(service.importMember(member, group, course, tempDir)).contains(created);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<MultipartFile>> files = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<QuizExercise> quizExercise = ArgumentCaptor.forClass(QuizExercise.class);
        verify(quizExerciseService).createQuizExercise(quizExercise.capture(), files.capture(), eq(false), isNull());
        assertThat(quizExercise.getValue().getCourseViaExerciseGroupOrCourseMember()).isSameAs(course);
        assertThat(files.getValue()).extracting(MultipartFile::getOriginalFilename).containsExactly(pictureName);
        verify(exerciseVariantGroupService).assignToGroup(created, group);
    }
}
