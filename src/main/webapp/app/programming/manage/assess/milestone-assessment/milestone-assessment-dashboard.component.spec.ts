import { beforeEach, describe, expect, it } from 'vitest';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { WritableSignal } from '@angular/core';
import { ActivatedRoute } from '@angular/router';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { EMPTY, Observable, of } from 'rxjs';
import { MockProvider } from 'ng-mocks';
import { TranslateService } from '@ngx-translate/core';
import { MockTranslateService } from 'test/helpers/mocks/service/mock-translate.service';
import { AlertService } from 'app/foundation/service/alert.service';
import { MilestoneAssessmentDashboardComponent } from 'app/programming/manage/assess/milestone-assessment/milestone-assessment-dashboard.component';
import { MilestoneAssessmentService, MilestoneAssessmentStudent } from 'app/programming/manage/assess/milestone-assessment/milestone-assessment.service';
import { ExerciseType } from 'app/exercise/shared/entities/exercise/exercise.model';

describe('MilestoneAssessmentDashboardComponent', () => {
    let fixture: ComponentFixture<MilestoneAssessmentDashboardComponent>;

    function students(): MilestoneAssessmentStudent[] {
        return [
            {
                studentLogin: 'student1',
                studentName: 'Ada',
                milestoneParticipationId: 5,
                tutorialGroups: ['Monday 10:00', 'Thursday 14:00'],
                exercises: [
                    {
                        exerciseId: 1,
                        title: 'Login form',
                        exerciseType: ExerciseType.PROGRAMMING,
                        userStory: true,
                        submissionId: 111,
                        assessed: true,
                        latestScore: 80,
                    },
                    { exerciseId: 2, title: 'Logout', exerciseType: ExerciseType.PROGRAMMING, userStory: true, submissionId: 222 },
                    { exerciseId: 3, title: 'Retro quiz', exerciseType: ExerciseType.QUIZ, submissionId: 333, latestScore: 50 },
                ],
            },
            {
                studentLogin: 'student2',
                milestoneParticipationId: 6,
                exercises: [
                    { exerciseId: 1, title: 'Login form', exerciseType: ExerciseType.PROGRAMMING, userStory: true },
                    { exerciseId: 2, title: 'Logout', exerciseType: ExerciseType.PROGRAMMING, userStory: true },
                    { exerciseId: 3, title: 'Retro quiz', exerciseType: ExerciseType.QUIZ },
                ],
            },
        ];
    }

    async function setup(getDashboard: () => Observable<MilestoneAssessmentStudent[]> = () => of(students())): Promise<void> {
        const route = { params: of({ courseId: '1', groupId: '10' }) } as unknown as ActivatedRoute;

        await TestBed.configureTestingModule({
            imports: [MilestoneAssessmentDashboardComponent],
            providers: [
                { provide: ActivatedRoute, useValue: route },
                { provide: TranslateService, useClass: MockTranslateService },
                MockProvider(MilestoneAssessmentService, { getAssessmentDashboard: getDashboard as never }),
                MockProvider(AlertService),
                provideHttpClient(),
                provideHttpClientTesting(),
            ],
        }).compileComponents();

        fixture = TestBed.createComponent(MilestoneAssessmentDashboardComponent);
        fixture.detectChanges();
    }

    /** Access to the protected state under test. */
    function comp(): {
        students: () => MilestoneAssessmentStudent[];
        exerciseColumns: () => { exerciseId: number }[];
        assessedCount: (student: MilestoneAssessmentStudent) => number;
        manuallyAssessable: (student: MilestoneAssessmentStudent) => { exerciseId: number }[];
        assessmentLink: (student: MilestoneAssessmentStudent) => (string | number)[];
        visibleStudents: () => MilestoneAssessmentStudent[];
        tutorialGroupOptions: () => { label: string; value: string }[];
        searchTerm: WritableSignal<string>;
        tutorialGroupFilter: WritableSignal<string>;
        openOnly: WritableSignal<boolean>;
        onSort: (event: { field: string; order: number }) => void;
    } {
        return fixture.componentInstance as never;
    }

    beforeEach(() => TestBed.resetTestingModule());

    it('renders one row per student and one column per exercise', async () => {
        await setup();

        expect(comp().students()).toHaveLength(2);
        // Taken from the first row: every row carries the group's exercises in the same order, started or not.
        expect(comp().exerciseColumns()).toHaveLength(3);
        expect(fixture.nativeElement.querySelectorAll('tbody tr')).toHaveLength(2);
    });

    it('names the tutorial groups of each student, so a tutor can find their own students', async () => {
        await setup();

        const rows: HTMLTableRowElement[] = Array.from(fixture.nativeElement.querySelectorAll('tbody tr'));
        expect(rows[0].cells[1].textContent?.trim()).toBe('Monday 10:00, Thursday 14:00');
        expect(rows[1].cells[1].textContent).toContain('artemisApp.milestoneAssessment.dashboard.noTutorialGroup');
    });

    it('shows one icon per exercise: assessed, assessment required, or no submission', async () => {
        await setup();

        const label = (row: number, column: number) =>
            fixture.nativeElement.querySelectorAll('tbody tr')[row].cells[column].querySelector('[role="img"]')?.getAttribute('aria-label');
        // Columns 0 and 1 are the student and the tutorial group.
        expect(label(0, 2)).toBe('artemisApp.milestoneAssessment.dashboard.assessed');
        expect(label(0, 3)).toBe('artemisApp.milestoneAssessment.dashboard.assessmentRequired');
        // A submitted quiz is graded automatically, so there is nothing left to do.
        expect(label(0, 4)).toBe('artemisApp.milestoneAssessment.dashboard.assessed');
        expect(label(1, 2)).toBe('artemisApp.milestoneAssessment.dashboard.noSubmission');
    });

    it('counts what is already assessed, which is what tells a tutor where to spend their time', async () => {
        await setup();

        expect(comp().assessedCount(students()[0])).toBe(1);
        expect(comp().assessedCount(students()[1])).toBe(0);
    });

    it('leaves quizzes out of the progress, since they are graded automatically', async () => {
        await setup();

        expect(
            comp()
                .manuallyAssessable(students()[0])
                .map((exercise) => exercise.exerciseId),
        ).toEqual([1, 2]);
    });

    it('links to the tabbed page for that student, addressed by login', async () => {
        await setup();

        expect(comp().assessmentLink(students()[0])).toEqual(['/course-management', 1, 'milestone-exercise-groups', 10, 'assessment', 'student1']);
    });

    it('says so when nobody has started the milestone yet, rather than rendering an empty table', async () => {
        await setup(() => of([]));

        expect(fixture.nativeElement.querySelector('table')).toBeNull();
        expect(fixture.nativeElement.textContent).toContain('artemisApp.milestoneAssessment.dashboard.noStudents');
    });

    it('does not render a table while the request is still outstanding', async () => {
        await setup(() => EMPTY);

        expect(fixture.nativeElement.querySelector('table')).toBeNull();
    });

    describe('sorting and filtering', () => {
        /** A third student who is in one of the first student's groups and has nothing left to assess. */
        function withThirdStudent(): MilestoneAssessmentStudent[] {
            return [
                ...students(),
                {
                    studentLogin: 'student3',
                    studentName: 'Bob',
                    milestoneParticipationId: 7,
                    tutorialGroups: ['Monday 10:00'],
                    exercises: [
                        { exerciseId: 1, title: 'Login form', exerciseType: ExerciseType.PROGRAMMING, userStory: true, submissionId: 411, assessed: true },
                        { exerciseId: 2, title: 'Logout', exerciseType: ExerciseType.PROGRAMMING, userStory: true, submissionId: 422, assessed: true },
                        { exerciseId: 3, title: 'Retro quiz', exerciseType: ExerciseType.QUIZ },
                    ],
                },
            ];
        }

        const logins = () =>
            comp()
                .visibleStudents()
                .map((student) => student.studentLogin);

        beforeEach(() => setup(() => of(withThirdStudent())));

        it('lists the students by name by default, falling back to the login', () => {
            expect(logins()).toEqual(['student1', 'student3', 'student2']);
        });

        it('searches the name, the login and the tutorial groups, ignoring case', () => {
            comp().searchTerm.set('ADA');
            expect(logins()).toEqual(['student1']);
            comp().searchTerm.set('student2');
            expect(logins()).toEqual(['student2']);
            comp().searchTerm.set('monday');
            expect(logins()).toEqual(['student1', 'student3']);
        });

        it('offers every tutorial group once, after "all" and "none"', () => {
            expect(
                comp()
                    .tutorialGroupOptions()
                    .map((option) => option.value),
            ).toEqual(['__all__', '__none__', 'Monday 10:00', 'Thursday 14:00']);
        });

        it('filters by one tutorial group, or by having none', () => {
            comp().tutorialGroupFilter.set('Thursday 14:00');
            expect(logins()).toEqual(['student1']);
            comp().tutorialGroupFilter.set('__none__');
            expect(logins()).toEqual(['student2']);
        });

        it('hides students with nothing left to assess when asked to', () => {
            comp().openOnly.set(true);
            expect(logins()).toEqual(['student1']);
        });

        it('sorts an exercise column with the open assessments first, and reverses on the second click', () => {
            comp().onSort({ field: 'exercise-2', order: 1 });
            expect(logins()).toEqual(['student1', 'student3', 'student2']);
            comp().onSort({ field: 'exercise-2', order: -1 });
            expect(logins()).toEqual(['student2', 'student3', 'student1']);
        });

        it('sorts by progress, with a student who submitted nothing first', () => {
            comp().onSort({ field: 'progress', order: 1 });
            expect(logins()).toEqual(['student2', 'student1', 'student3']);
        });

        it('says so when no student matches the filters', () => {
            comp().searchTerm.set('nobody');
            fixture.detectChanges();

            expect(fixture.nativeElement.querySelectorAll('tbody tr')).toHaveLength(1);
            expect(fixture.nativeElement.textContent).toContain('artemisApp.milestoneAssessment.dashboard.noMatches');
        });
    });
});
