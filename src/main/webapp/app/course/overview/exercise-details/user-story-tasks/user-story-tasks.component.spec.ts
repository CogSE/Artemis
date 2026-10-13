import { ApplicationRef } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { CdkDragDrop } from '@angular/cdk/drag-drop';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { of, throwError } from 'rxjs';
import { TranslateService } from '@ngx-translate/core';
import { MockTranslateService } from 'test/helpers/mocks/service/mock-translate.service';
import { MockDeleteDialogService } from 'test/helpers/mocks/service/mock-delete-dialog.service';
import { DeleteDialogService } from 'app/shared-ui/delete-dialog/service/delete-dialog.service';
import { UserStoryTasksComponent } from 'app/course/overview/exercise-details/user-story-tasks/user-story-tasks.component';
import { UserStoryTaskService } from 'app/programming/shared/services/user-story-task.service';
import { AlertService } from 'app/foundation/service/alert.service';
import { UserStoryTask } from 'app/exercise/shared/entities/participation/programming-exercise-student-participation.model';

describe('UserStoryTasksComponent', () => {
    let component: UserStoryTasksComponent;
    let fixture: ComponentFixture<UserStoryTasksComponent>;
    let userStoryTaskService: UserStoryTaskService;
    let alertService: AlertService;

    const tasks: UserStoryTask[] = [
        { id: 1, title: 'First task', taskPoints: 2, priority: 'LOW', estimatedEffortHours: 1, actualEffortHours: 0.5, state: 'NEW' },
        { id: 2, title: 'Second task', taskPoints: 5, priority: 'HIGH', estimatedEffortHours: 3, state: 'DONE' },
    ];

    const mockUserStoryTaskService = {
        getTasks: vi.fn().mockReturnValue(of(tasks)),
        createTask: vi.fn(),
        updateTask: vi.fn(),
        advanceState: vi.fn(),
        reorderTasks: vi.fn(),
        deleteTask: vi.fn(),
    };

    const mockAlertService = {
        error: vi.fn(),
    };

    beforeEach(() => {
        vi.clearAllMocks();
        mockUserStoryTaskService.getTasks.mockReturnValue(of(tasks));

        TestBed.configureTestingModule({
            imports: [UserStoryTasksComponent],
            providers: [
                { provide: UserStoryTaskService, useValue: mockUserStoryTaskService },
                { provide: AlertService, useValue: mockAlertService },
                { provide: TranslateService, useClass: MockTranslateService },
                { provide: DeleteDialogService, useClass: MockDeleteDialogService },
            ],
        });

        fixture = TestBed.createComponent(UserStoryTasksComponent);
        component = fixture.componentInstance;
        fixture.componentRef.setInput('exerciseId', 42);
        userStoryTaskService = TestBed.inject(UserStoryTaskService);
        alertService = TestBed.inject(AlertService);
    });

    it('should load the tasks for the exercise on init', () => {
        fixture.detectChanges();

        expect(userStoryTaskService.getTasks).toHaveBeenCalledWith(42);
        expect(component['tasks']()).toEqual(tasks);
    });

    it('should show an alert when loading fails', () => {
        mockUserStoryTaskService.getTasks.mockReturnValue(throwError(() => new Error('network error')));

        fixture.detectChanges();

        expect(alertService.error).toHaveBeenCalledWith('artemisApp.courseOverview.exerciseDetails.tasks.loadError');
    });

    it('should open the modal in create mode', () => {
        fixture.detectChanges();
        component['selectedTask'].set(tasks[0]);

        component['openCreateModal']();

        expect(component['selectedTask']()).toBeUndefined();
        expect(component['modalVisible']()).toBe(true);
    });

    it('should open the modal in edit mode with the given task', () => {
        fixture.detectChanges();

        component['openEditModal'](tasks[1]);

        expect(component['selectedTask']()).toEqual(tasks[1]);
        expect(component['modalVisible']()).toBe(true);
    });

    it('should create a new task and reload the list on save without an id', () => {
        fixture.detectChanges();
        const newTask: UserStoryTask = { title: 'Brand new', taskPoints: 1, priority: 'MEDIUM', estimatedEffortHours: 0.5 };
        mockUserStoryTaskService.createTask.mockReturnValue(of({ ...newTask, id: 3 }));
        vi.clearAllMocks();
        mockUserStoryTaskService.getTasks.mockReturnValue(of(tasks));

        component['onSaved'](newTask);

        expect(userStoryTaskService.createTask).toHaveBeenCalledWith(42, newTask);
        expect(userStoryTaskService.getTasks).toHaveBeenCalled();
    });

    it('should update an existing task and reload the list on save with an id', () => {
        fixture.detectChanges();
        const edited: UserStoryTask = { ...tasks[0], taskPoints: 9 };
        mockUserStoryTaskService.updateTask.mockReturnValue(of(edited));
        vi.clearAllMocks();
        mockUserStoryTaskService.getTasks.mockReturnValue(of(tasks));

        component['onSaved'](edited);

        expect(userStoryTaskService.updateTask).toHaveBeenCalledWith(1, edited);
        expect(userStoryTaskService.getTasks).toHaveBeenCalled();
    });

    it('should show an alert when saving fails', () => {
        fixture.detectChanges();
        mockUserStoryTaskService.updateTask.mockReturnValue(throwError(() => new Error('network error')));

        component['onSaved'](tasks[0]);

        expect(alertService.error).toHaveBeenCalledWith('artemisApp.courseOverview.exerciseDetails.tasks.saveError');
    });

    it('should delete a task and reload the list', () => {
        fixture.detectChanges();
        mockUserStoryTaskService.deleteTask.mockReturnValue(of(undefined));
        vi.clearAllMocks();
        mockUserStoryTaskService.getTasks.mockReturnValue(of(tasks));

        component['deleteTask'](tasks[0]);

        expect(userStoryTaskService.deleteTask).toHaveBeenCalledWith(1);
        expect(userStoryTaskService.getTasks).toHaveBeenCalled();
    });

    it('should map priority to severity and label key', () => {
        expect(component['prioritySeverity']('LOW')).toBe('success');
        expect(component['prioritySeverity']('MEDIUM')).toBe('warn');
        expect(component['prioritySeverity']('HIGH')).toBe('danger');
        expect(component['priorityLabelKey']('LOW')).toBe('artemisApp.courseOverview.exerciseDetails.tasks.priorityLow');
        expect(component['priorityLabelKey']('MEDIUM')).toBe('artemisApp.courseOverview.exerciseDetails.tasks.priorityMedium');
        expect(component['priorityLabelKey']('HIGH')).toBe('artemisApp.courseOverview.exerciseDetails.tasks.priorityHigh');
    });

    it('should map state to severity and label key', () => {
        expect(component['stateSeverity']('NEW')).toBe('contrast');
        expect(component['stateSeverity']('IN_PROGRESS')).toBe('info');
        expect(component['stateSeverity']('DONE')).toBe('success');
        expect(component['stateLabelKey']('NEW')).toBe('artemisApp.courseOverview.exerciseDetails.tasks.stateNew');
        expect(component['stateLabelKey']('IN_PROGRESS')).toBe('artemisApp.courseOverview.exerciseDetails.tasks.stateInProgress');
        expect(component['stateLabelKey']('DONE')).toBe('artemisApp.courseOverview.exerciseDetails.tasks.stateDone');
    });

    it('should hide time logging while the task is NEW', () => {
        const newTask: UserStoryTask = { ...tasks[0], state: 'NEW' };
        expect(component['showActualEffort'](newTask)).toBe(false);
        expect(component['canEditActualEffort'](newTask)).toBe(false);
    });

    it('should make time logging editable while the task is IN_PROGRESS', () => {
        const inProgressTask: UserStoryTask = { ...tasks[0], state: 'IN_PROGRESS' };
        expect(component['showActualEffort'](inProgressTask)).toBe(true);
        expect(component['canEditActualEffort'](inProgressTask)).toBe(true);
    });

    it('should show but not allow editing the logged effort once the task is DONE', () => {
        const doneTask: UserStoryTask = { ...tasks[0], state: 'DONE' };
        expect(component['showActualEffort'](doneTask)).toBe(true);
        expect(component['canEditActualEffort'](doneTask)).toBe(false);
    });

    it('should require a logged effort to advance an IN_PROGRESS task to DONE', () => {
        const withoutEffort: UserStoryTask = { ...tasks[0], state: 'IN_PROGRESS', actualEffortHours: undefined };
        const withEffort: UserStoryTask = { ...tasks[0], state: 'IN_PROGRESS', actualEffortHours: 1 };
        const stillNew: UserStoryTask = { ...tasks[0], state: 'NEW', actualEffortHours: undefined };

        expect(component['advanceRequiresEffort'](withoutEffort)).toBe(true);
        expect(component['advanceRequiresEffort'](withEffort)).toBe(false);
        expect(component['advanceRequiresEffort'](stillNew)).toBe(false);

        expect(component['advanceButtonTooltipKey'](withoutEffort)).toBe('artemisApp.courseOverview.exerciseDetails.tasks.advanceButtonRequiresEffort');
        expect(component['advanceButtonTooltipKey'](withEffort)).toBe('artemisApp.courseOverview.exerciseDetails.tasks.advanceButton');
    });

    it('should explain why the disabled advance button cannot be used when hovering it', () => {
        vi.useFakeTimers();
        try {
            mockUserStoryTaskService.getTasks.mockReturnValue(of([{ ...tasks[0], state: 'IN_PROGRESS', actualEffortHours: undefined }]));
            fixture.detectChanges();

            const button: HTMLButtonElement = fixture.nativeElement.querySelector('[data-testid="advance-task-btn"]');
            expect(button.disabled).toBe(true);

            // The disabled button itself gets no pointer events, so the hint must react to its wrapper.
            const hint: HTMLElement = fixture.nativeElement.querySelector('[data-testid="advance-task-hint"]');
            hint.dispatchEvent(new MouseEvent('mouseenter'));
            vi.advanceTimersByTime(200);
            TestBed.inject(ApplicationRef).tick();

            expect(document.body.textContent).toContain('artemisApp.courseOverview.exerciseDetails.tasks.advanceButtonRequiresEffort');
        } finally {
            vi.useRealTimers();
        }
    });

    it('should format estimated effort hours as H:MMh', () => {
        expect(component['formatEffortHours'](1)).toBe('1:00h');
        expect(component['formatEffortHours'](1.5)).toBe('1:30h');
        expect(component['formatEffortHours'](0.25)).toBe('0:15h');
        expect(component['formatEffortHours'](2.999)).toBe('3:00h');
    });

    it('should advance a task state and reload the list', () => {
        fixture.detectChanges();
        mockUserStoryTaskService.advanceState.mockReturnValue(of({ ...tasks[0], state: 'IN_PROGRESS' }));
        vi.clearAllMocks();
        mockUserStoryTaskService.getTasks.mockReturnValue(of(tasks));

        component['advanceState'](tasks[0]);

        expect(userStoryTaskService.advanceState).toHaveBeenCalledWith(1);
        expect(userStoryTaskService.getTasks).toHaveBeenCalled();
    });

    it('should reorder the board locally and persist the new order', () => {
        fixture.detectChanges();
        mockUserStoryTaskService.reorderTasks.mockReturnValue(of([tasks[1], tasks[0]]));

        component['drop']({ previousIndex: 0, currentIndex: 1 } as CdkDragDrop<UserStoryTask[]>);

        expect(component['tasks']()).toEqual([tasks[1], tasks[0]]);
        expect(userStoryTaskService.reorderTasks).toHaveBeenCalledWith(42, [2, 1]);
    });

    it('should do nothing when a drag ends without moving the item', () => {
        fixture.detectChanges();

        component['drop']({ previousIndex: 1, currentIndex: 1 } as CdkDragDrop<UserStoryTask[]>);

        expect(component['tasks']()).toEqual(tasks);
        expect(userStoryTaskService.reorderTasks).not.toHaveBeenCalled();
    });

    it('should show an alert and reload the list when persisting the new order fails', () => {
        fixture.detectChanges();
        mockUserStoryTaskService.reorderTasks.mockReturnValue(throwError(() => new Error('network error')));
        vi.clearAllMocks();
        mockUserStoryTaskService.getTasks.mockReturnValue(of(tasks));

        component['drop']({ previousIndex: 0, currentIndex: 1 } as CdkDragDrop<UserStoryTask[]>);

        expect(alertService.error).toHaveBeenCalledWith('artemisApp.courseOverview.exerciseDetails.tasks.reorderError');
        expect(userStoryTaskService.getTasks).toHaveBeenCalled();
    });

    it('should prefill the actual-effort draft from the task when starting to edit it', () => {
        fixture.detectChanges();

        component['startEditingActualEffort'](tasks[0]);
        expect(component['editingActualEffortTaskId']()).toBe(1);
        expect(component['draftActualEffortText']()).toBe('0:30h');

        component['startEditingActualEffort'](tasks[1]);
        expect(component['editingActualEffortTaskId']()).toBe(2);
        expect(component['draftActualEffortText']()).toBe('');
    });

    it('should clear the editing state on cancel without saving anything', () => {
        fixture.detectChanges();
        component['startEditingActualEffort'](tasks[0]);

        component['cancelActualEffortEdit']();

        expect(component['editingActualEffortTaskId']()).toBeUndefined();
        expect(userStoryTaskService.updateTask).not.toHaveBeenCalled();
    });

    it('should save the logged effort and reload the list', () => {
        fixture.detectChanges();
        mockUserStoryTaskService.updateTask.mockReturnValue(of({ ...tasks[0], actualEffortHours: 1.5 }));
        vi.clearAllMocks();
        mockUserStoryTaskService.getTasks.mockReturnValue(of(tasks));
        component['editingActualEffortTaskId'].set(1);
        component['draftActualEffortText'].set('1:30');

        component['saveActualEffort'](tasks[0]);

        expect(userStoryTaskService.updateTask).toHaveBeenCalledWith(1, { ...tasks[0], actualEffortHours: 1.5 });
        expect(component['editingActualEffortTaskId']()).toBeUndefined();
        expect(userStoryTaskService.getTasks).toHaveBeenCalled();
    });

    it('should not save an unparsable logged-effort draft', () => {
        fixture.detectChanges();
        component['editingActualEffortTaskId'].set(1);
        component['draftActualEffortText'].set('not a duration');

        component['saveActualEffort'](tasks[0]);

        expect(userStoryTaskService.updateTask).not.toHaveBeenCalled();
        expect(component['editingActualEffortTaskId']()).toBe(1);
    });

    it('should show an alert when saving the logged effort fails', () => {
        fixture.detectChanges();
        mockUserStoryTaskService.updateTask.mockReturnValue(throwError(() => new Error('network error')));
        component['draftActualEffortText'].set('1:30');

        component['saveActualEffort'](tasks[0]);

        expect(alertService.error).toHaveBeenCalledWith('artemisApp.courseOverview.exerciseDetails.tasks.actualEffortSaveError');
    });

    it('should show an alert when advancing fails', () => {
        fixture.detectChanges();
        mockUserStoryTaskService.advanceState.mockReturnValue(throwError(() => new Error('network error')));

        component['advanceState'](tasks[0]);

        expect(alertService.error).toHaveBeenCalledWith('artemisApp.courseOverview.exerciseDetails.tasks.advanceError');
    });
});
