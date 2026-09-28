package de.tum.cit.aet.artemis.programming.service;

import static de.tum.cit.aet.artemis.core.config.Constants.ARTEMIS_FILE_PATH_PREFIX;
import static de.tum.cit.aet.artemis.core.config.Constants.PROFILE_CORE;
import static de.tum.cit.aet.artemis.core.config.Constants.PROGRAMMING_EXERCISE_SHORT_NAME_MAX_LENGTH;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import de.tum.cit.aet.artemis.account.domain.User;
import de.tum.cit.aet.artemis.core.exception.BadRequestAlertException;
import de.tum.cit.aet.artemis.core.exception.InternalServerErrorException;
import de.tum.cit.aet.artemis.core.service.FileService;
import de.tum.cit.aet.artemis.core.service.TempFileUtilService;
import de.tum.cit.aet.artemis.core.service.ZipFileService;
import de.tum.cit.aet.artemis.core.util.FilePathConverter;
import de.tum.cit.aet.artemis.core.util.FileUtil;
import de.tum.cit.aet.artemis.course.domain.Course;
import de.tum.cit.aet.artemis.course.repository.CourseRepository;
import de.tum.cit.aet.artemis.exercise.domain.Exercise;
import de.tum.cit.aet.artemis.exercise.domain.MilestoneExerciseGroup;
import de.tum.cit.aet.artemis.exercise.dto.CreateMilestoneExerciseGroupDTO;
import de.tum.cit.aet.artemis.exercise.repository.MilestoneExerciseGroupRepository;
import de.tum.cit.aet.artemis.exercise.service.ExerciseVersionService;
import de.tum.cit.aet.artemis.plagiarism.domain.PlagiarismDetectionConfigHelper;
import de.tum.cit.aet.artemis.programming.domain.MilestoneExercise;
import de.tum.cit.aet.artemis.programming.domain.ProgrammingExercise;
import de.tum.cit.aet.artemis.programming.domain.ProgrammingExerciseBuildConfig;
import de.tum.cit.aet.artemis.programming.domain.UserStoryExercise;
import de.tum.cit.aet.artemis.programming.dto.ImportProgrammingExerciseRequestDTO;
import de.tum.cit.aet.artemis.programming.dto.MilestoneExportDetailsDTO;
import de.tum.cit.aet.artemis.programming.dto.MilestoneImportOptionsDTO;
import de.tum.cit.aet.artemis.programming.dto.MilestoneUserStoryExportDTO;
import de.tum.cit.aet.artemis.programming.repository.ProgrammingExerciseRepository;

/**
 * Exports a {@link MilestoneExerciseGroup} into a single archive and imports such an archive into a course.
 * <p>
 * The archive holds three things:
 * <ul>
 * <li>{@value #MILESTONE_ARCHIVE_FILE_NAME}: the anchor {@link MilestoneExercise}, exported exactly like any other
 * programming exercise (repositories, problem statement, exercise details), so that it can be recreated through the
 * regular import from file,</li>
 * <li>{@value #DETAILS_FILE_NAME}: the manifest ({@link MilestoneExportDetailsDTO}) describing the group and its
 * {@link UserStoryExercise}s, which have no repositories of their own,</li>
 * <li>{@value #EMBEDDED_FILES_DIRECTORY}/: the files embedded in the user stories' problem statements.</li>
 * </ul>
 * The import recreates the milestone through {@link ProgrammingExerciseImportFromFileService}, wires the group the same
 * way {@link MilestoneExerciseService#createMilestoneGroup} does, and then creates every user story through
 * {@link MilestoneExerciseService#createUserStoryExercise}, so the imported group ends up exactly like one built by hand.
 * <p>
 * Kept in a class of its own, deliberately without touching the existing export and import services, so that it can be
 * maintained next to the upstream code without merge conflicts.
 */
@Profile(PROFILE_CORE)
@Lazy
@Service
public class MilestoneExerciseImportExportService {

    private static final Logger log = LoggerFactory.getLogger(MilestoneExerciseImportExportService.class);

    private static final String ENTITY_NAME = "milestoneExerciseGroup";

    /** The manifest at the root of a milestone archive. */
    public static final String DETAILS_FILE_NAME = "Milestone-Details.json";

    /** The nested programming exercise export of the anchor milestone exercise. */
    public static final String MILESTONE_ARCHIVE_FILE_NAME = "Milestone-Exercise.zip";

    /** The directory holding the files embedded in the user stories' problem statements. */
    public static final String EMBEDDED_FILES_DIRECTORY = "files";

    /** The archive layout written by this class; an archive of a newer layout is rejected. */
    private static final int CURRENT_FORMAT_VERSION = 1;

    /** A file embedded into a problem statement, e.g. {@code /api/core/files/markdown/Markdown_2024-abc.png}. */
    private static final Pattern EMBEDDED_FILE_REFERENCE = Pattern.compile(Pattern.quote(ARTEMIS_FILE_PATH_PREFIX + "markdown/") + "([^)\"'\\s]+)");

    private static final Pattern NON_ALPHANUMERIC = Pattern.compile("[^a-zA-Z0-9]");

    /** How long the temporary export and import directories live before they are removed. */
    private static final long TEMPORARY_DIRECTORY_LIFETIME_MINUTES = 5;

    @Value("${artemis.repo-download-clone-path}")
    private Path repoDownloadClonePath;

    private final MilestoneExerciseGroupRepository milestoneExerciseGroupRepository;

    private final ProgrammingExerciseRepository programmingExerciseRepository;

    private final CourseRepository courseRepository;

    private final ProgrammingExerciseExportService programmingExerciseExportService;

    private final ProgrammingExerciseImportFromFileService programmingExerciseImportFromFileService;

    private final ProgrammingExerciseTaskService programmingExerciseTaskService;

    private final MilestoneExerciseService milestoneExerciseService;

    private final ProgrammingExerciseDeletionService programmingExerciseDeletionService;

    private final ExerciseVersionService exerciseVersionService;

    private final FileService fileService;

    private final ZipFileService zipFileService;

    private final TempFileUtilService tempFileUtilService;

    private final JsonMapper jsonMapper;

    public MilestoneExerciseImportExportService(MilestoneExerciseGroupRepository milestoneExerciseGroupRepository, ProgrammingExerciseRepository programmingExerciseRepository,
            CourseRepository courseRepository, ProgrammingExerciseExportService programmingExerciseExportService,
            ProgrammingExerciseImportFromFileService programmingExerciseImportFromFileService, ProgrammingExerciseTaskService programmingExerciseTaskService,
            MilestoneExerciseService milestoneExerciseService, ProgrammingExerciseDeletionService programmingExerciseDeletionService, ExerciseVersionService exerciseVersionService,
            FileService fileService, ZipFileService zipFileService, TempFileUtilService tempFileUtilService, JsonMapper jsonMapper) {
        this.milestoneExerciseGroupRepository = milestoneExerciseGroupRepository;
        this.programmingExerciseRepository = programmingExerciseRepository;
        this.courseRepository = courseRepository;
        this.programmingExerciseExportService = programmingExerciseExportService;
        this.programmingExerciseImportFromFileService = programmingExerciseImportFromFileService;
        this.programmingExerciseTaskService = programmingExerciseTaskService;
        this.milestoneExerciseService = milestoneExerciseService;
        this.programmingExerciseDeletionService = programmingExerciseDeletionService;
        this.exerciseVersionService = exerciseVersionService;
        this.fileService = fileService;
        this.zipFileService = zipFileService;
        this.tempFileUtilService = tempFileUtilService;
        this.jsonMapper = jsonMapper;
    }

    // ----------------------------------------------------------------------------------------------------------------
    // Export
    // ----------------------------------------------------------------------------------------------------------------

    /**
     * Exports a milestone group with its anchor milestone exercise and all of its user stories into one zip archive.
     * The archive lives in a temporary directory that is removed after a few minutes.
     *
     * @param groupId  the id of the milestone group to export
     * @param courseId the id of the course the group belongs to
     * @return the path to the created zip archive
     * @throws IOException if the archive cannot be written
     */
    public Path exportMilestoneGroup(long groupId, long courseId) throws IOException {
        MilestoneExerciseGroup group = milestoneExerciseGroupRepository.findByIdAndCourseIdElseThrow(groupId, courseId);
        long milestoneExerciseId = milestoneExerciseGroupRepository.findMilestoneExerciseIdByGroupId(groupId)
                .orElseThrow(() -> new BadRequestAlertException("The milestone group has no anchor milestone exercise", ENTITY_NAME, "milestoneExerciseMissing"));
        ProgrammingExercise milestoneExercise = programmingExerciseRepository
                .findByIdWithPlagiarismDetectionConfigTeamConfigGradingCriteriaAndCategoriesElseThrow(milestoneExerciseId);

        Path exportDir = fileService.createTemporaryDirectory(repoDownloadClonePath, "milestone-export-", TEMPORARY_DIRECTORY_LIFETIME_MINUTES);
        List<Path> pathsToBeZipped = new ArrayList<>();

        // The anchor exercise owns the shared repositories, so it goes through the regular programming exercise export.
        List<String> exportErrors = Collections.synchronizedList(new ArrayList<>());
        Path milestoneArchive = programmingExerciseExportService.exportProgrammingExerciseForDownload(milestoneExercise, exportErrors);
        if (!exportErrors.isEmpty()) {
            log.warn("Export of milestone exercise {} reported errors: {}", milestoneExerciseId, exportErrors);
        }
        Path milestoneArchiveInExport = exportDir.resolve(MILESTONE_ARCHIVE_FILE_NAME);
        FileUtils.copyFile(milestoneArchive.toFile(), milestoneArchiveInExport.toFile());
        pathsToBeZipped.add(milestoneArchiveInExport);

        // Creation order is the order the instructor built the stories in, which is the order the import recreates them in.
        List<Exercise> userStories = group.getExercises().stream().filter(UserStoryExercise.class::isInstance).sorted(Comparator.comparing(Exercise::getId)).toList();
        Path embeddedFilesDir = exportDir.resolve(EMBEDDED_FILES_DIRECTORY);
        List<MilestoneUserStoryExportDTO> userStoryDetails = new ArrayList<>();
        for (Exercise member : userStories) {
            if (!(programmingExerciseRepository
                    .findByIdWithPlagiarismDetectionConfigTeamConfigGradingCriteriaAndCategoriesElseThrow(member.getId()) instanceof UserStoryExercise userStory)) {
                continue;
            }
            // The ids a problem statement refers to its tests by mean nothing in another course, the names do.
            programmingExerciseTaskService.replaceTestIdsWithNames(userStory);
            copyEmbeddedFiles(userStory.getProblemStatement(), embeddedFilesDir);
            userStoryDetails.add(MilestoneUserStoryExportDTO.of(userStory));
        }
        if (Files.isDirectory(embeddedFilesDir)) {
            pathsToBeZipped.add(embeddedFilesDir);
        }

        MilestoneExportDetailsDTO details = new MilestoneExportDetailsDTO(CURRENT_FORMAT_VERSION, group.getTitle(), MILESTONE_ARCHIVE_FILE_NAME, userStoryDetails);
        pathsToBeZipped.add(FileUtil.writeObjectToJsonFile(details, jsonMapper, exportDir.resolve(DETAILS_FILE_NAME)));

        String timestamp = ZonedDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-Hmss"));
        String courseShortName = milestoneExercise.getCourseViaExerciseGroupOrCourseMember().getShortName();
        String zipFileName = FileUtil.sanitizeFilename("Milestone-" + courseShortName + "-" + group.getTitle() + "-" + groupId + "-" + timestamp + ".zip");
        Path zipPath = exportDir.resolve(zipFileName);
        zipFileService.createTemporaryZipFile(zipPath, pathsToBeZipped, TEMPORARY_DIRECTORY_LIFETIME_MINUTES);
        return zipPath;
    }

    /**
     * Copies every file embedded into the problem statement into the export, so that another instance can serve it too.
     * A file that cannot be found is skipped: the problem statement stays importable, only the image is missing.
     */
    private void copyEmbeddedFiles(@Nullable String problemStatement, Path embeddedFilesDir) {
        if (problemStatement == null) {
            return;
        }
        Matcher matcher = EMBEDDED_FILE_REFERENCE.matcher(problemStatement);
        while (matcher.find()) {
            String fileName = Path.of(matcher.group(1)).getFileName().toString();
            Path source = FilePathConverter.getMarkdownFilePath().resolve(fileName);
            Path target = embeddedFilesDir.resolve(fileName);
            if (!Files.exists(source) || Files.exists(target)) {
                continue;
            }
            try {
                FileUtils.copyFile(source.toFile(), target.toFile());
            }
            catch (IOException e) {
                log.warn("Could not copy the embedded file {} into the milestone export", fileName, e);
            }
        }
    }

    // ----------------------------------------------------------------------------------------------------------------
    // Import
    // ----------------------------------------------------------------------------------------------------------------

    /**
     * Imports a milestone archive created by {@link #exportMilestoneGroup} into the course: the anchor milestone
     * exercise with the content of its repositories, the group, and every user story of the group.
     * <p>
     * Titles and short names must be unique among a course's programming exercises. The ones taken from the archive
     * are therefore made unique by appending a number, unless the author chose the milestone's own ones explicitly.
     * If creating a user story fails, everything created so far is removed again, so a failed import leaves nothing
     * behind.
     *
     * @param courseId the id of the course to import into
     * @param zipFile  the uploaded milestone archive
     * @param options  the author's choices for the import
     * @param user     the user running the import
     * @return the imported milestone group, with its members and anchor initialized
     */
    public MilestoneExerciseGroup importMilestoneGroup(long courseId, MultipartFile zipFile, MilestoneImportOptionsDTO options, User user) {
        if (!"zip".equalsIgnoreCase(FilenameUtils.getExtension(zipFile.getOriginalFilename()))) {
            throw new BadRequestAlertException("The file is not a zip file", ENTITY_NAME, "fileNotZip");
        }
        Course course = courseRepository.findByIdElseThrow(courseId);
        Path importDir = null;
        try {
            importDir = tempFileUtilService.createTempDirectory("milestone-import-");
            Path uploadedArchive = importDir.resolve("milestone-archive.zip");
            zipFile.transferTo(uploadedArchive);

            Path milestoneArchive = importDir.resolve(MILESTONE_ARCHIVE_FILE_NAME);
            Path embeddedFilesDir = importDir.resolve(EMBEDDED_FILES_DIRECTORY);
            MilestoneExportDetailsDTO details = extractArchive(uploadedArchive, milestoneArchive, embeddedFilesDir);
            ImportProgrammingExerciseRequestDTO milestoneDetails = readExerciseDetails(milestoneArchive);

            MilestoneExerciseGroup group = importMilestoneExercise(milestoneDetails, milestoneArchive, options, course, user);
            copyEmbeddedFilesIntoMarkdownDirectory(embeddedFilesDir);
            importUserStories(details.userStories(), group, course);
            return milestoneExerciseService.findByIdAndCourseIdElseThrow(group.getId(), courseId);
        }
        catch (IOException | GitAPIException | URISyntaxException e) {
            log.error("Error while importing a milestone exercise group into course {}", courseId, e);
            throw new InternalServerErrorException("Error while importing the milestone: " + e.getMessage());
        }
        finally {
            fileService.scheduleDirectoryPathForRecursiveDeletion(importDir, TEMPORARY_DIRECTORY_LIFETIME_MINUTES);
        }
    }

    /**
     * Reads the manifest out of the archive and extracts only what the import needs: the nested milestone export and
     * the embedded files. Entries are written to fixed locations named by the import, never to a path an entry names,
     * so an archive cannot write outside the import directory.
     */
    private MilestoneExportDetailsDTO extractArchive(Path archive, Path milestoneArchiveTarget, Path embeddedFilesTarget) throws IOException {
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            ZipEntry detailsEntry = findEntry(zip, DETAILS_FILE_NAME);
            if (detailsEntry == null) {
                throw new BadRequestAlertException("The archive does not contain " + DETAILS_FILE_NAME + ". Is it a milestone export?", ENTITY_NAME, "milestoneDetailsMissing");
            }
            MilestoneExportDetailsDTO details;
            try (InputStream inputStream = zip.getInputStream(detailsEntry)) {
                details = jsonMapper.readValue(inputStream, MilestoneExportDetailsDTO.class);
            }
            catch (JacksonException e) {
                throw new BadRequestAlertException("The milestone details of the archive are not valid", ENTITY_NAME, "milestoneDetailsInvalid");
            }
            if (details.formatVersion() > CURRENT_FORMAT_VERSION) {
                throw new BadRequestAlertException("The archive was exported by a newer version of Artemis", ENTITY_NAME, "milestoneFormatUnsupported");
            }

            String milestoneArchiveName = details.milestoneArchive() == null ? MILESTONE_ARCHIVE_FILE_NAME : details.milestoneArchive();
            ZipEntry milestoneEntry = findEntry(zip, milestoneArchiveName);
            if (milestoneEntry == null) {
                throw new BadRequestAlertException("The archive does not contain the milestone exercise", ENTITY_NAME, "milestoneArchiveMissing");
            }
            try (InputStream inputStream = zip.getInputStream(milestoneEntry)) {
                FileUtils.copyInputStreamToFile(inputStream, milestoneArchiveTarget.toFile());
            }

            // The detail file sits at the root, so whatever prefix it has is where the embedded files directory is too.
            String prefix = detailsEntry.getName().substring(0, detailsEntry.getName().length() - DETAILS_FILE_NAME.length());
            String embeddedFilesPrefix = prefix + EMBEDDED_FILES_DIRECTORY + "/";
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory() || !entry.getName().startsWith(embeddedFilesPrefix)) {
                    continue;
                }
                String fileName = Path.of(entry.getName()).getFileName().toString();
                try (InputStream inputStream = zip.getInputStream(entry)) {
                    FileUtils.copyInputStreamToFile(inputStream, embeddedFilesTarget.resolve(fileName).toFile());
                }
            }
            return details;
        }
    }

    /**
     * Finds the entry with the given file name at the root of the archive, or directly below a single root directory
     * (which is how some tools re-pack an archive).
     */
    @Nullable
    private static ZipEntry findEntry(ZipFile zip, String fileName) {
        ZipEntry entry = zip.getEntry(fileName);
        if (entry != null) {
            return entry;
        }
        return zip.stream().filter(candidate -> !candidate.isDirectory()).filter(candidate -> {
            String name = candidate.getName();
            return name.endsWith("/" + fileName) && name.indexOf('/') == name.length() - fileName.length() - 1;
        }).findFirst().orElse(null);
    }

    /**
     * Reads the exercise details of the nested milestone export, the same file the regular import from file binds.
     */
    private ImportProgrammingExerciseRequestDTO readExerciseDetails(Path milestoneArchive) throws IOException {
        try (ZipFile zip = new ZipFile(milestoneArchive.toFile())) {
            List<? extends ZipEntry> detailEntries = zip.stream().filter(entry -> !entry.isDirectory()).filter(entry -> {
                String fileName = Path.of(entry.getName()).getFileName().toString();
                return fileName.startsWith(ProgrammingExerciseExportService.EXPORTED_EXERCISE_DETAILS_FILE_PREFIX) && fileName.endsWith(".json");
            }).toList();
            if (detailEntries.size() != 1) {
                throw new BadRequestAlertException("The milestone exercise in the archive has no unique exercise details file", ENTITY_NAME, "milestoneExerciseDetailsMissing");
            }
            try (InputStream inputStream = zip.getInputStream(detailEntries.getFirst())) {
                return jsonMapper.readValue(inputStream, ImportProgrammingExerciseRequestDTO.class);
            }
            catch (JacksonException e) {
                throw new BadRequestAlertException("The exercise details of the milestone exercise are not valid", ENTITY_NAME, "milestoneExerciseDetailsInvalid");
            }
        }
    }

    /**
     * Recreates the anchor milestone exercise from its nested export and wires a new group around it, mirroring
     * {@link MilestoneExerciseService#createMilestoneGroup} - only the exercise comes from the archive instead of the
     * default template.
     */
    private MilestoneExerciseGroup importMilestoneExercise(ImportProgrammingExerciseRequestDTO milestoneDetails, Path milestoneArchive, MilestoneImportOptionsDTO options,
            Course course, User user) throws IOException, GitAPIException, URISyntaxException {
        String title = hasText(options.title()) ? options.title().strip() : uniqueTitle(milestoneDetails.title(), course);
        String shortName = hasText(options.shortName()) ? options.shortName().strip() : uniqueShortName(milestoneDetails.shortName(), course);
        boolean keepDates = options.keepDates();

        // Only what a milestone may be configured with is taken over: the creation payload is exactly that set, so it
        // also applies the fields fixed for every milestone (points, score inclusion).
        CreateMilestoneExerciseGroupDTO createDTO = new CreateMilestoneExerciseGroupDTO(title, shortName, milestoneDetails.problemStatement(), null,
                milestoneDetails.programmingLanguage(), milestoneDetails.projectType(), milestoneDetails.packageName(), milestoneDetails.allowOnlineEditor(),
                milestoneDetails.allowOfflineIde(), Boolean.TRUE.equals(milestoneDetails.allowOnlineIde()), milestoneDetails.staticCodeAnalysisEnabled(),
                milestoneDetails.maxStaticCodeAnalysisPenalty(), keepDates ? milestoneDetails.releaseDate() : null, keepDates ? milestoneDetails.startDate() : null,
                keepDates ? milestoneDetails.dueDate() : null, keepDates ? milestoneDetails.assessmentDueDate() : null,
                keepDates ? milestoneDetails.exampleSolutionPublicationDate() : null, null);
        MilestoneExercise milestoneExercise = createDTO.toMilestoneExercise();
        milestoneExercise.setCourse(course);
        // The complete build configuration of the export, which the creation payload would only partially carry.
        ProgrammingExerciseBuildConfig buildConfig = milestoneDetails.buildConfig() == null ? new ProgrammingExerciseBuildConfig() : milestoneDetails.buildConfig().toEntity();
        PlagiarismDetectionConfigHelper.validatePlagiarismDetectionConfigOrThrow(milestoneExercise, ENTITY_NAME);

        MilestoneExercise created = (MilestoneExercise) programmingExerciseImportFromFileService.importProgrammingExerciseFromFile(milestoneExercise, buildConfig,
                new PathMultipartFile(milestoneArchive), course, user);
        exerciseVersionService.createExerciseVersion(created, user);

        MilestoneExerciseGroup group = new MilestoneExerciseGroup();
        group.setTitle(created.getTitle());
        group.setMilestoneExercise(created);
        group.setCourse(course);
        return milestoneExerciseGroupRepository.save(group);
    }

    /**
     * Creates the user stories through the regular creation path, which provisions their configuration, test cases
     * and channel from the milestone. On a failure the partially imported group is removed again.
     */
    private void importUserStories(@Nullable List<MilestoneUserStoryExportDTO> userStories, MilestoneExerciseGroup group, Course course) {
        if (userStories == null || userStories.isEmpty()) {
            return;
        }
        List<Long> createdIds = new ArrayList<>();
        try {
            for (MilestoneUserStoryExportDTO userStory : userStories) {
                String title = uniqueTitle(userStory.title(), course);
                String shortName = uniqueShortName(userStory.shortName(), course);
                UserStoryExercise created = milestoneExerciseService.createUserStoryExercise(userStory.toCreateDTO(title, shortName), group.getId(), course.getId());
                createdIds.add(created.getId());
            }
        }
        catch (RuntimeException e) {
            log.error("Importing the user stories of milestone group {} failed, removing the partially imported group", group.getId(), e);
            removePartialImport(group, createdIds, course);
            throw e;
        }
    }

    private void removePartialImport(MilestoneExerciseGroup group, List<Long> createdUserStoryIds, Course course) {
        try {
            // Never with the base repositories: a user story's repository uris are the milestone's.
            createdUserStoryIds.forEach(id -> programmingExerciseDeletionService.delete(id, false));
            milestoneExerciseService.deleteMilestoneGroup(group.getId(), course.getId());
        }
        catch (RuntimeException cleanupException) {
            log.error("Could not remove the partially imported milestone group {}", group.getId(), cleanupException);
        }
    }

    /**
     * Copies the embedded files of the user stories to where problem statements are served from. An existing file of
     * the same name is kept: the names carry a random part, so a clash means it is the same file.
     */
    private static void copyEmbeddedFilesIntoMarkdownDirectory(Path embeddedFilesDir) throws IOException {
        if (!Files.isDirectory(embeddedFilesDir)) {
            return;
        }
        try (var files = Files.list(embeddedFilesDir)) {
            for (Path file : files.toList()) {
                Path target = FilePathConverter.getMarkdownFilePath().resolve(file.getFileName());
                if (!Files.exists(target)) {
                    FileUtils.copyFile(file.toFile(), target.toFile());
                }
            }
        }
    }

    // ----------------------------------------------------------------------------------------------------------------
    // Unique titles and short names
    // ----------------------------------------------------------------------------------------------------------------

    private String uniqueTitle(@Nullable String title, Course course) {
        String base = hasText(title) ? title.strip() : "Milestone";
        String candidate = base;
        for (int suffix = 2; isTitleTaken(candidate, course); suffix++) {
            candidate = base + " " + suffix;
        }
        return candidate;
    }

    private String uniqueShortName(@Nullable String shortName, Course course) {
        String base = hasText(shortName) ? NON_ALPHANUMERIC.matcher(shortName).replaceAll("") : "milestone";
        if (base.length() > PROGRAMMING_EXERCISE_SHORT_NAME_MAX_LENGTH) {
            base = base.substring(0, PROGRAMMING_EXERCISE_SHORT_NAME_MAX_LENGTH);
        }
        String candidate = base;
        for (int suffix = 2; isShortNameTaken(candidate, course); suffix++) {
            String suffixText = String.valueOf(suffix);
            candidate = base.substring(0, Math.min(base.length(), PROGRAMMING_EXERCISE_SHORT_NAME_MAX_LENGTH - suffixText.length())) + suffixText;
        }
        return candidate;
    }

    private boolean isTitleTaken(String title, Course course) {
        return programmingExerciseRepository.countByTitleAndCourse(title, course) + programmingExerciseRepository.countByTitleAndExerciseGroupExamCourse(title, course) > 0;
    }

    private boolean isShortNameTaken(String shortName, Course course) {
        return programmingExerciseRepository.countByShortNameAndCourse(shortName, course)
                + programmingExerciseRepository.countByShortNameAndExerciseGroupExamCourse(shortName, course) > 0;
    }

    private static boolean hasText(@Nullable String value) {
        return value != null && !value.isBlank();
    }

    /**
     * Hands a file on disk to {@link ProgrammingExerciseImportFromFileService}, which takes the upload it was written
     * for. Nothing else of the multipart contract is needed there.
     */
    private record PathMultipartFile(Path path) implements MultipartFile {

        @Override
        public String getName() {
            return "file";
        }

        @Override
        public String getOriginalFilename() {
            return path.getFileName().toString();
        }

        @Override
        public String getContentType() {
            return "application/zip";
        }

        @Override
        public boolean isEmpty() {
            return getSize() == 0;
        }

        @Override
        public long getSize() {
            return path.toFile().length();
        }

        @Override
        public byte[] getBytes() throws IOException {
            return Files.readAllBytes(path);
        }

        @Override
        public InputStream getInputStream() throws IOException {
            return Files.newInputStream(path);
        }

        @Override
        public void transferTo(File dest) throws IOException {
            FileUtils.copyFile(path.toFile(), dest);
        }

        @Override
        public void transferTo(Path dest) throws IOException {
            FileUtils.copyFile(path.toFile(), dest.toFile());
        }
    }
}
