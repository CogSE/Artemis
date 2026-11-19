package de.tum.cit.aet.artemis.exercise.web;

import static de.tum.cit.aet.artemis.core.config.Constants.PROFILE_CORE;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import jakarta.validation.Valid;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import de.tum.cit.aet.artemis.account.domain.User;
import de.tum.cit.aet.artemis.account.repository.UserRepository;
import de.tum.cit.aet.artemis.assessment.domain.GradingScale;
import de.tum.cit.aet.artemis.assessment.service.GradingScaleService;
import de.tum.cit.aet.artemis.core.exception.BadRequestAlertException;
import de.tum.cit.aet.artemis.core.exception.ConflictException;
import de.tum.cit.aet.artemis.core.exception.InternalServerErrorException;
import de.tum.cit.aet.artemis.core.security.Role;
import de.tum.cit.aet.artemis.core.security.annotations.EnforceAtLeastInstructor;
import de.tum.cit.aet.artemis.core.security.annotations.EnforceAtLeastTutor;
import de.tum.cit.aet.artemis.core.service.AuthorizationCheckService;
import de.tum.cit.aet.artemis.core.service.featureusage.FeatureUsage;
import de.tum.cit.aet.artemis.core.service.featureusage.UserFeature;
import de.tum.cit.aet.artemis.core.service.messaging.InstanceMessageSendService;
import de.tum.cit.aet.artemis.core.util.HeaderUtil;
import de.tum.cit.aet.artemis.course.domain.Course;
import de.tum.cit.aet.artemis.exercise.domain.Exercise;
import de.tum.cit.aet.artemis.exercise.domain.participation.StudentParticipation;
import de.tum.cit.aet.artemis.exercise.dto.ParticipationDueDateUpdateDTO;
import de.tum.cit.aet.artemis.exercise.dto.ParticipationUpdateDTO;
import de.tum.cit.aet.artemis.exercise.dto.StudentIndividualDueDateDTO;
import de.tum.cit.aet.artemis.exercise.dto.StudentParticipationDTO;
import de.tum.cit.aet.artemis.exercise.repository.ExerciseRepository;
import de.tum.cit.aet.artemis.exercise.repository.MilestoneExerciseGroupRepository;
import de.tum.cit.aet.artemis.exercise.repository.StudentParticipationRepository;
import de.tum.cit.aet.artemis.exercise.service.ExerciseDateService;
import de.tum.cit.aet.artemis.exercise.service.ParticipationService;
import de.tum.cit.aet.artemis.programming.domain.MilestoneExercise;
import de.tum.cit.aet.artemis.programming.domain.ProgrammingExercise;
import de.tum.cit.aet.artemis.programming.service.MilestoneIndividualDueDateService;
import de.tum.cit.aet.artemis.quiz.domain.QuizExercise;

/**
 * REST controller for updating participations.
 */
@Profile(PROFILE_CORE)
@Lazy
@FeatureUsage(UserFeature.EXERCISE_PARTICIPATIONS_STAFF)
@RestController
@RequestMapping("api/exercise/")
public class ParticipationUpdateResource {

    private static final Logger log = LoggerFactory.getLogger(ParticipationUpdateResource.class);

    private static final String ENTITY_NAME = "participation";

    @Value("${jhipster.clientApp.name}")
    private String applicationName;

    private final ParticipationService participationService;

    private final AuthorizationCheckService authCheckService;

    private final ExerciseDateService exerciseDateService;

    private final InstanceMessageSendService instanceMessageSendService;

    private final GradingScaleService gradingScaleService;

    private final ExerciseRepository exerciseRepository;

    private final UserRepository userRepository;

    private final StudentParticipationRepository studentParticipationRepository;

    private final MilestoneIndividualDueDateService milestoneIndividualDueDateService;

    private final MilestoneExerciseGroupRepository milestoneExerciseGroupRepository;

    public ParticipationUpdateResource(ParticipationService participationService, ExerciseRepository exerciseRepository, AuthorizationCheckService authCheckService,
            UserRepository userRepository, StudentParticipationRepository studentParticipationRepository, ExerciseDateService exerciseDateService,
            InstanceMessageSendService instanceMessageSendService, GradingScaleService gradingScaleService, MilestoneIndividualDueDateService milestoneIndividualDueDateService,
            MilestoneExerciseGroupRepository milestoneExerciseGroupRepository) {
        this.participationService = participationService;
        this.exerciseRepository = exerciseRepository;
        this.authCheckService = authCheckService;
        this.userRepository = userRepository;
        this.studentParticipationRepository = studentParticipationRepository;
        this.exerciseDateService = exerciseDateService;
        this.instanceMessageSendService = instanceMessageSendService;
        this.gradingScaleService = gradingScaleService;
        this.milestoneIndividualDueDateService = milestoneIndividualDueDateService;
        this.milestoneExerciseGroupRepository = milestoneExerciseGroupRepository;
    }

    /**
     * PUT /participations : Updates an existing participation's presentation score.
     *
     * @param exerciseId the id of the exercise, the participation belongs to
     * @param dto        the DTO containing the participation update information
     * @return the ResponseEntity with status 200 (OK) and with body the updated participation, or with status 400 (Bad Request) if the participation is not valid, or with status
     *         500 (Internal Server Error) if the participation couldn't be updated
     */
    @PutMapping("exercises/{exerciseId}/participations")
    @EnforceAtLeastTutor
    public ResponseEntity<StudentParticipationDTO> updateParticipation(@PathVariable long exerciseId, @Valid @RequestBody ParticipationUpdateDTO dto) {
        log.debug("REST request to update Participation : {}", dto);
        if (dto.exerciseId() != exerciseId) {
            throw new ConflictException("The exercise of the participation does not match the exercise id in the URL", ENTITY_NAME, "noidmatch");
        }

        // Load the existing participation from database to avoid orphan removal issues with detached entities
        StudentParticipation existingParticipation = studentParticipationRepository.findByIdElseThrow(dto.id());
        if (existingParticipation.getExercise().getId() != exerciseId) {
            throw new ConflictException("The participation does not belong to the specified exercise", ENTITY_NAME, "noidmatch");
        }

        var user = userRepository.getUserWithAuthorities();
        authCheckService.checkHasAtLeastRoleForExerciseElseThrow(Role.TEACHING_ASSISTANT, existingParticipation.getExercise(), null);

        Course course = existingParticipation.getExercise().getCourseViaExerciseGroupOrCourseMember();
        boolean hideParticipant = !authCheckService.isAtLeastInstructorInCourse(course, user) && !authCheckService.isOwnerOfParticipation(existingParticipation)
                && exerciseDateService.isAssessmentAnonymizationActive(existingParticipation.getExercise());
        String participantLabel = hideParticipant ? existingParticipation.getId().toString() : existingParticipation.getParticipant().getName();
        Double newPresentationScore = dto.presentationScore();

        if (newPresentationScore != null && existingParticipation.getExercise().getPresentationScoreEnabled() != null
                && existingParticipation.getExercise().getPresentationScoreEnabled()) {
            Optional<GradingScale> gradingScale = gradingScaleService.findGradingScaleByCourseId(course.getId());

            // Presentation Score is only valid for non practice participations
            if (existingParticipation.isPracticeMode()) {
                throw new BadRequestAlertException("Presentation score is not allowed for practice participations", ENTITY_NAME, "presentationScoreInvalid");
            }

            // Validity of presentationScore for basic presentations
            if (course.getPresentationScore() != null && course.getPresentationScore() > 0) {
                if (newPresentationScore >= 1.) {
                    newPresentationScore = 1.;
                }
                else {
                    newPresentationScore = null;
                }
            }
            // Validity of presentationScore for graded presentations
            if (gradingScale.isPresent() && gradingScale.get().getPresentationsNumber() != null) {
                if (newPresentationScore != null && (newPresentationScore > 100. || newPresentationScore < 0.)) {
                    throw new BadRequestAlertException("The presentation grade must be between 0 and 100", ENTITY_NAME, "presentationGradeInvalid");
                }

                long presentationCountForParticipant = studentParticipationRepository.countPresentationScoresForParticipant(course.getId(),
                        existingParticipation.getParticipant().getId(), existingParticipation.getId());
                if (presentationCountForParticipant >= gradingScale.get().getPresentationsNumber()) {
                    throw new BadRequestAlertException("Participant already gave the maximum number of presentations", ENTITY_NAME,
                            "invalid.presentations.maxNumberOfPresentationsExceeded",
                            Map.of("name", participantLabel, "presentationsNumber", gradingScale.get().getPresentationsNumber()));
                }
            }
        }
        // Validity of presentationScore for no presentations
        else {
            newPresentationScore = null;
        }

        // Log if presentation score was removed or reduced
        if (existingParticipation.getPresentationScore() != null && newPresentationScore == null || course.getPresentationScore() != null
                && existingParticipation.getPresentationScore() != null && newPresentationScore != null && existingParticipation.getPresentationScore() > newPresentationScore) {
            log.info("{} removed the presentation score of {} for exercise with participationId {}", user.getLogin(), existingParticipation.getParticipantIdentifier(),
                    existingParticipation.getExercise().getId());
        }

        // Apply the update to the managed entity
        existingParticipation.setPresentationScore(newPresentationScore);

        StudentParticipation updatedParticipation = studentParticipationRepository.saveAndFlush(existingParticipation);
        var response = StudentParticipationDTO.ofAfterUpdate(updatedParticipation);
        return ResponseEntity.ok().headers(HeaderUtil.createEntityUpdateAlert(applicationName, true, ENTITY_NAME, participantLabel))
                .body(hideParticipant ? response.withoutParticipantInformation() : response);
    }

    /**
     * PUT /participations/update-individual-due-date : Updates the individual due dates for the given already existing participations.
     * <p>
     * If the exercise is a programming exercise, also triggers a scheduling
     * update for the participations where the individual due date has changed.
     *
     * @param exerciseId        of the exercise the participations belong to.
     * @param dueDateUpdateDTOs the DTOs containing participation IDs and new due dates.
     * @return all participations where the individual due date actually changed.
     */
    @PutMapping("exercises/{exerciseId}/participations/update-individual-due-date")
    @EnforceAtLeastInstructor
    public ResponseEntity<List<StudentParticipationDTO>> updateParticipationDueDates(@PathVariable long exerciseId,
            @Valid @RequestBody List<@Valid ParticipationDueDateUpdateDTO> dueDateUpdateDTOs) {
        final boolean anyInvalidExerciseId = dueDateUpdateDTOs.stream().anyMatch(dto -> dto.exerciseId() != exerciseId);
        if (anyInvalidExerciseId) {
            throw new BadRequestAlertException("The participation needs to be connected to the specified exercise", ENTITY_NAME, "exerciseidmismatch");
        }

        final Exercise exercise = exerciseRepository.findByIdElseThrow(exerciseId);
        authCheckService.checkHasAtLeastRoleForExerciseElseThrow(Role.INSTRUCTOR, exercise, null);

        if (exercise.isExamExercise()) {
            throw new BadRequestAlertException("Cannot set individual due dates for exam exercises", ENTITY_NAME, "examexercise");
        }
        if (exercise instanceof QuizExercise) {
            throw new BadRequestAlertException("Cannot set individual due dates for quiz exercises", ENTITY_NAME, "quizexercise");
        }
        // A member of a milestone group takes its individual due dates from the milestone: set there, they reach every member.
        // A date set on the member directly would be overwritten by the next copy from the milestone.
        if (milestoneExerciseGroupRepository.findMilestoneExerciseIdByUserStoryExerciseId(exerciseId).isPresent()) {
            throw new BadRequestAlertException("Set individual due dates on the milestone, which passes them on to every exercise of its group", ENTITY_NAME,
                    "milestonememberduedate");
        }

        final List<StudentParticipation> changedParticipations = participationService.updateIndividualDueDatesFromDTOs(exercise, dueDateUpdateDTOs);
        final List<StudentParticipation> updatedParticipations = studentParticipationRepository.saveAllAndFlush(changedParticipations);
        if (exercise instanceof MilestoneExercise) {
            // The members judge lateness by their own participation's due date, so they take the milestone's. Every
            // participation of the request is copied, not only the changed ones: saving a date again repairs members that
            // drifted out of step.
            List<StudentParticipation> requestedParticipations = studentParticipationRepository
                    .findAllById(dueDateUpdateDTOs.stream().map(ParticipationDueDateUpdateDTO::id).toList()).stream()
                    .filter(participation -> participation.getExercise() != null && participation.getExercise().getId() == exerciseId).toList();
            // A member the student has not started yet has no participation to carry the date, so it is started first.
            milestoneIndividualDueDateService.createMissingMemberParticipations(exerciseId, requestedParticipations);
            milestoneIndividualDueDateService.syncIndividualDueDatesToMembers(exerciseId, requestedParticipations)
                    .forEach(instanceMessageSendService::sendProgrammingExerciseSchedule);
        }

        if (!updatedParticipations.isEmpty() && exercise instanceof ProgrammingExercise programmingExercise) {
            log.info("Updating scheduling for exercise {} (id {}) due to changed individual due dates.", exercise.getTitle(), exercise.getId());
            instanceMessageSendService.sendProgrammingExerciseSchedule(programmingExercise.getId());
            List<StudentParticipation> participationsBeforeDueDate = updatedParticipations.stream().filter(exerciseDateService::isBeforeDueDate).toList();
            List<StudentParticipation> participationsAfterDueDate = updatedParticipations.stream().filter(exerciseDateService::isAfterDueDate).toList();

            if (exercise.isTeamMode()) {
                participationService.initializeTeamParticipations(participationsBeforeDueDate);
                participationService.initializeTeamParticipations(participationsAfterDueDate);
            }
        }

        return ResponseEntity.ok().body(updatedParticipations.stream().map(StudentParticipationDTO::ofAfterUpdate).toList());
    }

    /**
     * PUT exercises/:exerciseId/participations/individual-due-date/students/:studentLogin : Grants one student an
     * individual due date on a milestone, whether or not they have started it.
     * <p>
     * An individual due date lives on a participation. A student who has not started the milestone yet is given one here,
     * exactly as their own start would: the repository, the access token and the user story participations sharing that
     * repository, but no submission and no result. Every other member the student has not started is started as well, so
     * it can carry the date, which is then copied to every member of the milestone's group.
     *
     * @param exerciseId   the id of the milestone exercise
     * @param studentLogin the login of the student
     * @param dueDateDTO   the new individual due date, after the milestone's regular due date
     * @return the student's milestone participation with its new individual due date
     */
    @PutMapping("exercises/{exerciseId}/participations/individual-due-date/students/{studentLogin}")
    @EnforceAtLeastInstructor
    public ResponseEntity<StudentParticipationDTO> setMilestoneIndividualDueDateOfStudent(@PathVariable long exerciseId, @PathVariable String studentLogin,
            @Valid @RequestBody StudentIndividualDueDateDTO dueDateDTO) {
        final Exercise exercise = exerciseRepository.findByIdElseThrow(exerciseId);
        authCheckService.checkHasAtLeastRoleForExerciseElseThrow(Role.INSTRUCTOR, exercise, null);

        if (!(exercise instanceof MilestoneExercise) || !exercise.isCourseExercise() || exercise.isTeamMode()) {
            throw new BadRequestAlertException("Individual due dates can only be granted to a student without a participation on an individual course milestone", ENTITY_NAME,
                    "milestoneonly");
        }
        if (exercise.getDueDate() == null || dueDateDTO.individualDueDate().isBefore(exercise.getDueDate())) {
            throw new BadRequestAlertException("The individual due date has to lie after the due date of the milestone", ENTITY_NAME, "individualDueDateBeforeDueDate");
        }
        final Course course = exercise.getCourseViaExerciseGroupOrCourseMemberElseThrow();
        final User student = userRepository.findOneWithAuthoritiesByLogin(studentLogin).filter(user -> authCheckService.isAtLeastStudentInCourse(course, user))
                .orElseThrow(() -> new BadRequestAlertException("The student could not be found in this course", ENTITY_NAME, "studentNotFound"));

        final long participationId = participationService.findOneGradedByExerciseAndStudentAnyState(exercise, student).orElseGet(() -> startMilestoneForStudent(exercise, student))
                .getId();
        // Read back fresh: the start above saved the participation in several steps.
        final StudentParticipation participation = studentParticipationRepository.findByIdElseThrow(participationId);
        participation.setIndividualDueDate(dueDateDTO.individualDueDate());
        final StudentParticipation updatedParticipation = studentParticipationRepository.saveAndFlush(participation);

        milestoneIndividualDueDateService.createMissingMemberParticipations(exerciseId, List.of(updatedParticipation));
        milestoneIndividualDueDateService.syncIndividualDueDatesToMembers(exerciseId, List.of(updatedParticipation))
                .forEach(instanceMessageSendService::sendProgrammingExerciseSchedule);
        instanceMessageSendService.sendProgrammingExerciseSchedule(exerciseId);

        return ResponseEntity.ok().body(StudentParticipationDTO.ofAfterUpdate(updatedParticipation));
    }

    private StudentParticipation startMilestoneForStudent(Exercise milestoneExercise, User student) {
        try {
            return participationService.startExercise(milestoneExercise, student, false);
        }
        catch (Exception e) {
            log.error("Failed to start milestone {} for student {} to grant an individual due date", milestoneExercise.getId(), student.getLogin(), e);
            throw new InternalServerErrorException("Failed to create the student's participation in the milestone.");
        }
    }
}
