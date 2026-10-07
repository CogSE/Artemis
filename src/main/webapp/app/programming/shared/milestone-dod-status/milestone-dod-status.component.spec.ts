import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { TranslateService } from '@ngx-translate/core';
import { BehaviorSubject, EMPTY, Observable, of, throwError } from 'rxjs';
import { MockTranslateService } from 'test/helpers/mocks/service/mock-translate.service';
import { MilestoneDodStatus, MilestoneDodStatusComponent } from 'app/programming/shared/milestone-dod-status/milestone-dod-status.component';
import { ExerciseVariantGroupService, MilestoneStatusDTO } from 'app/course/manage/exercises/exercise-variant-group.service';
import { ProgrammingExerciseParticipationService } from 'app/programming/manage/services/programming-exercise-participation.service';
import { ParticipationWebsocketService } from 'app/course/shared/services/participation-websocket.service';
import { Exercise, ExerciseType } from 'app/exercise/shared/entities/exercise/exercise.model';
import { ProgrammingExerciseStudentParticipation } from 'app/exercise/shared/entities/participation/programming-exercise-student-participation.model';
import { Result } from 'app/exercise/shared/entities/result/result.model';
import { Feedback, FeedbackType, STATIC_CODE_ANALYSIS_FEEDBACK_IDENTIFIER } from 'app/assessment/shared/entities/feedback.model';

describe('MilestoneDodStatusComponent', () => {
    let fixture: ComponentFixture<MilestoneDodStatusComponent>;
    let liveResults: BehaviorSubject<Result | undefined>;
    let statusSpy: ReturnType<typeof vi.fn>;

    const TASKS = '[task][Sort](<testid>1</testid>,<testid>2</testid>)\n[task][Unresolved](typoTest)';

    const userStory = {
        id: 7,
        type: ExerciseType.USER_STORY,
        course: { id: 3 },
        exerciseVariantGroup: { id: 10, type: 'milestone', milestoneExerciseId: 99 },
    } as unknown as Exercise;

    function startedStatus(problemStatement: string | undefined = TASKS): MilestoneStatusDTO {
        return { participationId: 555, problemStatement } as MilestoneStatusDTO;
    }

    function testFeedback(testId: number, positive: boolean | undefined): Feedback {
        return { type: FeedbackType.AUTOMATIC, text: `test${testId}`, positive, testCase: { id: testId } } as Feedback;
    }

    function scaFeedback(penalty: number): Feedback {
        return { type: FeedbackType.AUTOMATIC, text: STATIC_CODE_ANALYSIS_FEEDBACK_IDENTIFIER + 'Bad Practice', detailText: JSON.stringify({ penalty }) } as Feedback;
    }

    function participationWith(result: Result | undefined): ProgrammingExerciseStudentParticipation {
        return {
            id: 555,
            exercise: { id: 99, type: ExerciseType.MILESTONE },
            submissions: result ? [{ id: 777, results: [result] }] : [],
        } as unknown as ProgrammingExerciseStudentParticipation;
    }

    async function setup(status: Observable<MilestoneStatusDTO>, participation: Observable<ProgrammingExerciseStudentParticipation> = EMPTY, exercise: Exercise = userStory) {
        statusSpy = vi.fn(() => status);
        await TestBed.configureTestingModule({
            imports: [MilestoneDodStatusComponent],
            providers: [
                { provide: TranslateService, useClass: MockTranslateService },
                { provide: ExerciseVariantGroupService, useValue: { getMilestoneStatus: statusSpy } },
                { provide: ProgrammingExerciseParticipationService, useValue: { getStudentParticipationWithLatestResult: vi.fn(() => participation) } },
                {
                    provide: ParticipationWebsocketService,
                    useValue: { subscribeForLatestResultOfParticipation: vi.fn(() => liveResults), unsubscribeForLatestResultOfParticipation: vi.fn() },
                },
            ],
        }).compileComponents();

        fixture = TestBed.createComponent(MilestoneDodStatusComponent);
        fixture.componentRef.setInput('exercise', exercise);
        fixture.detectChanges();
        await fixture.whenStable();
        fixture.detectChanges();
    }

    function status(): MilestoneDodStatus {
        return fixture.componentInstance.status();
    }

    function renderedStatus(): string | null | undefined {
        return (fixture.nativeElement as HTMLElement).querySelector('[data-testid="milestone-dod-status"]')?.getAttribute('data-status');
    }

    beforeEach(() => {
        liveResults = new BehaviorSubject<Result | undefined>(undefined);
    });

    afterEach(() => {
        vi.restoreAllMocks();
    });

    it('asks for the milestone standing of the story group', async () => {
        await setup(of(startedStatus()));
        expect(statusSpy).toHaveBeenCalledWith(3, 10);
    });

    it('is hidden while the milestone has not been started', async () => {
        await setup(of({ problemStatement: TASKS } as MilestoneStatusDTO));
        expect(status()).toBe('hidden');
        expect(renderedStatus()).toBeUndefined();
    });

    it('is hidden when the milestone problem statement references no tests', async () => {
        await setup(of(startedStatus('Just prose, [task][Unresolved](typoTest)')), of(participationWith({ id: 1, feedbacks: [] } as Result)));
        expect(status()).toBe('hidden');
    });

    it('stays hidden and silent when the milestone status cannot be loaded', async () => {
        await setup(throwError(() => new Error('404')));
        expect(status()).toBe('hidden');
    });

    it('does not load anything for an exercise outside a milestone group', async () => {
        await setup(of(startedStatus()), EMPTY, { ...userStory, exerciseVariantGroup: { id: 10, type: 'variant' } } as unknown as Exercise);
        expect(statusSpy).not.toHaveBeenCalled();
        expect(status()).toBe('hidden');
    });

    it('shows a dash before the first milestone build', async () => {
        await setup(of(startedStatus()), of(participationWith(undefined)));
        expect(status()).toBe('noResult');
        expect(renderedStatus()).toBe('noResult');
    });

    it('fails when a referenced test fails', async () => {
        await setup(of(startedStatus()), of(participationWith({ id: 1, feedbacks: [testFeedback(1, true), testFeedback(2, false)] } as Result)));
        expect(status()).toBe('failing');
        expect(renderedStatus()).toBe('failing');
        expect((fixture.nativeElement as HTMLElement).querySelector('fa-icon.text-state-danger')).not.toBeNull();
    });

    it('fails when a referenced test did not run', async () => {
        await setup(of(startedStatus()), of(participationWith({ id: 1, feedbacks: [testFeedback(1, true)] } as Result)));
        expect(status()).toBe('failing');
    });

    it('ignores failing tests the milestone tasks do not reference', async () => {
        await setup(of(startedStatus()), of(participationWith({ id: 1, feedbacks: [testFeedback(1, true), testFeedback(2, true), testFeedback(3, false)] } as Result)));
        expect(status()).toBe('met');
        expect((fixture.nativeElement as HTMLElement).querySelector('fa-icon.text-state-success')).not.toBeNull();
    });

    it('warns when the tests pass but code quality deducts points', async () => {
        await setup(of(startedStatus()), of(participationWith({ id: 1, feedbacks: [testFeedback(1, true), testFeedback(2, true), scaFeedback(1.5)] } as Result)));
        expect(status()).toBe('deducting');
        expect((fixture.nativeElement as HTMLElement).querySelector('fa-icon.text-state-warning')).not.toBeNull();
    });

    it('does not warn about code quality issues that cost nothing', async () => {
        await setup(of(startedStatus()), of(participationWith({ id: 1, feedbacks: [testFeedback(1, true), testFeedback(2, true), scaFeedback(0)] } as Result)));
        expect(status()).toBe('met');
    });

    it('lets a live milestone result replace the fetched one', async () => {
        await setup(of(startedStatus()), of(participationWith({ id: 1, feedbacks: [testFeedback(1, false), testFeedback(2, true)] } as Result)));
        expect(status()).toBe('failing');

        liveResults.next({ id: 2, feedbacks: [testFeedback(1, true), testFeedback(2, true)] } as Result);
        fixture.detectChanges();

        expect(status()).toBe('met');
    });

    it('releases the live result subscription on destroy', async () => {
        await setup(of(startedStatus()), of(participationWith(undefined)));
        const websocketService = TestBed.inject(ParticipationWebsocketService);

        fixture.destroy();

        expect(websocketService.unsubscribeForLatestResultOfParticipation).toHaveBeenCalledWith(555, expect.objectContaining({ id: 99 }));
    });
});
