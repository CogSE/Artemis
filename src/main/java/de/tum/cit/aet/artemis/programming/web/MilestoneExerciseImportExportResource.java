package de.tum.cit.aet.artemis.programming.web;

import static de.tum.cit.aet.artemis.core.config.Constants.PROFILE_CORE;
import static de.tum.cit.aet.artemis.core.util.TimeLogUtil.formatDurationFrom;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;

import jakarta.servlet.http.HttpServletRequest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import de.tum.cit.aet.artemis.account.repository.UserRepository;
import de.tum.cit.aet.artemis.core.exception.BadRequestAlertException;
import de.tum.cit.aet.artemis.core.security.annotations.enforceRoleInCourse.EnforceAtLeastEditorInCourse;
import de.tum.cit.aet.artemis.core.security.annotations.enforceRoleInCourse.EnforceAtLeastInstructorInCourse;
import de.tum.cit.aet.artemis.core.service.feature.Feature;
import de.tum.cit.aet.artemis.core.service.feature.FeatureToggle;
import de.tum.cit.aet.artemis.core.service.featureusage.FeatureUsage;
import de.tum.cit.aet.artemis.core.service.featureusage.UserFeature;
import de.tum.cit.aet.artemis.core.util.HeaderUtil;
import de.tum.cit.aet.artemis.exercise.domain.MilestoneExerciseGroup;
import de.tum.cit.aet.artemis.exercise.dto.MilestoneExerciseGroupDTO;
import de.tum.cit.aet.artemis.programming.dto.MilestoneImportOptionsDTO;
import de.tum.cit.aet.artemis.programming.service.MilestoneExerciseImportExportService;

/**
 * REST controller for exporting a milestone exercise group into an archive and importing such an archive into a course.
 * <p>
 * Authorization mirrors the programming exercise export and import: exporting needs an instructor, since the archive
 * contains the solution, importing an editor of the target course.
 */
@Profile(PROFILE_CORE)
@FeatureToggle(Feature.ProgrammingExercises)
@Lazy
@FeatureUsage(UserFeature.PROGRAMMING_IMPORT_EXPORT)
@RestController
@RequestMapping("api/programming/")
public class MilestoneExerciseImportExportResource {

    private static final Logger log = LoggerFactory.getLogger(MilestoneExerciseImportExportResource.class);

    private static final String ENTITY_NAME = "milestoneExerciseGroup";

    @Value("${jhipster.clientApp.name}")
    private String applicationName;

    private final MilestoneExerciseImportExportService milestoneExerciseImportExportService;

    private final UserRepository userRepository;

    public MilestoneExerciseImportExportResource(MilestoneExerciseImportExportService milestoneExerciseImportExportService, UserRepository userRepository) {
        this.milestoneExerciseImportExportService = milestoneExerciseImportExportService;
        this.userRepository = userRepository;
    }

    /**
     * GET /courses/:courseId/milestone-exercise-groups/:groupId/export : Export a milestone exercise group, with its
     * milestone exercise's repositories and all of its user stories, as one zip archive.
     *
     * @param courseId the id of the course the group belongs to
     * @param groupId  the id of the milestone group to export
     * @return the ResponseEntity with status 200 (OK) and the archive in the body
     * @throws IOException if the archive cannot be created
     */
    @GetMapping("courses/{courseId}/milestone-exercise-groups/{groupId}/export")
    @EnforceAtLeastInstructorInCourse
    @FeatureToggle(Feature.Exports)
    public ResponseEntity<Resource> exportMilestoneExerciseGroup(@PathVariable long courseId, @PathVariable long groupId) throws IOException {
        log.debug("REST request to export MilestoneExerciseGroup {} of course {}", groupId, courseId);
        long start = System.nanoTime();
        Path zipPath = milestoneExerciseImportExportService.exportMilestoneGroup(groupId, courseId);
        log.info("Export of milestone exercise group {} was successful in {}.", groupId, formatDurationFrom(start));
        InputStreamResource resource = new InputStreamResource(Files.newInputStream(zipPath));
        return ResponseEntity.ok().contentLength(Files.size(zipPath)).contentType(MediaType.APPLICATION_OCTET_STREAM).header("filename", zipPath.getFileName().toString())
                .body(resource);
    }

    /**
     * POST /courses/:courseId/milestone-exercise-groups/import-from-file : Import a milestone archive created by the
     * export above into the course, recreating the milestone exercise with its repositories, the group and all of its
     * members.
     * <p>
     * The archive is the raw request body rather than a multipart upload: it carries the full history of the
     * milestone's repositories and may therefore be larger than the multipart limit every other upload is held to. The
     * service reads at most {@link MilestoneExerciseImportExportService#MAX_IMPORT_ARCHIVE_SIZE} bytes of it.
     *
     * @param courseId  the id of the course to import into
     * @param title     the title of the imported milestone, or none to keep the archive's one
     * @param shortName the short name of the imported milestone, or none to keep the archive's one
     * @param keepDates whether the exported dates are kept
     * @param request   the request whose body is the milestone archive
     * @return the ResponseEntity with status 201 (Created) and the imported group in the body
     * @throws URISyntaxException if the Location URI syntax is incorrect
     * @throws IOException        if the request body cannot be read
     */
    @PostMapping(value = "courses/{courseId}/milestone-exercise-groups/import-from-file", consumes = { "application/zip", MediaType.APPLICATION_OCTET_STREAM_VALUE })
    @EnforceAtLeastEditorInCourse
    public ResponseEntity<MilestoneExerciseGroupDTO> importMilestoneExerciseGroup(@PathVariable long courseId, @RequestParam(required = false) String title,
            @RequestParam(required = false) String shortName, @RequestParam(defaultValue = "false") boolean keepDates, HttpServletRequest request)
            throws URISyntaxException, IOException {
        log.debug("REST request to import a MilestoneExerciseGroup into course {}", courseId);
        if (request.getContentLengthLong() > MilestoneExerciseImportExportService.MAX_IMPORT_ARCHIVE_SIZE) {
            throw new BadRequestAlertException("The milestone archive exceeds the maximum size", ENTITY_NAME, "milestoneArchiveTooLarge");
        }
        var user = userRepository.getUserWithAuthorities();
        MilestoneImportOptionsDTO options = new MilestoneImportOptionsDTO(title, shortName, keepDates);
        MilestoneExerciseGroup group = milestoneExerciseImportExportService.importMilestoneGroup(courseId, request.getInputStream(), options, user);
        return ResponseEntity.created(new URI("/api/exercise/courses/" + courseId + "/milestone-exercise-groups/" + group.getId()))
                .headers(HeaderUtil.createEntityCreationAlert(applicationName, true, ENTITY_NAME, group.getTitle())).body(new MilestoneExerciseGroupDTO(group));
    }
}
