import { ComponentFixture, TestBed } from '@angular/core/testing';
import { HttpResponse, provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { TranslateService } from '@ngx-translate/core';
import dayjs from 'dayjs/esm';
import { of, throwError } from 'rxjs';
import { MockTranslateService } from 'test/helpers/mocks/service/mock-translate.service';
import { MilestoneIndividualDueDateDialogComponent } from 'app/exercise/participation/milestone-individual-due-date-dialog/milestone-individual-due-date-dialog.component';
import { ParticipationService } from 'app/exercise/participation/participation.service';
import { AlertService } from 'app/foundation/service/alert.service';
import { Exercise, ExerciseType } from 'app/exercise/shared/entities/exercise/exercise.model';

describe('MilestoneIndividualDueDateDialogComponent', () => {
    let fixture: ComponentFixture<MilestoneIndividualDueDateDialogComponent>;
    let component: MilestoneIndividualDueDateDialogComponent;
    let participationService: ParticipationService;

    const dueDate = dayjs().add(1, 'day');
    const exercise: Exercise = {
        id: 7,
        type: ExerciseType.MILESTONE,
        dueDate,
        numberOfAssessmentsOfCorrectionRounds: [],
        secondCorrectionEnabled: false,
        studentAssignedTeamIdComputed: false,
    };

    // The dialog's state is protected; the spec reads and drives it as the template does.
    const state = () =>
        component as unknown as {
            studentLogin: { set(value: string): void };
            dueDate: { set(value: dayjs.Dayjs | undefined): void };
            isSaveDisabled(): boolean;
            save(): void;
        };

    beforeEach(async () => {
        await TestBed.configureTestingModule({
            imports: [MilestoneIndividualDueDateDialogComponent],
            providers: [provideHttpClient(), provideHttpClientTesting(), { provide: TranslateService, useClass: MockTranslateService }],
        }).compileComponents();

        participationService = TestBed.inject(ParticipationService);
        vi.spyOn(TestBed.inject(AlertService), 'success').mockReturnValue(undefined as never);
        fixture = TestBed.createComponent(MilestoneIndividualDueDateDialogComponent);
        component = fixture.componentInstance;
        fixture.componentRef.setInput('exercise', exercise);
        fixture.componentRef.setInput('visible', true);
        fixture.detectChanges();
    });

    afterEach(() => {
        vi.restoreAllMocks();
    });

    it('should only allow saving a student and a date after the milestone due date', () => {
        expect(state().isSaveDisabled()).toBe(true);

        state().studentLogin.set('ab12cde');
        state().dueDate.set(dueDate.subtract(2, 'day'));
        expect(state().isSaveDisabled()).toBe(true);

        state().dueDate.set(dueDate.add(2, 'day'));
        expect(state().isSaveDisabled()).toBe(false);
    });

    it('should grant the date, close and tell the page to reload', () => {
        const spy = vi.spyOn(participationService, 'setMilestoneIndividualDueDateOfStudent').mockReturnValue(of(new HttpResponse({ body: {} })));
        const saved = vi.fn();
        component.saved.subscribe(saved);
        const newDueDate = dueDate.add(2, 'day');

        state().studentLogin.set(' ab12cde ');
        state().dueDate.set(newDueDate);
        state().save();

        expect(spy).toHaveBeenCalledWith(exercise, 'ab12cde', newDueDate);
        expect(component.visible()).toBe(false);
        expect(saved).toHaveBeenCalledOnce();
    });

    it('should stay open when the server rejects the date', () => {
        vi.spyOn(participationService, 'setMilestoneIndividualDueDateOfStudent').mockReturnValue(throwError(() => new Error('rejected')));
        const saved = vi.fn();
        component.saved.subscribe(saved);

        state().studentLogin.set('ab12cde');
        state().dueDate.set(dueDate.add(2, 'day'));
        state().save();

        expect(component.visible()).toBe(true);
        expect(saved).not.toHaveBeenCalled();
        expect(state().isSaveDisabled()).toBe(false);
    });
});
