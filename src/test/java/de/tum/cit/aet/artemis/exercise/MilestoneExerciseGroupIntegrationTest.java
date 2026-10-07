package de.tum.cit.aet.artemis.exercise;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import tools.jackson.databind.JsonNode;

import de.tum.cit.aet.artemis.assessment.domain.AssessmentType;
import de.tum.cit.aet.artemis.assessment.domain.Feedback;
import de.tum.cit.aet.artemis.assessment.domain.Result;
import de.tum.cit.aet.artemis.assessment.domain.ScaFeedback;
import de.tum.cit.aet.artemis.course.domain.Course;
import de.tum.cit.aet.artemis.course.dto.CourseExercisesForOverviewDTO;
import de.tum.cit.aet.artemis.exercise.domain.Exercise;
import de.tum.cit.aet.artemis.exercise.domain.ExerciseType;
import de.tum.cit.aet.artemis.exercise.domain.ExerciseVariantGroup;
import de.tum.cit.aet.artemis.exercise.domain.MilestoneExerciseGroup;
import de.tum.cit.aet.artemis.exercise.domain.SubmissionType;
import de.tum.cit.aet.artemis.exercise.domain.participation.StudentParticipation;
import de.tum.cit.aet.artemis.exercise.dto.CreateUserStoryExerciseDTO;
import de.tum.cit.aet.artemis.exercise.dto.ExerciseOverviewDTO;
import de.tum.cit.aet.artemis.exercise.dto.ExerciseVariantGroupDTO;
import de.tum.cit.aet.artemis.exercise.dto.MilestoneAssessmentDTO;
import de.tum.cit.aet.artemis.exercise.dto.MilestoneAssessmentExerciseDTO;
import de.tum.cit.aet.artemis.exercise.dto.MilestoneAssessmentStudentDTO;
import de.tum.cit.aet.artemis.exercise.dto.MilestoneExerciseGroupDTO;
import de.tum.cit.aet.artemis.exercise.dto.MilestoneStatusDTO;
import de.tum.cit.aet.artemis.exercise.dto.UpdateMilestoneExerciseGroupDTO;
import de.tum.cit.aet.artemis.exercise.dto.UserStoryExerciseDTO;
import de.tum.cit.aet.artemis.exercise.repository.ExerciseVariantGroupRepository;
import de.tum.cit.aet.artemis.exercise.repository.MilestoneExerciseGroupRepository;
import de.tum.cit.aet.artemis.fileupload.util.FileUploadExerciseFactory;
import de.tum.cit.aet.artemis.programming.AbstractProgrammingIntegrationIndependentTest;
import de.tum.cit.aet.artemis.programming.domain.MilestoneExercise;
import de.tum.cit.aet.artemis.programming.domain.ProgrammingExercise;
import de.tum.cit.aet.artemis.programming.domain.ProgrammingExerciseStudentParticipation;
import de.tum.cit.aet.artemis.programming.domain.ProgrammingLanguage;
import de.tum.cit.aet.artemis.programming.domain.ProgrammingSubmission;
import de.tum.cit.aet.artemis.programming.domain.ProjectType;
import de.tum.cit.aet.artemis.programming.domain.StaticCodeAnalysisTool;
import de.tum.cit.aet.artemis.programming.domain.UserStoryExercise;
import de.tum.cit.aet.artemis.programming.util.ProgrammingExerciseFactory;
import de.tum.cit.aet.artemis.text.domain.TextSubmission;
import de.tum.cit.aet.artemis.text.util.TextExerciseFactory;

/**
 * Covers {@link MilestoneExerciseGroup} and its dedicated repository/routes, alongside ordinary
 * {@link ExerciseVariantGroup}s in the same course.
 * <p>
 * The two types share one table under a discriminator, so the point of these tests is that neither type's queries ever
 * see the other's rows, and that a milestone group's anchor exercise is actually fetched - its timeline is read through
 * that anchor, so an unfetched one silently reads as "no dates".
 * <p>
 * The milestone group is built directly through the repository rather than through the create endpoint: provisioning a
 * real {@link MilestoneExercise} would drag in the VCS/CI test infrastructure, and nothing here depends on it.
 */
class MilestoneExerciseGroupIntegrationTest extends AbstractProgrammingIntegrationIndependentTest {

    private static final String TEST_PREFIX = "milestonegrpinteg";

    @Autowired
    private MilestoneExerciseGroupRepository milestoneExerciseGroupRepository;

    @Autowired
    private ExerciseVariantGroupRepository exerciseVariantGroupRepository;

    private Course course;

    private MilestoneExercise milestoneExercise;

    private MilestoneExerciseGroup milestoneGroup;

    private ExerciseVariantGroup variantGroup;

    @BeforeEach
    void setUp() {
        userUtilService.addUsers(TEST_PREFIX, 1, 1, 1, 1);
        course = courseUtilService.addEnrolledEmptyCourse(TEST_PREFIX);

        milestoneExercise = new MilestoneExercise();
        milestoneExercise.setTitle("Milestone");
        milestoneExercise.setShortName("milestone" + TEST_PREFIX);
        milestoneExercise.setProgrammingLanguage(ProgrammingLanguage.JAVA);
        milestoneExercise.setCourse(course);
        milestoneExercise.setMaxPoints(0.0);
        // Truncated to milliseconds: PostgreSQL stores no finer precision, so an untruncated value would not survive the
        // round-trip the assertions compare against.
        milestoneExercise.setReleaseDate(ZonedDateTime.now().minusDays(1).truncatedTo(ChronoUnit.MILLIS));
        milestoneExercise.setDueDate(ZonedDateTime.now().plusDays(7).truncatedTo(ChronoUnit.MILLIS));
        milestoneExercise.generateAndSetProjectKey();
        milestoneExercise = (MilestoneExercise) programmingExerciseRepository.save(milestoneExercise);

        milestoneGroup = new MilestoneExerciseGroup();
        milestoneGroup.setTitle("Sprint 1");
        milestoneGroup.setMilestoneExercise(milestoneExercise);
        milestoneGroup.setCourse(course);
        milestoneGroup = milestoneExerciseGroupRepository.save(milestoneGroup);

        variantGroup = new ExerciseVariantGroup();
        variantGroup.setTitle("Loop variants");
        variantGroup.setMaxPoints(100.0);
        variantGroup.setCourse(course);
        variantGroup = exerciseVariantGroupRepository.save(variantGroup);

        course = courseRepository.findWithEagerExerciseVariantGroupsByIdElseThrow(course.getId());
        course.addExerciseVariantGroup(milestoneGroup);
        course.addExerciseVariantGroup(variantGroup);
        courseRepository.save(course);
    }

    private String variantGroupsUrl() {
        return "/api/exercise/courses/" + course.getId() + "/exercise-variant-groups";
    }

    private String milestoneGroupsUrl() {
        return "/api/exercise/courses/" + course.getId() + "/milestone-exercise-groups";
    }

    @Test
    void milestoneAndVariantGroupsAreStoredUnderTheirOwnDiscriminator() {
        assertThat(milestoneExerciseGroupRepository.findAllByCourseId(course.getId())).extracting(ExerciseVariantGroup::getId).containsExactly(milestoneGroup.getId());
        // Each repository sees only its own type: the two share a table, and the variant query cannot fetch a milestone
        // group's anchor without a TREAT that would drop every other group from the result.
        assertThat(exerciseVariantGroupRepository.findAllByCourseId(course.getId())).extracting(ExerciseVariantGroup::getId).containsExactly(variantGroup.getId());
        assertThat(exerciseVariantGroupRepository.findByIdAndCourseId(milestoneGroup.getId(), course.getId())).isEmpty();
    }

    @Test
    void milestoneGroupLookupsRejectAVariantGroup() {
        assertThat(milestoneExerciseGroupRepository.findByIdAndCourseId(milestoneGroup.getId(), course.getId())).isPresent();
        assertThat(milestoneExerciseGroupRepository.findByIdAndCourseId(variantGroup.getId(), course.getId())).isEmpty();
        assertThat(milestoneExerciseGroupRepository.findByIdAndCourseIdWithoutExercises(variantGroup.getId(), course.getId())).isEmpty();
    }

    @Test
    void milestoneGroupLookupsAreScopedToTheCourse() {
        Course otherCourse = courseUtilService.addEnrolledEmptyCourse(TEST_PREFIX + "other");
        assertThat(milestoneExerciseGroupRepository.findByIdAndCourseId(milestoneGroup.getId(), otherCourse.getId())).isEmpty();
        assertThat(milestoneExerciseGroupRepository.findAllByCourseId(otherCourse.getId())).isEmpty();
    }

    @Test
    void theAnchorExerciseIsFetchedSoTheGroupCanReportItsTimeline() {
        MilestoneExerciseGroup loaded = milestoneExerciseGroupRepository.findByIdAndCourseIdElseThrow(milestoneGroup.getId(), course.getId());

        assertThat(loaded.getMilestoneExercise()).isNotNull();
        assertThat(loaded.getMilestoneExercise().getId()).isEqualTo(milestoneExercise.getId());
        // Read through the group, which delegates to the anchor - null here would mean the anchor was left a proxy.
        assertThat(loaded.getDueDate()).isNotNull();
        assertThat(loaded.getDueDate().toInstant()).isEqualTo(milestoneExercise.getDueDate().toInstant());
    }

    @Test
    void theAnchorExerciseIdIsResolvableWithoutLoadingIt() {
        assertThat(milestoneExerciseGroupRepository.findMilestoneExerciseIdByGroupId(milestoneGroup.getId())).contains(milestoneExercise.getId());
        assertThat(milestoneExerciseGroupRepository.countExercisesByGroupId(milestoneGroup.getId())).isZero();
    }

    @Test
    @WithMockUser(username = TEST_PREFIX + "editor1", roles = "EDITOR")
    void milestoneGroupEndpointReturnsOnlyMilestoneGroupsWithTheirAnchor() throws Exception {
        List<MilestoneExerciseGroupDTO> groups = request.getList(milestoneGroupsUrl(), HttpStatus.OK, MilestoneExerciseGroupDTO.class);

        assertThat(groups).hasSize(1);
        MilestoneExerciseGroupDTO group = groups.getFirst();
        assertThat(group.id()).isEqualTo(milestoneGroup.getId());
        assertThat(group.title()).isEqualTo("Sprint 1");
        assertThat(group.milestoneExerciseId()).isEqualTo(milestoneExercise.getId());
        assertThat(group.dueDate()).isNotNull();
        assertThat(group.dueDate().toInstant()).isEqualTo(milestoneExercise.getDueDate().toInstant());
    }

    /**
     * The exercise management page is a tutor's too, and its group view is the only place a milestone group is shown at
     * all - and the only way into the group's assessment dashboard. Without the listing, a tutor sees the group's user
     * stories as ungrouped exercises and cannot reach the dashboard.
     */
    @Test
    @WithMockUser(username = TEST_PREFIX + "tutor1", roles = "TA")
    void aTutorReadsTheCoursesMilestoneGroups() throws Exception {
        List<MilestoneExerciseGroupDTO> groups = request.getList(milestoneGroupsUrl(), HttpStatus.OK, MilestoneExerciseGroupDTO.class);

        assertThat(groups).singleElement().satisfies(group -> {
            assertThat(group.id()).isEqualTo(milestoneGroup.getId());
            assertThat(group.milestoneExerciseId()).isEqualTo(milestoneExercise.getId());
        });
        assertThat(request.getList(variantGroupsUrl(), HttpStatus.OK, ExerciseVariantGroupDTO.class)).extracting(ExerciseVariantGroupDTO::id).containsExactly(variantGroup.getId());
    }

    /** Reading the groups is all a tutor may do with them: every write stays with the editors. */
    @Test
    @WithMockUser(username = TEST_PREFIX + "tutor1", roles = "TA")
    void aTutorMayNotChangeAMilestoneGroup() throws Exception {
        UpdateMilestoneExerciseGroupDTO updateDTO = new UpdateMilestoneExerciseGroupDTO(milestoneGroup.getId(), "Renamed", null, null, null, null, null);

        request.putWithResponseBody(milestoneGroupsUrl() + "/" + milestoneGroup.getId(), updateDTO, MilestoneExerciseGroupDTO.class, HttpStatus.FORBIDDEN);
        request.delete(milestoneGroupsUrl() + "/" + milestoneGroup.getId(), HttpStatus.FORBIDDEN);
    }

    @Test
    @WithMockUser(username = TEST_PREFIX + "editor1", roles = "EDITOR")
    void milestoneGroupEndpointExposesTheAnchorsLanguageAndProjectType() throws Exception {
        // The user story create form hides both fields, so the group DTO is the only place it can learn them - and it
        // needs them to seed a new story's problem statement from the milestone's own readme template: the Gradle and
        // Maven templates spell the example test names differently (testBubbleSort() vs testBubbleSort).
        milestoneExercise.setProjectType(ProjectType.PLAIN_MAVEN);
        programmingExerciseRepository.save(milestoneExercise);

        List<MilestoneExerciseGroupDTO> groups = request.getList(milestoneGroupsUrl(), HttpStatus.OK, MilestoneExerciseGroupDTO.class);

        assertThat(groups).singleElement().satisfies(group -> {
            assertThat(group.programmingLanguage()).isEqualTo(ProgrammingLanguage.JAVA);
            assertThat(group.projectType()).isEqualTo(ProjectType.PLAIN_MAVEN);
        });
    }

    @Test
    @WithMockUser(username = TEST_PREFIX + "editor1", roles = "EDITOR")
    void singleGroupEndpointRejectsAVariantGroup() throws Exception {
        request.get(milestoneGroupsUrl() + "/" + milestoneGroup.getId(), HttpStatus.OK, MilestoneExerciseGroupDTO.class);
        request.get(milestoneGroupsUrl() + "/" + variantGroup.getId(), HttpStatus.NOT_FOUND, MilestoneExerciseGroupDTO.class);
    }

    @Test
    @WithMockUser(username = TEST_PREFIX + "editor1", roles = "EDITOR")
    void updatingAMilestoneGroupWritesTheTimelineToItsAnchorExercise() throws Exception {
        ZonedDateTime newDueDate = ZonedDateTime.now().plusDays(21).truncatedTo(ChronoUnit.MILLIS);
        UpdateMilestoneExerciseGroupDTO updateDTO = new UpdateMilestoneExerciseGroupDTO(milestoneGroup.getId(), "Sprint 1 renamed", milestoneExercise.getReleaseDate(), null,
                newDueDate, null, null);

        MilestoneExerciseGroupDTO updated = request.putWithResponseBody(milestoneGroupsUrl() + "/" + milestoneGroup.getId(), updateDTO, MilestoneExerciseGroupDTO.class,
                HttpStatus.OK);

        assertThat(updated.title()).isEqualTo("Sprint 1 renamed");
        assertThat(updated.dueDate().toInstant()).isEqualTo(newDueDate.toInstant());
        // The milestone group stores no dates of its own - they live on the anchor exercise.
        assertThat(programmingExerciseRepository.findByIdElseThrow(milestoneExercise.getId()).getDueDate().toInstant()).isEqualTo(newDueDate.toInstant());
    }

    @Test
    @WithMockUser(username = TEST_PREFIX + "editor1", roles = "EDITOR")
    void updatingWithAMismatchedIdIsRejected() throws Exception {
        UpdateMilestoneExerciseGroupDTO mismatched = new UpdateMilestoneExerciseGroupDTO(milestoneGroup.getId() + 1, "Renamed", null, null, null, null, null);

        request.putWithResponseBody(milestoneGroupsUrl() + "/" + milestoneGroup.getId(), mismatched, MilestoneExerciseGroupDTO.class, HttpStatus.BAD_REQUEST);
    }

    @Test
    @WithMockUser(username = TEST_PREFIX + "editor1", roles = "EDITOR")
    void variantGroupEndpointsStillServeVariantGroupsUnchanged() throws Exception {
        ExerciseVariantGroupDTO fetched = request.get(variantGroupsUrl() + "/" + variantGroup.getId(), HttpStatus.OK, ExerciseVariantGroupDTO.class);

        assertThat(fetched.id()).isEqualTo(variantGroup.getId());
        assertThat(fetched.maxPoints()).isEqualTo(100.0);
    }

    @Test
    @WithMockUser(username = TEST_PREFIX + "student1", roles = "USER")
    void aStudentMayNotReadMilestoneGroups() throws Exception {
        request.getList(milestoneGroupsUrl(), HttpStatus.FORBIDDEN, MilestoneExerciseGroupDTO.class);
    }

    @Test
    @WithMockUser(username = TEST_PREFIX + "editor1", roles = "EDITOR")
    void anEditorMayNotDeleteAMilestoneGroup() throws Exception {
        request.delete(milestoneGroupsUrl() + "/" + milestoneGroup.getId(), HttpStatus.FORBIDDEN);

        assertThat(milestoneExerciseGroupRepository.findByIdAndCourseId(milestoneGroup.getId(), course.getId())).isPresent();
    }

    /**
     * The anchor exercise is never part of any exercise listing, so this endpoint is the only way the student group view
     * reaches its problem statement and learns whether the student has started it.
     */
    @Test
    @WithMockUser(username = TEST_PREFIX + "student1", roles = "USER")
    void milestoneStatusCarriesTheProblemStatementForAStudentWhoHasNotStartedIt() throws Exception {
        MilestoneStatusDTO status = request.get(milestoneGroupsUrl() + "/" + milestoneGroup.getId() + "/milestone-status", HttpStatus.OK, MilestoneStatusDTO.class);

        assertThat(status.participationId()).isNull();
        assertThat(status.problemStatement()).isEqualTo(milestoneExercise.getProblemStatement());
    }

    @Test
    @WithMockUser(username = TEST_PREFIX + "student1", roles = "USER")
    void milestoneStatusRejectsAVariantGroup() throws Exception {
        request.get(milestoneGroupsUrl() + "/" + variantGroup.getId() + "/milestone-status", HttpStatus.NOT_FOUND, MilestoneStatusDTO.class);
    }

    private String userStoryExercisesUrl(long groupId) {
        return milestoneGroupsUrl() + "/" + groupId + "/user-story-exercises";
    }

    private CreateUserStoryExerciseDTO userStoryPayload(String shortNameSuffix) {
        return new CreateUserStoryExerciseDTO("User story", "us" + shortNameSuffix + TEST_PREFIX, null, "Implement the thing", null, null, null, 5.0, null, null, null, null, null,
                null, null, null);
    }

    // The happy path is not covered here: creating a user story runs the whole programming-exercise creation pipeline,
    // which needs the ProgrammingLanguageFeature and version control beans this independent context does not provide (the
    // same reason the milestone group above is built through the repository). What the request contract itself
    // guarantees - that a payload cannot carry the settings the group owns - is covered by CreateUserStoryExerciseDTOTest.

    @Test
    @WithMockUser(username = TEST_PREFIX + "editor1", roles = "EDITOR")
    void creatingAUserStoryExerciseRejectsABlankTitle() throws Exception {
        CreateUserStoryExerciseDTO blankTitle = new CreateUserStoryExerciseDTO(" ", "us3" + TEST_PREFIX, null, null, null, null, null, 5.0, null, null, null, null, null, null,
                null, null);

        request.postWithResponseBody(userStoryExercisesUrl(milestoneGroup.getId()), blankTitle, UserStoryExerciseDTO.class, HttpStatus.BAD_REQUEST);
    }

    @Test
    @WithMockUser(username = TEST_PREFIX + "editor1", roles = "EDITOR")
    void creatingAUserStoryExerciseRejectsAVariantGroup() throws Exception {
        request.postWithResponseBody(userStoryExercisesUrl(variantGroup.getId()), userStoryPayload("4"), UserStoryExerciseDTO.class, HttpStatus.NOT_FOUND);
    }

    @Test
    @WithMockUser(username = TEST_PREFIX + "tutor1", roles = "TA")
    void aTutorMayNotCreateAUserStoryExercise() throws Exception {
        request.postWithResponseBody(userStoryExercisesUrl(milestoneGroup.getId()), userStoryPayload("5"), UserStoryExerciseDTO.class, HttpStatus.FORBIDDEN);
    }

    /**
     * The course overview is what the student group view builds its groups from, so a milestone group's members have to
     * carry the anchor exercise id along with the discriminator - otherwise the view knows the group is a milestone but
     * not which exercise its actions address.
     */
    @Test
    @WithMockUser(username = TEST_PREFIX + "student1", roles = "USER")
    void theCourseOverviewNamesTheAnchorExerciseOnAMilestoneGroupMember() throws Exception {
        UserStoryExercise member = new UserStoryExercise();
        member.setTitle("User story");
        member.setShortName("userstory" + TEST_PREFIX);
        member.setProgrammingLanguage(ProgrammingLanguage.JAVA);
        member.setCourse(course);
        member.setMaxPoints(10.0);
        member.setReleaseDate(ZonedDateTime.now().minusDays(1).truncatedTo(ChronoUnit.MILLIS));
        member.setExerciseVariantGroup(milestoneGroup);
        member.generateAndSetProjectKey();
        programmingExerciseRepository.save(member);

        var overview = request.get("/api/course/courses/" + course.getId() + "/exercises-for-overview", HttpStatus.OK, CourseExercisesForOverviewDTO.class);

        // The anchor itself is never listed: MilestoneExercise.isVisibleToStudents() is always false.
        assertThat(overview.exercises()).extracting(ExerciseOverviewDTO::id).doesNotContain(milestoneExercise.getId());
        assertThat(overview.exercises()).filteredOn(exercise -> exercise.id().equals(member.getId())).singleElement().satisfies(exercise -> {
            assertThat(exercise.exerciseVariantGroup()).isNotNull();
            assertThat(exercise.exerciseVariantGroup().type()).isEqualTo("milestone");
            assertThat(exercise.exerciseVariantGroup().milestoneExerciseId()).isEqualTo(milestoneExercise.getId());
        });
    }

    /**
     * A milestone group's points are the ones its anchor {@link MilestoneExercise} carries - {@code MilestoneScoreService}
     * writes {@code sum(user story points) - static code analysis penalty} (or {@code 0} on a BLOCKING category) onto its
     * result. The per-group breakdown the group detail page shows therefore has to report the anchor's value, and the
     * course total has to count the group through the anchor as well; summing the user stories instead would both ignore
     * the penalty and, since the score calculation deliberately skips milestone group members, leave the whole group out
     * of the course score.
     */
    @Test
    @WithMockUser(username = TEST_PREFIX + "student1", roles = "USER")
    void theCourseOverviewScoresAMilestoneGroupThroughItsAnchorExercise() throws Exception {
        // The anchor's maxPoints mirror the sum of its members' - MilestoneExercisePointsService keeps them in sync.
        milestoneExercise.setMaxPoints(10.0);
        milestoneExercise.setAssessmentType(AssessmentType.AUTOMATIC);
        milestoneExercise = (MilestoneExercise) programmingExerciseRepository.save(milestoneExercise);

        UserStoryExercise member = new UserStoryExercise();
        member.setTitle("User story");
        member.setShortName("usscored" + TEST_PREFIX);
        member.setProgrammingLanguage(ProgrammingLanguage.JAVA);
        member.setCourse(course);
        member.setMaxPoints(10.0);
        member.setAssessmentType(AssessmentType.AUTOMATIC);
        member.setReleaseDate(milestoneExercise.getReleaseDate());
        member.setDueDate(milestoneExercise.getDueDate());
        member.setExerciseVariantGroup(milestoneGroup);
        member.generateAndSetProjectKey();
        member = (UserStoryExercise) programmingExerciseRepository.save(member);

        // The story scored full marks while the anchor was aggregated down to 60% - exactly the divergence a static code
        // analysis penalty produces. Only the anchor's 6.0 may reach the client.
        addRatedResult(member, 100.0);
        addRatedResult(milestoneExercise, 60.0);

        var overview = request.get("/api/course/courses/" + course.getId() + "/exercises-for-overview", HttpStatus.OK, CourseExercisesForOverviewDTO.class);

        // The anchor is projected for the score calculation only; it is never rendered (MilestoneExercise.isVisibleToStudents()).
        assertThat(overview.exercises()).extracting(ExerciseOverviewDTO::id).doesNotContain(milestoneExercise.getId()).contains(member.getId());
        assertThat(overview.achievedPointsPerVariantGroup()).containsEntry(milestoneGroup.getId(), 6.0);
        assertThat(overview.totalScores().maxPoints()).isEqualTo(10.0);
        assertThat(overview.totalScores().studentScores().absoluteScore()).isEqualTo(6.0);
    }

    /** Gives {@code student1} a participation on the exercise with one rated, completed result at the given percentage. */
    private void addRatedResult(ProgrammingExercise exercise, double score) {
        ProgrammingExerciseStudentParticipation participation = participationUtilService.addStudentParticipationForProgrammingExercise(exercise, TEST_PREFIX + "student1");

        ProgrammingSubmission submission = new ProgrammingSubmission();
        submission.setParticipation(participation);
        submission.setCommitHash("commit-" + exercise.getId());
        submission.setType(SubmissionType.MANUAL);
        submission.setSubmissionDate(ZonedDateTime.now().truncatedTo(ChronoUnit.MILLIS));
        submission.setSubmitted(true);
        submission = programmingSubmissionRepository.save(submission);

        Result result = new Result();
        result.setAssessmentType(AssessmentType.AUTOMATIC);
        result.setCompletionDate(ZonedDateTime.now().truncatedTo(ChronoUnit.MILLIS));
        result.setSuccessful(true);
        result.setExerciseId(exercise.getId());
        result.setSubmission(submission);
        result.setScore(score);
        result.setRated(true);
        resultRepository.save(result);
    }

    /**
     * The instructor-facing exercise endpoint serializes the {@code Exercise} entity rather than a DTO, so its embedded
     * group goes through {@link MilestoneExerciseGroup} itself. It has to expose the same flat
     * {@code milestoneExerciseId} the DTO path does (see the overview test above): the user story detail page reads it
     * to load the anchor's template/solution build status, since the user story's own participations never get one.
     */
    @Test
    @WithMockUser(username = TEST_PREFIX + "tutor1", roles = "TA")
    void theExerciseEndpointNamesTheAnchorExerciseOnAUserStoryExercise() throws Exception {
        UserStoryExercise member = new UserStoryExercise();
        member.setTitle("User story");
        member.setShortName("userstoryentity" + TEST_PREFIX);
        member.setProgrammingLanguage(ProgrammingLanguage.JAVA);
        member.setCourse(course);
        member.setMaxPoints(10.0);
        member.setExerciseVariantGroup(milestoneGroup);
        member.generateAndSetProjectKey();
        programmingExerciseRepository.save(member);
        // The programming exercise endpoint answers with the build config, which is a row of its own.
        programmingExerciseBuildConfigRepository.saveForExercise(ProgrammingExerciseFactory.generateDefaultBuildConfig(), member);

        MvcResult result = request.performMvcRequest(MockMvcRequestBuilders.get("/api/programming/programming-exercises/" + member.getId())).andExpect(status().isOk()).andReturn();
        JsonNode group = request.getObjectMapper().readTree(result.getResponse().getContentAsString()).get("exerciseVariantGroup");

        assertThat(group).isNotNull();
        assertThat(group.get("type").asText()).isEqualTo("milestone");
        assertThat(group.get("milestoneExerciseId").asLong()).isEqualTo(milestoneExercise.getId());
    }

    /**
     * The course management exercise list is where the client drops the milestone exercise, which the milestone group's
     * card already represents. It can only do that when the milestone arrives under its own discriminator rather than as
     * a plain programming exercise, and the same holds for the user stories it lists under that card.
     */
    @Test
    @WithMockUser(username = TEST_PREFIX + "editor1", roles = "EDITOR")
    void courseManagementExerciseListReportsMilestoneAndUserStoryUnderTheirOwnType() throws Exception {
        UserStoryExercise story = addUserStoryMember("typed", null, null);

        MvcResult result = request.performMvcRequest(MockMvcRequestBuilders.get("/api/course/courses/" + course.getId() + "/with-exercises")).andExpect(status().isOk())
                .andReturn();
        JsonNode exercises = request.getObjectMapper().readTree(result.getResponse().getContentAsString()).get("exercises");

        Map<Long, String> typesById = new HashMap<>();
        exercises.forEach(exercise -> typesById.put(exercise.get("id").asLong(), exercise.get("type").asText()));
        assertThat(typesById).containsEntry(milestoneExercise.getId(), "milestone").containsEntry(story.getId(), "user-story");
    }

    /** Adds a user story to the milestone group. Kept minimal on purpose - only what the tests below read. */
    private UserStoryExercise addUserStoryMember(String shortNameSuffix, @Nullable ZonedDateTime releaseDate, @Nullable String problemStatement) {
        UserStoryExercise member = new UserStoryExercise();
        member.setTitle("User story " + shortNameSuffix);
        member.setShortName("us" + shortNameSuffix + TEST_PREFIX);
        member.setProgrammingLanguage(ProgrammingLanguage.JAVA);
        member.setCourse(course);
        member.setMaxPoints(10.0);
        member.setReleaseDate(releaseDate);
        member.setProblemStatement(problemStatement);
        member.setExerciseVariantGroup(milestoneGroup);
        member.generateAndSetProjectKey();
        return (UserStoryExercise) programmingExerciseRepository.save(member);
    }

    private String assessmentUrl() {
        return milestoneGroupsUrl() + "/" + milestoneGroup.getId() + "/assessment/students";
    }

    /**
     * Gives {@code student1} a participation on the exercise with one submission and one rated result, and returns that
     * result so a caller can attach static code analysis feedback to it.
     */
    private Result addStartedParticipationWithResult(ProgrammingExercise exercise, double score) {
        ProgrammingExerciseStudentParticipation participation = participationUtilService.addStudentParticipationForProgrammingExercise(exercise, TEST_PREFIX + "student1");
        participation.setRepositoryUri("https://example.local/" + exercise.getId() + ".git");
        programmingExerciseStudentParticipationRepository.save(participation);

        ProgrammingSubmission submission = new ProgrammingSubmission();
        submission.setParticipation(participation);
        submission.setCommitHash("assessment-commit");
        submission.setType(SubmissionType.MANUAL);
        submission.setSubmissionDate(ZonedDateTime.now().truncatedTo(ChronoUnit.MILLIS));
        submission.setSubmitted(true);
        submission = programmingSubmissionRepository.save(submission);

        Result result = new Result();
        result.setAssessmentType(AssessmentType.AUTOMATIC);
        result.setCompletionDate(ZonedDateTime.now().truncatedTo(ChronoUnit.MILLIS));
        result.setSuccessful(true);
        result.setExerciseId(exercise.getId());
        result.setSubmission(submission);
        result.setScore(score);
        result.setRated(true);
        return resultRepository.save(result);
    }

    /**
     * A tutor grading a milestone picks a student, not a submission: the group's stories share one repository and one
     * build, so the dashboard is keyed on the student and lists every story beside them.
     */
    @Test
    @WithMockUser(username = TEST_PREFIX + "tutor1", roles = "TA")
    void milestoneAssessmentDashboardListsEveryStartedStudentWithAllTheirStories() throws Exception {
        UserStoryExercise first = addUserStoryMember("first", null, "Implement the login form");
        UserStoryExercise second = addUserStoryMember("second", null, "Implement the logout");
        addStartedParticipationWithResult(milestoneExercise, 60.0);
        addStartedParticipationWithResult(first, 100.0);

        List<MilestoneAssessmentStudentDTO> dashboard = request.getList(assessmentUrl(), HttpStatus.OK, MilestoneAssessmentStudentDTO.class);

        assertThat(dashboard).singleElement().satisfies(row -> {
            assertThat(row.studentLogin()).isEqualTo(TEST_PREFIX + "student1");
            assertThat(row.milestoneParticipationId()).isNotNull();
            // Both stories appear, in title order - a story the student never started still gets an entry, because
            // "not started" is what the tutor needs to see rather than a row to hide.
            assertThat(row.exercises()).extracting(MilestoneAssessmentExerciseDTO::exerciseId).containsExactly(first.getId(), second.getId());
            assertThat(row.exercises().getFirst().submissionId()).isNotNull();
            assertThat(row.exercises().getFirst().latestScore()).isEqualTo(100.0);
            assertThat(row.exercises().getFirst().assessed()).isFalse();
            assertThat(row.exercises().getLast().participationId()).isNull();
            assertThat(row.exercises().getLast().submissionId()).isNull();
        });
    }

    /**
     * The milestone's own result is the only place the group's static code analysis issues exist - the fan-out copies
     * only test case feedback down to the stories, and every story has static code analysis switched off - so the
     * assessment page's first tab has nowhere else to read them from.
     */
    @Test
    @WithMockUser(username = TEST_PREFIX + "tutor1", roles = "TA")
    void milestoneAssessmentForOneStudentCarriesTheGroupsCodeQualityFeedbackAndItsStories() throws Exception {
        UserStoryExercise first = addUserStoryMember("first", null, "Implement the login form");
        UserStoryExercise second = addUserStoryMember("second", null, "Implement the logout");
        Result milestoneResult = addStartedParticipationWithResult(milestoneExercise, 60.0);
        addStartedParticipationWithResult(first, 100.0);

        ScaFeedback scaFeedback = new ScaFeedback();
        scaFeedback.setTool(StaticCodeAnalysisTool.SPOTBUGS);
        scaFeedback.setToolCategory("BAD_PRACTICE");
        scaFeedback.setCategory("Bad Practice");
        scaFeedback.setResult(milestoneResult);
        scaFeedbackRepository.save(scaFeedback);

        MilestoneAssessmentDTO assessment = request.get(assessmentUrl() + "/" + TEST_PREFIX + "student1", HttpStatus.OK, MilestoneAssessmentDTO.class);

        assertThat(assessment.milestoneExerciseId()).isEqualTo(milestoneExercise.getId());
        assertThat(assessment.problemStatement()).isEqualTo(milestoneExercise.getProblemStatement());
        assertThat(assessment.exercises()).extracting(MilestoneAssessmentExerciseDTO::exerciseId).containsExactly(first.getId(), second.getId());
        assertThat(assessment.milestoneResult()).isNotNull();
        // The rows live in a typed table and only reach the client through the synthesizer; without it the first tab
        // would render an empty issue list against a result that does have issues.
        assertThat(assessment.milestoneResult().feedbacks())
                .anyMatch(feedback -> feedback.text() != null && feedback.text().startsWith(Feedback.STATIC_CODE_ANALYSIS_FEEDBACK_IDENTIFIER));
    }

    /**
     * A milestone group may hold text, modeling, file upload and quiz exercises beside its user stories. Both views list
     * every member: user stories first, then the rest, each by title in natural order, so a tab index means the same thing
     * on either page.
     */
    @Test
    @WithMockUser(username = TEST_PREFIX + "tutor1", roles = "TA")
    void milestoneAssessmentListsEveryExerciseTypeWithUserStoriesFirstInNaturalTitleOrder() throws Exception {
        UserStoryExercise story10 = addUserStoryMember("10", null, null);
        UserStoryExercise story2 = addUserStoryMember("2", null, null);
        Exercise video = addMember(FileUploadExerciseFactory.generateFileUploadExercise(null, null, null, "pdf, mp4", course), "A video");
        Exercise essay = addMember(TextExerciseFactory.generateTextExercise(null, null, null, course), "An essay");
        addStartedParticipationWithResult(milestoneExercise, 60.0);
        StudentParticipation essayParticipation = participationUtilService.createAndSaveParticipationForExercise(essay, TEST_PREFIX + "student1");
        TextSubmission essaySubmission = new TextSubmission();
        essaySubmission.setText("An essay");
        essaySubmission.setSubmitted(true);
        essaySubmission.setParticipation(essayParticipation);
        essaySubmission = submissionRepository.save(essaySubmission);

        List<Long> expectedOrder = List.of(story2.getId(), story10.getId(), video.getId(), essay.getId());

        MilestoneAssessmentDTO assessment = request.get(assessmentUrl() + "/" + TEST_PREFIX + "student1", HttpStatus.OK, MilestoneAssessmentDTO.class);
        assertThat(assessment.exercises()).extracting(MilestoneAssessmentExerciseDTO::exerciseId).containsExactlyElementsOf(expectedOrder);
        assertThat(assessment.exercises()).extracting(MilestoneAssessmentExerciseDTO::exerciseType).containsExactly(ExerciseType.PROGRAMMING, ExerciseType.PROGRAMMING,
                ExerciseType.FILE_UPLOAD, ExerciseType.TEXT);
        assertThat(assessment.exercises()).extracting(MilestoneAssessmentExerciseDTO::userStory).containsExactly(true, true, false, false);
        assertThat(assessment.exercises().getLast().submissionId()).isEqualTo(essaySubmission.getId());
        assertThat(assessment.exercises().get(2).participationId()).isNull();

        List<MilestoneAssessmentStudentDTO> dashboard = request.getList(assessmentUrl(), HttpStatus.OK, MilestoneAssessmentStudentDTO.class);
        assertThat(dashboard).singleElement()
                .satisfies(row -> assertThat(row.exercises()).extracting(MilestoneAssessmentExerciseDTO::exerciseId).containsExactlyElementsOf(expectedOrder));
    }

    /** Adds a non-programming exercise to the milestone group under the given title. */
    private Exercise addMember(Exercise exercise, String title) {
        exercise.setTitle(title);
        exercise.setExerciseVariantGroup(milestoneGroup);
        return exerciseRepository.save(exercise);
    }

    @Test
    @WithMockUser(username = TEST_PREFIX + "student1", roles = "USER")
    void aStudentMayNotOpenTheMilestoneAssessment() throws Exception {
        request.getList(assessmentUrl(), HttpStatus.FORBIDDEN, MilestoneAssessmentStudentDTO.class);
        request.get(assessmentUrl() + "/" + TEST_PREFIX + "student1", HttpStatus.FORBIDDEN, MilestoneAssessmentDTO.class);
    }
}
