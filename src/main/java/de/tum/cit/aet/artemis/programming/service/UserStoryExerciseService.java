package de.tum.cit.aet.artemis.programming.service;

import static de.tum.cit.aet.artemis.core.config.Constants.PROFILE_CORE;
import static de.tum.cit.aet.artemis.programming.web.ProgrammingWebsocketTopics.TEST_CASES;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.context.annotation.Lazy;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import de.tum.cit.aet.artemis.communication.service.WebsocketMessagingService;
import de.tum.cit.aet.artemis.exercise.domain.Exercise;
import de.tum.cit.aet.artemis.exercise.domain.MilestoneExerciseGroup;
import de.tum.cit.aet.artemis.exercise.repository.MilestoneExerciseGroupRepository;
import de.tum.cit.aet.artemis.programming.domain.MilestoneExercise;
import de.tum.cit.aet.artemis.programming.domain.ProgrammingExerciseBuildConfig;
import de.tum.cit.aet.artemis.programming.domain.ProgrammingExerciseParticipation;
import de.tum.cit.aet.artemis.programming.domain.ProgrammingExerciseStudentParticipation;
import de.tum.cit.aet.artemis.programming.domain.ProgrammingExerciseTask;
import de.tum.cit.aet.artemis.programming.domain.ProgrammingExerciseTestCase;
import de.tum.cit.aet.artemis.programming.domain.ProgrammingSubmission;
import de.tum.cit.aet.artemis.programming.domain.SolutionProgrammingExerciseParticipation;
import de.tum.cit.aet.artemis.programming.domain.TemplateProgrammingExerciseParticipation;
import de.tum.cit.aet.artemis.programming.domain.UserStoryExercise;
import de.tum.cit.aet.artemis.programming.dto.ProgrammingExerciseTestCaseResponseDTO;
import de.tum.cit.aet.artemis.programming.repository.ProgrammingExerciseBuildConfigRepository;
import de.tum.cit.aet.artemis.programming.repository.ProgrammingExerciseRepository;
import de.tum.cit.aet.artemis.programming.repository.ProgrammingExerciseStudentParticipationRepository;
import de.tum.cit.aet.artemis.programming.repository.ProgrammingExerciseTaskRepository;
import de.tum.cit.aet.artemis.programming.repository.ProgrammingExerciseTestCaseRepository;
import de.tum.cit.aet.artemis.programming.repository.ProgrammingSubmissionRepository;

/**
 * Keeps a {@link UserStoryExercise}'s Language/Version-Control settings, repositories and test cases in sync with its
 * group's {@link MilestoneExercise}.
 * <p>
 * Language/VC/repository values are <em>copied</em>, not live-delegated through overridden getters: several of {@link
 * de.tum.cit.aet.artemis.programming.domain.ProgrammingExercise}'s own internal methods (e.g. {@code
 * getVcsTestRepositoryUri}, {@code getTemplateBuildPlanId}, {@code forceNewProjectKey}, {@code
 * disconnectRelatedEntities}) read their backing fields directly rather than through their own getters, so a getter
 * override would be silently bypassed by exactly the internal machinery (repo/build-plan lookups) that matters most.
 * <p>
 * Test cases are <em>duplicated</em> onto each {@code UserStoryExercise} as its own {@link ProgrammingExerciseTestCase}
 * rows for the same reason, one level deeper: {@link ProgrammingExerciseGradingService#calculateScoreForResult} and
 * {@link ProgrammingExerciseTaskService#updateTasksFromProblemStatement} both resolve test cases via a direct
 * {@code exercise_id} database query rather than through {@code exercise.getTestCases()}, so a shared pool owned only
 * by the milestone would be invisible to a user story's own problem-statement task parsing and to its own grading -
 * both would silently see zero test cases. Duplicating keeps every existing test-case/task/grading query working
 * completely unmodified for a {@code UserStoryExercise}, exactly as it does for any other {@code ProgrammingExercise}.
 * <p>
 * "The problem statement defines which tests are relevant" is implemented by reusing the existing, already-tested
 * {@code active} flag: {@link #updateRelevantTestCases} marks a user story's own test case active if and only if its
 * own {@link ProgrammingExerciseTask}s (parsed from its own problem statement) reference it - the grading pipeline
 * already only scores active test cases, so this needs no changes there either.
 * <p>
 * All of this is re-applied whenever a {@code UserStoryExercise} is created or (re-)assigned to a different
 * {@code MilestoneExerciseGroup}, mirroring how {@code ExerciseVariantGroupService} pushes a group's shared timeline
 * onto its members; the test-case duplication additionally re-runs on every sibling whenever the milestone's own test
 * suite changes (a new push to its solution repository), via {@link #syncAllMembersTestCases}.
 */
@Profile(PROFILE_CORE)
@Lazy
@Service
public class UserStoryExerciseService {

    private final ProgrammingExerciseTestCaseRepository programmingExerciseTestCaseRepository;

    private final ProgrammingExerciseTaskRepository programmingExerciseTaskRepository;

    private final ProgrammingExerciseTaskService programmingExerciseTaskService;

    private final ProgrammingExerciseRepository programmingExerciseRepository;

    private final ProgrammingExerciseBuildConfigRepository programmingExerciseBuildConfigRepository;

    private final MilestoneExerciseGroupRepository milestoneExerciseGroupRepository;

    private final ProgrammingExerciseStudentParticipationRepository programmingExerciseStudentParticipationRepository;

    private final ProgrammingSubmissionRepository programmingSubmissionRepository;

    private final ProgrammingSubmissionMessagingService programmingSubmissionMessagingService;

    private final WebsocketMessagingService websocketMessagingService;

    public UserStoryExerciseService(ProgrammingExerciseTestCaseRepository programmingExerciseTestCaseRepository,
            ProgrammingExerciseTaskRepository programmingExerciseTaskRepository, ProgrammingExerciseTaskService programmingExerciseTaskService,
            ProgrammingExerciseRepository programmingExerciseRepository, ProgrammingExerciseBuildConfigRepository programmingExerciseBuildConfigRepository,
            MilestoneExerciseGroupRepository milestoneExerciseGroupRepository, ProgrammingExerciseStudentParticipationRepository programmingExerciseStudentParticipationRepository,
            ProgrammingSubmissionRepository programmingSubmissionRepository, ProgrammingSubmissionMessagingService programmingSubmissionMessagingService,
            WebsocketMessagingService websocketMessagingService) {
        this.programmingExerciseTestCaseRepository = programmingExerciseTestCaseRepository;
        this.programmingExerciseTaskRepository = programmingExerciseTaskRepository;
        this.programmingExerciseTaskService = programmingExerciseTaskService;
        this.programmingExerciseRepository = programmingExerciseRepository;
        this.programmingExerciseBuildConfigRepository = programmingExerciseBuildConfigRepository;
        this.milestoneExerciseGroupRepository = milestoneExerciseGroupRepository;
        this.programmingExerciseStudentParticipationRepository = programmingExerciseStudentParticipationRepository;
        this.programmingSubmissionRepository = programmingSubmissionRepository;
        this.programmingSubmissionMessagingService = programmingSubmissionMessagingService;
        this.websocketMessagingService = websocketMessagingService;
    }

    /**
     * The milestone exercise of the group a user story belongs to.
     *
     * @param userStoryExerciseId the user story exercise
     * @return the id of its group's milestone exercise, or empty if it is not part of a milestone group
     */
    public Optional<Long> findMilestoneExerciseId(long userStoryExerciseId) {
        return milestoneExerciseGroupRepository.findMilestoneExerciseIdByUserStoryExerciseId(userStoryExerciseId);
    }

    /**
     * The participation of the milestone whose repository a user story participation works in.
     * <p>
     * A user story has no build of its own: its participation shares the milestone's repository, and the build that
     * runs on a push belongs to the milestone's participation, which is where everything the build produced is
     * recorded. A caller that needs one of those - the build log, for instance, which the fan-out does not copy onto
     * the stories - resolves the owning participation here instead of reading a copy.
     * <p>
     * Resolved through the repository uri rather than the student: that uri is exactly what the two participations
     * share, so the match is the participation whose build wrote the log. A team participation is not part of the
     * sharing scheme (see {@code ParticipationService.startUserStoryExercise}) and simply finds nothing.
     *
     * @param userStoryParticipation the participation of a user story exercise
     * @return the milestone participation sharing its repository, or empty if there is none
     */
    public Optional<ProgrammingExerciseStudentParticipation> findMilestoneParticipationForUserStory(ProgrammingExerciseParticipation userStoryParticipation) {
        String repositoryUri = userStoryParticipation.getRepositoryUri();
        if (repositoryUri == null) {
            return Optional.empty();
        }
        return milestoneExerciseGroupRepository.findMilestoneExerciseIdByUserStoryExerciseId(userStoryParticipation.getProgrammingExercise().getId())
                .flatMap(milestoneExerciseId -> programmingExerciseStudentParticipationRepository.findByExerciseIdAndRepositoryUri(milestoneExerciseId, repositoryUri));
    }

    /**
     * Creates a pending (resultless) {@link ProgrammingSubmission} for every {@code UserStoryExercise} sibling the
     * student has already started, and notifies each one, at the same moment the milestone's own canonical push is
     * notified (or its build triggered) - not later, once the build finishes and {@link ProgrammingExerciseGradingService#fanOutResultToUserStoryExercise} runs. Without this,
     * a sibling's "building..." / queued progress indicator (which is driven purely by a submission existing without
     * a result yet, see {@code ProgrammingSubmissionService}) never appears at all: the result-only fan-out only
     * lets a sibling jump straight from "-" to the final score once the build is already done, since the client
     * never learns a build started in the first place.
     *
     * @param sourceSubmission       the canonical participation's submission for the push or build trigger
     * @param milestoneExercise      the milestone exercise the push landed on
     * @param milestoneParticipation the student's milestone participation the push was made to
     */
    public void provisionPendingSubmissionsForUserStoryExercises(ProgrammingSubmission sourceSubmission, MilestoneExercise milestoneExercise,
            ProgrammingExerciseStudentParticipation milestoneParticipation) {
        // The siblings are the participations sharing this repository: graded ones for the graded repository, practice
        // ones for a practice repository.
        String repositoryUri = milestoneParticipation.getRepositoryUri();
        if (repositoryUri == null) {
            return;
        }

        milestoneExerciseGroupRepository.findByMilestoneExerciseIdWithExercises(milestoneExercise.getId()).ifPresent(group -> {
            for (Exercise member : group.getExercises()) {
                if (!(member instanceof UserStoryExercise userStoryExercise)) {
                    continue;
                }
                programmingExerciseStudentParticipationRepository.findByExerciseIdAndRepositoryUri(userStoryExercise.getId(), repositoryUri).ifPresent(targetParticipation -> {
                    // A rebuild of a commit the story already has a submission for (a trigger rather than a push) reuses
                    // it, just as the milestone side reuses its own. A second, resultless submission next to an assessed
                    // one would make the fan-out insert an automatic result beside the tutor's instead of updating it.
                    ProgrammingSubmission pendingSubmission = programmingSubmissionRepository
                            .findFirstByParticipationIdAndCommitHashOrderByIdDescWithFeedbacksAndTeamStudents(targetParticipation.getId(), sourceSubmission.getCommitHash());
                    if (pendingSubmission == null) {
                        pendingSubmission = new ProgrammingSubmission();
                        pendingSubmission.setParticipation(targetParticipation);
                        pendingSubmission.setSubmitted(true);
                        pendingSubmission.setType(sourceSubmission.getType());
                        pendingSubmission.setCommitHash(sourceSubmission.getCommitHash());
                        pendingSubmission.setSubmissionDate(sourceSubmission.getSubmissionDate());
                        pendingSubmission = programmingSubmissionRepository.save(pendingSubmission);
                    }
                    programmingSubmissionMessagingService.notifyUserAboutSubmission(pendingSubmission, userStoryExercise.getId());
                });
            }
        });
    }

    /**
     * Copies the Language/Version-Control settings and repository URIs from {@code milestoneExercise} onto
     * {@code userStoryExercise} in place. Does not persist - the caller is expected to save the exercise afterwards.
     * The template/solution participations and build config are created on first call (a brand-new
     * {@code UserStoryExercise}) and updated in place on every later call (a group re-assignment), since each
     * {@code UserStoryExercise} needs its own participation/build-config rows (unique per-exercise foreign keys) even
     * though their content - most importantly the repository URIs - is identical to the milestone's.
     *
     * @param userStoryExercise the exercise to update in place
     * @param milestoneExercise the group's milestone exercise to copy the configuration from
     * @param copyAllSettings   whether the grading settings of test cases the user story already has are reset to the
     *                              milestone's as well, see {@link #syncTestCasesFromMilestone}
     */
    public void applyMilestoneConfig(UserStoryExercise userStoryExercise, MilestoneExercise milestoneExercise, boolean copyAllSettings) {
        userStoryExercise.setProgrammingLanguage(milestoneExercise.getProgrammingLanguage());
        userStoryExercise.setPackageName(milestoneExercise.getPackageName());
        userStoryExercise.setProjectType(milestoneExercise.getProjectType());
        userStoryExercise.setAllowOnlineEditor(milestoneExercise.isAllowOnlineEditor());
        userStoryExercise.setAllowOfflineIde(milestoneExercise.isAllowOfflineIde());
        userStoryExercise.setAllowOnlineIde(milestoneExercise.isAllowOnlineIde());
        // Static code analysis describes the shared codebase, not one user story, so it is configured and priced once on
        // the milestone (see MilestoneScoreService) and deliberately NOT copied down here. Leaving it disabled makes the
        // generic SCA path in ProgrammingExerciseGradingService a no-op for user stories - which is what keeps the same
        // violation from being charged once per story - and hides the SCA grading tab on them.
        userStoryExercise.setStaticCodeAnalysisEnabled(false);
        userStoryExercise.setMaxStaticCodeAnalysisPenalty(null);

        String templateRepositoryUri = milestoneExercise.getTemplateRepositoryUri();
        if (userStoryExercise.getTemplateParticipation() == null) {
            userStoryExercise.setTemplateParticipation(new TemplateProgrammingExerciseParticipation());
        }
        userStoryExercise.getTemplateParticipation().setRepositoryUri(templateRepositoryUri);

        String solutionRepositoryUri = milestoneExercise.getSolutionRepositoryUri();
        if (userStoryExercise.getSolutionParticipation() == null) {
            userStoryExercise.setSolutionParticipation(new SolutionProgrammingExerciseParticipation());
        }
        userStoryExercise.getSolutionParticipation().setRepositoryUri(solutionRepositoryUri);

        userStoryExercise.setTestRepositoryUri(milestoneExercise.getTestRepositoryUri());

        // Each UserStoryExercise still gets its own project key (course short name + its own short name) - it's only
        // used for display/generation purposes here, since the actual repository URIs above are copied verbatim
        // rather than derived from it, and generateAndSetProjectKey() is a no-op once already set.
        userStoryExercise.generateAndSetProjectKey();

        // Requires both exercises to already have an id (an unsaved new UserStoryExercise has none yet) - the caller
        // saves the exercise first and calls syncBuildConfigFromMilestone and syncTestCasesFromMilestone separately in
        // that case; see the create endpoint.
        if (userStoryExercise.getId() != null) {
            syncBuildConfigFromMilestone(userStoryExercise, milestoneExercise);
            syncTestCasesFromMilestone(userStoryExercise, milestoneExercise, copyAllSettings);
            // the synced rows need a relevance decision, and the open problem statement editor the new test list
            updateRelevantTestCases(userStoryExercise);
        }
    }

    /**
     * Copies the milestone's build configuration onto the user story. The configuration is a row of its own that holds
     * its exercise's key, so each user story needs its own row even though the content is the milestone's. An existing
     * row of the user story is overwritten in place, which keeps the one-configuration-per-exercise key intact.
     *
     * @param userStoryExercise the already-persisted exercise to receive the configuration
     * @param milestoneExercise the group's milestone exercise to copy the configuration from
     */
    public void syncBuildConfigFromMilestone(UserStoryExercise userStoryExercise, MilestoneExercise milestoneExercise) {
        programmingExerciseBuildConfigRepository.findByProgrammingExerciseId(milestoneExercise.getId()).ifPresent(milestoneBuildConfig -> {
            ProgrammingExerciseBuildConfig copy = new ProgrammingExerciseBuildConfig(milestoneBuildConfig);
            programmingExerciseBuildConfigRepository.findByProgrammingExerciseId(userStoryExercise.getId()).ifPresent(existing -> copy.setId(existing.getId()));
            programmingExerciseBuildConfigRepository.saveForExercise(copy, userStoryExercise);
        });
    }

    /**
     * Duplicates {@code milestoneExercise}'s current test suite onto {@code userStoryExercise} as its own rows, matched
     * by test name. A new row starts inactive and with the milestone's weight, bonus and visibility; from then on these
     * grading settings belong to the user story and are configured on its own grading page, so a later sync leaves them
     * alone and only keeps the test type in step with the milestone. Existing rows keep their current {@code active}
     * state (only {@link #updateRelevantTestCases} changes that, based on task membership). A test case removed from the
     * milestone's suite is removed here too, dropping its task links along with it (join table). Persists immediately,
     * since (unlike the other config synced above) there is no single owning entity save that would otherwise cascade
     * these rows.
     * <p>
     * With {@code copyAllSettings}, the grading settings of existing rows are reset to the milestone's as well. That is
     * for a user story that moved to another milestone: what was configured for the old milestone's tests does not carry
     * over to the new one's.
     *
     * @param userStoryExercise the exercise whose test cases to update; must already be persisted (have an id)
     * @param milestoneExercise the group's milestone exercise, the source of truth for the shared test suite
     * @param copyAllSettings   whether existing rows also take the milestone's weight, bonus and visibility
     */
    public void syncTestCasesFromMilestone(UserStoryExercise userStoryExercise, MilestoneExercise milestoneExercise, boolean copyAllSettings) {
        Set<ProgrammingExerciseTestCase> milestoneTestCases = programmingExerciseTestCaseRepository.findByExerciseId(milestoneExercise.getId());
        Set<ProgrammingExerciseTestCase> ownTestCases = programmingExerciseTestCaseRepository.findByExerciseId(userStoryExercise.getId());
        Map<String, ProgrammingExerciseTestCase> ownByName = ownTestCases.stream()
                .collect(Collectors.toMap(ProgrammingExerciseTestCase::getTestName, Function.identity(), (first, second) -> first));

        List<ProgrammingExerciseTestCase> toSave = new ArrayList<>();
        for (ProgrammingExerciseTestCase milestoneTestCase : milestoneTestCases) {
            ProgrammingExerciseTestCase own = ownByName.get(milestoneTestCase.getTestName());
            if (own == null) {
                own = new ProgrammingExerciseTestCase();
                own.setTestName(milestoneTestCase.getTestName());
                own.setExercise(userStoryExercise);
                own.setActive(false);
            }
            else if (!copyAllSettings && own.getType() == milestoneTestCase.getType()) {
                // nothing the sync owns has changed, so the row is not written on every milestone build
                continue;
            }
            if (own.getId() == null || copyAllSettings) {
                // Only the initial grading settings come from the milestone, unless all are reset - see the javadoc
                own.setWeight(milestoneTestCase.getWeight());
                own.setBonusMultiplier(milestoneTestCase.getBonusMultiplier());
                own.setBonusPoints(milestoneTestCase.getBonusPoints());
                own.setVisibility(milestoneTestCase.getVisibility());
            }
            own.setType(milestoneTestCase.getType());
            toSave.add(own);
        }
        Set<String> milestoneTestCaseNames = milestoneTestCases.stream().map(ProgrammingExerciseTestCase::getTestName).collect(Collectors.toSet());
        List<ProgrammingExerciseTestCase> toDelete = ownTestCases.stream().filter(testCase -> !milestoneTestCaseNames.contains(testCase.getTestName())).toList();

        programmingExerciseTestCaseRepository.deleteAll(toDelete);
        programmingExerciseTestCaseRepository.saveAll(toSave);
    }

    /**
     * Runs {@link #syncTestCasesFromMilestone} for every {@code UserStoryExercise} member of {@code milestoneGroup},
     * then re-derives each one's relevant (active) test cases from its own current tasks. Called after the milestone's
     * solution build extracts a (possibly changed) set of test cases from its test repository, so every sibling's test
     * case rows stay current without requiring an edit to that sibling itself. Each sibling keeps its own grading
     * settings for the test cases it already has (see {@link #syncTestCasesFromMilestone}).
     *
     * @param milestoneGroup    the group whose members to update; its {@code exercises} must already be loaded
     * @param milestoneExercise the group's milestone exercise, freshly reloaded so its test cases are up to date
     */
    public void syncAllMembersTestCases(MilestoneExerciseGroup milestoneGroup, MilestoneExercise milestoneExercise) {
        for (var exercise : milestoneGroup.getExercises()) {
            if (exercise instanceof UserStoryExercise userStoryExercise) {
                syncTestCasesFromMilestone(userStoryExercise, milestoneExercise, false);
                updateRelevantTestCases(userStoryExercise);
            }
        }
    }

    /**
     * Runs {@link #applyMilestoneConfig} for every {@code UserStoryExercise} member of {@code milestoneGroup} and saves
     * each one. Dates are deliberately not copied here: the caller pushes the milestone's timeline onto every member of the
     * group - user stories and the other exercise types alike - through
     * {@code ExerciseVariantGroupService.saveWithTimelineAppliedToMembers}, which also re-derives each story's
     * build-and-test date and notifies about the change. Writing the dates here first would make that pass see them as
     * unchanged.
     * <p>
     * Called after a {@code MilestoneExercise} is edited (see {@code ProgrammingExerciseUpdateResource}), so every
     * sibling picks up the change without an edit of its own - the milestone create/edit page is deliberately the only
     * place Language/Version-Control/build settings are configured for the whole group (see
     * {@code ProgrammingExerciseUpdateComponent.isMilestoneMode} client-side).
     *
     * @param milestoneGroup    the group whose members to update; its {@code exercises} must already be loaded
     * @param milestoneExercise the group's (freshly saved) milestone exercise, the source of truth for the shared config
     */
    public void syncAllMembersConfig(MilestoneExerciseGroup milestoneGroup, MilestoneExercise milestoneExercise) {
        for (var exercise : milestoneGroup.getExercises()) {
            if (exercise instanceof UserStoryExercise) {
                programmingExerciseRepository.save(applyMilestoneConfigFreshFromDatabase(exercise.getId(), milestoneExercise, false));
            }
        }
    }

    /**
     * Re-fetches the persisted user story exercise identified by {@code userStoryExerciseId} with its
     * templateParticipation/solutionParticipation eagerly loaded, then runs {@link #applyMilestoneConfig} on that fresh
     * instance.
     * <p>
     * The re-fetch is needed because this architecture runs without an open Hibernate session across repository
     * calls: a caller's own instance (e.g. loaded by a generic {@code ExerciseRepository} lookup, or a member from
     * {@code MilestoneExerciseGroup.getExercises()}) never eagerly loads those associations, which
     * {@code applyMilestoneConfig} dereferences directly - it would otherwise throw {@code LazyInitializationException}.
     *
     * @param userStoryExerciseId the id of the already-persisted user story exercise to update
     * @param milestoneExercise   the group's milestone exercise, the source of truth for the shared config
     * @param copyAllSettings     whether the grading settings of existing test cases are reset to the milestone's as well
     * @return the fresh, config-updated instance - the caller still saves it, so its own further changes land on the same save
     */
    public UserStoryExercise applyMilestoneConfigFreshFromDatabase(long userStoryExerciseId, MilestoneExercise milestoneExercise, boolean copyAllSettings) {
        UserStoryExercise userStoryExercise = (UserStoryExercise) programmingExerciseRepository
                .findByIdWithTemplateAndSolutionParticipationTeamAssignmentConfigCategoriesCompetenciesAndVariantGroupElseThrow(userStoryExerciseId);
        applyMilestoneConfig(userStoryExercise, milestoneExercise, copyAllSettings);
        return userStoryExercise;
    }

    /**
     * Re-parses {@code userStoryExercise}'s own tasks from its own problem statement, then marks its own test cases
     * active if and only if at least one of those tasks references them - the mechanism behind "the problem statement
     * defines which tests are relevant, and therefore count towards grading, for a user story". The grading pipeline
     * already only scores active test cases (see {@link ProgrammingExerciseGradingService#calculateScoreForResult}), so
     * this alone is enough to make an unreferenced (shared-but-irrelevant-here) test case not count for this exercise.
     * <p>
     * Call whenever a user story's problem statement is saved (its own tasks may have changed) and after
     * {@link #syncTestCasesFromMilestone} (new test cases need a relevance decision too). Sends the resulting test cases
     * to the exercise's open editors.
     *
     * @param userStoryExercise the exercise whose task-to-test-case relevance to re-derive; must already be persisted
     */
    public void updateRelevantTestCases(UserStoryExercise userStoryExercise) {
        programmingExerciseTaskService.updateTasksFromProblemStatement(userStoryExercise);
        Set<ProgrammingExerciseTask> tasks = programmingExerciseTaskRepository.findByExerciseIdWithTestCases(userStoryExercise.getId());
        Set<Long> relevantTestCaseIds = tasks.stream().flatMap(task -> task.getTestCases().stream()).map(ProgrammingExerciseTestCase::getId).collect(Collectors.toSet());

        Set<ProgrammingExerciseTestCase> ownTestCases = programmingExerciseTestCaseRepository.findByExerciseId(userStoryExercise.getId());
        List<ProgrammingExerciseTestCase> changed = new ArrayList<>();
        for (ProgrammingExerciseTestCase testCase : ownTestCases) {
            boolean shouldBeActive = relevantTestCaseIds.contains(testCase.getId());
            boolean currentlyActive = Boolean.TRUE.equals(testCase.isActive());
            if (shouldBeActive != currentlyActive) {
                testCase.setActive(shouldBeActive);
                changed.add(testCase);
            }
        }
        programmingExerciseTestCaseRepository.saveAll(changed);
        broadcastTestCases(userStoryExercise);
    }

    /**
     * Sends the user story's current test cases to its open editors. Their test case list is otherwise only refreshed by
     * a build of the exercise itself, which a user story never has - its rows are created, removed and (de)activated
     * here instead.
     *
     * @param userStoryExercise the exercise whose test cases changed
     */
    private void broadcastTestCases(UserStoryExercise userStoryExercise) {
        Set<ProgrammingExerciseTestCaseResponseDTO> testCaseDTOs = programmingExerciseTestCaseRepository.findByExerciseId(userStoryExercise.getId()).stream()
                .map(ProgrammingExerciseTestCaseResponseDTO::of).collect(Collectors.toSet());
        websocketMessagingService.sendMessage(TEST_CASES.at(userStoryExercise.getId()), testCaseDTOs);
    }
}
