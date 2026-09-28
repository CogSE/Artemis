package de.tum.cit.aet.artemis.programming.service;

import static de.tum.cit.aet.artemis.core.config.Constants.PROFILE_CORE;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.commons.io.FileUtils;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import de.tum.cit.aet.artemis.assessment.domain.GradingCriterion;
import de.tum.cit.aet.artemis.assessment.dto.GradingCriterionDTO;
import de.tum.cit.aet.artemis.assessment.repository.GradingCriterionRepository;
import de.tum.cit.aet.artemis.core.util.FilePathConverter;
import de.tum.cit.aet.artemis.course.domain.Course;
import de.tum.cit.aet.artemis.exercise.domain.Exercise;
import de.tum.cit.aet.artemis.exercise.domain.ExerciseMode;
import de.tum.cit.aet.artemis.exercise.domain.ExerciseType;
import de.tum.cit.aet.artemis.exercise.domain.MilestoneExerciseGroup;
import de.tum.cit.aet.artemis.exercise.dto.TeamAssignmentConfigDTO;
import de.tum.cit.aet.artemis.exercise.repository.ExerciseRepository;
import de.tum.cit.aet.artemis.exercise.service.ExerciseDeletionService;
import de.tum.cit.aet.artemis.exercise.service.ExerciseVariantGroupService;
import de.tum.cit.aet.artemis.fileupload.api.FileUploadImportApi;
import de.tum.cit.aet.artemis.fileupload.domain.FileUploadExercise;
import de.tum.cit.aet.artemis.modeling.api.ModelingExerciseImportApi;
import de.tum.cit.aet.artemis.modeling.domain.DiagramType;
import de.tum.cit.aet.artemis.modeling.domain.ModelingExercise;
import de.tum.cit.aet.artemis.programming.dto.MilestoneMemberExportDTO;
import de.tum.cit.aet.artemis.quiz.domain.QuizExercise;
import de.tum.cit.aet.artemis.quiz.dto.exercise.QuizExerciseCreateDTO;
import de.tum.cit.aet.artemis.quiz.dto.question.create.DragAndDropQuestionCreateDTO;
import de.tum.cit.aet.artemis.quiz.dto.question.create.DragItemCreateDTO;
import de.tum.cit.aet.artemis.quiz.repository.QuizExerciseRepository;
import de.tum.cit.aet.artemis.quiz.service.QuizExerciseService;
import de.tum.cit.aet.artemis.text.api.TextExerciseImportApi;
import de.tum.cit.aet.artemis.text.domain.TextExercise;

/**
 * Exports and imports the members of a milestone exercise group that are not user stories: quiz, text, modeling and
 * file upload exercises. The milestone and its user stories are handled by {@link MilestoneExerciseImportExportService},
 * which delegates the remaining members here.
 * <p>
 * Text, modeling and file upload exercises are recreated through their modules' regular import (reached through the
 * module APIs, since the modules are optional), a quiz through the regular quiz creation. The created exercise then
 * joins the group like any exercise assigned to it by hand, which also gives it the group's timeline.
 */
@Profile(PROFILE_CORE)
@Lazy
@Service
public class MilestoneMemberImportExportService {

    private static final Logger log = LoggerFactory.getLogger(MilestoneMemberImportExportService.class);

    /** The archive directory holding the drag and drop images of the exported quizzes. */
    static final String QUIZ_FILES_DIRECTORY = "quiz-files";

    private static final String BACKGROUND_DIRECTORY = "background";

    private static final String DRAG_ITEM_DIRECTORY = "drag-item";

    private final ExerciseRepository exerciseRepository;

    private final GradingCriterionRepository gradingCriterionRepository;

    private final QuizExerciseRepository quizExerciseRepository;

    private final QuizExerciseService quizExerciseService;

    private final Optional<TextExerciseImportApi> textExerciseImportApi;

    private final Optional<ModelingExerciseImportApi> modelingExerciseImportApi;

    private final Optional<FileUploadImportApi> fileUploadImportApi;

    private final ExerciseVariantGroupService exerciseVariantGroupService;

    private final ExerciseDeletionService exerciseDeletionService;

    public MilestoneMemberImportExportService(ExerciseRepository exerciseRepository, GradingCriterionRepository gradingCriterionRepository,
            QuizExerciseRepository quizExerciseRepository, QuizExerciseService quizExerciseService, Optional<TextExerciseImportApi> textExerciseImportApi,
            Optional<ModelingExerciseImportApi> modelingExerciseImportApi, Optional<FileUploadImportApi> fileUploadImportApi,
            ExerciseVariantGroupService exerciseVariantGroupService, ExerciseDeletionService exerciseDeletionService) {
        this.exerciseRepository = exerciseRepository;
        this.gradingCriterionRepository = gradingCriterionRepository;
        this.quizExerciseRepository = quizExerciseRepository;
        this.quizExerciseService = quizExerciseService;
        this.textExerciseImportApi = textExerciseImportApi;
        this.modelingExerciseImportApi = modelingExerciseImportApi;
        this.fileUploadImportApi = fileUploadImportApi;
        this.exerciseVariantGroupService = exerciseVariantGroupService;
        this.exerciseDeletionService = exerciseDeletionService;
    }

    /**
     * Whether a member of this type can be exported here. Programming exercises are not: the only programming
     * exercises a milestone group holds are its user stories, which the milestone export describes itself.
     *
     * @param exercise a member of a milestone group
     * @return whether {@link #exportMember} handles it
     */
    public boolean isSupported(Exercise exercise) {
        return switch (exercise.getExerciseType()) {
            case QUIZ, TEXT, MODELING, FILE_UPLOAD -> true;
            default -> false;
        };
    }

    // ----------------------------------------------------------------------------------------------------------------
    // Export
    // ----------------------------------------------------------------------------------------------------------------

    /**
     * Describes a member for the export and copies the drag and drop images of a quiz into the export directory.
     *
     * @param member    a supported member of the exported group
     * @param exportDir the directory the archive is assembled in
     * @return the exportable description of the member
     */
    public MilestoneMemberExportDTO exportMember(Exercise member, Path exportDir) {
        if (member.getExerciseType() == ExerciseType.QUIZ) {
            return exportQuiz(member.getId(), exportDir);
        }
        Exercise exercise = exerciseRepository.findByIdWithCategoriesAndTeamAssignmentConfigElseThrow(member.getId());
        List<GradingCriterionDTO> criteria = gradingCriterionRepository.findByExerciseIdWithEagerGradingCriteria(exercise.getId()).stream().map(GradingCriterionDTO::of)
                .map(GradingCriterionDTO::withoutIds).toList();
        TeamAssignmentConfigDTO teamConfig = exercise.getMode() == ExerciseMode.TEAM && exercise.getTeamAssignmentConfig() != null
                ? TeamAssignmentConfigDTO.of(exercise.getTeamAssignmentConfig()).withoutId()
                : null;

        String exampleSolution = null;
        String diagramType = null;
        String exampleSolutionModel = null;
        String exampleSolutionExplanation = null;
        String filePattern = null;
        switch (exercise) {
            case TextExercise textExercise -> exampleSolution = textExercise.getExampleSolution();
            case ModelingExercise modelingExercise -> {
                diagramType = modelingExercise.getDiagramType() == null ? null : modelingExercise.getDiagramType().name();
                exampleSolutionModel = modelingExercise.getExampleSolutionModel();
                exampleSolutionExplanation = modelingExercise.getExampleSolutionExplanation();
            }
            case FileUploadExercise fileUploadExercise -> {
                exampleSolution = fileUploadExercise.getExampleSolution();
                filePattern = fileUploadExercise.getFilePattern();
            }
            default -> {
            }
        }
        return new MilestoneMemberExportDTO(exercise.getExerciseType(), exercise.getTitle(), exercise.getShortName(), exercise.getProblemStatement(),
                new HashSet<>(exercise.getCategories()), exercise.getDifficulty(), exercise.getMode(), teamConfig, exercise.getMaxPoints(), exercise.getBonusPoints(),
                exercise.getAssessmentType(), exercise.getIncludedInOverallScore(), exercise.getAllowComplaintsForAutomaticAssessments(), exercise.getPresentationScoreEnabled(),
                exercise.getSecondCorrectionEnabled(), exercise.getGradingInstructions(), criteria, exampleSolution, diagramType, exampleSolutionModel, exampleSolutionExplanation,
                filePattern, null, null);
    }

    private MilestoneMemberExportDTO exportQuiz(long quizExerciseId, Path exportDir) {
        QuizExercise quizExercise = quizExerciseRepository.findByIdWithQuestionsAndCompetenciesAndBatchesAndGradingCriteriaElseThrow(quizExerciseId);
        QuizExerciseCreateDTO full = QuizExerciseCreateDTO.of(quizExercise);
        // Dates are the group's, batches belong to runs of the source quiz, and competencies to the source course.
        QuizExerciseCreateDTO quiz = new QuizExerciseCreateDTO(full.title(), null, null, null, full.difficulty(), full.mode(), full.includedInOverallScore(), null,
                full.categories(), null, full.randomizeQuestionOrder(), full.quizMode(), full.duration(), null, full.quizQuestions());

        List<String> quizFiles = new ArrayList<>();
        for (var question : quiz.quizQuestions()) {
            if (question instanceof DragAndDropQuestionCreateDTO dragAndDropQuestion) {
                copyQuizFile(dragAndDropQuestion.backgroundFilePath(), FilePathConverter.getDragAndDropBackgroundFilePath(), BACKGROUND_DIRECTORY, exportDir, quizFiles);
                for (DragItemCreateDTO dragItem : dragAndDropQuestion.dragItems()) {
                    copyQuizFile(dragItem.pictureFilePath(), FilePathConverter.getDragItemFilePath(), DRAG_ITEM_DIRECTORY, exportDir, quizFiles);
                }
            }
        }
        return new MilestoneMemberExportDTO(ExerciseType.QUIZ, quizExercise.getTitle(), quizExercise.getShortName(), quizExercise.getProblemStatement(), null, null, null, null,
                quizExercise.getMaxPoints(), quizExercise.getBonusPoints(), null, null, null, null, null, null, null, null, null, null, null, null, quiz, quizFiles);
    }

    private static void copyQuizFile(@Nullable String fileName, Path sourceDirectory, String archiveDirectory, Path exportDir, List<String> quizFiles) {
        if (fileName == null) {
            return;
        }
        Path source = sourceDirectory.resolve(Path.of(fileName).getFileName().toString());
        String archivePath = QUIZ_FILES_DIRECTORY + "/" + archiveDirectory + "/" + source.getFileName();
        if (!Files.exists(source)) {
            log.warn("The drag and drop image {} of an exported quiz does not exist and is left out of the milestone export", source);
            return;
        }
        try {
            FileUtils.copyFile(source.toFile(), exportDir.resolve(archivePath).toFile());
            quizFiles.add(archivePath);
        }
        catch (IOException e) {
            log.warn("Could not copy the drag and drop image {} into the milestone export", source, e);
        }
    }

    // ----------------------------------------------------------------------------------------------------------------
    // Import
    // ----------------------------------------------------------------------------------------------------------------

    /**
     * Recreates an exported member in the course and adds it to the group.
     *
     * @param member       the exported member
     * @param group        the imported milestone group, with its anchor exercise fetched
     * @param course       the course to import into
     * @param extractedDir the directory the archive's quiz images were extracted to (by their archive path)
     * @return the created exercise, or empty if its module is disabled on this instance
     * @throws IOException if the images of a quiz cannot be stored
     */
    public Optional<Exercise> importMember(MilestoneMemberExportDTO member, MilestoneExerciseGroup group, Course course, Path extractedDir) throws IOException {
        Optional<Exercise> created = switch (member.type()) {
            case QUIZ -> Optional.of(importQuiz(member, course, extractedDir));
            case TEXT -> textExerciseImportApi.map(api -> {
                TextExercise exercise = new TextExercise();
                applyCommonFields(member, exercise, course);
                exercise.setExampleSolution(member.exampleSolution());
                return api.importTextExercise(exercise, new TextExercise());
            });
            case MODELING -> modelingExerciseImportApi.map(api -> {
                ModelingExercise exercise = new ModelingExercise();
                applyCommonFields(member, exercise, course);
                exercise.setDiagramType(member.diagramType() == null ? null : DiagramType.valueOf(member.diagramType()));
                exercise.setExampleSolutionModel(member.exampleSolutionModel());
                exercise.setExampleSolutionExplanation(member.exampleSolutionExplanation());
                return api.importModelingExercise(exercise, new ModelingExercise());
            });
            case FILE_UPLOAD -> fileUploadImportApi.map(api -> {
                FileUploadExercise exercise = new FileUploadExercise();
                applyCommonFields(member, exercise, course);
                exercise.setExampleSolution(member.exampleSolution());
                exercise.setFilePattern(member.filePattern());
                return api.importFileUploadExercise(exercise, new FileUploadExercise());
            });
            default -> Optional.empty();
        };
        if (created.isEmpty()) {
            log.warn("Skipping the {} exercise '{}' of the imported milestone: the module is not enabled or the type is not supported", member.type(), member.title());
            return Optional.empty();
        }
        // Joining the group gives the exercise the group's timeline, exactly like an exercise added by hand.
        exerciseVariantGroupService.assignToGroup(created.get(), group);
        return created;
    }

    /**
     * Fills the fields every exercise type shares. The exercise is handed to its module's import as the new exercise,
     * which keeps every value set here and only fills the rest from the (empty) source.
     */
    private static void applyCommonFields(MilestoneMemberExportDTO member, Exercise exercise, Course course) {
        exercise.setCourse(course);
        exercise.setTitle(member.title());
        exercise.setShortName(member.shortName());
        exercise.setProblemStatement(member.problemStatement());
        exercise.setCategories(member.categories() == null ? new HashSet<>() : new HashSet<>(member.categories()));
        exercise.setDifficulty(member.difficulty());
        ExerciseMode mode = member.mode() == null ? ExerciseMode.INDIVIDUAL : member.mode();
        if (mode == ExerciseMode.TEAM && member.teamAssignmentConfig() == null) {
            // A team exercise cannot exist without its team sizes; without them it is imported as an individual one.
            mode = ExerciseMode.INDIVIDUAL;
        }
        exercise.setMode(mode);
        exercise.setTeamAssignmentConfig(mode == ExerciseMode.TEAM ? member.teamAssignmentConfig().toEntity() : null);
        if (member.maxPoints() != null) {
            exercise.setMaxPoints(member.maxPoints());
        }
        exercise.setBonusPoints(member.bonusPoints() == null ? 0.0 : member.bonusPoints());
        exercise.setAssessmentType(member.assessmentType());
        if (member.includedInOverallScore() != null) {
            exercise.setIncludedInOverallScore(member.includedInOverallScore());
        }
        exercise.setAllowComplaintsForAutomaticAssessments(Boolean.TRUE.equals(member.allowComplaintsForAutomaticAssessments()));
        exercise.setPresentationScoreEnabled(Boolean.TRUE.equals(member.presentationScoreEnabled()));
        exercise.setSecondCorrectionEnabled(Boolean.TRUE.equals(member.secondCorrectionEnabled()));
        exercise.setGradingInstructions(member.gradingInstructions());
        Set<GradingCriterion> criteria = member.gradingCriteria() == null ? new HashSet<>()
                : member.gradingCriteria().stream().map(GradingCriterionDTO::toEntity).collect(Collectors.toCollection(HashSet::new));
        exercise.setGradingCriteria(criteria);
    }

    /**
     * Creates a quiz through the regular quiz creation. Its drag and drop images are handed over as the uploads the
     * creation expects - named by the stored file name the questions refer to - unless an image of that name is already
     * stored here (an import into the instance it was exported from), which the creation copies by itself.
     */
    private QuizExercise importQuiz(MilestoneMemberExportDTO member, Course course, Path extractedDir) throws IOException {
        if (member.quiz() == null) {
            throw new IllegalArgumentException("The exported quiz '" + member.title() + "' carries no questions");
        }
        QuizExercise quizExercise = member.quiz().toDomainObject();
        quizExercise.setCourse(course);
        quizExercise.setCompetencyLinks(new HashSet<>());

        List<MultipartFile> files = new ArrayList<>();
        for (var question : member.quiz().quizQuestions()) {
            if (question instanceof DragAndDropQuestionCreateDTO dragAndDropQuestion) {
                addQuizUpload(dragAndDropQuestion.backgroundFilePath(), FilePathConverter.getDragAndDropBackgroundFilePath(), BACKGROUND_DIRECTORY, extractedDir, files);
                for (DragItemCreateDTO dragItem : dragAndDropQuestion.dragItems()) {
                    addQuizUpload(dragItem.pictureFilePath(), FilePathConverter.getDragItemFilePath(), DRAG_ITEM_DIRECTORY, extractedDir, files);
                }
            }
        }
        return quizExerciseService.createQuizExercise(quizExercise, files, false, null);
    }

    private static void addQuizUpload(@Nullable String fileName, Path storageDirectory, String archiveDirectory, Path extractedDir, List<MultipartFile> files) {
        // Only a plain file name is looked up; the quiz creation rejects anything else anyway, but before this reads it.
        if (fileName == null || fileName.contains("/") || fileName.contains("\\") || fileName.startsWith(".") || Files.exists(storageDirectory.resolve(fileName))) {
            return;
        }
        Path extracted = extractedDir.resolve(QUIZ_FILES_DIRECTORY).resolve(archiveDirectory).resolve(fileName);
        if (Files.exists(extracted) && files.stream().noneMatch(file -> fileName.equals(file.getOriginalFilename()))) {
            files.add(new MilestoneExerciseImportExportService.PathMultipartFile(extracted, fileName));
        }
    }

    /**
     * Removes a member created by a failed import again.
     *
     * @param exerciseId the id of the created member
     */
    public void deleteMember(long exerciseId) {
        exerciseDeletionService.delete(exerciseId, false);
    }
}
