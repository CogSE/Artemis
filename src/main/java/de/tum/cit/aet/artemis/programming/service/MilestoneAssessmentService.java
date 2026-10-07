package de.tum.cit.aet.artemis.programming.service;

import static de.tum.cit.aet.artemis.core.config.Constants.PROFILE_CORE;

import java.math.BigInteger;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.jspecify.annotations.Nullable;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import de.tum.cit.aet.artemis.account.repository.UserRepository;
import de.tum.cit.aet.artemis.assessment.domain.Result;
import de.tum.cit.aet.artemis.assessment.repository.ResultRepository;
import de.tum.cit.aet.artemis.exercise.domain.Exercise;
import de.tum.cit.aet.artemis.exercise.domain.MilestoneExerciseGroup;
import de.tum.cit.aet.artemis.exercise.domain.Submission;
import de.tum.cit.aet.artemis.exercise.domain.participation.StudentParticipation;
import de.tum.cit.aet.artemis.exercise.dto.MilestoneAssessmentDTO;
import de.tum.cit.aet.artemis.exercise.dto.MilestoneAssessmentExerciseDTO;
import de.tum.cit.aet.artemis.exercise.dto.MilestoneAssessmentStudentDTO;
import de.tum.cit.aet.artemis.exercise.repository.MilestoneExerciseGroupRepository;
import de.tum.cit.aet.artemis.exercise.repository.StudentParticipationRepository;
import de.tum.cit.aet.artemis.programming.domain.MilestoneExercise;
import de.tum.cit.aet.artemis.programming.domain.ProgrammingExerciseStudentParticipation;
import de.tum.cit.aet.artemis.programming.domain.UserStoryExercise;
import de.tum.cit.aet.artemis.programming.dto.ResultDTO;
import de.tum.cit.aet.artemis.programming.repository.ProgrammingExerciseRepository;
import de.tum.cit.aet.artemis.programming.repository.ProgrammingExerciseStudentParticipationRepository;

/**
 * Assembles the tutor-facing views of a {@code MilestoneExerciseGroup}: the dashboard a tutor picks a student from, and
 * everything the milestone assessment page needs for the student they picked.
 * <p>
 * Grading a milestone is organised by student rather than by submission, unlike every other assessment flow in Artemis.
 * A group's user stories share one repository and one build, so assessing them one story at a time - which is what the
 * per-exercise dashboards offer - means reading the same codebase once per story, for a different student each time.
 * Both views here are therefore keyed on the student. A group may also hold text, modeling, file upload and quiz
 * exercises; both views list those after the user stories, in the same order, so a tab index means the same thing on
 * either page.
 */
@Profile(PROFILE_CORE)
@Lazy
@Service
public class MilestoneAssessmentService {

    private final MilestoneExerciseGroupRepository milestoneExerciseGroupRepository;

    private final ProgrammingExerciseStudentParticipationRepository programmingExerciseStudentParticipationRepository;

    private final StudentParticipationRepository studentParticipationRepository;

    private final ProgrammingExerciseRepository programmingExerciseRepository;

    private final ResultRepository resultRepository;

    private final ProgrammingFeedbackSynthesizerService programmingFeedbackSynthesizerService;

    private final UserRepository userRepository;

    public MilestoneAssessmentService(MilestoneExerciseGroupRepository milestoneExerciseGroupRepository,
            ProgrammingExerciseStudentParticipationRepository programmingExerciseStudentParticipationRepository, StudentParticipationRepository studentParticipationRepository,
            ProgrammingExerciseRepository programmingExerciseRepository, ResultRepository resultRepository,
            ProgrammingFeedbackSynthesizerService programmingFeedbackSynthesizerService, UserRepository userRepository) {
        this.milestoneExerciseGroupRepository = milestoneExerciseGroupRepository;
        this.programmingExerciseStudentParticipationRepository = programmingExerciseStudentParticipationRepository;
        this.studentParticipationRepository = studentParticipationRepository;
        this.programmingExerciseRepository = programmingExerciseRepository;
        this.resultRepository = resultRepository;
        this.programmingFeedbackSynthesizerService = programmingFeedbackSynthesizerService;
        this.userRepository = userRepository;
    }

    /**
     * Every student who has started the group's milestone, with the standing of each of their exercises in the group.
     * <p>
     * A student appears as soon as they have a milestone participation, because that is the moment the shared
     * repository exists - an exercise they never started still gets a cell, with nothing to assess, which is itself what
     * the tutor needs to know.
     *
     * @param groupId  the id of the milestone exercise group
     * @param courseId the id of the course the group belongs to
     * @return one entry per student, ordered by login so the dashboard is stable across reloads
     */
    public List<MilestoneAssessmentStudentDTO> getAssessmentDashboard(long groupId, long courseId) {
        MilestoneExerciseGroup group = milestoneExerciseGroupRepository.findByIdAndCourseIdWithDetailsElseThrow(groupId, courseId);
        long milestoneExerciseId = milestoneExerciseId(group);
        List<Exercise> exercises = orderedExercises(group);

        // One query per exercise rather than one per (exercise, student): a group has a handful of exercises and a course
        // has many students, so the per-student shape would be a query per cell of the table.
        Map<Long, Map<String, StudentParticipation>> participationsByExercise = exercises.stream().collect(Collectors.toMap(Exercise::getId,
                exercise -> byStudentLogin(studentParticipationRepository.findAllWithEagerSubmissionsAndEagerResultsAndEagerAssessorByExerciseIdIgnoreTestRuns(exercise.getId()))));

        return programmingExerciseStudentParticipationRepository.findAllByExerciseIdAndRepositoryUriIsNotNullAndTestRunFalse(milestoneExerciseId).stream()
                .filter(participation -> participation.getStudent().isPresent()).sorted(Comparator.comparing(participation -> participation.getStudent().orElseThrow().getLogin()))
                .map(milestoneParticipation -> {
                    var student = milestoneParticipation.getStudent().orElseThrow();
                    List<MilestoneAssessmentExerciseDTO> exerciseStates = exercises.stream()
                            .map(exercise -> toExerciseDTO(exercise, participationsByExercise.get(exercise.getId()).get(student.getLogin()))).toList();
                    return new MilestoneAssessmentStudentDTO(student.getLogin(), student.getName(), milestoneParticipation.getId(), exerciseStates);
                }).toList();
    }

    /**
     * Everything the milestone assessment page renders for one student: the group-level information of its first tab
     * and the exercises its remaining tabs grade.
     *
     * @param groupId      the id of the milestone exercise group
     * @param courseId     the id of the course the group belongs to
     * @param studentLogin the login of the student whose milestone is being assessed
     * @return the milestone's own information together with the ordered exercises
     */
    public MilestoneAssessmentDTO getAssessmentForStudent(long groupId, long courseId, String studentLogin) {
        MilestoneExerciseGroup group = milestoneExerciseGroupRepository.findByIdAndCourseIdWithDetailsElseThrow(groupId, courseId);
        long milestoneExerciseId = milestoneExerciseId(group);
        MilestoneExercise milestoneExercise = (MilestoneExercise) programmingExerciseRepository.findByIdElseThrow(milestoneExerciseId);
        long studentId = userRepository.getArbitraryValueElseThrow(userRepository.findIdByLogin(studentLogin), studentLogin);

        List<MilestoneAssessmentExerciseDTO> exercises = orderedExercises(group).stream().map(exercise -> toExerciseDTO(exercise,
                studentParticipationRepository.findWithSubmissionsResultsAndAssessorByExerciseIdAndStudentId(exercise.getId(), studentId).stream().findFirst().orElse(null)))
                .toList();

        Optional<ProgrammingExerciseStudentParticipation> milestoneParticipation = programmingExerciseStudentParticipationRepository
                .findByExerciseIdAndStudentId(milestoneExerciseId, studentId);

        return new MilestoneAssessmentDTO(milestoneExerciseId, milestoneExercise.getTitle(), milestoneExercise.getProblemStatement(),
                Boolean.TRUE.equals(milestoneExercise.isStaticCodeAnalysisEnabled()), milestoneExercise.getMaxStaticCodeAnalysisPenalty(), milestoneExercise.getMaxPoints(),
                milestoneParticipation.map(ProgrammingExerciseStudentParticipation::getId).orElse(null),
                milestoneParticipation.map(participation -> milestoneResult(participation, milestoneExercise)).orElse(null), exercises);
    }

    /**
     * The student's latest milestone result, with the static code analysis feedback the group's issues live in.
     * <p>
     * Those rows sit in a typed table and only become client-shaped feedback through the synthesizer, so the explicit
     * overload is used: this runs off a plain REST request whose result graph does not reach the exercise, and letting
     * the synthesizer walk there itself would mean a lazy load that {@code open-in-view} being off does not allow.
     */
    @Nullable
    private ResultDTO milestoneResult(ProgrammingExerciseStudentParticipation milestoneParticipation, MilestoneExercise milestoneExercise) {
        Optional<Result> result = resultRepository.findLatestResultWithFeedbacksForParticipation(milestoneParticipation.getId(), true);
        if (result.isEmpty()) {
            return null;
        }
        Result milestoneResult = result.get();
        programmingFeedbackSynthesizerService.attachSynthesizedFeedback(milestoneResult, milestoneExercise, false);
        // The submission came back with the result above, but its participation did not - and that is what the payload
        // is built from. Point it at the one already loaded rather than letting the DTO reach for an uninitialized proxy.
        if (milestoneResult.getSubmission() != null) {
            milestoneResult.getSubmission().setParticipation(milestoneParticipation);
        }
        return ResultDTO.of(milestoneResult);
    }

    /**
     * One exercise's standing for a single student. An exercise the student never started still produces an entry, with
     * everything but the exercise itself unset - "not started" is information a tutor needs, not a row to hide.
     */
    private MilestoneAssessmentExerciseDTO toExerciseDTO(Exercise exercise, @Nullable StudentParticipation participation) {
        boolean userStory = exercise instanceof UserStoryExercise;
        if (participation == null) {
            return new MilestoneAssessmentExerciseDTO(exercise.getId(), exercise.getTitle(), exercise.getExerciseType(), userStory, exercise.getMaxPoints(), null, null, null, null,
                    false);
        }
        Submission latestSubmission = participation.findLatestSubmission().orElse(null);
        Result latestResult = latestSubmission == null ? null : latestSubmission.getLatestResult();
        String assessorLogin = latestResult == null || latestResult.getAssessor() == null ? null : latestResult.getAssessor().getLogin();
        boolean assessed = latestResult != null && latestResult.isManual() && latestResult.getCompletionDate() != null;
        return new MilestoneAssessmentExerciseDTO(exercise.getId(), exercise.getTitle(), exercise.getExerciseType(), userStory, exercise.getMaxPoints(), participation.getId(),
                latestSubmission == null ? null : latestSubmission.getId(), latestResult == null ? null : latestResult.getScore(), assessorLogin, assessed);
    }

    /**
     * The group's exercises in the order both views render them: user stories first, then every other exercise, each
     * block by title in natural order (so "US 2" comes before "US 10"), with the id breaking ties.
     */
    private static List<Exercise> orderedExercises(MilestoneExerciseGroup group) {
        Comparator<Exercise> byUserStoryFirst = Comparator.comparing(exercise -> !(exercise instanceof UserStoryExercise));
        Comparator<Exercise> byTitle = Comparator.comparing(exercise -> Objects.requireNonNullElse(exercise.getTitle(), ""), MilestoneAssessmentService::compareNaturally);
        return group.getExercises().stream().sorted(byUserStoryFirst.thenComparing(byTitle).thenComparing(Exercise::getId)).toList();
    }

    /**
     * Compares two strings the way a person reads them: runs of digits by their numeric value, everything else
     * case-insensitively. A plain string comparison would put "US 10" before "US 2".
     */
    private static int compareNaturally(String first, String second) {
        int i = 0;
        int j = 0;
        while (i < first.length() && j < second.length()) {
            char a = first.charAt(i);
            char b = second.charAt(j);
            if (Character.isDigit(a) && Character.isDigit(b)) {
                int endA = i;
                while (endA < first.length() && Character.isDigit(first.charAt(endA))) {
                    endA++;
                }
                int endB = j;
                while (endB < second.length() && Character.isDigit(second.charAt(endB))) {
                    endB++;
                }
                int comparison = new BigInteger(first.substring(i, endA)).compareTo(new BigInteger(second.substring(j, endB)));
                if (comparison != 0) {
                    return comparison;
                }
                i = endA;
                j = endB;
            }
            else {
                int comparison = Character.compare(Character.toLowerCase(a), Character.toLowerCase(b));
                if (comparison != 0) {
                    return comparison;
                }
                i++;
                j++;
            }
        }
        return Integer.compare(first.length() - i, second.length() - j);
    }

    private static long milestoneExerciseId(MilestoneExerciseGroup group) {
        Long milestoneExerciseId = group.getMilestoneExerciseId();
        if (milestoneExerciseId == null) {
            throw new IllegalStateException("The milestone group " + group.getId() + " has no anchor milestone exercise");
        }
        return milestoneExerciseId;
    }

    private static Map<String, StudentParticipation> byStudentLogin(Set<StudentParticipation> participations) {
        return participations.stream().filter(participation -> participation.getStudent().isPresent())
                .collect(Collectors.toMap(participation -> participation.getStudent().orElseThrow().getLogin(), Function.identity(), (first, second) -> first));
    }

}
