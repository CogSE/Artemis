import { beforeEach, describe, expect, it } from 'vitest';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { TranslateService } from '@ngx-translate/core';
import { MockTranslateService } from 'test/helpers/mocks/service/mock-translate.service';
import { MilestoneAssessmentTasksComponent } from 'app/programming/manage/assess/milestone-assessment/milestone-assessment-tasks.component';
import { UserStoryTask } from 'app/exercise/shared/entities/participation/programming-exercise-student-participation.model';

describe('MilestoneAssessmentTasksComponent', () => {
    let fixture: ComponentFixture<MilestoneAssessmentTasksComponent>;

    function task(overrides: Partial<UserStoryTask>): UserStoryTask {
        return { id: 1, title: 'Task', taskPoints: 3, priority: 'MEDIUM', estimatedEffortHours: 1, state: 'NEW', ...overrides };
    }

    async function setup(tasks: UserStoryTask[]): Promise<void> {
        await TestBed.configureTestingModule({
            imports: [MilestoneAssessmentTasksComponent],
            providers: [{ provide: TranslateService, useClass: MockTranslateService }],
        }).compileComponents();
        fixture = TestBed.createComponent(MilestoneAssessmentTasksComponent);
        fixture.componentRef.setInput('tasks', tasks);
        fixture.detectChanges();
    }

    function query(testId: string): HTMLElement[] {
        return Array.from(fixture.nativeElement.querySelectorAll(`[data-testid="${testId}"]`));
    }

    function comp(): { estimatedEffortHours: () => number; actualEffortHours: () => number | undefined } {
        return fixture.componentInstance as never;
    }

    beforeEach(() => TestBed.resetTestingModule());

    it('lists every task of the board in the order it was sent', async () => {
        await setup([
            task({ id: 1, title: 'Build the login form', description: 'With validation', state: 'DONE', actualEffortHours: 2.5 }),
            task({ id: 2, title: 'Write the tests', priority: 'HIGH' }),
        ]);

        const rows = query('milestone-assessment-task-row');
        expect(rows).toHaveLength(2);
        expect(rows[0].textContent).toContain('Build the login form');
        expect(rows[0].textContent).toContain('With validation');
        expect(rows[0].textContent).toContain('2:30h');
        expect(rows[1].textContent).toContain('Write the tests');
        expect(query('milestone-assessment-tasks-empty')).toHaveLength(0);
    });

    it('sums the planned effort and the logged effort of the whole board', async () => {
        await setup([task({ id: 1, estimatedEffortHours: 1.5, actualEffortHours: 2 }), task({ id: 2, estimatedEffortHours: 3 })]);

        expect(comp().estimatedEffortHours()).toBe(4.5);
        // Only the logged tasks count; one without logged time is not a zero.
        expect(comp().actualEffortHours()).toBe(2);
        expect(query('milestone-assessment-tasks-totals')).toHaveLength(1);
    });

    it('tells nothing has been logged apart from a logged zero', async () => {
        await setup([task({ id: 1 })]);

        expect(comp().actualEffortHours()).toBeUndefined();
    });

    it('says so when the student created no tasks for the story', async () => {
        await setup([]);

        expect(query('milestone-assessment-task-row')).toHaveLength(0);
        expect(query('milestone-assessment-tasks-empty')).toHaveLength(1);
        expect(query('milestone-assessment-tasks-totals')).toHaveLength(0);
    });
});
