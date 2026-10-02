import { ChangeDetectionStrategy, Component, OnInit, inject, input, signal } from '@angular/core';
import { CdkDrag, CdkDragDrop, CdkDragHandle, CdkDropList, moveItemInArray } from '@angular/cdk/drag-drop';
import { FormsModule } from '@angular/forms';
import { faArrowRight, faBars, faCheck, faClock, faPen, faPlus, faTrash, faXmark } from '@fortawesome/free-solid-svg-icons';
import { FaIconComponent } from '@fortawesome/angular-fontawesome';
import {
    TumAetUiButtonComponent,
    TumAetUiButtonDirective,
    TumAetUiCardComponent,
    TumAetUiInputDirective,
    TumAetUiTagComponent,
    TumAetUiTooltipDirective,
} from '@tumaet/ui-angular';
import { TaskPriority, TaskState, UserStoryTask } from 'app/exercise/shared/entities/participation/programming-exercise-student-participation.model';
import { UserStoryTaskService } from 'app/programming/shared/services/user-story-task.service';
import { AlertService } from 'app/foundation/service/alert.service';
import { ArtemisTranslatePipe } from 'app/foundation/pipes/artemis-translate.pipe';
import { TranslateDirective } from 'app/foundation/language/translate.directive';
import { DeleteButtonDirective } from 'app/shared-ui/delete-dialog/directive/delete-button.directive';
import { UserStoryTaskEditModalComponent } from 'app/course/overview/exercise-details/user-story-tasks/user-story-task-edit-modal.component';
import { formatEffortHours, parseEffortHours } from 'app/course/overview/exercise-details/user-story-tasks/task-effort-format.util';
import { cloneWith } from 'app/foundation/util/deep-clone.util';

/**
 * The "Tasks" tab of a user story exercise's participation view: the tasks the current user (or their team) created
 * for themself while working on the story, with create/edit/delete actions.
 */
@Component({
    selector: 'jhi-user-story-tasks',
    templateUrl: './user-story-tasks.component.html',
    styleUrl: './user-story-tasks.component.scss',
    imports: [
        FormsModule,
        FaIconComponent,
        TumAetUiButtonComponent,
        TumAetUiButtonDirective,
        TumAetUiCardComponent,
        TumAetUiInputDirective,
        TumAetUiTagComponent,
        TumAetUiTooltipDirective,
        ArtemisTranslatePipe,
        TranslateDirective,
        DeleteButtonDirective,
        UserStoryTaskEditModalComponent,
        CdkDropList,
        CdkDrag,
        CdkDragHandle,
    ],
    changeDetection: ChangeDetectionStrategy.OnPush,
})
export class UserStoryTasksComponent implements OnInit {
    private readonly userStoryTaskService = inject(UserStoryTaskService);
    private readonly alertService = inject(AlertService);

    readonly exerciseId = input.required<number>();

    protected readonly faPlus = faPlus;
    protected readonly faPen = faPen;
    protected readonly faTrash = faTrash;
    protected readonly faArrowRight = faArrowRight;
    protected readonly faBars = faBars;
    protected readonly faClock = faClock;
    protected readonly faCheck = faCheck;
    protected readonly faXmark = faXmark;

    protected readonly formatEffortHours = formatEffortHours;
    protected readonly parseEffortHours = parseEffortHours;

    protected readonly tasks = signal<UserStoryTask[]>([]);
    protected readonly modalVisible = signal(false);
    protected readonly selectedTask = signal<UserStoryTask | undefined>(undefined);

    /** The task whose actual-effort field is currently being edited inline on its tile, if any. */
    protected readonly editingActualEffortTaskId = signal<number | undefined>(undefined);
    protected readonly draftActualEffortText = signal('');

    ngOnInit(): void {
        this.loadTasks();
    }

    protected loadTasks(): void {
        this.userStoryTaskService.getTasks(this.exerciseId()).subscribe({
            next: (tasks) => this.tasks.set(tasks),
            error: () => this.alertService.error('artemisApp.courseOverview.exerciseDetails.tasks.loadError'),
        });
    }

    protected openCreateModal(): void {
        this.selectedTask.set(undefined);
        this.modalVisible.set(true);
    }

    protected openEditModal(task: UserStoryTask): void {
        this.selectedTask.set(task);
        this.modalVisible.set(true);
    }

    protected onSaved(task: UserStoryTask): void {
        const request = task.id ? this.userStoryTaskService.updateTask(task.id, task) : this.userStoryTaskService.createTask(this.exerciseId(), task);
        request.subscribe({
            next: () => this.loadTasks(),
            error: () => this.alertService.error('artemisApp.courseOverview.exerciseDetails.tasks.saveError'),
        });
    }

    /** Reorders the board locally (optimistic) and persists the new order; reloads from the server on failure. */
    protected drop(event: CdkDragDrop<UserStoryTask[]>): void {
        if (event.previousIndex === event.currentIndex) {
            return;
        }
        const reordered = [...this.tasks()];
        moveItemInArray(reordered, event.previousIndex, event.currentIndex);
        this.tasks.set(reordered);

        const orderedTaskIds = reordered.map((task) => task.id!);
        this.userStoryTaskService.reorderTasks(this.exerciseId(), orderedTaskIds).subscribe({
            error: () => {
                this.alertService.error('artemisApp.courseOverview.exerciseDetails.tasks.reorderError');
                this.loadTasks();
            },
        });
    }

    protected priorityLabelKey(priority: TaskPriority): string {
        switch (priority) {
            case 'LOW':
                return 'artemisApp.courseOverview.exerciseDetails.tasks.priorityLow';
            case 'MEDIUM':
                return 'artemisApp.courseOverview.exerciseDetails.tasks.priorityMedium';
            case 'HIGH':
                return 'artemisApp.courseOverview.exerciseDetails.tasks.priorityHigh';
        }
    }

    protected prioritySeverity(priority: TaskPriority): 'success' | 'warn' | 'danger' {
        switch (priority) {
            case 'LOW':
                return 'success';
            case 'MEDIUM':
                return 'warn';
            case 'HIGH':
                return 'danger';
        }
    }

    protected stateLabelKey(state: TaskState): string {
        switch (state) {
            case 'NEW':
                return 'artemisApp.courseOverview.exerciseDetails.tasks.stateNew';
            case 'IN_PROGRESS':
                return 'artemisApp.courseOverview.exerciseDetails.tasks.stateInProgress';
            case 'DONE':
                return 'artemisApp.courseOverview.exerciseDetails.tasks.stateDone';
        }
    }

    protected stateSeverity(state: TaskState): 'contrast' | 'info' | 'success' {
        switch (state) {
            case 'NEW':
                return 'contrast';
            case 'IN_PROGRESS':
                return 'info';
            case 'DONE':
                return 'success';
        }
    }

    /** Time logging is meaningless before a task is started, so it stays hidden until it leaves NEW. */
    protected showActualEffort(task: UserStoryTask): boolean {
        return (task.state ?? 'NEW') !== 'NEW';
    }

    /** Editable only while IN_PROGRESS: NEW hides the field entirely, and DONE freezes whatever was last logged. */
    protected canEditActualEffort(task: UserStoryTask): boolean {
        return (task.state ?? 'NEW') === 'IN_PROGRESS';
    }

    /** The advance button would move an IN_PROGRESS task to DONE, which requires a logged actual effort first. */
    protected advanceRequiresEffort(task: UserStoryTask): boolean {
        return (task.state ?? 'NEW') === 'IN_PROGRESS' && task.actualEffortHours === undefined;
    }

    protected advanceButtonTooltipKey(task: UserStoryTask): string {
        return this.advanceRequiresEffort(task)
            ? 'artemisApp.courseOverview.exerciseDetails.tasks.advanceButtonRequiresEffort'
            : 'artemisApp.courseOverview.exerciseDetails.tasks.advanceButton';
    }

    /** Opens the inline editor for the real time logged on a task, prefilled with its current value. */
    protected startEditingActualEffort(task: UserStoryTask): void {
        this.editingActualEffortTaskId.set(task.id);
        this.draftActualEffortText.set(task.actualEffortHours !== undefined ? this.formatEffortHours(task.actualEffortHours) : '');
    }

    protected cancelActualEffortEdit(): void {
        this.editingActualEffortTaskId.set(undefined);
    }

    protected saveActualEffort(task: UserStoryTask): void {
        const hours = this.parseEffortHours(this.draftActualEffortText());
        if (hours === undefined || !task.id) {
            return;
        }
        this.userStoryTaskService.updateTask(task.id, cloneWith(task, { actualEffortHours: hours })).subscribe({
            next: () => {
                this.editingActualEffortTaskId.set(undefined);
                this.loadTasks();
            },
            error: () => this.alertService.error('artemisApp.courseOverview.exerciseDetails.tasks.actualEffortSaveError'),
        });
    }

    protected advanceState(task: UserStoryTask): void {
        if (!task.id) {
            return;
        }
        this.userStoryTaskService.advanceState(task.id).subscribe({
            next: () => this.loadTasks(),
            error: () => this.alertService.error('artemisApp.courseOverview.exerciseDetails.tasks.advanceError'),
        });
    }

    protected deleteTask(task: UserStoryTask): void {
        if (!task.id) {
            return;
        }
        this.userStoryTaskService.deleteTask(task.id).subscribe({
            next: () => this.loadTasks(),
            error: () => this.alertService.error('artemisApp.courseOverview.exerciseDetails.tasks.deleteError'),
        });
    }
}
