package de.tum.cit.aet.artemis.programming;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.ZonedDateTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.security.test.context.support.WithMockUser;

import de.tum.cit.aet.artemis.course.domain.Course;
import de.tum.cit.aet.artemis.exercise.domain.MilestoneExerciseGroup;
import de.tum.cit.aet.artemis.exercise.repository.MilestoneExerciseGroupRepository;
import de.tum.cit.aet.artemis.programming.domain.MilestoneExercise;
import de.tum.cit.aet.artemis.programming.domain.ProgrammingLanguage;
import de.tum.cit.aet.artemis.programming.domain.TaskPriority;
import de.tum.cit.aet.artemis.programming.domain.TaskState;
import de.tum.cit.aet.artemis.programming.domain.UserStoryExercise;
import de.tum.cit.aet.artemis.programming.domain.UserStoryTask;
import de.tum.cit.aet.artemis.programming.dto.UserStoryTaskDTO;
import de.tum.cit.aet.artemis.programming.repository.UserStoryTaskRepository;

/**
 * Covers the tasks a student creates for themself while working on a user story exercise: their own personal or team
 * scrum board.
 */
class UserStoryTaskIntegrationTest extends AbstractProgrammingIntegrationIndependentTest {

    private static final String TEST_PREFIX = "userstorytask";

    @Autowired
    private UserStoryTaskRepository userStoryTaskRepository;

    @Autowired
    private MilestoneExerciseGroupRepository milestoneExerciseGroupRepository;

    private Course course;

    private MilestoneExercise milestoneExercise;

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

        MilestoneExerciseGroup group = new MilestoneExerciseGroup();
        group.setTitle("Sprint 1");
        group.setMilestoneExercise(milestoneExercise);
        group.setCourse(course);
        group = milestoneExerciseGroupRepository.save(group);

        course = courseRepository.findWithEagerExerciseVariantGroupsByIdElseThrow(course.getId());
        course.addExerciseVariantGroup(group);
        courseRepository.save(course);

        userStory = new UserStoryExercise();
        userStory.setTitle("User story us1");
        userStory.setShortName("us1" + TEST_PREFIX);
        userStory.setProgrammingLanguage(ProgrammingLanguage.JAVA);
        userStory.setCourse(course);
        userStory.setMaxPoints(2.0);
        userStory.setDueDate(ZonedDateTime.now().plusDays(7));
        userStory.generateAndSetProjectKey();
        programmingExerciseRepository.save(userStory);
        // A new exercise is always stored outside any variant group (see Exercise#dropVariantGroupOfNewExercise), so it joins afterwards.
        userStory.setExerciseVariantGroup(group);
        userStory = (UserStoryExercise) programmingExerciseRepository.save(userStory);
    }

    private String tasksUrl(long exerciseId) {
        return "/api/programming/user-story-exercises/" + exerciseId + "/tasks";
    }

    private String taskUrl(long taskId) {
        return "/api/programming/user-story-tasks/" + taskId;
    }

    private String advanceUrl(long taskId) {
        return "/api/programming/user-story-tasks/" + taskId + "/advance-state";
    }

    private String reorderUrl(long exerciseId) {
        return "/api/programming/user-story-exercises/" + exerciseId + "/tasks/reorder";
    }

    private UserStoryTaskDTO aTask() {
        return new UserStoryTaskDTO(null, "Set up the repository", "Clone and configure the local checkout", 2, TaskPriority.MEDIUM, 1.5, null, TaskState.NEW);
    }

    @Test
    @WithMockUser(username = TEST_PREFIX + "student1", roles = "USER")
    void creatingATaskAddsItToTheBoard() throws Exception {
        var participation = participationUtilService.addStudentParticipationForProgrammingExercise(userStory, studentLogin);

        UserStoryTaskDTO created = request.postWithResponseBody(tasksUrl(userStory.getId()), aTask(), UserStoryTaskDTO.class, HttpStatus.OK);

        assertThat(created.id()).isNotNull();
        assertThat(created.title()).isEqualTo("Set up the repository");
        assertThat(created.priority()).isEqualTo(TaskPriority.MEDIUM);
        assertThat(created.state()).isEqualTo(TaskState.NEW);
        assertThat(userStoryTaskRepository.findAllByParticipationIdOrderByOrderIndexAsc(participation.getId())).hasSize(1);
    }

    @Test
    @WithMockUser(username = TEST_PREFIX + "student1", roles = "USER")
    void listingTasksReturnsOnlyTheOwnBoardsTasksInCreationOrder() throws Exception {
        participationUtilService.addStudentParticipationForProgrammingExercise(userStory, studentLogin);
        UserStoryTaskDTO first = request.postWithResponseBody(tasksUrl(userStory.getId()), aTask(), UserStoryTaskDTO.class, HttpStatus.OK);
        UserStoryTaskDTO second = request.postWithResponseBody(tasksUrl(userStory.getId()),
                new UserStoryTaskDTO(null, "Write tests", null, 3, TaskPriority.HIGH, 2.0, null, TaskState.NEW), UserStoryTaskDTO.class, HttpStatus.OK);

        List<UserStoryTaskDTO> tasks = request.getList(tasksUrl(userStory.getId()), HttpStatus.OK, UserStoryTaskDTO.class);

        assertThat(tasks).extracting(UserStoryTaskDTO::id).containsExactly(first.id(), second.id());
    }

    @Test
    @WithMockUser(username = TEST_PREFIX + "student1", roles = "USER")
    void reorderingPersistsTheNewOrder() throws Exception {
        participationUtilService.addStudentParticipationForProgrammingExercise(userStory, studentLogin);
        UserStoryTaskDTO first = request.postWithResponseBody(tasksUrl(userStory.getId()), aTask(), UserStoryTaskDTO.class, HttpStatus.OK);
        UserStoryTaskDTO second = request.postWithResponseBody(tasksUrl(userStory.getId()),
                new UserStoryTaskDTO(null, "Write tests", null, 3, TaskPriority.HIGH, 2.0, null, TaskState.NEW), UserStoryTaskDTO.class, HttpStatus.OK);
        UserStoryTaskDTO third = request.postWithResponseBody(tasksUrl(userStory.getId()),
                new UserStoryTaskDTO(null, "Deploy", null, 1, TaskPriority.LOW, 0.5, null, TaskState.NEW), UserStoryTaskDTO.class, HttpStatus.OK);

        List<UserStoryTaskDTO> reordered = request.putWithResponseBodyList(reorderUrl(userStory.getId()), List.of(third.id(), first.id(), second.id()), UserStoryTaskDTO.class,
                HttpStatus.OK);
        assertThat(reordered).extracting(UserStoryTaskDTO::id).containsExactly(third.id(), first.id(), second.id());

        // The new order is durable, not just the immediate response.
        List<UserStoryTaskDTO> reloaded = request.getList(tasksUrl(userStory.getId()), HttpStatus.OK, UserStoryTaskDTO.class);
        assertThat(reloaded).extracting(UserStoryTaskDTO::id).containsExactly(third.id(), first.id(), second.id());
    }

    @Test
    @WithMockUser(username = TEST_PREFIX + "student1", roles = "USER")
    void reorderingIsRejectedWithTheWrongNumberOfIds() throws Exception {
        participationUtilService.addStudentParticipationForProgrammingExercise(userStory, studentLogin);
        UserStoryTaskDTO first = request.postWithResponseBody(tasksUrl(userStory.getId()), aTask(), UserStoryTaskDTO.class, HttpStatus.OK);
        request.postWithResponseBody(tasksUrl(userStory.getId()), new UserStoryTaskDTO(null, "Write tests", null, 3, TaskPriority.HIGH, 2.0, null, TaskState.NEW),
                UserStoryTaskDTO.class, HttpStatus.OK);

        request.put(reorderUrl(userStory.getId()), List.of(first.id()), HttpStatus.BAD_REQUEST);
    }

    @Test
    @WithMockUser(username = TEST_PREFIX + "student1", roles = "USER")
    void reorderingIsRejectedWithATaskFromAnotherBoard() throws Exception {
        var participation = participationUtilService.addStudentParticipationForProgrammingExercise(userStory, studentLogin);
        UserStoryTaskDTO first = request.postWithResponseBody(tasksUrl(userStory.getId()), aTask(), UserStoryTaskDTO.class, HttpStatus.OK);
        var foreignTask = createTaskDirectlyFor(TEST_PREFIX + "student2");

        request.put(reorderUrl(userStory.getId()), List.of(foreignTask.getId()), HttpStatus.BAD_REQUEST);

        assertThat(userStoryTaskRepository.findAllByParticipationIdOrderByOrderIndexAsc(participation.getId())).extracting(UserStoryTask::getId).containsExactly(first.id());
    }

    @Test
    @WithMockUser(username = TEST_PREFIX + "student1", roles = "USER")
    void creatingATaskIsRejectedWithoutAParticipation() throws Exception {
        request.postWithResponseBody(tasksUrl(userStory.getId()), aTask(), UserStoryTaskDTO.class, HttpStatus.BAD_REQUEST);
    }

    @Test
    @WithMockUser(username = TEST_PREFIX + "student1", roles = "USER")
    void creatingATaskIsRejectedForANonUserStoryExercise() throws Exception {
        request.postWithResponseBody(tasksUrl(milestoneExercise.getId()), aTask(), UserStoryTaskDTO.class, HttpStatus.BAD_REQUEST);
    }

    @Test
    @WithMockUser(username = TEST_PREFIX + "student1", roles = "USER")
    void creatingATaskIsRejectedWithAnEmptyTitle() throws Exception {
        participationUtilService.addStudentParticipationForProgrammingExercise(userStory, studentLogin);

        request.postWithResponseBody(tasksUrl(userStory.getId()), new UserStoryTaskDTO(null, "   ", null, 1, TaskPriority.LOW, 1.0, null, TaskState.NEW), UserStoryTaskDTO.class,
                HttpStatus.BAD_REQUEST);
    }

    @Test
    @WithMockUser(username = TEST_PREFIX + "student1", roles = "USER")
    void creatingATaskIsRejectedWithNegativeValues() throws Exception {
        participationUtilService.addStudentParticipationForProgrammingExercise(userStory, studentLogin);

        request.postWithResponseBody(tasksUrl(userStory.getId()), new UserStoryTaskDTO(null, "Negative points", null, -1, TaskPriority.LOW, 1.0, null, TaskState.NEW),
                UserStoryTaskDTO.class, HttpStatus.BAD_REQUEST);
        request.postWithResponseBody(tasksUrl(userStory.getId()), new UserStoryTaskDTO(null, "Negative effort", null, 1, TaskPriority.LOW, -1.0, null, TaskState.NEW),
                UserStoryTaskDTO.class, HttpStatus.BAD_REQUEST);
    }

    @Test
    @WithMockUser(username = TEST_PREFIX + "student1", roles = "USER")
    void updatingATaskChangesItsFields() throws Exception {
        participationUtilService.addStudentParticipationForProgrammingExercise(userStory, studentLogin);
        UserStoryTaskDTO created = request.postWithResponseBody(tasksUrl(userStory.getId()), aTask(), UserStoryTaskDTO.class, HttpStatus.OK);

        // Unlike creation, an edit may set the state directly - it is not restricted to the one-step advance. DONE
        // requires a logged actual effort, so this also carries one.
        UserStoryTaskDTO updated = request.putWithResponseBody(taskUrl(created.id()),
                new UserStoryTaskDTO(created.id(), "Set up the repository (done)", null, 5, TaskPriority.HIGH, 3.0, 2.5, TaskState.DONE), UserStoryTaskDTO.class, HttpStatus.OK);

        assertThat(updated.title()).isEqualTo("Set up the repository (done)");
        assertThat(updated.taskPoints()).isEqualTo(5);
        assertThat(updated.priority()).isEqualTo(TaskPriority.HIGH);
        assertThat(updated.estimatedEffortHours()).isEqualTo(3.0);
        assertThat(updated.actualEffortHours()).isEqualTo(2.5);
        assertThat(updated.state()).isEqualTo(TaskState.DONE);
    }

    @Test
    @WithMockUser(username = TEST_PREFIX + "student1", roles = "USER")
    void loggingActualEffortStartsUnsetAndCanBeEditedIn() throws Exception {
        participationUtilService.addStudentParticipationForProgrammingExercise(userStory, studentLogin);
        // A crafted create request cannot pre-set logged time either - it is always null on creation.
        UserStoryTaskDTO created = request.postWithResponseBody(tasksUrl(userStory.getId()),
                new UserStoryTaskDTO(null, "Set up the repository", null, 2, TaskPriority.MEDIUM, 1.5, 4.0, TaskState.NEW), UserStoryTaskDTO.class, HttpStatus.OK);
        assertThat(created.actualEffortHours()).isNull();

        UserStoryTaskDTO updated = request.putWithResponseBody(taskUrl(created.id()),
                new UserStoryTaskDTO(created.id(), created.title(), null, created.taskPoints(), created.priority(), created.estimatedEffortHours(), 2.5, created.state()),
                UserStoryTaskDTO.class, HttpStatus.OK);

        assertThat(updated.actualEffortHours()).isEqualTo(2.5);
    }

    @Test
    @WithMockUser(username = TEST_PREFIX + "student1", roles = "USER")
    void updatingATaskIsRejectedWithNegativeActualEffort() throws Exception {
        participationUtilService.addStudentParticipationForProgrammingExercise(userStory, studentLogin);
        UserStoryTaskDTO created = request.postWithResponseBody(tasksUrl(userStory.getId()), aTask(), UserStoryTaskDTO.class, HttpStatus.OK);

        request.putWithResponseBody(taskUrl(created.id()),
                new UserStoryTaskDTO(created.id(), created.title(), null, created.taskPoints(), created.priority(), created.estimatedEffortHours(), -1.0, created.state()),
                UserStoryTaskDTO.class, HttpStatus.BAD_REQUEST);
    }

    @Test
    @WithMockUser(username = TEST_PREFIX + "student1", roles = "USER")
    void updatingATaskIsRejectedWithoutAState() throws Exception {
        participationUtilService.addStudentParticipationForProgrammingExercise(userStory, studentLogin);
        UserStoryTaskDTO created = request.postWithResponseBody(tasksUrl(userStory.getId()), aTask(), UserStoryTaskDTO.class, HttpStatus.OK);

        request.putWithResponseBody(taskUrl(created.id()), new UserStoryTaskDTO(created.id(), "Missing state", null, 1, TaskPriority.LOW, 1.0, null, null), UserStoryTaskDTO.class,
                HttpStatus.BAD_REQUEST);
    }

    @Test
    @WithMockUser(username = TEST_PREFIX + "student2", roles = "USER")
    void anotherStudentCannotUpdateTheTask() throws Exception {
        var task = createTaskDirectlyFor(studentLogin);

        request.putWithResponseBody(taskUrl(task.getId()), new UserStoryTaskDTO(task.getId(), "Hijacked", null, 1, TaskPriority.LOW, 1.0, null, TaskState.NEW),
                UserStoryTaskDTO.class, HttpStatus.FORBIDDEN);
    }

    @Test
    @WithMockUser(username = TEST_PREFIX + "student1", roles = "USER")
    void advancingATaskShiftsItThroughEachState() throws Exception {
        participationUtilService.addStudentParticipationForProgrammingExercise(userStory, studentLogin);
        UserStoryTaskDTO created = request.postWithResponseBody(tasksUrl(userStory.getId()), aTask(), UserStoryTaskDTO.class, HttpStatus.OK);
        assertThat(created.state()).isEqualTo(TaskState.NEW);

        UserStoryTaskDTO inProgress = request.putWithResponseBody(advanceUrl(created.id()), null, UserStoryTaskDTO.class, HttpStatus.OK);
        assertThat(inProgress.state()).isEqualTo(TaskState.IN_PROGRESS);
        logActualEffort(inProgress, 1.5);

        UserStoryTaskDTO done = request.putWithResponseBody(advanceUrl(created.id()), null, UserStoryTaskDTO.class, HttpStatus.OK);
        assertThat(done.state()).isEqualTo(TaskState.DONE);
    }

    @Test
    @WithMockUser(username = TEST_PREFIX + "student1", roles = "USER")
    void advancingToDoneIsRejectedWithoutLoggedEffort() throws Exception {
        participationUtilService.addStudentParticipationForProgrammingExercise(userStory, studentLogin);
        UserStoryTaskDTO created = request.postWithResponseBody(tasksUrl(userStory.getId()), aTask(), UserStoryTaskDTO.class, HttpStatus.OK);
        request.putWithResponseBody(advanceUrl(created.id()), null, UserStoryTaskDTO.class, HttpStatus.OK);

        UserStoryTaskDTO stillInProgress = request.putWithResponseBody(advanceUrl(created.id()), null, UserStoryTaskDTO.class, HttpStatus.BAD_REQUEST);

        assertThat(stillInProgress).isNull();
        assertThat(userStoryTaskRepository.findById(created.id()).orElseThrow().getState()).isEqualTo(TaskState.IN_PROGRESS);
    }

    @Test
    @WithMockUser(username = TEST_PREFIX + "student1", roles = "USER")
    void advancingADoneTaskIsRejected() throws Exception {
        participationUtilService.addStudentParticipationForProgrammingExercise(userStory, studentLogin);
        UserStoryTaskDTO created = request.postWithResponseBody(tasksUrl(userStory.getId()), aTask(), UserStoryTaskDTO.class, HttpStatus.OK);
        UserStoryTaskDTO inProgress = request.putWithResponseBody(advanceUrl(created.id()), null, UserStoryTaskDTO.class, HttpStatus.OK);
        logActualEffort(inProgress, 1.0);
        request.putWithResponseBody(advanceUrl(created.id()), null, UserStoryTaskDTO.class, HttpStatus.OK);

        request.putWithResponseBody(advanceUrl(created.id()), null, UserStoryTaskDTO.class, HttpStatus.BAD_REQUEST);
    }

    @Test
    @WithMockUser(username = TEST_PREFIX + "student1", roles = "USER")
    void settingStateToDoneDirectlyIsRejectedWithoutLoggedEffort() throws Exception {
        participationUtilService.addStudentParticipationForProgrammingExercise(userStory, studentLogin);
        UserStoryTaskDTO created = request.postWithResponseBody(tasksUrl(userStory.getId()), aTask(), UserStoryTaskDTO.class, HttpStatus.OK);

        request.putWithResponseBody(taskUrl(created.id()),
                new UserStoryTaskDTO(created.id(), created.title(), null, created.taskPoints(), created.priority(), created.estimatedEffortHours(), null, TaskState.DONE),
                UserStoryTaskDTO.class, HttpStatus.BAD_REQUEST);
    }

    @Test
    @WithMockUser(username = TEST_PREFIX + "student1", roles = "USER")
    void settingStateToDoneDirectlySucceedsWithLoggedEffort() throws Exception {
        participationUtilService.addStudentParticipationForProgrammingExercise(userStory, studentLogin);
        UserStoryTaskDTO created = request.postWithResponseBody(tasksUrl(userStory.getId()), aTask(), UserStoryTaskDTO.class, HttpStatus.OK);

        UserStoryTaskDTO updated = request.putWithResponseBody(taskUrl(created.id()),
                new UserStoryTaskDTO(created.id(), created.title(), null, created.taskPoints(), created.priority(), created.estimatedEffortHours(), 2.0, TaskState.DONE),
                UserStoryTaskDTO.class, HttpStatus.OK);

        assertThat(updated.state()).isEqualTo(TaskState.DONE);
        assertThat(updated.actualEffortHours()).isEqualTo(2.0);
    }

    @Test
    @WithMockUser(username = TEST_PREFIX + "student2", roles = "USER")
    void anotherStudentCannotAdvanceTheTask() throws Exception {
        var task = createTaskDirectlyFor(studentLogin);

        request.putWithResponseBody(advanceUrl(task.getId()), null, UserStoryTaskDTO.class, HttpStatus.FORBIDDEN);
    }

    private void logActualEffort(UserStoryTaskDTO task, double hours) throws Exception {
        request.putWithResponseBody(taskUrl(task.id()),
                new UserStoryTaskDTO(task.id(), task.title(), task.description(), task.taskPoints(), task.priority(), task.estimatedEffortHours(), hours, task.state()),
                UserStoryTaskDTO.class, HttpStatus.OK);
    }

    @Test
    @WithMockUser(username = TEST_PREFIX + "student1", roles = "USER")
    void deletingATaskRemovesItFromTheBoard() throws Exception {
        participationUtilService.addStudentParticipationForProgrammingExercise(userStory, studentLogin);
        UserStoryTaskDTO created = request.postWithResponseBody(tasksUrl(userStory.getId()), aTask(), UserStoryTaskDTO.class, HttpStatus.OK);

        request.delete(taskUrl(created.id()), HttpStatus.OK);

        assertThat(userStoryTaskRepository.findById(created.id())).isEmpty();
    }

    @Test
    @WithMockUser(username = TEST_PREFIX + "student2", roles = "USER")
    void anotherStudentCannotDeleteTheTask() throws Exception {
        var task = createTaskDirectlyFor(studentLogin);

        request.delete(taskUrl(task.getId()), HttpStatus.FORBIDDEN);

        assertThat(userStoryTaskRepository.findById(task.getId())).isPresent();
    }

    @Test
    @WithMockUser(username = TEST_PREFIX + "student1", roles = "USER")
    void theBoardIsReadOnlyAfterTheDueDate() throws Exception {
        var task = createTaskDirectlyFor(studentLogin);
        passTheDueDate();
        UserStoryTaskDTO changed = new UserStoryTaskDTO(task.getId(), "Changed", null, 3, TaskPriority.HIGH, 2.0, null, TaskState.NEW);

        request.postWithResponseBody(tasksUrl(userStory.getId()), aTask(), UserStoryTaskDTO.class, HttpStatus.BAD_REQUEST);
        request.putWithResponseBody(taskUrl(task.getId()), changed, UserStoryTaskDTO.class, HttpStatus.BAD_REQUEST);
        request.putWithResponseBody(advanceUrl(task.getId()), null, UserStoryTaskDTO.class, HttpStatus.BAD_REQUEST);
        request.put(reorderUrl(userStory.getId()), List.of(task.getId()), HttpStatus.BAD_REQUEST);
        request.delete(taskUrl(task.getId()), HttpStatus.BAD_REQUEST);

        // Still readable, and nothing above got through.
        assertThat(request.getList(tasksUrl(userStory.getId()), HttpStatus.OK, UserStoryTaskDTO.class)).singleElement().satisfies(unchanged -> {
            assertThat(unchanged.title()).isEqualTo("Existing task");
            assertThat(unchanged.state()).isEqualTo(TaskState.NEW);
        });
    }

    @Test
    @WithMockUser(username = TEST_PREFIX + "student1", roles = "USER")
    void anIndividualExtensionKeepsTheBoardOpenAfterTheDueDate() throws Exception {
        var task = createTaskDirectlyFor(studentLogin);
        passTheDueDate();
        var participation = studentParticipationRepository.findByIdElseThrow(task.getParticipation().getId());
        participation.setIndividualDueDate(ZonedDateTime.now().plusDays(1));
        studentParticipationRepository.save(participation);

        request.postWithResponseBody(tasksUrl(userStory.getId()), aTask(), UserStoryTaskDTO.class, HttpStatus.OK);
        request.putWithResponseBody(advanceUrl(task.getId()), null, UserStoryTaskDTO.class, HttpStatus.OK);
    }

    @Test
    @WithMockUser(username = TEST_PREFIX + "student1", roles = "USER")
    void theBoardIsReadOnlyInPracticeMode() throws Exception {
        // The due date is still ahead, so only practice mode can freeze the board.
        var task = createTaskDirectlyFor(studentLogin);
        participationUtilService.createAndSavePracticeParticipationForExercise(userStory, studentLogin);
        UserStoryTaskDTO changed = new UserStoryTaskDTO(task.getId(), "Changed", null, 3, TaskPriority.HIGH, 2.0, null, TaskState.NEW);

        request.postWithResponseBody(tasksUrl(userStory.getId()), aTask(), UserStoryTaskDTO.class, HttpStatus.BAD_REQUEST);
        request.putWithResponseBody(taskUrl(task.getId()), changed, UserStoryTaskDTO.class, HttpStatus.BAD_REQUEST);
        request.putWithResponseBody(advanceUrl(task.getId()), null, UserStoryTaskDTO.class, HttpStatus.BAD_REQUEST);
        request.put(reorderUrl(userStory.getId()), List.of(task.getId()), HttpStatus.BAD_REQUEST);
        request.delete(taskUrl(task.getId()), HttpStatus.BAD_REQUEST);

        // The graded board is still readable next to the practice participation, and nothing above got through.
        assertThat(request.getList(tasksUrl(userStory.getId()), HttpStatus.OK, UserStoryTaskDTO.class)).singleElement().satisfies(unchanged -> {
            assertThat(unchanged.id()).isEqualTo(task.getId());
            assertThat(unchanged.title()).isEqualTo("Existing task");
            assertThat(unchanged.state()).isEqualTo(TaskState.NEW);
        });
    }

    private void passTheDueDate() {
        userStory.setDueDate(ZonedDateTime.now().minusHours(1));
        userStory = (UserStoryExercise) programmingExerciseRepository.save(userStory);
    }

    private UserStoryTask createTaskDirectlyFor(String login) {
        var participation = participationUtilService.addStudentParticipationForProgrammingExercise(userStory, login);
        var task = new UserStoryTask();
        task.setParticipation(participation);
        task.setTitle("Existing task");
        task.setTaskPoints(1);
        task.setPriority(TaskPriority.LOW);
        task.setEstimatedEffortHours(1.0);
        return userStoryTaskRepository.save(task);
    }
}
