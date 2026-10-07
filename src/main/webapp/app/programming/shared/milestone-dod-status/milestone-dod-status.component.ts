import { ChangeDetectionStrategy, Component, DestroyRef, computed, effect, inject, input, signal, untracked } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FaIconComponent } from '@fortawesome/angular-fontawesome';
import { faCircleCheck, faCircleXmark, faTriangleExclamation } from '@fortawesome/free-solid-svg-icons';
import { Subscription } from 'rxjs';
import { filter, map } from 'rxjs/operators';
import { ExerciseVariantGroupService, MilestoneStatusDTO } from 'app/course/manage/exercises/exercise-variant-group.service';
import { ParticipationWebsocketService } from 'app/course/shared/services/participation-websocket.service';
import { Exercise, getCourseFromExercise } from 'app/exercise/shared/entities/exercise/exercise.model';
import { getLatestSubmission } from 'app/exercise/shared/entities/participation/participation.model';
import { ProgrammingExerciseStudentParticipation } from 'app/exercise/shared/entities/participation/programming-exercise-student-participation.model';
import { Result } from 'app/exercise/shared/entities/result/result.model';
import { getLatestSubmissionResult } from 'app/exercise/shared/entities/submission/submission.model';
import { convertDateFromServer } from 'app/foundation/util/date.utils';
import { cloneWith } from 'app/foundation/util/deep-clone.util';
import { ProgrammingExerciseParticipationService } from 'app/programming/manage/services/programming-exercise-participation.service';
import { taskRegex } from 'app/programming/shared/instructions-render/extensions/programming-exercise-task.extension';
import { ProgrammingExerciseInstructionService, TestCaseState } from 'app/programming/shared/instructions-render/services/programming-exercise-instruction.service';
import { milestoneScaPenalty } from 'app/programming/shared/milestone-code-quality/milestone-code-quality.util';
import { InformationBox, InformationBoxComponent } from 'app/shared-ui/information-box/information-box.component';

/**
 * Where the milestone's Definition of Done stands, in the order the box escalates through them.
 * <p>
 * `hidden` when there is nothing to judge (no milestone, not started, or a problem statement naming no tests),
 * `noResult` before the first milestone build, `failing` while any test the milestone's tasks reference has not
 * passed, `deducting` when they all pass but static code analysis costs points, and `met` otherwise.
 */
export type MilestoneDodStatus = 'hidden' | 'noResult' | 'failing' | 'deducting' | 'met';

/**
 * The milestone's Definition of Done as an information box on a user story's page.
 * <p>
 * The DoD is what the milestone's own problem statement demands: the tests its `[task]` entries reference must pass,
 * and the group's code quality decides whether points are deducted. Both live on the milestone's result only - the
 * fan-out copies a story just its own tests, and never the static code analysis feedback - so a student working on a
 * story would otherwise not learn that the group as a whole is failing until they open the milestone overview page,
 * which is where the tooltip sends them for the details.
 * <p>
 * A build that is queued or running deliberately does not change the box: it keeps showing the last known standing
 * until the new result arrives over the websocket.
 */
@Component({
    selector: 'jhi-milestone-dod-status',
    templateUrl: './milestone-dod-status.component.html',
    imports: [FaIconComponent, InformationBoxComponent],
    /* preserveWhitespaces: false is required here because the global tsconfig sets preserveWhitespaces: true,
     * which inserts whitespace text nodes that break [contentComponent] slot matching in jhi-information-box. */
    preserveWhitespaces: false,
    changeDetection: ChangeDetectionStrategy.OnPush,
})
export class MilestoneDodStatusComponent {
    private readonly destroyRef = inject(DestroyRef);
    private readonly exerciseVariantGroupService = inject(ExerciseVariantGroupService);
    private readonly programmingExerciseParticipationService = inject(ProgrammingExerciseParticipationService);
    private readonly participationWebsocketService = inject(ParticipationWebsocketService);
    private readonly instructionService = inject(ProgrammingExerciseInstructionService);

    /** The user story whose milestone group is judged. */
    readonly exercise = input.required<Exercise>();

    protected readonly faCircleCheck = faCircleCheck;
    protected readonly faCircleXmark = faCircleXmark;
    protected readonly faTriangleExclamation = faTriangleExclamation;

    protected readonly milestoneStatus = signal<MilestoneStatusDTO | undefined>(undefined);
    protected readonly milestoneParticipation = signal<ProgrammingExerciseStudentParticipation | undefined>(undefined);
    /** The most recent milestone result pushed over the websocket, which supersedes the fetched one. */
    private readonly liveResult = signal<Result | undefined>(undefined);

    /** The group and participation already requested, so an unrelated re-render does not re-issue a request. */
    private requestedGroupId?: number;
    private requestedParticipationId?: number;
    private subscribedParticipationId?: number;
    private resultSubscription?: Subscription;

    /**
     * The ids of every test the milestone's `[task]` entries reference. The stored statement carries them as
     * `<testid>` markers; a name that never resolved to a test case (a typo, or a test that does not exist yet) has no
     * id and cannot be judged, so it is left out rather than counted as failing forever.
     */
    protected readonly referencedTestIds = computed<number[]>(() => {
        const problemStatement = this.milestoneStatus()?.problemStatement;
        if (!problemStatement) {
            return [];
        }
        const ids = [...problemStatement.matchAll(taskRegex)].flatMap((match) => (match[2] ? this.instructionService.convertTestListToIds(match[2], undefined) : []));
        return [...new Set(ids.filter((id) => id >= 0))];
    });

    protected readonly result = computed<Result | undefined>(() => {
        const liveResult = this.liveResult();
        if (liveResult) {
            return liveResult;
        }
        const participation = this.milestoneParticipation();
        return participation ? getLatestSubmissionResult(getLatestSubmission(participation)) : undefined;
    });

    readonly status = computed<MilestoneDodStatus>(() => {
        const testIds = this.referencedTestIds();
        if (this.milestoneStatus()?.participationId === undefined || testIds.length === 0) {
            return 'hidden';
        }
        const result = this.result();
        if (!result) {
            return 'noResult';
        }
        // Anything short of every referenced test passing - a failure, a test that never ran, or a result without any
        // test feedback at all (e.g. a build failure) - means the Definition of Done is not met.
        if (this.instructionService.testStatusForTask(testIds, result).testCaseState !== TestCaseState.SUCCESS) {
            return 'failing';
        }
        return milestoneScaPenalty(result) > 0 ? 'deducting' : 'met';
    });

    protected readonly infoBoxData = computed<InformationBox>(() => ({
        title: 'artemisApp.exerciseVariantGroup.detail.dod.title',
        content: { type: 'string', value: '' },
        isContentComponent: true,
        tooltip: `artemisApp.exerciseVariantGroup.detail.dod.tooltip.${this.status()}`,
    }));

    constructor() {
        effect(() => {
            const exercise = this.exercise();
            const groupId = exercise.exerciseVariantGroup?.type === 'milestone' ? exercise.exerciseVariantGroup.id : undefined;
            const courseId = getCourseFromExercise(exercise)?.id;
            if (groupId === undefined || courseId === undefined || groupId === this.requestedGroupId) {
                return;
            }
            untracked(() => this.loadMilestoneStatus(courseId, groupId));
        });

        effect(() => {
            const participationId = this.milestoneStatus()?.participationId;
            const milestoneExerciseId = this.exercise().exerciseVariantGroup?.milestoneExerciseId;
            if (participationId === undefined || participationId === this.requestedParticipationId) {
                return;
            }
            untracked(() => {
                this.loadMilestoneParticipation(participationId);
                this.subscribeToResults(participationId, milestoneExerciseId);
            });
        });

        this.destroyRef.onDestroy(() => this.tearDownResultSubscription());
    }

    /** Errors only leave the box hidden: it is supplementary information, and must not alert the student. */
    private loadMilestoneStatus(courseId: number, groupId: number): void {
        this.requestedGroupId = groupId;
        this.exerciseVariantGroupService
            .getMilestoneStatus(courseId, groupId)
            .pipe(takeUntilDestroyed(this.destroyRef))
            .subscribe({
                next: (status) => this.milestoneStatus.set(status),
                error: () => (this.requestedGroupId = undefined),
            });
    }

    private loadMilestoneParticipation(participationId: number): void {
        this.requestedParticipationId = participationId;
        this.programmingExerciseParticipationService
            .getStudentParticipationWithLatestResult(participationId)
            .pipe(takeUntilDestroyed(this.destroyRef))
            .subscribe({
                next: (participation) => this.milestoneParticipation.set(participation),
                error: () => (this.requestedParticipationId = undefined),
            });
    }

    /** Follows the milestone's results live, the same way the milestone group page does. */
    private subscribeToResults(participationId: number, milestoneExerciseId: number | undefined): void {
        if (participationId === this.subscribedParticipationId) {
            return;
        }
        this.tearDownResultSubscription();
        this.subscribedParticipationId = participationId;
        this.resultSubscription = this.participationWebsocketService
            .subscribeForLatestResultOfParticipation(participationId, true, milestoneExerciseId)
            .pipe(
                // The subject seeds with undefined; only actual results are of interest here.
                filter((result): result is Result => !!result),
                // ParticipationWebsocketService leaves the wire's date strings alone, so every consumer converts them itself.
                map((result) => cloneWith(result, { completionDate: convertDateFromServer(result.completionDate) })),
                takeUntilDestroyed(this.destroyRef),
            )
            .subscribe((result) => this.liveResult.set(result));
    }

    private tearDownResultSubscription(): void {
        const participationId = this.subscribedParticipationId;
        this.resultSubscription?.unsubscribe();
        this.resultSubscription = undefined;
        this.subscribedParticipationId = undefined;
        const milestoneExercise = this.milestoneParticipation()?.exercise;
        if (participationId !== undefined && milestoneExercise) {
            // Only actually closes the shared websocket subscription once the exercise is past due.
            this.participationWebsocketService.unsubscribeForLatestResultOfParticipation(participationId, milestoneExercise);
        }
    }
}
