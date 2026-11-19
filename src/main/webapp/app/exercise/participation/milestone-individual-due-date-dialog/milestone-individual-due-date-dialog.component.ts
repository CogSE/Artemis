import { Component, computed, inject, input, model, output, signal } from '@angular/core';
import { TumAetUiButtonDirective, TumAetUiDatePickerComponent, TumAetUiDialogComponent, TumAetUiInputDirective } from '@tumaet/ui-angular';
import dayjs from 'dayjs/esm';
import { Exercise } from 'app/exercise/shared/entities/exercise/exercise.model';
import { ParticipationService } from 'app/exercise/participation/participation.service';
import { AlertService } from 'app/foundation/service/alert.service';
import { TranslateDirective } from 'app/foundation/language/translate.directive';
import { ArtemisTranslatePipe } from 'app/foundation/pipes/artemis-translate.pipe';

/**
 * Grants one student an individual due date on a milestone, also a student who has not started it yet: the server then
 * gives them a participation without a submission, and the date reaches every exercise of the milestone's group.
 */
@Component({
    selector: 'jhi-milestone-individual-due-date-dialog',
    templateUrl: './milestone-individual-due-date-dialog.component.html',
    imports: [TumAetUiDialogComponent, TumAetUiInputDirective, TumAetUiDatePickerComponent, TumAetUiButtonDirective, TranslateDirective, ArtemisTranslatePipe],
})
export class MilestoneIndividualDueDateDialogComponent {
    private readonly participationService = inject(ParticipationService);
    private readonly alertService = inject(AlertService);

    readonly exercise = input.required<Exercise>();
    readonly visible = model(false);
    /** Emitted once the date is saved, so the participation list can show the student's (possibly new) row. */
    readonly saved = output<void>();

    protected readonly studentLogin = signal('');
    protected readonly dueDate = signal<dayjs.Dayjs | undefined>(undefined);
    // A picker keeps its last committed value while its text is not a date, so a save must wait for the text to parse.
    protected readonly isDueDateTextValid = signal(true);
    protected readonly isSaving = signal(false);
    protected readonly isDueDateBeforeExerciseDueDate = computed(() => {
        const dueDate = this.dueDate();
        const exerciseDueDate = this.exercise().dueDate;
        return !!dueDate && !!exerciseDueDate && dueDate.isBefore(exerciseDueDate);
    });
    protected readonly isSaveDisabled = computed(
        () => this.studentLogin().trim() === '' || !this.dueDate() || !this.isDueDateTextValid() || this.isDueDateBeforeExerciseDueDate() || this.isSaving(),
    );

    protected onStudentLoginInput(event: Event) {
        this.studentLogin.set((event.target as HTMLInputElement).value);
    }

    protected cancel() {
        this.visible.set(false);
        this.reset();
    }

    protected save() {
        const dueDate = this.dueDate();
        const studentLogin = this.studentLogin().trim();
        if (!dueDate || !studentLogin) {
            return;
        }
        this.isSaving.set(true);
        this.participationService.setMilestoneIndividualDueDateOfStudent(this.exercise(), studentLogin, dueDate).subscribe({
            next: () => {
                this.alertService.success('artemisApp.participation.updateDueDates.success', { name: studentLogin });
                this.visible.set(false);
                this.reset();
                this.saved.emit();
            },
            // The server's failure alert (unknown student, date before the due date) is shown by the HTTP interceptor.
            error: () => this.isSaving.set(false),
        });
    }

    private reset() {
        this.studentLogin.set('');
        this.dueDate.set(undefined);
        this.isDueDateTextValid.set(true);
        this.isSaving.set(false);
    }
}
