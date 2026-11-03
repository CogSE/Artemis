import { ChangeDetectionStrategy, Component, DestroyRef, computed, inject, linkedSignal, signal } from '@angular/core';
import { takeUntilDestroyed, toSignal } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { TranslateService } from '@ngx-translate/core';
import {
    TumAetUiButtonDirective,
    TumAetUiSearchFieldComponent,
    TumAetUiSelectComponent,
    TumAetUiTableDirective,
    TumAetUiTableSortEvent,
    TumAetUiTableSortableColumnComponent,
    TumAetUiToggleSwitchComponent,
    TumAetUiTooltipDirective,
} from '@tumaet/ui-angular';
import { FaIconComponent } from '@fortawesome/angular-fontawesome';
import { faCheck, faMinus, faQuestion } from '@fortawesome/free-solid-svg-icons';
import { ArtemisTranslatePipe } from 'app/foundation/pipes/artemis-translate.pipe';
import { TranslateDirective } from 'app/foundation/language/translate.directive';
import { AlertService } from 'app/foundation/service/alert.service';
import { SortService } from 'app/foundation/service/sort.service';
import { CourseTitleBarTitleDirective } from 'app/course/shared/directives/course-title-bar-title.directive';
import { ExerciseType } from 'app/exercise/shared/entities/exercise/exercise.model';
import { MilestoneAssessmentExercise, MilestoneAssessmentService, MilestoneAssessmentStudent } from './milestone-assessment.service';

/** The assessment state of one exercise of one student, shown as a single icon in the dashboard. */
type MilestoneAssessmentCellState = 'assessed' | 'required' | 'noSubmission';

/** Ascending order of the cell states when sorting by an exercise column: the work a tutor still has to do comes first. */
const CELL_STATE_RANK: Record<MilestoneAssessmentCellState, number> = { required: 0, assessed: 1, noSubmission: 2 };

/** Sort field prefix of an exercise column, followed by the exercise id. */
const EXERCISE_SORT_PREFIX = 'exercise-';

/** Tutorial group filter values that are not a group title. A title cannot clash with them, since they are not shown. */
const ALL_TUTORIAL_GROUPS = '__all__';
const NO_TUTORIAL_GROUP = '__none__';

/**
 * The milestone's own assessment dashboard: one row per student, one column per exercise of the group, user stories first.
 * <p>
 * Deliberately not modelled on the per-exercise dashboard's "start assessing" flow, which hands a tutor a random
 * student's submission. A milestone group's stories share one repository and one build, so grading them one story at a
 * time across different students means reading the same codebase over and over. Here the tutor reads the table, sees
 * who still needs work, and picks the student.
 */
@Component({
    selector: 'jhi-milestone-assessment-dashboard',
    templateUrl: './milestone-assessment-dashboard.component.html',
    styleUrl: './milestone-assessment-dashboard.component.scss',
    imports: [
        RouterLink,
        FormsModule,
        TranslateDirective,
        ArtemisTranslatePipe,
        FaIconComponent,
        TumAetUiButtonDirective,
        TumAetUiSearchFieldComponent,
        TumAetUiSelectComponent,
        TumAetUiTableDirective,
        TumAetUiTableSortableColumnComponent,
        TumAetUiToggleSwitchComponent,
        TumAetUiTooltipDirective,
        CourseTitleBarTitleDirective,
    ],
    changeDetection: ChangeDetectionStrategy.OnPush,
})
export class MilestoneAssessmentDashboardComponent {
    private readonly route = inject(ActivatedRoute);
    private readonly destroyRef = inject(DestroyRef);
    private readonly milestoneAssessmentService = inject(MilestoneAssessmentService);
    private readonly alertService = inject(AlertService);
    private readonly sortService = inject(SortService);
    private readonly translateService = inject(TranslateService);

    protected readonly courseId = signal<number>(0);
    protected readonly groupId = signal<number>(0);
    protected readonly students = signal<MilestoneAssessmentStudent[]>([]);
    protected readonly isLoading = signal(true);

    /**
     * The column headers, taken from the first row rather than fetched separately: every row carries the group's
     * exercises in the same order, including the ones a given student never started.
     */
    protected readonly exerciseColumns = computed<MilestoneAssessmentExercise[]>(() => this.students()[0]?.exercises ?? []);

    /** Matched case-insensitively against each student's name, login and tutorial groups. */
    protected readonly searchTerm = signal('');
    /** Whether to hide every student who has nothing left to assess. */
    protected readonly openOnly = signal(false);
    protected readonly sortField = signal('student');
    protected readonly sortAscending = signal(true);

    /** Re-evaluates the translated filter labels when the language changes. */
    private readonly languageChange = toSignal(this.translateService.onLangChange);

    /** "All", "No tutorial group", then every tutorial group a listed student is in, in natural order. */
    protected readonly tutorialGroupOptions = computed<{ label: string; value: string }[]>(() => {
        this.languageChange();
        const titles = this.sortService.sortByFunction([...new Set(this.students().flatMap((student) => student.tutorialGroups ?? []))], (title) => title, true);
        return [
            { label: this.translateService.instant('artemisApp.milestoneAssessment.dashboard.allTutorialGroups'), value: ALL_TUTORIAL_GROUPS },
            { label: this.translateService.instant('artemisApp.milestoneAssessment.dashboard.noTutorialGroupFilter'), value: NO_TUTORIAL_GROUP },
            ...titles.map((title) => ({ label: title, value: title })),
        ];
    });

    /** Falls back to all tutorial groups when a reload drops the selected one, rather than leaving an empty table behind. */
    protected readonly tutorialGroupFilter = linkedSignal<{ value: string }[], string>({
        source: this.tutorialGroupOptions,
        computation: (options, previous) => (previous && options.some((option) => option.value === previous.value) ? previous.value : ALL_TUTORIAL_GROUPS),
    });

    /** The rows the table shows: the students passing every filter, in the chosen order. */
    protected readonly visibleStudents = computed<MilestoneAssessmentStudent[]>(() => {
        const term = this.searchTerm().trim().toLowerCase();
        const tutorialGroup = this.tutorialGroupFilter();
        const openOnly = this.openOnly();
        const filtered = this.students().filter((student) => {
            const tutorialGroups = student.tutorialGroups ?? [];
            if (term && ![student.studentName ?? '', student.studentLogin, ...tutorialGroups].some((text) => text.toLowerCase().includes(term))) {
                return false;
            }
            if (tutorialGroup === NO_TUTORIAL_GROUP ? tutorialGroups.length > 0 : tutorialGroup !== ALL_TUTORIAL_GROUPS && !tutorialGroups.includes(tutorialGroup)) {
                return false;
            }
            return !openOnly || student.exercises.some((exercise) => this.cellState(exercise) === 'required');
        });
        return this.sortService.sortByFunction(filtered, this.sortKey(this.sortField()), this.sortAscending());
    });

    protected readonly faCheck = faCheck;
    protected readonly faQuestion = faQuestion;
    protected readonly faMinus = faMinus;

    constructor() {
        this.route.params.pipe(takeUntilDestroyed(this.destroyRef)).subscribe((params) => {
            this.courseId.set(Number(params['courseId']));
            this.groupId.set(Number(params['groupId']));
            this.load();
        });
    }

    private load(): void {
        this.isLoading.set(true);
        this.milestoneAssessmentService
            .getAssessmentDashboard(this.courseId(), this.groupId())
            .pipe(takeUntilDestroyed(this.destroyRef))
            .subscribe({
                next: (students) => {
                    this.students.set(students);
                    this.isLoading.set(false);
                },
                error: () => {
                    this.isLoading.set(false);
                    this.alertService.error('artemisApp.milestoneAssessment.loadFailed');
                },
            });
    }

    protected onSort(event: TumAetUiTableSortEvent): void {
        this.sortField.set(event.field);
        this.sortAscending.set(event.order === 1);
    }

    protected exerciseSortField(exercise: MilestoneAssessmentExercise): string {
        return EXERCISE_SORT_PREFIX + exercise.exerciseId;
    }

    /** The value a column sorts by. A student without a tutorial group, or an exercise missing from a row, sorts last. */
    private sortKey(field: string): (student: MilestoneAssessmentStudent) => unknown {
        if (field.startsWith(EXERCISE_SORT_PREFIX)) {
            const exerciseId = Number(field.slice(EXERCISE_SORT_PREFIX.length));
            return (student) => {
                const exercise = student.exercises.find((candidate) => candidate.exerciseId === exerciseId);
                return exercise ? CELL_STATE_RANK[this.cellState(exercise)] : undefined;
            };
        }
        switch (field) {
            case 'tutorialGroup':
                return (student) => student.tutorialGroups?.[0];
            case 'progress':
                return (student) => {
                    const assessable = this.manuallyAssessable(student).length;
                    return assessable ? this.assessedCount(student) / assessable : undefined;
                };
            default:
                return (student) => student.studentName || student.studentLogin;
        }
    }

    protected assessmentLink(student: MilestoneAssessmentStudent): (string | number)[] {
        return ['/course-management', this.courseId(), 'milestone-exercise-groups', this.groupId(), 'assessment', student.studentLogin];
    }

    /**
     * What a tutor still has to do for one cell: nothing without a submission, or once it is assessed. A quiz is graded
     * automatically, so a submitted quiz counts as assessed.
     */
    protected cellState(exercise: MilestoneAssessmentExercise): MilestoneAssessmentCellState {
        if (!exercise.submissionId) {
            return 'noSubmission';
        }
        return exercise.assessed || exercise.exerciseType === ExerciseType.QUIZ ? 'assessed' : 'required';
    }

    /** How many of a student's exercises are already assessed, which is what tells a tutor where to spend their time. */
    protected assessedCount(student: MilestoneAssessmentStudent): number {
        return this.manuallyAssessable(student).filter((exercise) => exercise.assessed).length;
    }

    /** The exercises a tutor grades by hand. A quiz is graded automatically, so counting it would make progress unreachable. */
    protected manuallyAssessable(student: MilestoneAssessmentStudent): MilestoneAssessmentExercise[] {
        return student.exercises.filter((exercise) => exercise.exerciseType !== ExerciseType.QUIZ);
    }
}
