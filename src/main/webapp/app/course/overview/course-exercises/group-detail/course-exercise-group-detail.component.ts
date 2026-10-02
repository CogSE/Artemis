import { ChangeDetectionStrategy, Component, DestroyRef, EnvironmentInjector, afterNextRender, computed, effect, inject, linkedSignal, signal, untracked } from '@angular/core';
import { takeUntilDestroyed, toObservable } from '@angular/core/rxjs-interop';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { DomSanitizer, SafeHtml } from '@angular/platform-browser';
import { FaIconComponent } from '@fortawesome/angular-fontawesome';
import { faCircleInfo, faLayerGroup, faPlayCircle, faRotateRight, faWrench } from '@fortawesome/free-solid-svg-icons';
import { Subscription } from 'rxjs';
import { filter, finalize, map, skip } from 'rxjs/operators';
import { HttpErrorResponse } from '@angular/common/http';
import { DifficultyLevel, Exercise, ExerciseType, IncludedInOverallScore, getExerciseUrlSegment, getIcon } from 'app/exercise/shared/entities/exercise/exercise.model';
import { CourseExerciseGroup, buildGroupsFromExercises } from 'app/exercise/shared/entities/exercise/course-exercise-group.model';
import { CourseOverviewExercisesService } from 'app/course/overview/services/course-overview-exercises.service';
import { CourseStorageService } from 'app/course/manage/services/course-storage.service';
import { ExerciseVariantGroupService, MilestoneStatusDTO } from 'app/course/manage/exercises/exercise-variant-group.service';
import { CourseSidebarToggleButtonComponent } from 'app/course/shared/course-sidebar-toggle-button/course-sidebar-toggle-button.component';
import { EntityTitleService, EntityType } from 'app/core/navbar/entity-title.service';
import { ProgrammingExercisePlantUmlExtensionWrapper } from 'app/programming/shared/instructions-render/extensions/programming-exercise-plant-uml.extension';
import { taskRegex } from 'app/programming/shared/instructions-render/extensions/programming-exercise-task.extension';
import { htmlForMarkdown } from 'app/foundation/util/markdown.conversion.util';
import { ArtemisDatePipe } from 'app/foundation/pipes/artemis-date.pipe';
import { ArtemisTimeAgoPipe } from 'app/foundation/pipes/artemis-time-ago.pipe';
import { ArtemisTranslatePipe } from 'app/foundation/pipes/artemis-translate.pipe';
import { TranslateDirective } from 'app/foundation/language/translate.directive';
import { ExerciseHeadersInformationComponent } from 'app/exercise/exercise-headers/exercise-headers-information/exercise-headers-information.component';
import { InformationBox, InformationBoxComponent } from 'app/shared-ui/information-box/information-box.component';
import { StudentParticipation } from 'app/exercise/shared/entities/participation/student-participation.model';
import { ParticipationService } from 'app/exercise/participation/participation.service';
import { CourseExerciseService } from 'app/exercise/course-exercises/course-exercise.service';
import { Course } from 'app/course/shared/entities/course.model';
import { ArtemisServerDateService } from 'app/foundation/service/server-date.service';
import { ScoresStorageService } from 'app/course/manage/course-scores/scores-storage.service';
import { AlertService } from 'app/foundation/service/alert.service';
import { isDateLessThanAWeekInTheFuture } from 'app/foundation/util/date.utils';
import { TumAetUiButtonDirective, TumAetUiMenuComponent, TumAetUiMenuItemDirective, TumAetUiMenuTriggerDirective, TumAetUiTooltipDirective } from '@tumaet/ui-angular';
import { roundValueSpecifiedByCourseSettings } from 'app/foundation/util/utils';
import { cloneWith, hydrate } from 'app/foundation/util/deep-clone.util';
import { convertDateFromServer } from 'app/foundation/util/date.utils';
import { ExerciseActionButtonComponent } from 'app/shared-ui/components/buttons/exercise-action-button/exercise-action-button.component';
import { FeatureToggle } from 'app/foundation/feature-toggle/feature-toggle.service';
import { FeatureToggleDirective } from 'app/foundation/feature-toggle/feature-toggle.directive';
import { CodeButtonComponent } from 'app/shared-ui/components/buttons/code-button/code-button.component';
import { ProgrammingExerciseStudentParticipation } from 'app/exercise/shared/entities/participation/programming-exercise-student-participation.model';
import { ProgrammingExerciseParticipationService } from 'app/programming/manage/services/programming-exercise-participation.service';
import { ProgrammingExercise } from 'app/programming/shared/entities/programming-exercise.model';
import { Result } from 'app/exercise/shared/entities/result/result.model';
import { Participation, getLatestSubmission } from 'app/exercise/shared/entities/participation/participation.model';
import { getLatestResultOfStudentParticipation } from 'app/exercise/participation/participation.utils';
import { ParticipationWebsocketService } from 'app/course/shared/services/participation-websocket.service';
import { ProgrammingSubmissionService, ProgrammingSubmissionState } from 'app/programming/shared/services/programming-submission.service';
import { ResultComponent } from 'app/exercise/result/result.component';
import { MilestoneCodeQualityComponent } from 'app/programming/shared/milestone-code-quality/milestone-code-quality.component';
import { ProgrammingExerciseInstructionComponent } from 'app/programming/shared/instructions-render/programming-exercise-instruction.component';

@Component({
    selector: 'jhi-course-exercise-group-detail',
    templateUrl: './course-exercise-group-detail.component.html',
    styleUrls: ['./course-exercise-group-detail.component.scss'],
    imports: [
        RouterLink,
        FaIconComponent,
        ArtemisDatePipe,
        ArtemisTimeAgoPipe,
        ArtemisTranslatePipe,
        TranslateDirective,
        ExerciseHeadersInformationComponent,
        InformationBoxComponent,
        TumAetUiTooltipDirective,
        ExerciseActionButtonComponent,
        FeatureToggleDirective,
        CodeButtonComponent,
        TumAetUiButtonDirective,
        TumAetUiMenuComponent,
        TumAetUiMenuItemDirective,
        TumAetUiMenuTriggerDirective,
        MilestoneCodeQualityComponent,
        ResultComponent,
        ProgrammingExerciseInstructionComponent,
        CourseSidebarToggleButtonComponent,
    ],
    /* preserveWhitespaces: false is required here because the global tsconfig sets preserveWhitespaces: true,
     * which inserts whitespace text nodes that break [contentComponent] slot matching in jhi-information-box. */
    preserveWhitespaces: false,
    changeDetection: ChangeDetectionStrategy.OnPush,
})
export class CourseExerciseGroupDetailComponent {
    private readonly route = inject(ActivatedRoute);
    private readonly courseOverviewExercisesService = inject(CourseOverviewExercisesService);
    private readonly courseStorageService = inject(CourseStorageService);
    private readonly exerciseVariantGroupService = inject(ExerciseVariantGroupService);
    private readonly entityTitleService = inject(EntityTitleService);
    private readonly destroyRef = inject(DestroyRef);
    private readonly plantUmlWrapper = inject(ProgrammingExercisePlantUmlExtensionWrapper);
    private readonly sanitizer = inject(DomSanitizer);
    private readonly injector = inject(EnvironmentInjector);
    private readonly serverDateService = inject(ArtemisServerDateService);
    private readonly scoresStorageService = inject(ScoresStorageService);
    private readonly participationService = inject(ParticipationService);
    private readonly courseExerciseService = inject(CourseExerciseService);
    private readonly alertService = inject(AlertService);
    private readonly programmingExerciseParticipationService = inject(ProgrammingExerciseParticipationService);
    private readonly participationWebsocketService = inject(ParticipationWebsocketService);
    private readonly programmingSubmissionService = inject(ProgrammingSubmissionService);

    protected readonly faLayerGroup = faLayerGroup;
    protected readonly faCircleInfo = faCircleInfo;
    protected readonly faPlayCircle = faPlayCircle;
    protected readonly faWrench = faWrench;
    protected readonly faRotateRight = faRotateRight;
    protected readonly getIcon = getIcon;
    protected readonly DifficultyLevel = DifficultyLevel;
    protected readonly FeatureToggle = FeatureToggle;

    private readonly now = this.serverDateService.now();

    /*
     * The milestone state below is kept per group (and per participation), never as a single value. The router reuses this
     * component when only :groupId changes, and a group that was already loaded is not fetched again - so a single value
     * would keep showing whichever group was loaded last: going 1 -> 2 -> 1 left group 2's description, task results and
     * code quality on group 1's page. Each response is stored under the key it was requested for and the current group's
     * entry is selected by computeds, which also keeps a late response for a group already left from overwriting the
     * one on screen.
     */

    /** Each loaded group's milestone status, keyed by group id. */
    private readonly milestoneStatusByGroupId = signal<Map<number, MilestoneStatusDTO>>(new Map());
    /** Whether the requesting student has started the current group's anchor milestone exercise; undefined until loaded. */
    protected readonly milestoneStatus = computed<MilestoneStatusDTO | undefined>(() => {
        const groupId = this.groupId();
        return groupId === undefined ? undefined : this.milestoneStatusByGroupId().get(groupId);
    });
    protected readonly isStartingMilestone = signal(false);
    /**
     * Groups whose milestone-status request failed. Without this the header simply renders nothing when the request fails
     * - no button, no message - which is indistinguishable from "this group has no start action", and a 404 (the most
     * likely failure here) is suppressed by the global alert handler, so the failure was completely invisible.
     */
    private readonly failedMilestoneStatusGroupIds = signal<ReadonlySet<number>>(new Set());
    private readonly loadingMilestoneStatusGroupIds = signal<ReadonlySet<number>>(new Set());
    protected readonly milestoneStatusFailed = computed<boolean>(() => this.isCurrentGroupIn(this.failedMilestoneStatusGroupIds()));
    protected readonly isLoadingMilestoneStatus = computed<boolean>(() => this.isCurrentGroupIn(this.loadingMilestoneStatusGroupIds()));
    /** Milestone groups whose status has already been requested, so revisiting a group does not re-fetch it. */
    private readonly requestedMilestoneStatusGroupIds = new Set<number>();

    /**
     * The student's own participation in the group's anchor milestone, with its latest result and feedback - the only
     * place the group's static code analysis feedback lives (see `MilestoneCodeQualityComponent`). Undefined until the
     * milestone has been started and the request has come back.
     */
    protected readonly milestoneParticipation = computed<ProgrammingExerciseStudentParticipation | undefined>(() => {
        const participationId = this.milestoneStatus()?.participationId;
        return participationId === undefined ? undefined : this.milestoneParticipationsById().get(participationId);
    });
    /** Each loaded milestone participation, keyed by participation id. */
    private readonly milestoneParticipationsById = signal<Map<number, ProgrammingExerciseStudentParticipation>>(new Map());

    /**
     * The most recent milestone result pushed over the websocket, which supersedes the one the participation request
     * brought along. Undefined until a build finishes while this page is open.
     * <p>
     * A result arrives here twice per push: once when the build finishes, and once again ~half a second later when
     * `MilestoneScoreService` has aggregated the group's story points onto it. Both updates carry the same result id
     * and the same static code analysis feedback - the aggregation rewrites the very same row - so the code-quality
     * box does not flicker between two different issue sets; only the score changes.
     */
    private readonly liveMilestoneResult = computed<Result | undefined>(() => {
        const participationId = this.milestoneStatus()?.participationId;
        return participationId === undefined ? undefined : this.liveMilestoneResultsByParticipationId().get(participationId);
    });
    /** The latest websocket result per milestone participation, keyed by the participation it was subscribed for. */
    private readonly liveMilestoneResultsByParticipationId = signal<Map<number, Result>>(new Map());
    /** Whether the milestone's build is queued / running, so the code-quality box can say so instead of showing a stale count. */
    protected readonly isMilestoneBuilding = signal(false);
    protected readonly isMilestoneQueued = signal(false);
    /** The participation the live subscriptions below are currently open for, so they can be released after it changes. */
    private subscribedMilestoneParticipationId?: number;
    private milestoneResultSubscription?: Subscription;
    private milestoneSubmissionSubscription?: Subscription;
    /** Milestone participations already requested, so an unrelated re-render does not re-issue the request. */
    private readonly requestedMilestoneParticipationIds = new Set<number>();

    /** The anchor milestone exercise itself, which is where static code analysis is configured. */
    protected readonly milestoneExercise = computed<ProgrammingExercise | undefined>(() => this.milestoneParticipation()?.exercise);

    /**
     * The latest result of the milestone's own build, which carries the group's static code analysis feedback. Prefers
     * whatever the websocket last delivered over the snapshot the one-shot participation request brought along.
     */
    protected readonly milestoneResult = computed<Result | undefined>(() => {
        const liveResult = this.liveMilestoneResult();
        if (liveResult) {
            return liveResult;
        }
        // Across all submissions rather than off the latest one: a push creates a pending submission without a result, and
        // the latest submission's result would then be nothing for the whole build.
        return getLatestResultOfStudentParticipation(this.milestoneParticipation(), true);
    });

    /**
     * Whether the group's shared build failed, which is what gives the status badge something to open: the build log.
     * Reads the result's own submission first and falls back to the participation's latest, the same order
     * {@link FeedbackComponent} uses to decide whether to fetch the log, so badge and dialog agree.
     */
    protected readonly isMilestoneBuildFailed = computed<boolean>(() => {
        const participation = this.milestoneParticipation();
        const submission = this.milestoneResult()?.submission ?? (participation ? getLatestSubmission(participation) : undefined);
        // Read through the shared Submission type, which does not declare the flag a programming submission carries.
        return submission !== undefined && 'buildFailed' in submission && submission.buildFailed === true;
    });

    private readonly groupId = signal<number | undefined>(undefined);
    private readonly courseExercises = signal<Exercise[]>([]);
    protected readonly course = signal<Course | undefined>(undefined);

    /**
     * Websocket-updated participations of the group's member exercises, keyed by exercise id. A milestone build fans a
     * result out to every started user story (server-side: `ProgrammingExerciseGradingService.fanOutResultToUserStoryExercise`),
     * and each of those results is broadcast on the student's personal topic - so the cards below can stay current
     * without re-fetching the course dashboard.
     */
    private readonly liveParticipations = signal<Map<number, StudentParticipation>>(new Map());
    /** Member participations already handed to the websocket service, so an unrelated re-render does not re-register them. */
    private readonly registeredVariantParticipationIds = new Set<number>();

    protected readonly isSidebarCollapsed = signal(false);
    private readonly sidebarToggle = signal<(() => void) | undefined>(undefined);
    protected readonly showSidebarToggle = computed(() => !!this.sidebarToggle());
    protected readonly toggleSidebar = () => this.sidebarToggle()?.();

    private plantUmlCallbacks: Array<() => void> = [];

    protected readonly group = computed<CourseExerciseGroup | undefined>(() => {
        const groupId = this.groupId();
        if (groupId === undefined) {
            return undefined;
        }
        return buildGroupsFromExercises(this.courseExercises()).find((candidate) => candidate.id === groupId);
    });
    protected readonly exercises = computed<Exercise[]>(() => this.group()?.exercises ?? []);

    /**
     * Sum of maxPoints over the INCLUDED_COMPLETELY variants only, matching the server's inclusion rule; bonus and
     * not-included variants must not inflate the denominator.
     */
    protected readonly exerciseSumMaxPoints = computed<number>(() =>
        this.roundPoints(
            this.exercises()
                .filter((exercise) => exercise.includedInOverallScore === IncludedInOverallScore.INCLUDED_COMPLETELY)
                .reduce((sum, ex) => sum + (ex.maxPoints ?? 0), 0),
        ),
    );

    /**
     * The group's real maximum: min(sum of variant max points, group cap), since a cap above the sum cannot raise
     * what is achievable. The cap is detected with an explicit undefined check so a zero cap still applies.
     */
    protected readonly effectiveGroupMaxPoints = computed<number>(() => {
        const cap = this.group()?.maxPoints;
        const sum = this.exerciseSumMaxPoints();
        return this.roundPoints(cap !== undefined ? Math.min(sum, cap) : sum);
    });

    /** Whether the cap actually reduces the achievable maximum (set and strictly below the variants' sum). Only then is
     * the cap explanation (tooltip / callout) meaningful; a cap ≥ the sum behaves exactly like no cap. */
    protected readonly capReducesMaxPoints = computed<boolean>(() => {
        const cap = this.group()?.maxPoints;
        return cap !== undefined && cap < this.exerciseSumMaxPoints();
    });

    /**
     * The student's group points, taken from the authoritative server value via {@link ScoresStorageService}, which is
     * already capped and plagiarism-adjusted. Falls back to 0 until the dashboard scores load.
     * <p>
     * For a milestone group, a live update takes precedence once one has arrived while the page is open - a milestone
     * result, or a new result on any of the group's other members. The group's points are then rebuilt the way the
     * server builds them: the milestone result's score, which `MilestoneScoreService` recomputes as
     * `sum(story points) - static code analysis penalty`, plus every non-story member's own points (text, modeling, file
     * upload, quiz and plain programming members are not part of that aggregate - they count on their own results). That
     * live figure is not plagiarism-adjusted, unlike the stored one, so a flagged student's number only re-aligns on the
     * next dashboard load - the accepted price of showing a live number.
     */
    protected readonly achievedGroupPoints = computed<number>(() => {
        const group = this.group();
        if (group?.id === undefined) {
            return 0;
        }
        if (group.type === 'milestone' && this.hasLiveMilestoneGroupUpdate()) {
            return this.roundPoints(this.milestoneResultPoints() + this.nonStoryMemberPoints());
        }
        return this.roundPoints(this.scoresStorageService.getStoredAchievedGroupPoints(this.courseId, group.id) ?? 0);
    });

    /** Whether anything that changes a milestone group's points has been pushed since the page loaded its stored value. */
    private readonly hasLiveMilestoneGroupUpdate = computed<boolean>(() => {
        if (this.liveMilestoneResult()) {
            return true;
        }
        const liveExerciseIds = this.liveParticipations();
        return this.nonStoryMembers().some((exercise) => exercise.id !== undefined && liveExerciseIds.has(exercise.id));
    });

    /** The points the milestone's own result carries - the group's user story points after the code quality penalty. */
    private readonly milestoneResultPoints = computed<number>(() => {
        const score = this.milestoneResult()?.score;
        const milestoneMaxPoints = this.milestoneExercise()?.maxPoints;
        return score !== undefined && milestoneMaxPoints !== undefined ? (score / 100) * milestoneMaxPoints : 0;
    });

    /** The members of the group that are not user stories, and so are credited on their own results rather than through the milestone. */
    private readonly nonStoryMembers = computed<Exercise[]>(() =>
        this.exercises().filter((exercise) => exercise.type !== ExerciseType.USER_STORY && exercise.includedInOverallScore !== IncludedInOverallScore.NOT_INCLUDED),
    );

    /** The points the student holds on the group's non-story members, each from its latest rated result. */
    private readonly nonStoryMemberPoints = computed<number>(() =>
        this.nonStoryMembers().reduce((sum, exercise) => {
            const score = getLatestResultOfStudentParticipation(this.exerciseParticipation(exercise), false)?.score;
            return sum + (score !== undefined ? (score / 100) * (exercise.maxPoints ?? 0) : 0);
        }, 0),
    );

    /**
     * What the points box shows: {@link achievedGroupPoints}, except that a queued or running milestone build keeps the
     * value from before it instead of whatever passes through in the meantime. The group's points only have a new
     * settled value once the build's aggregated result has arrived, so anything shown earlier (a zero, or a raw build
     * score) would only flash a wrong number at the student.
     */
    protected readonly displayedGroupPoints = linkedSignal<{ points: number; isBuildPending: boolean }, number>({
        source: () => ({ points: this.achievedGroupPoints(), isBuildPending: this.isMilestoneBuilding() || this.isMilestoneQueued() }),
        computation: (source, previous) => (source.isBuildPending && previous !== undefined ? previous.value : source.points),
    });

    /**
     * The milestone as the instructions renderer needs it once the student has started it: the participation's exercise,
     * carrying the problem statement from the milestone-status request. That request is the documented source of the
     * statement (the milestone itself is never sent to students), so it is set explicitly rather than trusting the
     * participation's nested exercise to carry it.
     * <p>
     * Rendered through `ProgrammingExerciseInstructionComponent`, the milestone's `[task]` entries show the tests they
     * reference together with their outcome in the student's latest milestone build - the milestone owns the group's
     * full test suite, so its own result is where those outcomes live.
     */
    protected readonly milestoneInstructionsExercise = computed<ProgrammingExercise | undefined>(() => {
        const exercise = this.milestoneExercise();
        const problemStatement = this.milestoneStatus()?.problemStatement;
        if (!exercise || !problemStatement) {
            return undefined;
        }
        return cloneWith(exercise, { problemStatement });
    });

    /**
     * The milestone group's description, which is its anchor MilestoneExercise's problem statement, as shown before the
     * student has started the milestone (see {@link milestoneInstructionsExercise} for afterwards). The milestone itself
     * is never rendered to students, so it arrives via the milestone-status request the view already makes rather than
     * with the dashboard payload — the callout therefore falls back to the generic heading until that resolves.
     *
     * Rendered by {@link renderMilestoneDescription} with the PlantUML extension, so diagrams in the description render
     * before the milestone is started too. It is a signal rather than a
     * computed because that extension is stateful (setExerciseId plus callbacks flushed in afterNextRender), which a pure
     * computed cannot drive.
     */
    protected readonly milestoneDescriptionHtml = signal<SafeHtml | undefined>(undefined);

    protected readonly pointsInfoBoxData: InformationBox = {
        title: 'artemisApp.courseOverview.exerciseDetails.points',
        content: { type: 'string', value: '' },
        isContentComponent: true,
    };

    /** The shared build's status, labelled like the exercise page's own status box (see ExerciseHeadersInformationComponent). */
    protected readonly buildStatusInfoBoxData: InformationBox = {
        title: 'artemisApp.courseOverview.exerciseDetails.status',
        content: { type: 'string', value: '' },
        isContentComponent: true,
    };

    protected readonly variantsInfoBoxData = computed<InformationBox>(() => ({
        title: this.group()?.type === 'milestone' ? 'artemisApp.exerciseVariantGroup.detail.milestoneVariants' : 'artemisApp.exerciseVariantGroup.detail.variants',
        content: { type: 'string', value: this.exercises().length },
    }));

    /** Dynamic date info boxes for the group header, mirroring the exercise due-date + assessment-due logic. */
    protected readonly groupDateInfoBoxes = computed<InformationBox[]>(() => {
        const group = this.group();
        const now = this.now;
        const dueDate = group?.dueDate;
        const startDate = group?.startDate;
        const assessmentDueDate = group?.assessmentDueDate;
        const items: InformationBox[] = [];

        if (dueDate) {
            if (dueDate.isBefore(now)) {
                items.push({
                    title: 'artemisApp.courseOverview.exerciseDetails.submissionDueOver',
                    content: { type: 'dateTime', value: dueDate },
                    isContentComponent: true,
                });
            } else {
                const relative = isDateLessThanAWeekInTheFuture(dueDate, now);
                const color = dueDate.isBetween(now, now.add(1, 'day')) ? 'danger' : 'body-color';
                items.push({
                    title: 'artemisApp.courseOverview.exerciseDetails.submissionDue',
                    content: { type: relative ? 'timeAgo' : 'dateTime', value: dueDate },
                    isContentComponent: true,
                    contentColor: color,
                    tooltip: relative ? 'artemisApp.courseOverview.exerciseDetails.submissionDueTooltip' : undefined,
                    tooltipParams: relative ? { date: dueDate.format('lll') } : undefined,
                });
            }
            if (dueDate.isBefore(now) && assessmentDueDate?.isAfter(now)) {
                items.push({
                    title: 'artemisApp.courseOverview.exerciseDetails.assessmentDue',
                    content: { type: 'dateTime', value: assessmentDueDate },
                    isContentComponent: true,
                    tooltip: 'artemisApp.courseOverview.exerciseDetails.assessmentDueTooltip',
                    tooltipParams: { date: assessmentDueDate.format('lll') },
                });
            }
        }
        if (startDate?.isAfter(now)) {
            const relative = isDateLessThanAWeekInTheFuture(startDate, now);
            items.push({
                title: 'artemisApp.courseOverview.exerciseDetails.startDate',
                content: { type: relative ? 'timeAgo' : 'dateTime', value: startDate },
                isContentComponent: true,
            });
        }

        return items;
    });

    protected courseId = 0;

    constructor() {
        this.courseId = Number(this.route.parent?.parent?.snapshot.params['courseId']);
        this.route.params.pipe(takeUntilDestroyed(this.destroyRef)).subscribe((params) => this.groupId.set(Number(params['groupId'])));

        this.plantUmlWrapper
            .subscribeForInjectableElementsFound()
            .pipe(takeUntilDestroyed(this.destroyRef))
            .subscribe((cb) => this.plantUmlCallbacks.push(cb));

        effect(() => {
            const status = this.milestoneStatus();
            const milestoneDescription = status?.problemStatement ? { milestoneExerciseId: status.milestoneExerciseId, problemStatement: status.problemStatement } : undefined;
            untracked(() => this.renderMilestoneDescription(milestoneDescription));
        });

        // The course itself is already loaded by the course overview container this route lives in; the only field read
        // from it here is maxComplaintTimeDays, via the exercise header.
        this.course.set(this.courseStorageService.getCourse(this.courseId));
        this.courseStorageService
            .subscribeToCourseUpdates(this.courseId)
            .pipe(takeUntilDestroyed(this.destroyRef))
            .subscribe((course) => this.course.set(course));

        // The exercises come from the same endpoint the exercises tab uses, freshly for this navigation, and the same
        // response populates the achieved variant group points that {@link achievedGroupPoints} reads out of the
        // ScoresStorageService.
        this.courseOverviewExercisesService
            .loadIfNeeded(this.courseId)
            .pipe(takeUntilDestroyed(this.destroyRef))
            .subscribe({
                next: (overview) => this.courseExercises.set(overview.exercises ?? []),
            });

        toObservable(this.group)
            .pipe(takeUntilDestroyed(this.destroyRef))
            .subscribe((g) => {
                if (g?.id !== undefined && g.title) {
                    this.entityTitleService.setTitle(EntityType.EXERCISE_VARIANT_GROUP, [g.id], g.title);
                }
            });

        effect(() => {
            const group = this.group();
            const groupId = group?.id;
            if (group?.type !== 'milestone' || groupId === undefined || this.requestedMilestoneStatusGroupIds.has(groupId)) {
                return;
            }
            untracked(() => this.loadMilestoneStatus(groupId));
        });

        effect(() => {
            const participationId = this.milestoneStatus()?.participationId;
            if (participationId === undefined || this.requestedMilestoneParticipationIds.has(participationId)) {
                return;
            }
            untracked(() => this.loadMilestoneParticipation(participationId));
        });

        // The participation request above is a one-shot snapshot. A build triggered by a push from the student's own
        // IDE - the normal case for a milestone, whose repository is shared across the whole group - has to reach this
        // page without a reload, so the same participation is also followed live.
        effect(() => {
            const status = this.milestoneStatus();
            untracked(() => this.subscribeToMilestoneUpdates(status?.participationId, status?.milestoneExerciseId));
        });

        // Registering the member participations is what lets the websocket service merge an incoming result into a
        // participation at all: an unregistered participation is silently skipped when a result arrives for it.
        effect(() => {
            const exercises = this.exercises();
            untracked(() => this.registerVariantParticipations(exercises));
        });

        // One app-wide stream carrying every participation the websocket service updated; the initial (seed) value is
        // skipped because it only replays what is already on screen.
        this.participationWebsocketService
            .subscribeForParticipationChanges()
            .pipe(skip(1), takeUntilDestroyed(this.destroyRef))
            .subscribe((participation) => this.applyLiveParticipation(participation));

        this.destroyRef.onDestroy(() => this.tearDownMilestoneSubscriptions());
    }

    /**
     * (Re)opens the milestone participation's live result and build-status subscriptions. Called with an undefined
     * participation id while the milestone has not been started yet, which only releases whatever may still be open.
     */
    private subscribeToMilestoneUpdates(participationId: number | undefined, milestoneExerciseId: number | undefined): void {
        if (participationId === this.subscribedMilestoneParticipationId) {
            return;
        }
        this.tearDownMilestoneSubscriptions();
        if (participationId === undefined) {
            return;
        }
        this.subscribedMilestoneParticipationId = participationId;

        this.milestoneResultSubscription = this.participationWebsocketService
            .subscribeForLatestResultOfParticipation(participationId, true, milestoneExerciseId)
            .pipe(
                // The subject seeds with undefined; only actual results are of interest here.
                filter((result): result is Result => !!result),
                // ParticipationWebsocketService deliberately leaves the wire's date strings alone, so every consumer
                // converts them itself (the same step UpdatingResultComponent does).
                map((result) => cloneWith(result, { completionDate: convertDateFromServer(result.completionDate) })),
                takeUntilDestroyed(this.destroyRef),
            )
            .subscribe((result) => this.liveMilestoneResultsByParticipationId.update((results) => new Map(results).set(participationId, result)));

        if (milestoneExerciseId === undefined) {
            return;
        }
        // Purely so the box can say "building" between the push and the result instead of showing a count that is
        // known to be out of date.
        this.milestoneSubmissionSubscription = this.programmingSubmissionService
            .getLatestPendingSubmissionByParticipationId(participationId, milestoneExerciseId, true)
            .pipe(takeUntilDestroyed(this.destroyRef))
            .subscribe(({ submissionState }) => {
                this.isMilestoneQueued.set(submissionState === ProgrammingSubmissionState.IS_QUEUED);
                this.isMilestoneBuilding.set(submissionState === ProgrammingSubmissionState.IS_BUILDING_PENDING_SUBMISSION);
            });
    }

    /**
     * Releases the milestone's live subscriptions.
     * <p>
     * The submission subscription is dropped locally first and only then handed back to the
     * {@link ProgrammingSubmissionService}, so that service sees the remaining observers when it decides whether the
     * shared per-participation state may go - releasing only the local subscription leaks that state until logout
     * (the ordering `UpdatingResultComponent.tearDownSubmissionSubscription` documents).
     */
    private tearDownMilestoneSubscriptions(): void {
        const participationId = this.subscribedMilestoneParticipationId;
        this.milestoneResultSubscription?.unsubscribe();
        this.milestoneResultSubscription = undefined;
        this.milestoneSubmissionSubscription?.unsubscribe();
        this.milestoneSubmissionSubscription = undefined;
        this.subscribedMilestoneParticipationId = undefined;
        this.isMilestoneBuilding.set(false);
        this.isMilestoneQueued.set(false);
        if (participationId === undefined) {
            return;
        }
        const exercise = this.milestoneExercise();
        if (exercise) {
            // Only actually closes the shared websocket subscription once the exercise is past due; while it is still
            // running the stream is deliberately kept open for the rest of the app.
            this.participationWebsocketService.unsubscribeForLatestResultOfParticipation(participationId, exercise);
        }
        this.programmingSubmissionService.unsubscribeForLatestSubmissionOfParticipation(participationId);
    }

    /**
     * Hands the group's member participations to the websocket service, which caches them and merges incoming results
     * into them. Only the graded participation is registered, matching what the cards render.
     */
    private registerVariantParticipations(exercises: Exercise[]): void {
        for (const exercise of exercises) {
            const participation = this.participationService.getSpecificStudentParticipation(exercise.studentParticipations ?? [], false);
            if (participation?.id === undefined || this.registeredVariantParticipationIds.has(participation.id)) {
                continue;
            }
            this.registeredVariantParticipationIds.add(participation.id);
            // The exercise is passed explicitly: the dashboard payload's participations carry no back-reference to it,
            // and addParticipation refuses a participation it cannot link to one.
            this.participationWebsocketService.addParticipation(participation, exercise);
        }
    }

    /**
     * Records a websocket-updated participation so the group's cards render its new result. The stream is app-wide, so
     * anything that is not one of this group's own registered participations is ignored. A replacement map is stored
     * because a signal only notifies when the reference changes.
     */
    private applyLiveParticipation(participation: Participation | undefined): void {
        const participationId = participation?.id;
        const exerciseId = participation?.exercise?.id;
        if (participationId === undefined || exerciseId === undefined || !this.registeredVariantParticipationIds.has(participationId)) {
            return;
        }
        const next = new Map(this.liveParticipations());
        next.set(exerciseId, participation as StudentParticipation);
        this.liveParticipations.set(next);
    }

    /**
     * Loads the milestone's own participation with its latest result, which is what the code-quality panel reads the
     * group's static code analysis feedback from. Deliberately quiet on failure: unlike the start action, this is
     * supplementary information, so a failure simply leaves the panel unrendered rather than alerting the student. The
     * participation is released from the requested set again so a later revisit retries.
     */
    private loadMilestoneParticipation(participationId: number): void {
        this.requestedMilestoneParticipationIds.add(participationId);
        this.programmingExerciseParticipationService
            .getStudentParticipationWithLatestResult(participationId)
            .pipe(takeUntilDestroyed(this.destroyRef))
            .subscribe({
                next: (participation) => this.milestoneParticipationsById.update((participations) => new Map(participations).set(participationId, participation)),
                error: () => this.requestedMilestoneParticipationIds.delete(participationId),
            });
    }

    /**
     * Loads whether the student has started the group's anchor milestone. The group is marked as requested up front so
     * an unrelated re-render does not re-issue it; a failure releases that mark again and is surfaced, so the student
     * gets a retry instead of a header that silently renders nothing.
     */
    private loadMilestoneStatus(groupId: number): void {
        this.requestedMilestoneStatusGroupIds.add(groupId);
        this.failedMilestoneStatusGroupIds.update((groupIds) => withoutId(groupIds, groupId));
        this.loadingMilestoneStatusGroupIds.update((groupIds) => withId(groupIds, groupId));
        this.exerciseVariantGroupService
            .getMilestoneStatus(this.courseId, groupId)
            .pipe(
                finalize(() => this.loadingMilestoneStatusGroupIds.update((groupIds) => withoutId(groupIds, groupId))),
                takeUntilDestroyed(this.destroyRef),
            )
            .subscribe({
                next: (status) => this.setMilestoneStatus(groupId, status),
                error: (error: HttpErrorResponse) => {
                    this.requestedMilestoneStatusGroupIds.delete(groupId);
                    this.failedMilestoneStatusGroupIds.update((groupIds) => withId(groupIds, groupId));
                    this.alertService.error('artemisApp.exerciseVariantGroup.detail.milestoneStatusLoadFailed');
                },
            });
    }

    private setMilestoneStatus(groupId: number, status: MilestoneStatusDTO): void {
        this.milestoneStatusByGroupId.update((statuses) => new Map(statuses).set(groupId, status));
    }

    private isCurrentGroupIn(groupIds: ReadonlySet<number>): boolean {
        const groupId = this.groupId();
        return groupId !== undefined && groupIds.has(groupId);
    }

    /** Retries the milestone-status request after a failure, from the button rendered in the start action's place. */
    protected retryMilestoneStatus(): void {
        const groupId = this.group()?.id;
        if (groupId === undefined || this.isLoadingMilestoneStatus()) {
            return;
        }
        this.loadMilestoneStatus(groupId);
    }

    /**
     * Renders the milestone's description (see {@link milestoneDescriptionHtml}), including its PlantUML diagrams, which
     * are injected once the rendered markup is in the DOM.
     */
    private renderMilestoneDescription(milestoneDescription?: { milestoneExerciseId: number; problemStatement: string }): void {
        this.plantUmlCallbacks = [];
        this.milestoneDescriptionHtml.set(milestoneDescription ? this.renderStatement(milestoneDescription.milestoneExerciseId, milestoneDescription.problemStatement) : undefined);

        afterNextRender(
            () => {
                this.plantUmlCallbacks.forEach((cb) => cb());
                this.plantUmlCallbacks = [];
            },
            { injector: this.injector },
        );
    }

    /**
     * Hands this page the exercise sidebar's state and its toggle. Implementing it is what makes
     * {@code CourseExercisesComponent} recognise the activated route component as one that renders the expand button
     * itself, so a collapsed sidebar can be brought back from a group page rather than only from an exercise page.
     */
    setSidebarToggle(isCollapsed: boolean, toggleSidebar: () => void): void {
        this.isSidebarCollapsed.set(isCollapsed);
        this.sidebarToggle.set(toggleSidebar);
    }

    /** A problem statement as preview HTML: task syntax stripped to its name, PlantUML diagrams scoped to the exercise. */
    private renderStatement(exerciseId: number, problemStatement: string): SafeHtml {
        // Strip task syntax — [task][Name](tests) → Name — so it renders as plain text instead of a link.
        const preprocessed = problemStatement.replace(taskRegex, (_match, name: string) => name);
        this.plantUmlWrapper.setExerciseId(exerciseId);
        return this.sanitizer.bypassSecurityTrustHtml(htmlForMarkdown(preprocessed, [this.plantUmlWrapper.getExtension()]));
    }

    /**
     * Rounds a points figure the way every other Artemis points display does (see
     * {@code ExerciseHeadersInformationComponent.achievedPoints}, which renders this page's own variant cards), so a
     * derived value does not reach the header at full float width - `(75 / 100) * 20` is `14.999999999999998`.
     * <p>
     * Guarded on the course being present rather than handing the helper an undefined one: it reports that to Sentry on
     * every call, and the computeds below re-run on every change detection - an expected gap before
     * {@link CourseStorageService} has the course would become a stream of captured errors rather than a single one.
     */
    private roundPoints(value: number): number {
        const course = this.course();
        return course ? roundValueSpecifiedByCourseSettings(value, course) : value;
    }

    /**
     * The graded participation for a variant. The dashboard also returns practice runs in unspecified order, so the
     * first entry could otherwise show practice points on the card.
     */
    protected exerciseParticipation(exercise: Exercise): StudentParticipation | undefined {
        const liveParticipation = exercise.id !== undefined ? this.liveParticipations().get(exercise.id) : undefined;
        return liveParticipation ?? this.participationService.getSpecificStudentParticipation(exercise.studentParticipations ?? [], false);
    }

    protected exerciseLink(exercise: Exercise): string {
        return `/courses/${this.courseId}/exercises/${exercise.id}`;
    }

    /**
     * Starts the group's anchor milestone exercise for the current student, so every UserStoryExercise in the group
     * shares its repository (server-side: `ParticipationService.shareSiblingRepositoryIfAvailable`) instead of each
     * provisioning its own once the student starts it.
     */
    protected startMilestone(): void {
        const status = this.milestoneStatus();
        const groupId = this.groupId();
        if (!status || groupId === undefined || status.started || this.isStartingMilestone()) {
            return;
        }
        this.isStartingMilestone.set(true);
        this.courseExerciseService
            // The milestone itself is never loaded before it is started; only the new participation is read below.
            .startExercise(status.milestoneExerciseId, hydrate(new ProgrammingExercise(undefined, undefined), { id: status.milestoneExerciseId, type: ExerciseType.MILESTONE }))
            .pipe(finalize(() => this.isStartingMilestone.set(false)))
            .subscribe({
                next: (participation) => {
                    const programmingParticipation = participation as ProgrammingExerciseStudentParticipation;
                    // Stored under the group the start was requested for, which need not be the one on screen by now.
                    this.setMilestoneStatus(
                        groupId,
                        cloneWith(status, { started: true, participationId: programmingParticipation.id, repositoryUri: programmingParticipation.repositoryUri }),
                    );
                },
                error: (error: HttpErrorResponse) => {
                    if (error.status !== 403) {
                        this.alertService.error('artemisApp.exercise.startError');
                    }
                },
            });
    }

    /** Where the group's "Instructor actions" dropdown entry for one member exercise links to: its own course-management detail page. */
    protected exerciseManagementRouterLink(exercise: Exercise): (string | number)[] {
        return ['/course-management', this.courseId, getExerciseUrlSegment(exercise.type), exercise.id ?? 0];
    }

    /**
     * The milestone's own participation, wrapped as a single-element array for `jhi-code-button`'s `[participations]`
     * input - the group view shows the "Code" button for the milestone's (shared) repository directly, instead of the
     * plain "started" text a normal exercise page would show once a participation exists.
     */
    protected readonly milestoneCodeButtonParticipations = computed<ProgrammingExerciseStudentParticipation[]>(() => {
        const status = this.milestoneStatus();
        if (!status?.started || status.participationId === undefined) {
            return [];
        }
        const participation = new ProgrammingExerciseStudentParticipation();
        participation.id = status.participationId;
        participation.repositoryUri = status.repositoryUri;
        return [participation];
    });

    protected routerLinkForMilestoneRepository(): (string | number)[] {
        const status = this.milestoneStatus();
        if (!status?.participationId) {
            return ['/courses', this.courseId, 'exercises', status?.milestoneExerciseId ?? 0];
        }
        return ['/courses', this.courseId, 'exercises', status.milestoneExerciseId, 'repository', status.participationId];
    }
}

/** A copy of the set with the id added; a signal only notifies when the reference changes. */
function withId(ids: ReadonlySet<number>, id: number): ReadonlySet<number> {
    return new Set(ids).add(id);
}

/** A copy of the set without the id. */
function withoutId(ids: ReadonlySet<number>, id: number): ReadonlySet<number> {
    const copy = new Set(ids);
    copy.delete(id);
    return copy;
}
