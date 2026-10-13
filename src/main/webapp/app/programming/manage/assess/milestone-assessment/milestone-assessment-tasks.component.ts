import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { TumAetUiTableDirective, TumAetUiTagComponent } from '@tumaet/ui-angular';
import { ArtemisTranslatePipe } from 'app/foundation/pipes/artemis-translate.pipe';
import { TranslateDirective } from 'app/foundation/language/translate.directive';
import { UserStoryTask } from 'app/exercise/shared/entities/participation/programming-exercise-student-participation.model';
import { formatEffortHours } from 'app/course/overview/exercise-details/user-story-tasks/task-effort-format.util';
import { priorityLabelKey, prioritySeverity, stateLabelKey, stateSeverity } from 'app/course/overview/exercise-details/user-story-tasks/user-story-task-display.util';

/**
 * The tasks a student broke a user story down into, shown read-only to the tutor above the story's assessment editor.
 * <p>
 * Collapsed by default: the board is context for the grade, not the thing being graded, so it should not push the
 * code editor out of view. The summary alone already tells the tutor how many tasks there are and how the planned
 * effort compares to the logged one.
 */
@Component({
    selector: 'jhi-milestone-assessment-tasks',
    templateUrl: './milestone-assessment-tasks.component.html',
    styleUrl: './milestone-assessment-tasks.component.scss',
    imports: [TumAetUiTableDirective, TumAetUiTagComponent, ArtemisTranslatePipe, TranslateDirective],
    changeDetection: ChangeDetectionStrategy.OnPush,
})
export class MilestoneAssessmentTasksComponent {
    readonly tasks = input.required<UserStoryTask[]>();

    protected readonly formatEffortHours = formatEffortHours;
    protected readonly priorityLabelKey = priorityLabelKey;
    protected readonly prioritySeverity = prioritySeverity;
    protected readonly stateLabelKey = stateLabelKey;
    protected readonly stateSeverity = stateSeverity;

    protected readonly estimatedEffortHours = computed(() => this.tasks().reduce((sum, task) => sum + task.estimatedEffortHours, 0));

    /** Undefined while no task has any time logged, which reads differently from a real zero. */
    protected readonly actualEffortHours = computed<number | undefined>(() => {
        const logged = this.tasks().filter((task) => task.actualEffortHours !== undefined);
        return logged.length ? logged.reduce((sum, task) => sum + task.actualEffortHours!, 0) : undefined;
    });
}
