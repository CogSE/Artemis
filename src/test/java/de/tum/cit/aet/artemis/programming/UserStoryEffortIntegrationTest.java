package de.tum.cit.aet.artemis.programming;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.ZonedDateTime;
import java.util.List;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.security.test.context.support.WithMockUser;

import de.tum.cit.aet.artemis.course.domain.Course;
import de.tum.cit.aet.artemis.exercise.domain.MilestoneExerciseGroup;
import de.tum.cit.aet.artemis.exercise.repository.MilestoneExerciseGroupRepository;
import de.tum.cit.aet.artemis.programming.domain.MilestoneExercise;
import de.tum.cit.aet.artemis.programming.domain.ProgrammingExerciseStudentParticipation;
import de.tum.cit.aet.artemis.programming.domain.ProgrammingLanguage;
import de.tum.cit.aet.artemis.programming.domain.TaskPriority;
import de.tum.cit.aet.artemis.programming.domain.UserStoryExercise;
import de.tum.cit.aet.artemis.programming.domain.UserStoryTask;
import de.tum.cit.aet.artemis.programming.dto.UserStoryEffortDTO;
import de.tum.cit.aet.artemis.programming.dto.UserStoryEffortStatusDTO;
import de.tum.cit.aet.artemis.programming.repository.UserStoryTaskRepository;

/**
 * Covers the effort a student reports for a user story exercise - summed from their task board rather than entered
 * by hand.
 */
class UserStoryEffortIntegrationTest extends AbstractProgrammingIntegrationIndependentTest {

    private static final String TEST_PREFIX = "userstoryeffort";

    @Autowired
    private UserStoryTaskRepository userStoryTaskRepository;

    @Autowired
    private MilestoneExerciseGroupRepository milestoneExerciseGroupRepository;

    private Course course;

    private MilestoneExercise milestoneExercise;

    private MilestoneExerciseGroup group;

    private UserStoryExercise userStory;

    private String studentLogin;

    @BeforeEach
    void setUp() {
        userUtilService.addUsers(TEST_PREFIX, 2, 1, 0, 1);
        studentLogin = TEST_PREFIX + "student1";
        course = courseUtilService.addEnrolledEmptyCourse(TEST_PREFIX);

        milestoneExercise = new MilestoneExercise();
        milestoneExercise.setTitle("Milestone");
        milestoneExercise.setShortName("ms" + TEST_PREFIX);
        milestoneExercise.setProgrammingLanguage(ProgrammingLanguage.JAVA);
        milestoneExercise.setCourse(course);
        milestoneExercise.setMaxPoints(0.0);
        milestoneExercise.setDueDate(ZonedDateTime.now().plusDays(7));
        milestoneExercise.generateAndSetProjectKey();
        milestoneExercise = (MilestoneExercise) programmingExerciseRepository.save(milestoneExercise);

        group = new MilestoneExerciseGroup();
        group.setTitle("Sprint 1");
        group.setMilestoneExercise(milestoneExercise);
        group.setCourse(course);
        group = milestoneExerciseGroupRepository.save(group);

        course = courseRepository.findWithEagerExerciseVariantGroupsByIdElseThrow(course.getId());
        course.addExerciseVariantGroup(group);
        courseRepository.save(course);

        userStory = createUserStory("us1", ZonedDateTime.now().plusDays(7));
    }

    private UserStoryExercise createUserStory(String shortName, ZonedDateTime dueDate) {
        UserStoryExercise exercise = new UserStoryExercise();
        exercise.setTitle("User story " + shortName);
        exercise.setShortName(shortName + TEST_PREFIX);
        exercise.setProgrammingLanguage(ProgrammingLanguage.JAVA);
        exercise.setCourse(course);
        exercise.setMaxPoints(2.0);
        exercise.setDueDate(dueDate);
        exercise.generateAndSetProjectKey();
        programmingExerciseRepository.save(exercise);
        // A new exercise is always stored outside any variant group (see Exercise#dropVariantGroupOfNewExercise), so it joins afterwards.
        exercise.setExerciseVariantGroup(group);
        return (UserStoryExercise) programmingExerciseRepository.save(exercise);
    }

    private String effortUrl(long exerciseId) {
        return "/api/programming/user-story-exercises/" + exerciseId + "/effort";
    }

    private UserStoryTask taskFor(ProgrammingExerciseStudentParticipation participation, double estimatedHours, @Nullable Double actualHours) {
        UserStoryTask task = new UserStoryTask();
        task.setParticipation(participation);
        task.setTitle("Task");
        task.setTaskPoints(1);
        task.setPriority(TaskPriority.LOW);
        task.setEstimatedEffortHours(estimatedHours);
        task.setActualEffortHours(actualHours);
        return userStoryTaskRepository.save(task);
    }

    @Test
    @WithMockUser(username = TEST_PREFIX + "student1", roles = "USER")
    void readingEffortReturnsAZeroEstimateAndNoActualBeforeAnyTaskExists() throws Exception {
        participationUtilService.addStudentParticipationForProgrammingExercise(userStory, studentLogin);

        UserStoryEffortDTO effort = request.get(effortUrl(userStory.getId()), HttpStatus.OK, UserStoryEffortDTO.class);

        assertThat(effort.estimatedEffort()).isEqualTo(0.0);
        assertThat(effort.actualEffort()).isNull();
    }

    @Test
    @WithMockUser(username = TEST_PREFIX + "student1", roles = "USER")
    void readingEffortSumsTheParticipantsTasks() throws Exception {
        var participation = participationUtilService.addStudentParticipationForProgrammingExercise(userStory, studentLogin);
        taskFor(participation, 2.0, 1.0);
        taskFor(participation, 3.5, null);

        UserStoryEffortDTO effort = request.get(effortUrl(userStory.getId()), HttpStatus.OK, UserStoryEffortDTO.class);

        assertThat(effort.estimatedEffort()).isEqualTo(5.5);
        // SUM ignores the task with no actual effort logged yet rather than treating it as 0.
        assertThat(effort.actualEffort()).isEqualTo(1.0);
    }

    @Test
    @WithMockUser(username = TEST_PREFIX + "student1", roles = "USER")
    void readingEffortReportsTheGradedBoardInPracticeMode() throws Exception {
        var participation = participationUtilService.addStudentParticipationForProgrammingExercise(userStory, studentLogin);
        taskFor(participation, 2.0, 1.0);
        participationUtilService.createAndSavePracticeParticipationForExercise(userStory, studentLogin);

        UserStoryEffortDTO effort = request.get(effortUrl(userStory.getId()), HttpStatus.OK, UserStoryEffortDTO.class);

        assertThat(effort.estimatedEffort()).isEqualTo(2.0);
        assertThat(effort.actualEffort()).isEqualTo(1.0);
    }

    @Test
    @WithMockUser(username = TEST_PREFIX + "student1", roles = "USER")
    void readingEffortIsRejectedWithoutAParticipation() throws Exception {
        request.get(effortUrl(userStory.getId()), HttpStatus.BAD_REQUEST, UserStoryEffortDTO.class);
    }

    @Test
    @WithMockUser(username = TEST_PREFIX + "student1", roles = "USER")
    void readingEffortIsRejectedForANonUserStoryExercise() throws Exception {
        request.get(effortUrl(milestoneExercise.getId()), HttpStatus.BAD_REQUEST, UserStoryEffortDTO.class);
    }

    @Test
    @WithMockUser(username = TEST_PREFIX + "student1", roles = "USER")
    void theCourseLookupReportsEveryStartedStory() throws Exception {
        var participation = participationUtilService.addStudentParticipationForProgrammingExercise(userStory, studentLogin);
        taskFor(participation, 2.0, null);
        UserStoryExercise otherStory = createUserStory("us4", ZonedDateTime.now().plusDays(7));
        participationUtilService.addStudentParticipationForProgrammingExercise(otherStory, studentLogin);

        List<UserStoryEffortStatusDTO> statuses = request.getList("/api/programming/courses/" + course.getId() + "/user-story-efforts", HttpStatus.OK,
                UserStoryEffortStatusDTO.class);

        assertThat(statuses).extracting(UserStoryEffortStatusDTO::exerciseId).containsExactlyInAnyOrder(userStory.getId(), otherStory.getId());
        assertThat(statuses).filteredOn(status -> status.exerciseId().equals(userStory.getId())).singleElement().extracting(UserStoryEffortStatusDTO::estimatedEffort)
                .isEqualTo(2.0);
        // The story with no tasks yet sums to 0, not null - there is simply nothing to add up.
        assertThat(statuses).filteredOn(status -> status.exerciseId().equals(otherStory.getId())).singleElement().extracting(UserStoryEffortStatusDTO::estimatedEffort)
                .isEqualTo(0.0);
    }
}
