import { ChangeDetectionStrategy, Component, computed, effect, input, model, output, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { TumAetUiButtonComponent, TumAetUiDialogComponent, TumAetUiInputDirective, TumAetUiSelectButtonComponent, TumAetUiSelectComponent } from '@tumaet/ui-angular';
import { TaskPriority, TaskState, UserStoryTask } from 'app/exercise/shared/entities/participation/programming-exercise-student-participation.model';
import { ArtemisTranslatePipe } from 'app/foundation/pipes/artemis-translate.pipe';
import { TranslateDirective } from 'app/foundation/language/translate.directive';
import { cloneWith } from 'app/foundation/util/deep-clone.util';
import { parseEffortHours, splitEffortHours } from 'app/course/overview/exercise-details/user-story-tasks/task-effort-format.util';

const MAX_TITLE_LENGTH = 255;

/** Task points are estimated on the (deduplicated) Fibonacci scale used for agile planning poker. */
const TASK_POINTS_FIBONACCI_OPTIONS: number[] = [1, 2, 3, 5, 8, 13];

/** Renders decimal hours as a zero-padded "hh:mm" for the masked effort input - fixed-width so the auto-jump-past-":" logic can rely on a 2-digit hour segment. */
function formatEffortForInput(hours: number): string {
    const { hours: wholeHours, minutes } = splitEffortHours(hours);
    return `${wholeHours.toString().padStart(2, '0')}:${minutes.toString().padStart(2, '0')}`;
}

interface PriorityOption {
    value: TaskPriority;
    labelKey: string;
}

interface StateOption {
    value: TaskState;
    labelKey: string;
}

/**
 * Declarative create/edit dialog for a {@link UserStoryTask}. Editing an existing task comes in via {@link task};
 * omitting it opens the dialog in "create" mode. Saving emits the (possibly new) task on {@link saved} and closes;
 * cancelling just closes.
 */
@Component({
    selector: 'jhi-user-story-task-edit-modal',
    templateUrl: './user-story-task-edit-modal.component.html',
    imports: [
        FormsModule,
        TumAetUiDialogComponent,
        TumAetUiInputDirective,
        TumAetUiSelectComponent,
        TumAetUiSelectButtonComponent,
        TumAetUiButtonComponent,
        ArtemisTranslatePipe,
        TranslateDirective,
    ],
    changeDetection: ChangeDetectionStrategy.OnPush,
})
export class UserStoryTaskEditModalComponent {
    protected readonly MAX_TITLE_LENGTH = MAX_TITLE_LENGTH;
    protected readonly taskPointsOptions = TASK_POINTS_FIBONACCI_OPTIONS;

    /** Two-way visibility, driven by the parent. */
    readonly visible = model<boolean>(false);
    /** The task being edited, supplied by the parent; omitted while creating a new one. */
    readonly task = input<UserStoryTask | undefined>(undefined);
    /** Emits the created/edited task on save; cancel/close emit nothing. */
    readonly saved = output<UserStoryTask>();

    protected readonly isNew = computed(() => !this.task());
    protected readonly headerStringKey = computed(() =>
        this.isNew() ? 'artemisApp.courseOverview.exerciseDetails.tasks.createHeader' : 'artemisApp.courseOverview.exerciseDetails.tasks.editHeader',
    );

    protected readonly priorityOptions: PriorityOption[] = [
        { value: 'LOW', labelKey: 'artemisApp.courseOverview.exerciseDetails.tasks.priorityLow' },
        { value: 'MEDIUM', labelKey: 'artemisApp.courseOverview.exerciseDetails.tasks.priorityMedium' },
        { value: 'HIGH', labelKey: 'artemisApp.courseOverview.exerciseDetails.tasks.priorityHigh' },
    ];

    protected readonly stateOptions: StateOption[] = [
        { value: 'NEW', labelKey: 'artemisApp.courseOverview.exerciseDetails.tasks.stateNew' },
        { value: 'IN_PROGRESS', labelKey: 'artemisApp.courseOverview.exerciseDetails.tasks.stateInProgress' },
        { value: 'DONE', labelKey: 'artemisApp.courseOverview.exerciseDetails.tasks.stateDone' },
    ];

    protected readonly draftTitle = signal('');
    protected readonly draftDescription = signal('');
    protected readonly draftTaskPoints = signal<number | undefined>(undefined);
    protected readonly draftPriority = signal<TaskPriority | undefined>(undefined);
    protected readonly draftEstimatedEffortText = signal('');
    protected readonly draftState = signal<TaskState | undefined>(undefined);

    /** Parsed from {@link draftEstimatedEffortText}; undefined while the entered "hh:mm" is not valid. */
    protected readonly draftEstimatedEffortHours = computed(() => parseEffortHours(this.draftEstimatedEffortText()));

    protected readonly isTitleValid = computed(() => {
        const title = this.draftTitle().trim();
        return title.length > 0 && title.length <= MAX_TITLE_LENGTH;
    });
    protected readonly isSaveDisabled = computed(
        () =>
            !this.isTitleValid() ||
            this.draftTaskPoints() === undefined ||
            this.draftPriority() === undefined ||
            this.draftEstimatedEffortHours() === undefined ||
            this.draftState() === undefined,
    );

    constructor() {
        // Refilled on every open, not only when the task changes: creating twice in a row keeps `task` undefined, and
        // reopening the same task after a cancel must drop the discarded edits.
        effect(() => {
            if (!this.visible()) {
                return;
            }
            const t = this.task();
            this.draftTitle.set(t?.title ?? '');
            this.draftDescription.set(t?.description ?? '');
            this.draftTaskPoints.set(t?.taskPoints);
            this.draftPriority.set(t?.priority);
            this.draftEstimatedEffortText.set(t?.estimatedEffortHours !== undefined ? formatEffortForInput(t.estimatedEffortHours) : '');
            // A new task always starts at NEW - the picker only matters for an existing one.
            this.draftState.set(t?.state ?? 'NEW');
        });
    }

    onSave(): void {
        const base: UserStoryTask = this.task() ?? { title: '', taskPoints: 0, priority: 'LOW', estimatedEffortHours: 0, state: 'NEW' };
        const updated: UserStoryTask = cloneWith(base, {
            title: this.draftTitle().trim(),
            description: this.draftDescription().trim() || undefined,
            taskPoints: this.draftTaskPoints()!,
            priority: this.draftPriority()!,
            estimatedEffortHours: this.draftEstimatedEffortHours()!,
            state: this.draftState()!,
        });
        this.saved.emit(updated);
        this.visible.set(false);
    }

    onCancel(): void {
        this.visible.set(false);
    }

    /**
     * Keeps {@link draftEstimatedEffortText} formatted as "hh:mm" while typing: the ":" is (re-)inserted as soon as
     * the hour segment has 2 digits, and the caret is moved past it so the minutes can be typed right after -
     * without the user having to type the ":" themselves.
     */
    protected onEffortInput(event: Event): void {
        const inputElement = event.target as HTMLInputElement;
        const caretBefore = inputElement.selectionStart ?? inputElement.value.length;
        const digitsBeforeCaret = (inputElement.value.slice(0, caretBefore).match(/\d/g) ?? []).length;

        const digits = inputElement.value.replace(/\D/g, '').slice(0, 4);
        const formatted = digits.length < 2 ? digits : `${digits.slice(0, 2)}:${digits.slice(2)}`;

        const caretDigits = Math.min(digitsBeforeCaret, digits.length);
        const caret = caretDigits + (caretDigits >= 2 ? 1 : 0);

        inputElement.value = formatted;
        inputElement.setSelectionRange(caret, caret);
        this.draftEstimatedEffortText.set(formatted);
    }
}
