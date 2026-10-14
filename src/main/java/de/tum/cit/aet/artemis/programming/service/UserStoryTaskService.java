package de.tum.cit.aet.artemis.programming.service;

import static de.tum.cit.aet.artemis.core.config.Constants.PROFILE_CORE;

import java.time.ZonedDateTime;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.jspecify.annotations.Nullable;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import de.tum.cit.aet.artemis.account.domain.User;
import de.tum.cit.aet.artemis.core.exception.AccessForbiddenException;
import de.tum.cit.aet.artemis.core.exception.BadRequestAlertException;
import de.tum.cit.aet.artemis.exercise.domain.Exercise;
import de.tum.cit.aet.artemis.exercise.domain.participation.StudentParticipation;
import de.tum.cit.aet.artemis.exercise.repository.ExerciseRepository;
import de.tum.cit.aet.artemis.exercise.repository.StudentParticipationRepository;
import de.tum.cit.aet.artemis.exercise.service.ParticipationAuthorizationCheckService;
import de.tum.cit.aet.artemis.exercise.service.ParticipationService;
import de.tum.cit.aet.artemis.programming.domain.TaskState;
import de.tum.cit.aet.artemis.programming.domain.UserStoryExercise;
import de.tum.cit.aet.artemis.programming.domain.UserStoryTask;
import de.tum.cit.aet.artemis.programming.dto.UserStoryTaskDTO;
import de.tum.cit.aet.artemis.programming.repository.UserStoryTaskRepository;

/**
 * Reads and writes the tasks a participant creates for themself while working on a {@link UserStoryExercise}.
 * <p>
 * Every task belongs to the participant's {@code StudentParticipation}, so a team shares one board - see
 * {@link UserStoryTask} for why it does not hang off the exercise directly.
 */
@Profile(PROFILE_CORE)
@Lazy
@Service
public class UserStoryTaskService {

    private static final String ENTITY_NAME = "userStoryTask";

    /** Upper bound on a single task's estimated effort, in hours. Guards against a slipped decimal point. */
    private static final double MAX_EFFORT_HOURS = 1000.0;

    private static final int MAX_TITLE_LENGTH = 255;

    /** Task points are estimated on the (deduplicated) Fibonacci scale used for agile planning poker - see the client's matching dropdown. */
    private static final Set<Integer> TASK_POINTS_FIBONACCI_OPTIONS = Set.of(1, 2, 3, 5, 8, 13);

    private final UserStoryTaskRepository userStoryTaskRepository;

    private final ExerciseRepository exerciseRepository;

    private final ParticipationService participationService;

    private final ParticipationAuthorizationCheckService participationAuthorizationCheckService;

    private final StudentParticipationRepository studentParticipationRepository;

    public UserStoryTaskService(UserStoryTaskRepository userStoryTaskRepository, ExerciseRepository exerciseRepository, ParticipationService participationService,
            ParticipationAuthorizationCheckService participationAuthorizationCheckService, StudentParticipationRepository studentParticipationRepository) {
        this.userStoryTaskRepository = userStoryTaskRepository;
        this.exerciseRepository = exerciseRepository;
        this.participationService = participationService;
        this.participationAuthorizationCheckService = participationAuthorizationCheckService;
        this.studentParticipationRepository = studentParticipationRepository;
    }

    /**
     * The user's tasks for the story, in the user's own manual board order.
     *
     * @param exerciseId the id of the user story exercise
     * @param user       the requesting user
     * @return the tasks on the user's (or their team's) board
     */
    public List<UserStoryTaskDTO> findAllForUser(long exerciseId, User user) {
        StudentParticipation participation = resolveOwnParticipationElseThrow(exerciseId, user);
        return userStoryTaskRepository.findAllByParticipationIdOrderByOrderIndexAsc(participation.getId()).stream().map(UserStoryTaskDTO::of).toList();
    }

    /**
     * Creates a new task on the user's board for the story, appended at the end.
     *
     * @param exerciseId the id of the user story exercise
     * @param taskDTO    the task to create
     * @param user       the creating user
     * @return the created task
     */
    public UserStoryTaskDTO create(long exerciseId, UserStoryTaskDTO taskDTO, User user) {
        StudentParticipation participation = resolveOwnParticipationElseThrow(exerciseId, user);
        checkBeforeDueDateElseThrow(participation);
        validate(taskDTO);

        UserStoryTask task = new UserStoryTask();
        task.setParticipation(participation);
        task.setOrderIndex((int) userStoryTaskRepository.countByParticipationId(participation.getId()));
        applyFields(task, taskDTO);
        // Forced regardless of what was sent: a new task has no logged time yet.
        task.setActualEffortHours(null);
        return UserStoryTaskDTO.of(userStoryTaskRepository.save(task));
    }

    /**
     * Updates a task the user owns, including its state and actual effort - unlike creation, an edit may set the
     * state directly to any value (rather than only advancing it one step) and may log real time spent.
     *
     * @param taskId  the id of the task to update
     * @param taskDTO the new field values
     * @param user    the requesting user
     * @return the updated task
     */
    public UserStoryTaskDTO update(long taskId, UserStoryTaskDTO taskDTO, User user) {
        UserStoryTask task = userStoryTaskRepository.findByIdElseThrow(taskId);
        checkBeforeDueDateElseThrow(checkOwnTaskElseThrow(task, user));
        validate(taskDTO);
        checkDoneRequiresActualEffort(taskDTO.state(), taskDTO.actualEffortHours());
        applyFields(task, taskDTO);
        task.setState(taskDTO.state());
        task.setActualEffortHours(taskDTO.actualEffortHours());
        return UserStoryTaskDTO.of(userStoryTaskRepository.save(task));
    }

    /**
     * Shifts a task the user owns one step forward: NEW to IN_PROGRESS, or IN_PROGRESS to DONE.
     *
     * @param taskId the id of the task to advance
     * @param user   the requesting user
     * @return the task in its new state
     */
    public UserStoryTaskDTO advanceState(long taskId, User user) {
        UserStoryTask task = userStoryTaskRepository.findByIdElseThrow(taskId);
        checkBeforeDueDateElseThrow(checkOwnTaskElseThrow(task, user));
        TaskState next = task.getState().next();
        if (next == null) {
            throw new BadRequestAlertException("A done task cannot be advanced any further", ENTITY_NAME, "alreadyDone");
        }
        checkDoneRequiresActualEffort(next, task.getActualEffortHours());
        task.setState(next);
        return UserStoryTaskDTO.of(userStoryTaskRepository.save(task));
    }

    /**
     * Reorders the user's board to match the given sequence of task ids (drag-and-drop).
     *
     * @param exerciseId     the id of the user story exercise
     * @param orderedTaskIds every task currently on the board, in the new order
     * @param user           the requesting user
     * @return the tasks in their new order
     */
    public List<UserStoryTaskDTO> reorder(long exerciseId, List<Long> orderedTaskIds, User user) {
        StudentParticipation participation = resolveOwnParticipationElseThrow(exerciseId, user);
        checkBeforeDueDateElseThrow(participation);
        List<UserStoryTask> tasks = userStoryTaskRepository.findAllByParticipationIdOrderByOrderIndexAsc(participation.getId());

        if (orderedTaskIds.size() != tasks.size() || new HashSet<>(orderedTaskIds).size() != orderedTaskIds.size()) {
            throw new BadRequestAlertException("Received the wrong number of task ids", ENTITY_NAME, "taskIdsSizeMismatch");
        }
        Map<Long, UserStoryTask> tasksById = tasks.stream().collect(Collectors.toMap(UserStoryTask::getId, task -> task));
        Set<Long> boardTaskIds = tasksById.keySet();
        if (!boardTaskIds.containsAll(orderedTaskIds)) {
            throw new BadRequestAlertException("Received a task that is not on this board", ENTITY_NAME, "taskMismatch");
        }

        for (int index = 0; index < orderedTaskIds.size(); index++) {
            tasksById.get(orderedTaskIds.get(index)).setOrderIndex(index);
        }
        List<UserStoryTask> saved = userStoryTaskRepository.saveAll(tasks);
        return saved.stream().sorted(Comparator.comparing(UserStoryTask::getOrderIndex)).map(UserStoryTaskDTO::of).toList();
    }

    /**
     * Deletes a task the user owns.
     *
     * @param taskId the id of the task to delete
     * @param user   the requesting user
     */
    public void delete(long taskId, User user) {
        UserStoryTask task = userStoryTaskRepository.findByIdElseThrow(taskId);
        checkBeforeDueDateElseThrow(checkOwnTaskElseThrow(task, user));
        userStoryTaskRepository.delete(task);
    }

    /**
     * Rejects moving a task to {@link TaskState#DONE} without a logged actual effort - time logging only becomes
     * available once a task is {@code IN_PROGRESS}, and marking it done is the point at which that number must exist.
     */
    private void checkDoneRequiresActualEffort(TaskState targetState, @Nullable Double actualEffortHours) {
        if (targetState == TaskState.DONE && actualEffortHours == null) {
            throw new BadRequestAlertException("The actual effort must be logged before a task can be marked as done", ENTITY_NAME, "actualEffortRequiredForDone");
        }
    }

    private void applyFields(UserStoryTask task, UserStoryTaskDTO taskDTO) {
        task.setTitle(taskDTO.title().trim());
        task.setDescription(taskDTO.description());
        task.setTaskPoints(taskDTO.taskPoints());
        task.setPriority(taskDTO.priority());
        task.setEstimatedEffortHours(taskDTO.estimatedEffortHours());
    }

    /**
     * Resolves the participation the user creates tasks on, rejecting anything that is not the user's own
     * participation in a user story exercise.
     *
     * @param exerciseId the id of the exercise, which must be a {@link UserStoryExercise}
     * @param user       the requesting user
     * @return the user's (or their team's) participation in that exercise
     */
    private StudentParticipation resolveOwnParticipationElseThrow(long exerciseId, User user) {
        Exercise exercise = exerciseRepository.findByIdElseThrow(exerciseId);
        if (!(exercise instanceof UserStoryExercise)) {
            throw new BadRequestAlertException("Tasks can only be created for a user story exercise", ENTITY_NAME, "notUserStoryExercise");
        }
        // Team-aware: for a team exercise this resolves the team's single participation, so its members share one board.
        StudentParticipation participation = participationService.findOneByExerciseAndStudentAnyState(exercise, user)
                .orElseThrow(() -> new BadRequestAlertException("The exercise has to be started before tasks can be created for it", ENTITY_NAME, "participationMissing"));
        // Belt and braces: the lookup above is already scoped to this user, so this only ever fires if that changes.
        participationAuthorizationCheckService.checkCanAccessParticipationElseThrow(participation);
        return participation;
    }

    /**
     * Rejects any caller other than the participant (or teammate) the task's participation belongs to. Deliberately
     * stricter than {@link ParticipationAuthorizationCheckService#checkCanAccessParticipationElseThrow}, which also
     * lets a tutor read the participation: a personal task board is the participant's own to change. A tutor only ever
     * reads it, through the milestone assessment ({@code MilestoneAssessmentService}).
     *
     * @return the participation the task belongs to
     */
    private StudentParticipation checkOwnTaskElseThrow(UserStoryTask task, User user) {
        StudentParticipation participation = studentParticipationRepository.findByIdElseThrow(task.getParticipation().getId());
        if (!participation.isOwnedBy(user)) {
            throw new AccessForbiddenException(ENTITY_NAME, task.getId());
        }
        return participation;
    }

    /**
     * Rejects any change to a board once its story is due: the board is what the story is assessed against, so it is
     * frozen from then on. A participation's individual due date wins over the story's own, as for submissions.
     * The exercise is reloaded by id, since the participation's may be an unfetched proxy.
     */
    private void checkBeforeDueDateElseThrow(StudentParticipation participation) {
        ZonedDateTime dueDate = participation.getIndividualDueDate();
        if (dueDate == null) {
            dueDate = exerciseRepository.findByIdElseThrow(participation.getExercise().getId()).getDueDate();
        }
        if (dueDate != null && ZonedDateTime.now().isAfter(dueDate)) {
            throw new BadRequestAlertException("Tasks can no longer be changed after the due date", ENTITY_NAME, "dueDatePassed");
        }
    }

    private void validate(UserStoryTaskDTO taskDTO) {
        String title = taskDTO.title() == null ? "" : taskDTO.title().trim();
        if (title.isEmpty() || title.length() > MAX_TITLE_LENGTH) {
            throw new BadRequestAlertException("The task title must be between 1 and " + MAX_TITLE_LENGTH + " characters", ENTITY_NAME, "titleInvalid");
        }
        if (taskDTO.taskPoints() == null || !TASK_POINTS_FIBONACCI_OPTIONS.contains(taskDTO.taskPoints())) {
            throw new BadRequestAlertException("The task points must be one of the Fibonacci scale values " + TASK_POINTS_FIBONACCI_OPTIONS, ENTITY_NAME, "taskPointsInvalid");
        }
        if (taskDTO.priority() == null) {
            throw new BadRequestAlertException("The task priority must be set", ENTITY_NAME, "priorityMissing");
        }
        if (taskDTO.state() == null) {
            throw new BadRequestAlertException("The task state must be set", ENTITY_NAME, "stateMissing");
        }
        if (taskDTO.estimatedEffortHours() == null || taskDTO.estimatedEffortHours() < 0) {
            throw new BadRequestAlertException("The estimated effort must not be negative", ENTITY_NAME, "estimatedEffortHoursInvalid");
        }
        if (taskDTO.estimatedEffortHours() > MAX_EFFORT_HOURS) {
            throw new BadRequestAlertException("The estimated effort must not exceed " + MAX_EFFORT_HOURS + " hours", ENTITY_NAME, "estimatedEffortHoursTooLarge");
        }
        if (taskDTO.actualEffortHours() != null) {
            if (taskDTO.actualEffortHours() < 0) {
                throw new BadRequestAlertException("The actual effort must not be negative", ENTITY_NAME, "actualEffortHoursInvalid");
            }
            if (taskDTO.actualEffortHours() > MAX_EFFORT_HOURS) {
                throw new BadRequestAlertException("The actual effort must not exceed " + MAX_EFFORT_HOURS + " hours", ENTITY_NAME, "actualEffortHoursTooLarge");
            }
        }
    }
}
