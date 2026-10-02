import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { TranslateService } from '@ngx-translate/core';
import { MockTranslateService } from 'test/helpers/mocks/service/mock-translate.service';
import { UserStoryTaskEditModalComponent } from 'app/course/overview/exercise-details/user-story-tasks/user-story-task-edit-modal.component';
import { UserStoryTask } from 'app/exercise/shared/entities/participation/programming-exercise-student-participation.model';

describe('UserStoryTaskEditModalComponent', () => {
    let fixture: ComponentFixture<UserStoryTaskEditModalComponent>;
    let component: UserStoryTaskEditModalComponent;

    const existingTask: UserStoryTask = {
        id: 1,
        title: 'Write tests',
        description: 'Cover the happy path',
        taskPoints: 3,
        priority: 'HIGH',
        estimatedEffortHours: 2,
        state: 'IN_PROGRESS',
    };

    beforeEach(async () => {
        await TestBed.configureTestingModule({
            imports: [UserStoryTaskEditModalComponent],
            providers: [{ provide: TranslateService, useClass: MockTranslateService }],
        }).compileComponents();
        fixture = TestBed.createComponent(UserStoryTaskEditModalComponent);
        component = fixture.componentInstance;
        // The drafts are only filled while the dialog is open, as the parent opens it right after choosing the task.
        fixture.componentRef.setInput('visible', true);
    });

    it('should be in create mode with empty drafts when no task is given', () => {
        fixture.detectChanges();

        expect(component['isNew']()).toBe(true);
        expect(component['draftTitle']()).toBe('');
        expect(component['draftPriority']()).toBeUndefined();
        // A new task always starts at NEW, even though the picker that lets it be changed is hidden while creating.
        expect(component['draftState']()).toBe('NEW');
        expect(component['isSaveDisabled']()).toBe(true);
    });

    it('should be in edit mode and prefill the drafts from the given task', () => {
        fixture.componentRef.setInput('task', existingTask);
        fixture.detectChanges();

        expect(component['isNew']()).toBe(false);
        expect(component['draftTitle']()).toBe('Write tests');
        expect(component['draftDescription']()).toBe('Cover the happy path');
        expect(component['draftTaskPoints']()).toBe(3);
        expect(component['draftPriority']()).toBe('HIGH');
        expect(component['draftEstimatedEffortText']()).toBe('02:00');
        expect(component['draftEstimatedEffortHours']()).toBe(2);
        expect(component['draftState']()).toBe('IN_PROGRESS');
        expect(component['isSaveDisabled']()).toBe(false);
    });

    it('should disable saving until title, points, priority and effort are all set', () => {
        fixture.detectChanges();

        component['draftTitle'].set('New task');
        expect(component['isSaveDisabled']()).toBe(true);

        component['draftTaskPoints'].set(1);
        expect(component['isSaveDisabled']()).toBe(true);

        component['draftPriority'].set('LOW');
        expect(component['isSaveDisabled']()).toBe(true);

        component['draftEstimatedEffortText'].set('0:30');
        expect(component['isSaveDisabled']()).toBe(false);
    });

    it('should treat an unparsable estimated-effort text as invalid', () => {
        fixture.detectChanges();
        component['draftTitle'].set('New task');
        component['draftTaskPoints'].set(1);
        component['draftPriority'].set('LOW');
        component['draftEstimatedEffortText'].set('0:30');
        expect(component['isSaveDisabled']()).toBe(false);

        component['draftEstimatedEffortText'].set('not a duration');

        expect(component['draftEstimatedEffortHours']()).toBeUndefined();
        expect(component['isSaveDisabled']()).toBe(true);
    });

    it('should auto-insert the ":" and jump the caret past it once 2 hour digits are typed', () => {
        fixture.detectChanges();
        const inputElement = { value: '1', selectionStart: 1, setSelectionRange: vi.fn() } as unknown as HTMLInputElement;

        component['onEffortInput']({ target: inputElement } as unknown as Event);

        expect(inputElement.value).toBe('1');
        expect(component['draftEstimatedEffortText']()).toBe('1');

        inputElement.value = '12';
        inputElement.selectionStart = 2;
        component['onEffortInput']({ target: inputElement } as unknown as Event);

        expect(inputElement.value).toBe('12:');
        expect(inputElement.setSelectionRange).toHaveBeenLastCalledWith(3, 3);
        expect(component['draftEstimatedEffortText']()).toBe('12:');
    });

    it('should emit a new task with trimmed fields on save when creating', () => {
        let emitted: UserStoryTask | undefined;
        fixture.detectChanges();
        component.saved.subscribe((task) => (emitted = task));

        component['draftTitle'].set('  New task  ');
        component['draftDescription'].set('  ');
        component['draftTaskPoints'].set(5);
        component['draftPriority'].set('LOW');
        component['draftEstimatedEffortText'].set('1:00');
        component['onSave']();

        expect(emitted).toEqual({ title: 'New task', description: undefined, taskPoints: 5, priority: 'LOW', estimatedEffortHours: 1, state: 'NEW' });
        expect(component['visible']()).toBe(false);
    });

    it('should emit the edited task keeping its id on save when editing', () => {
        let emitted: UserStoryTask | undefined;
        fixture.componentRef.setInput('task', existingTask);
        fixture.detectChanges();
        component.saved.subscribe((task) => (emitted = task));

        component['draftTaskPoints'].set(4);
        component['onSave']();

        expect(emitted).toEqual({ ...existingTask, taskPoints: 4 });
    });

    it('should emit the edited task with a directly-set state', () => {
        let emitted: UserStoryTask | undefined;
        fixture.componentRef.setInput('task', existingTask);
        fixture.detectChanges();
        component.saved.subscribe((task) => (emitted = task));

        component['draftState'].set('DONE');
        component['onSave']();

        expect(emitted).toEqual({ ...existingTask, state: 'DONE' });
    });

    it('should close without emitting on cancel', () => {
        let emitted = false;
        fixture.detectChanges();
        component.saved.subscribe(() => (emitted = true));

        component['onCancel']();

        expect(emitted).toBe(false);
        expect(component['visible']()).toBe(false);
    });

    it('should clear the drafts when creating again after a saved task', () => {
        fixture.detectChanges();
        component['draftTitle'].set('First task');
        component['draftDescription'].set('Some details');
        component['draftTaskPoints'].set(5);
        component['draftPriority'].set('LOW');
        component['draftEstimatedEffortText'].set('01:00');
        component['onSave']();
        fixture.detectChanges();

        component.visible.set(true);
        fixture.detectChanges();

        expect(component['draftTitle']()).toBe('');
        expect(component['draftDescription']()).toBe('');
        expect(component['draftTaskPoints']()).toBeUndefined();
        expect(component['draftPriority']()).toBeUndefined();
        expect(component['draftEstimatedEffortText']()).toBe('');
        expect(component['draftState']()).toBe('NEW');
    });

    it('should drop discarded edits when reopening the same task after a cancel', () => {
        fixture.componentRef.setInput('task', existingTask);
        fixture.detectChanges();
        component['draftTitle'].set('Discarded title');
        component['onCancel']();
        fixture.detectChanges();

        component.visible.set(true);
        fixture.detectChanges();

        expect(component['draftTitle']()).toBe('Write tests');
    });
});
