package de.tum.cit.aet.artemis.programming.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import org.apache.commons.io.FileUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.multipart.MultipartFile;

import tools.jackson.databind.json.JsonMapper;

import de.tum.cit.aet.artemis.account.domain.User;
import de.tum.cit.aet.artemis.core.exception.BadRequestAlertException;
import de.tum.cit.aet.artemis.core.service.FileService;
import de.tum.cit.aet.artemis.core.service.TempFileUtilService;
import de.tum.cit.aet.artemis.core.service.ZipFileService;
import de.tum.cit.aet.artemis.core.test_repository.CourseTestRepository;
import de.tum.cit.aet.artemis.core.util.JsonObjectMapper;
import de.tum.cit.aet.artemis.course.domain.Course;
import de.tum.cit.aet.artemis.exercise.domain.MilestoneExerciseGroup;
import de.tum.cit.aet.artemis.exercise.dto.CreateUserStoryExerciseDTO;
import de.tum.cit.aet.artemis.exercise.repository.MilestoneExerciseGroupRepository;
import de.tum.cit.aet.artemis.exercise.service.ExerciseVersionService;
import de.tum.cit.aet.artemis.programming.domain.MilestoneExercise;
import de.tum.cit.aet.artemis.programming.domain.ProgrammingExercise;
import de.tum.cit.aet.artemis.programming.domain.ProgrammingExerciseBuildConfig;
import de.tum.cit.aet.artemis.programming.domain.UserStoryExercise;
import de.tum.cit.aet.artemis.programming.dto.MilestoneExportDetailsDTO;
import de.tum.cit.aet.artemis.programming.dto.MilestoneImportOptionsDTO;
import de.tum.cit.aet.artemis.programming.dto.MilestoneUserStoryExportDTO;
import de.tum.cit.aet.artemis.programming.test_repository.ProgrammingExerciseTestRepository;

@ExtendWith(MockitoExtension.class)
class MilestoneExerciseImportExportServiceTest {

    @Mock
    private MilestoneExerciseGroupRepository milestoneExerciseGroupRepository;

    @Mock
    private ProgrammingExerciseTestRepository programmingExerciseRepository;

    @Mock
    private CourseTestRepository courseRepository;

    @Mock
    private ProgrammingExerciseExportService programmingExerciseExportService;

    @Mock
    private ProgrammingExerciseImportFromFileService programmingExerciseImportFromFileService;

    @Mock
    private ProgrammingExerciseTaskService programmingExerciseTaskService;

    @Mock
    private MilestoneExerciseService milestoneExerciseService;

    @Mock
    private ProgrammingExerciseDeletionService programmingExerciseDeletionService;

    @Mock
    private ExerciseVersionService exerciseVersionService;

    @Mock
    private FileService fileService;

    @Mock
    private TempFileUtilService tempFileUtilService;

    @TempDir
    private Path tempDir;

    private final JsonMapper jsonMapper = JsonObjectMapper.get();

    private MilestoneExerciseImportExportService service;

    private Course course;

    @BeforeEach
    void setUp() {
        service = new MilestoneExerciseImportExportService(milestoneExerciseGroupRepository, programmingExerciseRepository, courseRepository, programmingExerciseExportService,
                programmingExerciseImportFromFileService, programmingExerciseTaskService, milestoneExerciseService, programmingExerciseDeletionService, exerciseVersionService,
                fileService, new ZipFileService(fileService), tempFileUtilService, jsonMapper);
        ReflectionTestUtils.setField(service, "repoDownloadClonePath", tempDir);
        course = new Course();
        course.setId(1L);
        course.setShortName("course");
    }

    @Test
    void exportBundlesTheMilestoneArchiveWithTheUserStoriesInCreationOrder() throws Exception {
        MilestoneExercise milestone = new MilestoneExercise();
        milestone.setId(10L);
        milestone.setCourse(course);
        UserStoryExercise secondStory = userStory(12L, "Logout", "logout");
        UserStoryExercise firstStory = userStory(11L, "Login", "login");
        MilestoneExerciseGroup group = new MilestoneExerciseGroup();
        group.setId(5L);
        group.setTitle("Sprint 1");
        group.setExercises(Set.of(secondStory, firstStory));

        Path exportDir = Files.createDirectories(tempDir.resolve("export"));
        Path milestoneArchive = tempDir.resolve("material.zip");
        FileUtils.writeStringToFile(milestoneArchive.toFile(), "milestone", StandardCharsets.UTF_8);
        when(milestoneExerciseGroupRepository.findByIdAndCourseIdElseThrow(5L, 1L)).thenReturn(group);
        when(milestoneExerciseGroupRepository.findMilestoneExerciseIdByGroupId(5L)).thenReturn(Optional.of(10L));
        when(programmingExerciseRepository.findByIdWithPlagiarismDetectionConfigTeamConfigGradingCriteriaAndCategoriesElseThrow(10L)).thenReturn(milestone);
        when(programmingExerciseRepository.findByIdWithPlagiarismDetectionConfigTeamConfigGradingCriteriaAndCategoriesElseThrow(11L)).thenReturn(firstStory);
        when(programmingExerciseRepository.findByIdWithPlagiarismDetectionConfigTeamConfigGradingCriteriaAndCategoriesElseThrow(12L)).thenReturn(secondStory);
        when(fileService.createTemporaryDirectory(eq(tempDir), anyString(), anyLong())).thenReturn(exportDir);
        when(programmingExerciseExportService.exportProgrammingExerciseForDownload(eq(milestone), anyList())).thenReturn(milestoneArchive);

        Path zipPath = service.exportMilestoneGroup(5L, 1L);

        try (ZipFile zip = new ZipFile(zipPath.toFile())) {
            assertThat(zip.getEntry(MilestoneExerciseImportExportService.MILESTONE_ARCHIVE_FILE_NAME)).isNotNull();
            MilestoneExportDetailsDTO details;
            try (InputStream inputStream = zip.getInputStream(zip.getEntry(MilestoneExerciseImportExportService.DETAILS_FILE_NAME))) {
                details = jsonMapper.readValue(inputStream, MilestoneExportDetailsDTO.class);
            }
            assertThat(details.groupTitle()).isEqualTo("Sprint 1");
            assertThat(details.userStories()).extracting(MilestoneUserStoryExportDTO::shortName).containsExactly("login", "logout");
        }
        verify(programmingExerciseTaskService).replaceTestIdsWithNames(firstStory);
        verify(programmingExerciseTaskService).replaceTestIdsWithNames(secondStory);
    }

    @Test
    void importRecreatesTheMilestoneAndItsUserStoriesWithUniqueNames() throws Exception {
        MultipartFile archive = milestoneArchive(List.of(storyDetails("Login", "login"), storyDetails("Logout", "logout")));
        prepareImport();
        // The exported milestone title and the first story's short name are already taken in the target course.
        when(programmingExerciseRepository.countByTitleAndCourse(anyString(), eq(course))).thenAnswer(invocation -> "dtoShapedImport".equals(invocation.getArgument(0)) ? 1L : 0L);
        when(programmingExerciseRepository.countByShortNameAndCourse(anyString(), eq(course))).thenAnswer(invocation -> "login".equals(invocation.getArgument(0)) ? 1L : 0L);
        MilestoneExercise createdMilestone = new MilestoneExercise();
        createdMilestone.setId(20L);
        createdMilestone.setTitle("dtoShapedImport 2");
        when(programmingExerciseImportFromFileService.importProgrammingExerciseFromFile(any(ProgrammingExercise.class), any(ProgrammingExerciseBuildConfig.class),
                any(MultipartFile.class), eq(course), any(User.class))).thenReturn(createdMilestone);
        when(milestoneExerciseGroupRepository.save(any(MilestoneExerciseGroup.class))).thenAnswer(invocation -> {
            MilestoneExerciseGroup saved = invocation.getArgument(0);
            saved.setId(30L);
            return saved;
        });
        when(milestoneExerciseService.createUserStoryExercise(any(CreateUserStoryExerciseDTO.class), eq(30L), eq(1L))).thenAnswer(invocation -> userStory(40L, "story", "story"));
        MilestoneExerciseGroup importedGroup = new MilestoneExerciseGroup();
        when(milestoneExerciseService.findByIdAndCourseIdElseThrow(30L, 1L)).thenReturn(importedGroup);

        MilestoneExerciseGroup result = service.importMilestoneGroup(1L, archive, new MilestoneImportOptionsDTO(null, "sprintTwo", false), new User());

        assertThat(result).isSameAs(importedGroup);
        ArgumentCaptor<ProgrammingExercise> milestoneCaptor = ArgumentCaptor.forClass(ProgrammingExercise.class);
        ArgumentCaptor<MultipartFile> nestedArchiveCaptor = ArgumentCaptor.forClass(MultipartFile.class);
        verify(programmingExerciseImportFromFileService).importProgrammingExerciseFromFile(milestoneCaptor.capture(), any(ProgrammingExerciseBuildConfig.class),
                nestedArchiveCaptor.capture(), eq(course), any(User.class));
        assertThat(milestoneCaptor.getValue()).isInstanceOf(MilestoneExercise.class);
        assertThat(milestoneCaptor.getValue().getTitle()).isEqualTo("dtoShapedImport 2");
        assertThat(milestoneCaptor.getValue().getShortName()).isEqualTo("sprintTwo");
        assertThat(milestoneCaptor.getValue().getProgrammingLanguage()).hasToString("JAVA");
        assertThat(milestoneCaptor.getValue().getDueDate()).isNull();
        assertThat(nestedArchiveCaptor.getValue().getOriginalFilename()).endsWith(".zip");

        ArgumentCaptor<CreateUserStoryExerciseDTO> storyCaptor = ArgumentCaptor.forClass(CreateUserStoryExerciseDTO.class);
        verify(milestoneExerciseService, times(2)).createUserStoryExercise(storyCaptor.capture(), eq(30L), eq(1L));
        assertThat(storyCaptor.getAllValues()).extracting(CreateUserStoryExerciseDTO::shortName).containsExactly("login2", "logout");
        assertThat(storyCaptor.getAllValues()).extracting(CreateUserStoryExerciseDTO::problemStatement).containsExactly("[task][Login](testLogin)", "[task][Logout](testLogout)");
    }

    @Test
    void aFailedUserStoryRemovesThePartiallyImportedGroup() throws Exception {
        MultipartFile archive = milestoneArchive(List.of(storyDetails("Login", "login"), storyDetails("Logout", "logout")));
        prepareImport();
        MilestoneExercise createdMilestone = new MilestoneExercise();
        createdMilestone.setId(20L);
        createdMilestone.setTitle("dtoShapedImport");
        when(programmingExerciseImportFromFileService.importProgrammingExerciseFromFile(any(ProgrammingExercise.class), any(ProgrammingExerciseBuildConfig.class),
                any(MultipartFile.class), eq(course), any(User.class))).thenReturn(createdMilestone);
        when(milestoneExerciseGroupRepository.save(any(MilestoneExerciseGroup.class))).thenAnswer(invocation -> {
            MilestoneExerciseGroup saved = invocation.getArgument(0);
            saved.setId(30L);
            return saved;
        });
        when(milestoneExerciseService.createUserStoryExercise(any(CreateUserStoryExerciseDTO.class), eq(30L), eq(1L))).thenReturn(userStory(40L, "Login", "login"))
                .thenThrow(new BadRequestAlertException("invalid", "Exercise", "invalid"));

        assertThatThrownBy(() -> service.importMilestoneGroup(1L, archive, new MilestoneImportOptionsDTO(null, null, true), new User()))
                .isInstanceOf(BadRequestAlertException.class);

        // The created story shares the milestone's repositories, so they must survive its removal.
        verify(programmingExerciseDeletionService).delete(40L, false);
        verify(milestoneExerciseService).deleteMilestoneGroup(30L, 1L);
    }

    @Test
    void anArchiveWithoutMilestoneDetailsIsRejected() throws Exception {
        prepareTempDirectory();
        when(courseRepository.findByIdElseThrow(1L)).thenReturn(course);
        var plainProgrammingExport = new ClassPathResource("test-data/import-from-file/valid-import-dto-details.zip");
        MultipartFile archive = new MockMultipartFile("file", "plain.zip", "application/zip", plainProgrammingExport.getInputStream());

        assertThatThrownBy(() -> service.importMilestoneGroup(1L, archive, new MilestoneImportOptionsDTO(null, null, false), new User()))
                .isInstanceOf(BadRequestAlertException.class);
        verify(programmingExerciseImportFromFileService, never()).importProgrammingExerciseFromFile(any(), any(), any(), any(), any());
    }

    private void prepareImport() throws IOException {
        prepareTempDirectory();
        when(courseRepository.findByIdElseThrow(1L)).thenReturn(course);
    }

    private void prepareTempDirectory() throws IOException {
        Path importDir = Files.createDirectories(tempDir.resolve("import"));
        when(tempFileUtilService.createTempDirectory(anyString())).thenReturn(importDir);
    }

    private static UserStoryExercise userStory(long id, String title, String shortName) {
        UserStoryExercise userStory = new UserStoryExercise();
        userStory.setId(id);
        userStory.setTitle(title);
        userStory.setShortName(shortName);
        userStory.setMaxPoints(5.0);
        return userStory;
    }

    private static MilestoneUserStoryExportDTO storyDetails(String title, String shortName) {
        return new MilestoneUserStoryExportDTO(title, shortName, "[task][" + title + "](test" + title + ")", null, null, null, 5.0, 0.0, null, null, null, null, null, List.of());
    }

    /**
     * Builds a milestone archive around the regular programming exercise export from the test resources.
     */
    private MultipartFile milestoneArchive(List<MilestoneUserStoryExportDTO> userStories) throws IOException {
        var details = new MilestoneExportDetailsDTO(1, "Sprint 1", MilestoneExerciseImportExportService.MILESTONE_ARCHIVE_FILE_NAME, new ArrayList<>(userStories));
        var nestedExport = new ClassPathResource("test-data/import-from-file/valid-import-dto-details.zip");
        Path archive = tempDir.resolve("milestone.zip");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(archive))) {
            zip.putNextEntry(new ZipEntry(MilestoneExerciseImportExportService.DETAILS_FILE_NAME));
            zip.write(jsonMapper.writeValueAsBytes(details));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry(MilestoneExerciseImportExportService.MILESTONE_ARCHIVE_FILE_NAME));
            try (InputStream inputStream = nestedExport.getInputStream()) {
                inputStream.transferTo(zip);
            }
            zip.closeEntry();
        }
        return new MockMultipartFile("file", "milestone.zip", "application/zip", Files.readAllBytes(archive));
    }
}
