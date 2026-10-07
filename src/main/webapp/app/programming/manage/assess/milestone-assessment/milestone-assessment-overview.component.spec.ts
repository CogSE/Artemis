import { describe, expect, it } from 'vitest';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { By } from '@angular/platform-browser';
import { MockComponent } from 'ng-mocks';
import { TranslateService } from '@ngx-translate/core';
import { MockTranslateService } from 'test/helpers/mocks/service/mock-translate.service';
import { ExerciseType } from 'app/exercise/shared/entities/exercise/exercise.model';
import { Result } from 'app/exercise/shared/entities/result/result.model';
import { getAllResultsOfAllSubmissions } from 'app/exercise/shared/entities/submission/submission.model';
import { MilestoneAssessmentOverviewComponent } from 'app/programming/manage/assess/milestone-assessment/milestone-assessment-overview.component';
import { MilestoneAssessment } from 'app/programming/manage/assess/milestone-assessment/milestone-assessment.service';
import { ProgrammingExerciseInstructionComponent } from 'app/programming/shared/instructions-render/programming-exercise-instruction.component';
import { MilestoneCodeQualityComponent } from 'app/programming/shared/milestone-code-quality/milestone-code-quality.component';

describe('MilestoneAssessmentOverviewComponent', () => {
    let fixture: ComponentFixture<MilestoneAssessmentOverviewComponent>;

    const PROBLEM_STATEMENT = '[task][Sort the list](<testid>1</testid>)';

    function assessment(overrides: Partial<MilestoneAssessment> = {}): MilestoneAssessment {
        return {
            milestoneExerciseId: 99,
            milestoneTitle: 'Sprint 1',
            problemStatement: PROBLEM_STATEMENT,
            milestoneParticipationId: 555,
            milestoneResult: { id: 888, submission: { id: 777 }, feedbacks: [{ id: 1, testCase: { id: 1 }, positive: true }] } as unknown as Result,
            exercises: [],
            ...overrides,
        };
    }

    async function setup(milestoneAssessment: MilestoneAssessment): Promise<void> {
        await TestBed.configureTestingModule({
            imports: [MilestoneAssessmentOverviewComponent],
            providers: [{ provide: TranslateService, useClass: MockTranslateService }],
        })
            .overrideComponent(MilestoneAssessmentOverviewComponent, {
                remove: { imports: [ProgrammingExerciseInstructionComponent, MilestoneCodeQualityComponent] },
                add: { imports: [MockComponent(ProgrammingExerciseInstructionComponent), MockComponent(MilestoneCodeQualityComponent)] },
            })
            .compileComponents();

        fixture = TestBed.createComponent(MilestoneAssessmentOverviewComponent);
        fixture.componentRef.setInput('assessment', milestoneAssessment);
        fixture.detectChanges();
    }

    function renderer(): ProgrammingExerciseInstructionComponent | undefined {
        return fixture.debugElement.query(By.directive(ProgrammingExerciseInstructionComponent))?.componentInstance;
    }

    it('renders the statement with its tasks against the student milestone participation and its latest result', async () => {
        await setup(assessment());

        const exercise = renderer()?.exercise();
        expect(exercise?.id).toBe(99);
        expect(exercise?.type).toBe(ExerciseType.MILESTONE);
        // Unstripped, exactly as the student's group page renders it.
        expect(exercise?.problemStatement).toBe(PROBLEM_STATEMENT);

        const participation = renderer()?.participation();
        expect(participation?.id).toBe(555);
        // Carried along so the renderer judges the tasks without loading the result a second time.
        const results = getAllResultsOfAllSubmissions(participation?.submissions);
        expect(results.map((result) => result.id)).toEqual([888]);
        expect(results[0].feedbacks).toHaveLength(1);
    });

    it('renders the statement without task outcomes for a student who never started the milestone', async () => {
        await setup(assessment({ milestoneParticipationId: undefined, milestoneResult: undefined }));

        expect(renderer()?.exercise()?.problemStatement).toBe(PROBLEM_STATEMENT);
        expect(renderer()?.participation()).toBeUndefined();
    });

    it('passes a started milestone without a build on with no results', async () => {
        await setup(assessment({ milestoneResult: undefined }));

        expect(renderer()?.participation()?.id).toBe(555);
        expect(renderer()?.participation()?.submissions).toEqual([]);
    });

    it('says so instead of mounting the renderer when the milestone has no problem statement', async () => {
        await setup(assessment({ problemStatement: undefined }));

        expect(renderer()).toBeUndefined();
        expect(fixture.debugElement.query(By.css('[jhiTranslate="artemisApp.milestoneAssessment.overview.noProblemStatement"]'))).not.toBeNull();
    });
});
