import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { WritableSignal } from '@angular/core';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { By } from '@angular/platform-browser';
import { HttpErrorResponse, provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { BehaviorSubject, EMPTY, Observable, filter, of, throwError } from 'rxjs';
import dayjs from 'dayjs/esm';
import { MockComponent, MockDirective, MockPipe, MockProvider } from 'ng-mocks';
import { TumAetUiTooltipDirective } from '@tumaet/ui-angular';
import { CourseSidebarToggleButtonComponent } from 'app/course/shared/course-sidebar-toggle-button/course-sidebar-toggle-button.component';
import { ResultComponent } from 'app/exercise/result/result.component';
import { ExerciseHeadersInformationComponent } from 'app/exercise/exercise-headers/exercise-headers-information/exercise-headers-information.component';
import { InformationBox, InformationBoxComponent } from 'app/shared-ui/information-box/information-box.component';
import { StudentParticipation } from 'app/exercise/shared/entities/participation/student-participation.model';
import { CourseExerciseGroupDetailComponent } from 'app/course/overview/course-exercises/group-detail/course-exercise-group-detail.component';
import { CourseOverviewExercisesService } from 'app/course/overview/services/course-overview-exercises.service';
import { CourseStorageService } from 'app/course/manage/services/course-storage.service';
import { CourseExercisesForOverviewDTO } from 'app/course/shared/entities/course-exercises-for-overview-dto';
import { ExerciseVariantGroupService, MilestoneStatusDTO } from 'app/course/manage/exercises/exercise-variant-group.service';
import { AlertService } from 'app/foundation/service/alert.service';
import { EntityTitleService } from 'app/core/navbar/entity-title.service';
import { ProgrammingExercisePlantUmlExtensionWrapper } from 'app/programming/shared/instructions-render/extensions/programming-exercise-plant-uml.extension';
import { ArtemisServerDateService } from 'app/foundation/service/server-date.service';
import { ScoresStorageService } from 'app/course/manage/course-scores/scores-storage.service';
import { Course } from 'app/course/shared/entities/course.model';
import { Exercise, ExerciseType, IncludedInOverallScore } from 'app/exercise/shared/entities/exercise/exercise.model';
import { ParticipationService } from 'app/exercise/participation/participation.service';
import { CourseExerciseService } from 'app/exercise/course-exercises/course-exercise.service';
import { MockParticipationService } from 'test/helpers/mocks/service/mock-participation.service';
import { ProgrammingExerciseParticipationService } from 'app/programming/manage/services/programming-exercise-participation.service';
import { ProgrammingExerciseStudentParticipation } from 'app/exercise/shared/entities/participation/programming-exercise-student-participation.model';
import { ProgrammingExercise } from 'app/programming/shared/entities/programming-exercise.model';
import { Result } from 'app/exercise/shared/entities/result/result.model';
import { Participation } from 'app/exercise/shared/entities/participation/participation.model';
import { ParticipationWebsocketService } from 'app/course/shared/services/participation-websocket.service';
import { ProgrammingSubmissionService, ProgrammingSubmissionState, ProgrammingSubmissionStateObj } from 'app/programming/shared/services/programming-submission.service';
import { Submission } from 'app/exercise/shared/entities/submission/submission.model';
import { TranslateService } from '@ngx-translate/core';
import { MockTranslateService } from 'test/helpers/mocks/service/mock-translate.service';
import { ArtemisDatePipe } from 'app/foundation/pipes/artemis-date.pipe';
import { ArtemisTimeAgoPipe } from 'app/foundation/pipes/artemis-time-ago.pipe';
import { ArtemisTranslatePipe } from 'app/foundation/pipes/artemis-translate.pipe';
import { TranslateDirective } from 'app/foundation/language/translate.directive';

describe('CourseExerciseGroupDetailComponent', () => {
    let fixture: ComponentFixture<CourseExerciseGroupDetailComponent>;
    /** Server-computed achieved points per variant group id, as the ScoresStorageService would hold after a dashboard load. */
    let storedGroupPoints: Map<number, number>;

    /**
     * The live streams the component subscribes to, owned by the test so it can push a build result or a build-status
     * change at will. They stand in for the two root services the real page reaches the websocket through.
     */
    let latestResults: Map<number, BehaviorSubject<Result | undefined>>;
    let participationChanges: BehaviorSubject<Participation | undefined>;
    let pendingSubmissions: BehaviorSubject<ProgrammingSubmissionStateObj>;
    let registeredParticipations: { participationId?: number; exerciseId?: number }[];
    /** The route's params, so a test can switch groups the way the router does: same component, new :groupId. */
    let routeParams: BehaviorSubject<{ groupId: string }>;
    let releasedResultParticipationIds: number[];
    let releasedSubmissionParticipationIds: number[];

    /** The subject the component's result subscription for the given participation reads from, created on first use. */
    function latestResultOf(participationId: number): BehaviorSubject<Result | undefined> {
        const existing = latestResults.get(participationId);
        if (existing) {
            return existing;
        }
        const subject = new BehaviorSubject<Result | undefined>(undefined);
        latestResults.set(participationId, subject);
        return subject;
    }

    const GROUP_ID = 10;

    /** Two exercises in group 10 (cap 15), each worth 10 points and fully included in the score, participations 101 and 102. */
    function exercisesInGroup(groupMaxPoints: number | undefined): Exercise[] {
        const reference = { id: GROUP_ID, title: 'Sorting variants', maxPoints: groupMaxPoints };
        return [
            {
                id: 1,
                type: ExerciseType.TEXT,
                maxPoints: 10,
                includedInOverallScore: IncludedInOverallScore.INCLUDED_COMPLETELY,
                exerciseVariantGroup: reference,
                studentParticipations: [{ id: 101 }],
            } as unknown as Exercise,
            {
                id: 2,
                type: ExerciseType.TEXT,
                maxPoints: 10,
                includedInOverallScore: IncludedInOverallScore.INCLUDED_COMPLETELY,
                exerciseVariantGroup: reference,
                studentParticipations: [{ id: 102 }],
            } as unknown as Exercise,
        ];
    }

    /** An additional variant of the given inclusion type worth 10 points, joining group 10. */
    function extraVariant(includedInOverallScore: IncludedInOverallScore, groupMaxPoints: number | undefined): Exercise {
        const reference = { id: GROUP_ID, title: 'Sorting variants', maxPoints: groupMaxPoints };
        return { id: 3, type: ExerciseType.TEXT, maxPoints: 10, includedInOverallScore, exerciseVariantGroup: reference } as unknown as Exercise;
    }

    /** A milestone group member, so the component's milestone-status effect actually runs. */
    function milestoneGroupMember(): Exercise {
        const reference = { id: GROUP_ID, title: 'Sprint 1', type: 'milestone' as const };
        return { id: 1, type: ExerciseType.USER_STORY, maxPoints: 10, exerciseVariantGroup: reference, problemStatement: 'a' } as unknown as Exercise;
    }

    /** A text exercise in the same milestone group, which counts on its own result rather than through the milestone. */
    function milestoneTextMember(): Exercise {
        const reference = { id: GROUP_ID, title: 'Sprint 1', type: 'milestone' as const };
        return {
            id: 2,
            type: ExerciseType.TEXT,
            maxPoints: 20,
            includedInOverallScore: IncludedInOverallScore.INCLUDED_COMPLETELY,
            exerciseVariantGroup: reference,
            studentParticipations: [{ id: 202 } as StudentParticipation],
            problemStatement: 'b',
        } as unknown as Exercise;
    }

    async function setup(
        exercises: Exercise[],
        options?: {
            getMilestoneStatus?: (courseId: number, groupId: number) => Observable<MilestoneStatusDTO>;
            getStudentParticipationWithLatestResult?: (participationId: number) => Observable<ProgrammingExerciseStudentParticipation>;
            /** The course's configured points accuracy; left unset the component rounds to the model's default of 1. */
            accuracyOfScores?: number;
            /** Keep the real template (with its projected children stubbed), for assertions about the header itself. */
            renderTemplate?: boolean;
        },
    ): Promise<void> {
        const course = { id: 1, exercises, accuracyOfScores: options?.accuracyOfScores } as Course;
        routeParams = new BehaviorSubject({ groupId: String(GROUP_ID) });
        const route = {
            params: routeParams,
            parent: { parent: { snapshot: { params: { courseId: '1' } } } },
        } as unknown as ActivatedRoute;

        const testBed = TestBed.configureTestingModule({
            imports: [CourseExerciseGroupDetailComponent],
            providers: [
                { provide: ActivatedRoute, useValue: route },
                MockProvider(CourseOverviewExercisesService, { loadIfNeeded: () => of({ exercises } as CourseExercisesForOverviewDTO) }),
                MockProvider(CourseStorageService, { getCourse: () => course, subscribeToCourseUpdates: () => EMPTY }),
                MockProvider(ExerciseVariantGroupService, {
                    getMilestoneStatus: (options?.getMilestoneStatus ?? (() => EMPTY)) as never,
                }),
                MockProvider(ProgrammingExerciseParticipationService, {
                    getStudentParticipationWithLatestResult: (options?.getStudentParticipationWithLatestResult ?? (() => EMPTY)) as never,
                }),
                MockProvider(AlertService),
                // The component injects CourseExerciseService for startMilestone(); the real one pulls in
                // ParticipationWebsocketService -> AccountService -> TranslateService, none of which this spec provides.
                MockProvider(CourseExerciseService),
                MockProvider(EntityTitleService),
                MockProvider(ArtemisServerDateService, { now: () => dayjs() }),
                { provide: ScoresStorageService, useValue: { getStoredAchievedGroupPoints: (_courseId: number, groupId: number) => storedGroupPoints.get(groupId) } },
                { provide: ParticipationService, useClass: MockParticipationService },
                {
                    provide: ParticipationWebsocketService,
                    useValue: {
                        subscribeForLatestResultOfParticipation: (participationId: number, _personal: boolean, exerciseId?: number) => {
                            registeredParticipations.push({ participationId, exerciseId });
                            return latestResultOf(participationId);
                        },
                        subscribeForParticipationChanges: () => participationChanges,
                        addParticipation: (participation: StudentParticipation, exercise: Exercise) => {
                            registeredParticipations.push({ participationId: participation.id, exerciseId: exercise?.id });
                        },
                        unsubscribeForLatestResultOfParticipation: (participationId: number) => releasedResultParticipationIds.push(participationId),
                    },
                },
                {
                    provide: ProgrammingSubmissionService,
                    useValue: {
                        getLatestPendingSubmissionByParticipationId: () => pendingSubmissions.asObservable(),
                        unsubscribeForLatestSubmissionOfParticipation: (participationId: number) => releasedSubmissionParticipationIds.push(participationId),
                    },
                },
                // The sidebar toggle button in the rendered header translates its tooltip.
                { provide: TranslateService, useClass: MockTranslateService },
                provideHttpClient(),
                provideHttpClientTesting(),
            ],
        });

        if (options?.renderTemplate) {
            // Keep the real template, but stub everything it projects into, so the assertions are about the header
            // itself rather than about what an exercise card or an information box renders.
            testBed.overrideComponent(CourseExerciseGroupDetailComponent, {
                remove: {
                    imports: [
                        RouterLink,
                        ExerciseHeadersInformationComponent,
                        InformationBoxComponent,
                        TumAetUiTooltipDirective,
                        TranslateDirective,
                        ArtemisDatePipe,
                        ArtemisTimeAgoPipe,
                        ArtemisTranslatePipe,
                        ResultComponent,
                    ],
                },
                add: {
                    imports: [
                        MockDirective(RouterLink),
                        MockComponent(ExerciseHeadersInformationComponent),
                        MockComponent(InformationBoxComponent),
                        MockDirective(TumAetUiTooltipDirective),
                        MockDirective(TranslateDirective),
                        MockPipe(ArtemisDatePipe),
                        MockPipe(ArtemisTimeAgoPipe),
                        MockPipe(ArtemisTranslatePipe),
                        MockComponent(ResultComponent),
                    ],
                },
            });
        } else {
            // Render nothing: this spec exercises the component's scoring logic, not its template.
            testBed.overrideComponent(CourseExerciseGroupDetailComponent, { set: { template: '' } });
        }
        await testBed.compileComponents();

        fixture = TestBed.createComponent(CourseExerciseGroupDetailComponent);
    }

    /** Access to protected members under test. */
    function comp(): {
        group: () => { id?: number; title?: string } | undefined;
        exercises: () => Exercise[];
        exerciseSumMaxPoints: () => number;
        effectiveGroupMaxPoints: () => number;
        capReducesMaxPoints: () => boolean;
        variantsInfoBoxData: () => InformationBox;
        pointsInfoBoxData: InformationBox;
        groupDateInfoBoxes: () => InformationBox[];
        exerciseParticipation: (exercise: Exercise) => StudentParticipation | undefined;
        exerciseLink: (exercise: Exercise) => string;
    } {
        return fixture.componentInstance as never;
    }

    /** Reads the protected computed under test. */
    function achievedGroupPoints(): number {
        return (fixture.componentInstance as unknown as { achievedGroupPoints: () => number }).achievedGroupPoints();
    }

    function displayedGroupPoints(): number {
        return (fixture.componentInstance as unknown as { displayedGroupPoints: () => number }).displayedGroupPoints();
    }

    beforeEach(() => {
        storedGroupPoints = new Map();
        latestResults = new Map();
        participationChanges = new BehaviorSubject<Participation | undefined>(undefined);
        pendingSubmissions = new BehaviorSubject<ProgrammingSubmissionStateObj>({
            participationId: 555,
            submissionState: ProgrammingSubmissionState.HAS_NO_PENDING_SUBMISSION,
        });
        registeredParticipations = [];
        releasedResultParticipationIds = [];
        releasedSubmissionParticipationIds = [];
    });

    afterEach(() => {
        vi.restoreAllMocks();
    });

    it('surfaces the authoritative server-computed points for the group', async () => {
        // The server already caps and plagiarism-adjusts this value; the component must display it verbatim.
        storedGroupPoints.set(GROUP_ID, 13);
        await setup(exercisesInGroup(15));
        expect(achievedGroupPoints()).toBe(13);
    });

    it('falls back to 0 when the server stored no contribution for the group', async () => {
        // e.g. before the dashboard scores load, or when the group contributes nothing.
        await setup(exercisesInGroup(15));
        expect(achievedGroupPoints()).toBe(0);
    });

    it('rounds a stored value that does not divide cleanly, instead of showing it at full float width', async () => {
        storedGroupPoints.set(GROUP_ID, 13.333333333333334);
        await setup(exercisesInGroup(15));
        expect(achievedGroupPoints()).toBe(13.3);
    });

    it('rounds the group maximum, so a sum of fractional variant points does not drift into the header', async () => {
        // 0.1 + 0.2 is 0.30000000000000004 in IEEE-754, which is exactly what the header used to print.
        const reference = { id: GROUP_ID, title: 'Sorting variants' };
        const fractionalVariant = (id: number, maxPoints: number) =>
            ({
                id,
                type: ExerciseType.TEXT,
                maxPoints,
                includedInOverallScore: IncludedInOverallScore.INCLUDED_COMPLETELY,
                exerciseVariantGroup: reference,
            }) as unknown as Exercise;
        await setup([fractionalVariant(1, 0.1), fractionalVariant(2, 0.2)]);

        expect(comp().exerciseSumMaxPoints()).toBe(0.3);
        expect(comp().effectiveGroupMaxPoints()).toBe(0.3);
    });

    describe('effectiveGroupMaxPoints', () => {
        it('shows the variants sum when no cap is configured', async () => {
            await setup(exercisesInGroup(undefined));
            expect(comp().effectiveGroupMaxPoints()).toBe(20);
            expect(comp().capReducesMaxPoints()).toBe(false);
        });

        it('caps the maximum at a binding cap (below the variants sum)', async () => {
            await setup(exercisesInGroup(15));
            expect(comp().effectiveGroupMaxPoints()).toBe(15);
            expect(comp().capReducesMaxPoints()).toBe(true);
        });

        it('ignores a cap that exceeds the variants sum and shows the sum instead', async () => {
            await setup(exercisesInGroup(100));
            // A student can never earn more than the 20 the variants offer, so the course maximum is 20, not 100.
            expect(comp().effectiveGroupMaxPoints()).toBe(20);
            expect(comp().capReducesMaxPoints()).toBe(false);
        });

        it('treats a zero cap as a binding cap that discards all group points', async () => {
            await setup(exercisesInGroup(0));
            expect(comp().effectiveGroupMaxPoints()).toBe(0);
            expect(comp().capReducesMaxPoints()).toBe(true);
        });

        it('excludes a bonus variant from the maximum, matching the server inclusion rule', async () => {
            // Two INCLUDED_COMPLETELY variants (10 each) + one INCLUDED_AS_BONUS variant: the course maximum only counts
            // the first two, so the cap of 25 is not binding even though the raw sum of all variants would be 30.
            await setup([...exercisesInGroup(25), extraVariant(IncludedInOverallScore.INCLUDED_AS_BONUS, 25)]);
            expect(comp().exerciseSumMaxPoints()).toBe(20);
            expect(comp().effectiveGroupMaxPoints()).toBe(20);
            expect(comp().capReducesMaxPoints()).toBe(false);
        });

        it('excludes a not-included variant from the maximum, matching the server inclusion rule', async () => {
            await setup([...exercisesInGroup(15), extraVariant(IncludedInOverallScore.NOT_INCLUDED, 15)]);
            expect(comp().exerciseSumMaxPoints()).toBe(20);
            // The cap of 15 binds against the included sum of 20, not the raw sum of 30.
            expect(comp().effectiveGroupMaxPoints()).toBe(15);
            expect(comp().capReducesMaxPoints()).toBe(true);
        });
    });

    describe('group resolution', () => {
        it('resolves the group, its members and the sum of max points from the dashboard exercises', async () => {
            await setup(exercisesInGroup(15));
            expect(comp().group()?.id).toBe(GROUP_ID);
            expect(
                comp()
                    .exercises()
                    .map((e) => e.id),
            ).toEqual([1, 2]);
            expect(comp().exerciseSumMaxPoints()).toBe(20);
            expect(comp().variantsInfoBoxData().content.value).toBe(2);
            expect(comp().pointsInfoBoxData.isContentComponent).toBe(true);
        });

        it('resolves no group when the course has no exercises of that group', async () => {
            await setup([{ id: 5, type: ExerciseType.TEXT } as Exercise]);
            expect(comp().group()).toBeUndefined();
            expect(comp().exercises()).toEqual([]);
        });

        it('publishes the group title to the entity title service', async () => {
            await setup(exercisesInGroup(15));
            const titleService = TestBed.inject(EntityTitleService);
            const setTitleSpy = vi.spyOn(titleService, 'setTitle');
            fixture.detectChanges();
            await fixture.whenStable();
            expect(setTitleSpy).toHaveBeenCalledWith(expect.anything(), [GROUP_ID], 'Sorting variants');
        });
    });

    describe('groupDateInfoBoxes', () => {
        function exercisesWithGroupDates(dates: { dueDate?: dayjs.Dayjs; startDate?: dayjs.Dayjs; assessmentDueDate?: dayjs.Dayjs }): Exercise[] {
            const reference = { id: GROUP_ID, title: 'Sorting variants', ...dates };
            return [{ id: 1, type: ExerciseType.TEXT, exerciseVariantGroup: reference, problemStatement: 'a' } as unknown as Exercise];
        }

        it('shows the due-over box for a past due date', async () => {
            await setup(exercisesWithGroupDates({ dueDate: dayjs().subtract(2, 'day') }));
            const boxes = comp().groupDateInfoBoxes();
            expect(boxes.map((b) => b.title)).toEqual(['artemisApp.courseOverview.exerciseDetails.submissionDueOver']);
        });

        it('shows a relative due date with tooltip when due within a week', async () => {
            await setup(exercisesWithGroupDates({ dueDate: dayjs().add(3, 'day') }));
            const box = comp().groupDateInfoBoxes()[0];
            expect(box.title).toBe('artemisApp.courseOverview.exerciseDetails.submissionDue');
            expect(box.content.type).toBe('timeAgo');
            expect(box.tooltip).toBeDefined();
        });

        it('shows an absolute due date when due later than a week', async () => {
            await setup(exercisesWithGroupDates({ dueDate: dayjs().add(2, 'week') }));
            const box = comp().groupDateInfoBoxes()[0];
            expect(box.content.type).toBe('dateTime');
            expect(box.tooltip).toBeUndefined();
        });

        it('marks a due date within 24 hours as danger', async () => {
            await setup(exercisesWithGroupDates({ dueDate: dayjs().add(2, 'hour') }));
            expect(comp().groupDateInfoBoxes()[0].contentColor).toBe('danger');
        });

        it('adds the assessment-due box when the due date passed and assessment is still open', async () => {
            await setup(exercisesWithGroupDates({ dueDate: dayjs().subtract(1, 'day'), assessmentDueDate: dayjs().add(1, 'day') }));
            const titles = comp()
                .groupDateInfoBoxes()
                .map((b) => b.title);
            expect(titles).toContain('artemisApp.courseOverview.exerciseDetails.assessmentDue');
        });

        it('adds the start-date box for a future start date', async () => {
            await setup(exercisesWithGroupDates({ startDate: dayjs().add(2, 'day') }));
            const titles = comp()
                .groupDateInfoBoxes()
                .map((b) => b.title);
            expect(titles).toEqual(['artemisApp.courseOverview.exerciseDetails.startDate']);
        });

        it('shows no boxes without group dates', async () => {
            await setup(exercisesWithGroupDates({}));
            expect(comp().groupDateInfoBoxes()).toEqual([]);
        });
    });

    describe('helpers', () => {
        it('returns the graded participation and the exercise link', async () => {
            await setup(exercisesInGroup(undefined));
            const exercise = comp().exercises()[0];
            expect(comp().exerciseParticipation(exercise)?.id).toBe(101);
            expect(comp().exerciseParticipation({} as Exercise)).toBeUndefined();
            expect(comp().exerciseLink(exercise)).toBe('/courses/1/exercises/1');
        });

        it('selects the graded participation even when a practice run is listed first', async () => {
            // The dashboard response includes practice test runs in unspecified order; the card must not show them.
            const exercises = exercisesInGroup(undefined);
            exercises[0].studentParticipations = [{ id: 201, testRun: true } as StudentParticipation, { id: 101 } as StudentParticipation];
            await setup(exercises);
            expect(comp().exerciseParticipation(comp().exercises()[0])?.id).toBe(101);
        });
    });
    describe('milestone status', () => {
        /** Access to the protected milestone-status state under test. */
        function milestone(): {
            milestoneStatus: () => MilestoneStatusDTO | undefined;
            milestoneStatusFailed: () => boolean;
            isLoadingMilestoneStatus: () => boolean;
            retryMilestoneStatus: () => void;
        } {
            return fixture.componentInstance as never;
        }

        it('loads the milestone status for a milestone group', async () => {
            const status = { milestoneExerciseId: 99, started: false } as MilestoneStatusDTO;
            const statusSpy = vi.fn(() => of(status));
            await setup([milestoneGroupMember()], { getMilestoneStatus: statusSpy });
            fixture.detectChanges();
            await fixture.whenStable();

            expect(statusSpy).toHaveBeenCalled();
            expect(milestone().milestoneStatus()).toBe(status);
            expect(milestone().milestoneStatusFailed()).toBe(false);
        });

        it('surfaces a failed status request instead of silently rendering nothing', async () => {
            // A 404 here is suppressed by the global alert handler, so without this the whole header - start button
            // included - simply disappears, which is exactly how the missing "Start exercise" button was reported.
            const statusSpy = vi.fn(() => throwError(() => new HttpErrorResponse({ status: 404 })));
            await setup([milestoneGroupMember()], { getMilestoneStatus: statusSpy });
            const alertSpy = vi.spyOn(TestBed.inject(AlertService), 'error');
            fixture.detectChanges();
            await fixture.whenStable();

            expect(statusSpy).toHaveBeenCalled();
            expect(milestone().milestoneStatus()).toBeUndefined();
            expect(milestone().milestoneStatusFailed()).toBe(true);
            expect(alertSpy).toHaveBeenCalledWith('artemisApp.exerciseVariantGroup.detail.milestoneStatusLoadFailed');
            const requested = (fixture.componentInstance as unknown as { requestedMilestoneStatusGroupIds: Set<number> })['requestedMilestoneStatusGroupIds'];
            expect(requested.has(GROUP_ID)).toBe(false);
        });

        it('retries the status request after a failure', async () => {
            const status = { milestoneExerciseId: 99, started: false } as MilestoneStatusDTO;
            let firstCall = true;
            const statusSpy = vi.fn(() => {
                if (firstCall) {
                    firstCall = false;
                    return throwError(() => new HttpErrorResponse({ status: 404 }));
                }
                return of(status);
            });
            await setup([milestoneGroupMember()], { getMilestoneStatus: statusSpy as never });
            fixture.detectChanges();
            await fixture.whenStable();
            expect(milestone().milestoneStatusFailed()).toBe(true);

            milestone().retryMilestoneStatus();
            await fixture.whenStable();

            expect(statusSpy).toHaveBeenCalledTimes(2);
            expect(milestone().milestoneStatus()).toBe(status);
            expect(milestone().milestoneStatusFailed()).toBe(false);
            expect(milestone().isLoadingMilestoneStatus()).toBe(false);
        });
    });

    describe('milestone participation', () => {
        /** Access to the protected milestone-participation state under test. */
        function milestone(): {
            milestoneExercise: () => ProgrammingExercise | undefined;
            milestoneResult: () => Result | undefined;
            milestoneInstructionsExercise: () => ProgrammingExercise | undefined;
        } {
            return fixture.componentInstance as never;
        }

        /** A started milestone whose participation the code-quality panel would read the group's SCA feedback from. */
        function startedStatus(): MilestoneStatusDTO {
            return { milestoneExerciseId: 99, started: true, participationId: 555 } as MilestoneStatusDTO;
        }

        function participationWithResult(result: Result): ProgrammingExerciseStudentParticipation {
            return {
                id: 555,
                exercise: { id: 99, type: 'milestone', staticCodeAnalysisEnabled: true } as unknown as ProgrammingExercise,
                submissions: [{ id: 777, results: [result] }],
            } as unknown as ProgrammingExerciseStudentParticipation;
        }

        /** Access to the protected build state the status badge reads. */
        function buildFailed(): boolean {
            return (fixture.componentInstance as unknown as { isMilestoneBuildFailed: () => boolean }).isMilestoneBuildFailed();
        }

        // The badge states the shared build's outcome; only a failed build has something to open, namely the log, which
        // a student cannot reach any other way once the online editor is switched off.
        it('lets the status badge open its details only for a failed build', async () => {
            const result = { id: 888, submission: { id: 777, buildFailed: true } } as unknown as Result;
            await setup([milestoneGroupMember()], {
                getMilestoneStatus: () => of(startedStatus()),
                getStudentParticipationWithLatestResult: () => of(participationWithResult(result)),
                renderTemplate: true,
            });
            fixture.detectChanges();
            await fixture.whenStable();
            fixture.detectChanges();

            expect(buildFailed()).toBe(true);
            const badge = fixture.debugElement.query(By.directive(ResultComponent));
            expect(badge).not.toBeNull();
            expect((badge.componentInstance as ResultComponent).allowOpeningDetails()).toBe(true);
        });

        // The bar is a row of labelled pills, and the shared build's outcome is one of them - the same box, under the
        // same label, that the exercise page gives a status.
        it('renders the status badge inside the labelled status box', async () => {
            const result = { id: 888, submission: { id: 777, buildFailed: true } } as unknown as Result;
            await setup([milestoneGroupMember()], {
                getMilestoneStatus: () => of(startedStatus()),
                getStudentParticipationWithLatestResult: () => of(participationWithResult(result)),
                renderTemplate: true,
            });
            fixture.detectChanges();
            await fixture.whenStable();
            fixture.detectChanges();

            const statusBox = fixture.debugElement.query(By.css('[data-testid="milestone-build-status"]'));
            expect(statusBox).not.toBeNull();
            const badge = statusBox.query(By.directive(ResultComponent));
            expect(badge).not.toBeNull();
            // The bar states the group's points and its code issues in boxes of their own, so this one is purely
            // about whether the shared build came through.
            expect((badge.componentInstance as ResultComponent).buildOutcomeOnly()).toBe(true);
            expect((fixture.componentInstance as unknown as { buildStatusInfoBoxData: InformationBox }).buildStatusInfoBoxData.title).toBe(
                'artemisApp.courseOverview.exerciseDetails.status',
            );
        });

        it('keeps the status badge closed for a build that passed', async () => {
            const result = { id: 888, submission: { id: 777, buildFailed: false } } as unknown as Result;
            await setup([milestoneGroupMember()], {
                getMilestoneStatus: () => of(startedStatus()),
                getStudentParticipationWithLatestResult: () => of(participationWithResult(result)),
            });
            fixture.detectChanges();
            await fixture.whenStable();

            expect(buildFailed()).toBe(false);
        });

        it('loads the milestone participation with its latest result once the milestone has been started', async () => {
            const result = { id: 888, codeIssueCount: 2 } as Result;
            const participationSpy = vi.fn(() => of(participationWithResult(result)));
            await setup([milestoneGroupMember()], { getMilestoneStatus: () => of(startedStatus()), getStudentParticipationWithLatestResult: participationSpy });
            fixture.detectChanges();
            await fixture.whenStable();

            expect(participationSpy).toHaveBeenCalledWith(555);
            expect(milestone().milestoneExercise()?.id).toBe(99);
            expect(milestone().milestoneResult()).toBe(result);
        });

        it('hands the milestone statement with its task test ids to the instructions renderer once the participation is loaded', async () => {
            const problemStatement = '[task][Sort the list](<testid>1</testid>,<testid>2</testid>)';
            const participation = participationWithResult({ id: 888 } as Result);
            await setup([milestoneGroupMember()], {
                getMilestoneStatus: () => of({ ...startedStatus(), problemStatement }),
                getStudentParticipationWithLatestResult: () => of(participation),
            });
            fixture.detectChanges();
            await fixture.whenStable();

            const instructionsExercise = milestone().milestoneInstructionsExercise();
            expect(instructionsExercise?.id).toBe(99);
            // Unstripped: the renderer needs the task syntax to resolve the tests against the milestone result.
            expect(instructionsExercise?.problemStatement).toBe(problemStatement);
            // A copy, so the participation's own exercise is left as the server sent it.
            expect(participation.exercise?.problemStatement).toBeUndefined();
        });

        it('keeps the stripped description until the milestone participation is available', async () => {
            const problemStatement = '[task][Sort the list](<testid>1</testid>)';
            const notStarted = { milestoneExerciseId: 99, started: false, problemStatement } as MilestoneStatusDTO;
            await setup([milestoneGroupMember()], { getMilestoneStatus: () => of(notStarted) });
            fixture.detectChanges();
            await fixture.whenStable();

            expect(milestone().milestoneInstructionsExercise()).toBeUndefined();
        });

        it('does not request a participation before the student has started the milestone', async () => {
            const participationSpy = vi.fn(() => EMPTY);
            const notStarted = { milestoneExerciseId: 99, started: false } as MilestoneStatusDTO;
            await setup([milestoneGroupMember()], { getMilestoneStatus: () => of(notStarted), getStudentParticipationWithLatestResult: participationSpy as never });
            fixture.detectChanges();
            await fixture.whenStable();

            expect(participationSpy).not.toHaveBeenCalled();
            expect(milestone().milestoneResult()).toBeUndefined();
        });

        it('leaves the panel unrendered and stays retryable when the participation request fails', async () => {
            // Unlike the start action, this is supplementary information: a failure must not alert the student, and it
            // must not permanently block a later attempt either.
            const participationSpy = vi.fn(() => throwError(() => new HttpErrorResponse({ status: 500 })));
            await setup([milestoneGroupMember()], { getMilestoneStatus: () => of(startedStatus()), getStudentParticipationWithLatestResult: participationSpy as never });
            const alertSpy = vi.spyOn(TestBed.inject(AlertService), 'error');
            fixture.detectChanges();
            await fixture.whenStable();

            expect(participationSpy).toHaveBeenCalledOnce();
            expect(milestone().milestoneExercise()).toBeUndefined();
            expect(milestone().milestoneResult()).toBeUndefined();
            expect(alertSpy).not.toHaveBeenCalled();
            const requested = (fixture.componentInstance as unknown as { requestedMilestoneParticipationIds: Set<number> })['requestedMilestoneParticipationIds'];
            expect(requested.has(555)).toBe(false);
        });
    });

    describe('live updates', () => {
        /** Access to the protected live state under test. */
        function live(): {
            milestoneExercise: () => ProgrammingExercise | undefined;
            milestoneResult: () => Result | undefined;
            isMilestoneBuilding: () => boolean;
            isMilestoneQueued: () => boolean;
            exerciseParticipation: (exercise: Exercise) => StudentParticipation | undefined;
        } {
            return fixture.componentInstance as never;
        }

        function startedStatus(): MilestoneStatusDTO {
            return { milestoneExerciseId: 99, started: true, participationId: 555 } as MilestoneStatusDTO;
        }

        /**
         * The REST snapshot the page starts from: one finished build worth 2 of the milestone's 10 points. The milestone is
         * worth exactly its only story (see milestoneGroupMember) - the server keeps the two equal, and a fixture where they
         * differ would assert group points above the group's own maximum.
         */
        function initialParticipation(): ProgrammingExerciseStudentParticipation {
            return {
                id: 555,
                exercise: { id: 99, type: 'milestone', staticCodeAnalysisEnabled: true, maxPoints: 10 } as unknown as ProgrammingExercise,
                submissions: [{ id: 777, results: [{ id: 888, score: 20 } as Result] }],
            } as unknown as ProgrammingExerciseStudentParticipation;
        }

        async function setupStartedMilestone(accuracyOfScores?: number): Promise<void> {
            await setup([milestoneGroupMember()], {
                getMilestoneStatus: () => of(startedStatus()),
                getStudentParticipationWithLatestResult: () => of(initialParticipation()),
                accuracyOfScores,
            });
            fixture.detectChanges();
            await fixture.whenStable();
        }

        it('replaces the milestone result when a build finishes, keeping the exercise the REST snapshot brought along', async () => {
            await setupStartedMilestone();
            expect(live().milestoneResult()?.id).toBe(888);

            latestResultOf(555).next({ id: 999, score: 75, feedbacks: [] } as unknown as Result);

            expect(live().milestoneResult()?.id).toBe(999);
            // The websocket payload carries no exercise, so the only source of staticCodeAnalysisEnabled must survive it.
            expect(live().milestoneExercise()?.id).toBe(99);
            expect(live().milestoneExercise()?.staticCodeAnalysisEnabled).toBe(true);
        });

        it('converts the result date, which the websocket service deliberately leaves as it came off the wire', async () => {
            await setupStartedMilestone();

            latestResultOf(555).next({ id: 999, completionDate: '2026-09-10T10:00:00Z' } as unknown as Result);

            expect(dayjs.isDayjs(live().milestoneResult()?.completionDate)).toBe(true);
        });

        it('subscribes for the milestone participation as a personal subscription of its own exercise', async () => {
            await setupStartedMilestone();

            expect(registeredParticipations).toContainEqual({ participationId: 555, exerciseId: 99 });
        });

        it('reports a queued and then a running build, and stops reporting one once the result lands', async () => {
            await setupStartedMilestone();

            pendingSubmissions.next({ participationId: 555, submissionState: ProgrammingSubmissionState.IS_QUEUED });
            expect(live().isMilestoneQueued()).toBe(true);
            expect(live().isMilestoneBuilding()).toBe(false);

            pendingSubmissions.next({ participationId: 555, submissionState: ProgrammingSubmissionState.IS_BUILDING_PENDING_SUBMISSION });
            expect(live().isMilestoneQueued()).toBe(false);
            expect(live().isMilestoneBuilding()).toBe(true);

            pendingSubmissions.next({ participationId: 555, submissionState: ProgrammingSubmissionState.HAS_NO_PENDING_SUBMISSION });
            expect(live().isMilestoneQueued()).toBe(false);
            expect(live().isMilestoneBuilding()).toBe(false);
        });

        it('releases the shared websocket and submission state when the page goes away', async () => {
            await setupStartedMilestone();

            fixture.destroy();

            expect(releasedResultParticipationIds).toContain(555);
            // Leaving this out would leak the ProgrammingSubmissionService's per-participation state until logout.
            expect(releasedSubmissionParticipationIds).toContain(555);
        });

        it('renders a variant card from the participation the websocket updated, not the dashboard snapshot', async () => {
            const exercise = milestoneGroupMember();
            (exercise as Exercise).studentParticipations = [{ id: 101 } as StudentParticipation];
            await setup([exercise], { getMilestoneStatus: () => of(startedStatus()), getStudentParticipationWithLatestResult: () => of(initialParticipation()) });
            fixture.detectChanges();
            await fixture.whenStable();

            // Registering the participation is what lets the websocket service merge a result into it at all.
            expect(registeredParticipations).toContainEqual({ participationId: 101, exerciseId: exercise.id });
            expect(live().exerciseParticipation(exercise)?.submissions).toBeUndefined();

            const updated = { id: 101, exercise, submissions: [{ id: 1, results: [{ id: 2 } as Result] }] } as unknown as StudentParticipation;
            participationChanges.next(updated);

            expect(live().exerciseParticipation(exercise)).toBe(updated);
        });

        it('ignores participation updates that do not belong to this group', async () => {
            const exercise = milestoneGroupMember();
            (exercise as Exercise).studentParticipations = [{ id: 101 } as StudentParticipation];
            await setup([exercise], { getMilestoneStatus: () => of(startedStatus()), getStudentParticipationWithLatestResult: () => of(initialParticipation()) });
            fixture.detectChanges();
            await fixture.whenStable();

            // The participation stream is app-wide, so another page's exercise must not leak onto this group's cards.
            participationChanges.next({ id: 4242, exercise: { id: 4242 } as Exercise } as unknown as StudentParticipation);

            expect(live().exerciseParticipation(exercise)?.id).toBe(101);
        });

        it('follows the aggregated milestone score for the group points, and uses the stored value until one arrives', async () => {
            storedGroupPoints.set(GROUP_ID, 13);
            await setupStartedMilestone();
            expect(achievedGroupPoints()).toBe(13);

            // The server writes the group's points (story points minus the SCA penalty) onto the milestone's own result.
            latestResultOf(555).next({ id: 888, score: 75 } as unknown as Result);

            expect(achievedGroupPoints()).toBe(7.5);
        });

        it('rounds a live score that does not divide cleanly into the milestone points', async () => {
            await setupStartedMilestone();

            // 33.333 % of the milestone's 10 points is 3.3333 - the raw figure the header used to print in full.
            latestResultOf(555).next({ id: 888, score: 33.333 } as unknown as Result);

            expect(achievedGroupPoints()).toBe(3.3);
        });

        it('honours a course configured for more decimals rather than forcing a single one', async () => {
            await setupStartedMilestone(2);

            latestResultOf(555).next({ id: 888, score: 33.333 } as unknown as Result);

            expect(achievedGroupPoints()).toBe(3.33);
        });

        it('adds a non-story member result to the milestone points once it arrives live', async () => {
            const textMember = milestoneTextMember();
            await setup([milestoneGroupMember(), textMember], {
                getMilestoneStatus: () => of(startedStatus()),
                getStudentParticipationWithLatestResult: () => of(initialParticipation()),
            });
            fixture.detectChanges();
            await fixture.whenStable();

            // The milestone carries the story points only (20 % of its 10); the text member is not part of that aggregate.
            participationChanges.next({
                id: 202,
                exercise: textMember,
                submissions: [{ id: 3, results: [{ id: 4, rated: true, score: 50 } as Result] }],
            } as unknown as StudentParticipation);

            // 2 story points from the milestone snapshot + 50 % of the text member's 20 points.
            expect(achievedGroupPoints()).toBe(12);
        });

        it('keeps showing the previous group points while a build is queued or running', async () => {
            await setupStartedMilestone();
            latestResultOf(555).next({ id: 888, score: 75 } as unknown as Result);
            expect(displayedGroupPoints()).toBe(7.5);

            pendingSubmissions.next({ participationId: 555, submissionState: ProgrammingSubmissionState.IS_QUEUED });
            pendingSubmissions.next({ participationId: 555, submissionState: ProgrammingSubmissionState.IS_BUILDING_PENDING_SUBMISSION });
            // Whatever passes through mid-build - here a zero - must not replace the settled value.
            latestResultOf(555).next({ id: 889, score: 0 } as unknown as Result);
            expect(displayedGroupPoints()).toBe(7.5);

            pendingSubmissions.next({ participationId: 555, submissionState: ProgrammingSubmissionState.HAS_NO_PENDING_SUBMISSION });
            latestResultOf(555).next({ id: 889, score: 50 } as unknown as Result);
            expect(displayedGroupPoints()).toBe(5);
        });

        it('keeps the previous milestone result when a push adds a pending submission without one', async () => {
            const withPendingSubmission = initialParticipation();
            withPendingSubmission.submissions = [...(withPendingSubmission.submissions ?? []), { id: 778, results: [] } as unknown as Submission];
            await setup([milestoneGroupMember()], {
                getMilestoneStatus: () => of(startedStatus()),
                getStudentParticipationWithLatestResult: () => of(withPendingSubmission),
            });
            fixture.detectChanges();
            await fixture.whenStable();

            expect(live().milestoneResult()?.id).toBe(888);
        });
    });

    describe('switching between milestone groups', () => {
        const OTHER_GROUP_ID = 20;

        function member(groupId: number, exerciseId: number): Exercise {
            const reference = { id: groupId, title: `Sprint ${groupId}`, type: 'milestone' as const };
            return { id: exerciseId, type: ExerciseType.USER_STORY, maxPoints: 10, exerciseVariantGroup: reference, problemStatement: 'a' } as unknown as Exercise;
        }

        function statusOf(groupId: number): MilestoneStatusDTO {
            return {
                milestoneExerciseId: groupId * 10,
                started: true,
                participationId: groupId * 100,
                problemStatement: `Description of group ${groupId}`,
            } as MilestoneStatusDTO;
        }

        function participationOf(participationId: number): ProgrammingExerciseStudentParticipation {
            return {
                id: participationId,
                exercise: { id: participationId / 10, type: 'milestone', maxPoints: 10 } as unknown as ProgrammingExercise,
                submissions: [{ id: participationId + 1, results: [{ id: participationId + 2, score: 50 } as Result] }],
            } as unknown as ProgrammingExerciseStudentParticipation;
        }

        /** Access to the protected milestone state under test. */
        function state(): {
            milestoneStatus: () => MilestoneStatusDTO | undefined;
            milestoneParticipation: () => ProgrammingExerciseStudentParticipation | undefined;
            milestoneResult: () => Result | undefined;
        } {
            return fixture.componentInstance as never;
        }

        async function switchTo(groupId: number): Promise<void> {
            routeParams.next({ groupId: String(groupId) });
            fixture.detectChanges();
            await fixture.whenStable();
        }

        async function setupTwoGroups(getMilestoneStatus: (courseId: number, groupId: number) => Observable<MilestoneStatusDTO>): Promise<void> {
            await setup([member(GROUP_ID, 1), member(OTHER_GROUP_ID, 2)], {
                getMilestoneStatus,
                getStudentParticipationWithLatestResult: (participationId) => of(participationOf(participationId)),
            });
            fixture.detectChanges();
            await fixture.whenStable();
        }

        it('shows the first group again after switching away and back, without requesting it twice', async () => {
            const getMilestoneStatus = vi.fn((_courseId: number, groupId: number) => of(statusOf(groupId)));
            await setupTwoGroups(getMilestoneStatus);
            expect(state().milestoneStatus()?.problemStatement).toBe('Description of group 10');

            await switchTo(OTHER_GROUP_ID);
            expect(state().milestoneStatus()?.problemStatement).toBe('Description of group 20');
            expect(state().milestoneParticipation()?.id).toBe(2000);

            await switchTo(GROUP_ID);
            // This is what stayed on group 20's description, task results and code quality before.
            expect(state().milestoneStatus()?.problemStatement).toBe('Description of group 10');
            expect(state().milestoneParticipation()?.id).toBe(1000);
            expect(state().milestoneResult()?.id).toBe(1002);
            // Revisiting a group still does not re-fetch it.
            expect(getMilestoneStatus.mock.calls.filter(([, groupId]) => groupId === GROUP_ID)).toHaveLength(1);
        });

        it('does not let a late response for the group just left overwrite the one on screen', async () => {
            const lateStatusOfFirstGroup = new BehaviorSubject<MilestoneStatusDTO | undefined>(undefined);
            await setupTwoGroups((_courseId, groupId) =>
                groupId === GROUP_ID ? (lateStatusOfFirstGroup.pipe(filter((status) => !!status)) as Observable<MilestoneStatusDTO>) : of(statusOf(groupId)),
            );

            await switchTo(OTHER_GROUP_ID);
            lateStatusOfFirstGroup.next(statusOf(GROUP_ID));
            expect(state().milestoneStatus()?.problemStatement).toBe('Description of group 20');

            await switchTo(GROUP_ID);
            expect(state().milestoneStatus()?.problemStatement).toBe('Description of group 10');
        });

        it('renders the description of a milestone that is not started yet with its diagrams, scoped to the milestone', async () => {
            const notStarted = {
                milestoneExerciseId: 100,
                started: false,
                problemStatement: '[task][Implement A](testA)\n\n@startuml\nA -> B\n@enduml',
            } as MilestoneStatusDTO;
            await setup([member(GROUP_ID, 1)], { getMilestoneStatus: () => of(notStarted) });
            const setExerciseId = vi.spyOn(TestBed.inject(ProgrammingExercisePlantUmlExtensionWrapper), 'setExerciseId');
            fixture.detectChanges();
            await fixture.whenStable();

            // Scoped to the anchor, so its diagram containers cannot collide with a member preview's.
            expect(setExerciseId).toHaveBeenCalledWith(100);
            const description = String((fixture.componentInstance as unknown as { milestoneDescriptionHtml: () => unknown }).milestoneDescriptionHtml());
            expect(description).toContain('Implement A');
            expect(description).not.toContain('[task]');
        });

        it('re-renders the description for the group switched to', async () => {
            await setup([member(GROUP_ID, 1), member(OTHER_GROUP_ID, 2)], { getMilestoneStatus: (_courseId, groupId) => of(statusOf(groupId)) });
            const setExerciseId = vi.spyOn(TestBed.inject(ProgrammingExercisePlantUmlExtensionWrapper), 'setExerciseId');
            fixture.detectChanges();
            await fixture.whenStable();

            await switchTo(OTHER_GROUP_ID);

            expect(setExerciseId).toHaveBeenCalledWith(200);
            expect(String((fixture.componentInstance as unknown as { milestoneDescriptionHtml: () => unknown }).milestoneDescriptionHtml())).toContain('Description of group 20');
        });

        it("keeps each group's live milestone result to that group", async () => {
            await setupTwoGroups((_courseId, groupId) => of(statusOf(groupId)));
            latestResultOf(1000).next({ id: 901, score: 40 } as unknown as Result);
            expect(state().milestoneResult()?.id).toBe(901);

            await switchTo(OTHER_GROUP_ID);
            expect(state().milestoneResult()?.id).toBe(2002);

            await switchTo(GROUP_ID);
            expect(state().milestoneResult()?.id).toBe(901);
        });
    });

    describe('title bar', () => {
        /** Access to the protected sidebar state the header renders from. */
        function sidebar(): { isSidebarCollapsed: WritableSignal<boolean>; showSidebarToggle: () => boolean } {
            return fixture.componentInstance as never;
        }

        it('renders the compact title bar the exercise page uses, not the taller detail header', async () => {
            // #13918: a group page and the variants it links to sit side by side in the same shell, so they must
            // wear the same bar. `page-top-bar` is what pins it to the shell's title-bar height.
            await setup(exercisesInGroup(undefined), { renderTemplate: true });
            fixture.detectChanges();

            expect(fixture.debugElement.query(By.css('.page-top-bar'))).not.toBeNull();
            expect(fixture.debugElement.query(By.css('.detail-header-card'))).toBeNull();
        });

        it('offers the sidebar expand button only once the sidebar is collapsed', async () => {
            await setup(exercisesInGroup(undefined), { renderTemplate: true });
            fixture.detectChanges();
            // Nothing has handed the page a toggle yet, so there is no button to show.
            expect(fixture.debugElement.query(By.directive(CourseSidebarToggleButtonComponent))).toBeNull();

            const toggleSidebar = vi.fn();
            fixture.componentInstance.setSidebarToggle(false, toggleSidebar);
            fixture.detectChanges();
            expect(fixture.debugElement.query(By.directive(CourseSidebarToggleButtonComponent))).toBeNull();

            sidebar().isSidebarCollapsed.set(true);
            fixture.detectChanges();
            const button = fixture.debugElement.query(By.directive(CourseSidebarToggleButtonComponent));
            expect(button).not.toBeNull();

            // #13918: without this the collapsed sidebar could only be brought back by the keyboard shortcut.
            (button.componentInstance as CourseSidebarToggleButtonComponent).toggleSidebar.emit();
            expect(toggleSidebar).toHaveBeenCalledOnce();
        });
    });
});
