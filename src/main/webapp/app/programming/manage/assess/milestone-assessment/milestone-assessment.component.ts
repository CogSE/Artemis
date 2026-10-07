import { ChangeDetectionStrategy, Component, DestroyRef, computed, inject, signal, viewChild } from '@angular/core';
import { DecimalPipe } from '@angular/common';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { ActivatedRoute } from '@angular/router';
import { TranslateService } from '@ngx-translate/core';
import { TumAetUiTabComponent, TumAetUiTabListComponent, TumAetUiTabValue, TumAetUiTabsComponent } from '@tumaet/ui-angular';
import { ArtemisTranslatePipe } from 'app/foundation/pipes/artemis-translate.pipe';
import { TranslateDirective } from 'app/foundation/language/translate.directive';
import { AlertService } from 'app/foundation/service/alert.service';
import { CodeEditorTutorAssessmentContainerComponent } from 'app/programming/manage/assess/code-editor-tutor-assessment-container/code-editor-tutor-assessment-container.component';
import { CourseTitleBarTitleDirective } from 'app/course/shared/directives/course-title-bar-title.directive';
import { ExerciseType } from 'app/exercise/shared/entities/exercise/exercise.model';
import { FileUploadAssessmentComponent } from 'app/fileupload/manage/assess/file-upload-assessment.component';
import { ModelingAssessmentEditorComponent } from 'app/modeling/manage/assess/modeling-assessment-editor/modeling-assessment-editor.component';
import { TextSubmissionAssessmentComponent } from 'app/text/manage/assess/submission-assessment/text-submission-assessment.component';
import { getMilestoneAssessmentDashboardLink } from 'app/foundation/util/navigation.utils';
import { MilestoneAssessment, MilestoneAssessmentExercise, MilestoneAssessmentService } from './milestone-assessment.service';
import { MilestoneAssessmentOverviewComponent } from './milestone-assessment-overview.component';
import { Title } from '@angular/platform-browser';

/** The tab value of the group-level first tab; every other tab is keyed by its exercise id. */
const OVERVIEW_TAB = 'milestone';

/**
 * Grades one student's whole milestone: a group-level first tab, then one tab per exercise of the group - the user
 * stories first, then any text, modeling, file upload or quiz exercise, in the order the server sends them. Each tab
 * mounts the assessment editor of its exercise type; a quiz is graded automatically and only shows its score.
 * <p>
 * Only the active exercise's assessment editor is mounted at a time, and that is a hard constraint rather than a
 * preference: `DomainService` is `providedIn: 'root'` and holds a single global domain, so two live editors would
 * fight over which repository the code editor is pointed at. Switching tabs therefore tears the previous editor down,
 * which is also why a switch has to be refused while it holds unsaved feedback.
 * <p>
 * The milestone and the student are named in the course title bar. A tutor must never be in doubt whose work is on screen -
 * unlike every other assessment page, this one is reached by choosing a person rather than by being handed a
 * submission.
 */
@Component({
    selector: 'jhi-milestone-assessment',
    templateUrl: './milestone-assessment.component.html',
    styleUrl: './milestone-assessment.component.scss',
    imports: [
        TranslateDirective,
        ArtemisTranslatePipe,
        TumAetUiTabsComponent,
        TumAetUiTabListComponent,
        TumAetUiTabComponent,
        CourseTitleBarTitleDirective,
        MilestoneAssessmentOverviewComponent,
        CodeEditorTutorAssessmentContainerComponent,
        TextSubmissionAssessmentComponent,
        ModelingAssessmentEditorComponent,
        FileUploadAssessmentComponent,
        DecimalPipe,
    ],
    changeDetection: ChangeDetectionStrategy.OnPush,
})
export class MilestoneAssessmentComponent {
    private readonly route = inject(ActivatedRoute);
    private readonly destroyRef = inject(DestroyRef);
    private readonly milestoneAssessmentService = inject(MilestoneAssessmentService);
    private readonly alertService = inject(AlertService);
    private readonly translateService = inject(TranslateService);
    private readonly titleService = inject(Title);

    protected readonly courseId = signal<number>(0);
    protected readonly groupId = signal<number>(0);
    protected readonly studentLogin = signal<string>('');
    protected readonly assessment = signal<MilestoneAssessment | undefined>(undefined);
    protected readonly isLoading = signal(true);
    protected readonly activeTab = signal<TumAetUiTabValue>(OVERVIEW_TAB);

    protected readonly OVERVIEW_TAB = OVERVIEW_TAB;

    protected readonly ExerciseType = ExerciseType;

    /**
     * Where each mounted editor's "Exercise Dashboard" button leads. A tutor arrives here from the group's dashboard,
     * having chosen a student rather than a submission, so the exercise's own dashboard would drop them out of the
     * milestone they are grading.
     */
    protected readonly assessmentDashboardLink = computed<string[]>(() => getMilestoneAssessmentDashboardLink(this.courseId(), this.groupId()));

    /** The group's exercises in tab order: user stories first, then the rest, as sorted by the server. */
    protected readonly exercises = computed<MilestoneAssessmentExercise[]>(() => this.assessment()?.exercises ?? []);

    /** The exercise the active tab addresses, or undefined while the group-level tab is shown. */
    protected readonly activeExercise = computed<MilestoneAssessmentExercise | undefined>(() => {
        const active = this.activeTab();
        return active === OVERVIEW_TAB ? undefined : this.exercises().find((exercise) => exercise.exerciseId === active);
    });

    /**
     * The next exercise after the active one that a tutor can actually assess: one with a submission, and not a quiz,
     * which is graded automatically.
     */
    protected readonly nextExercise = computed<MilestoneAssessmentExercise | undefined>(() => {
        const exercises = this.exercises();
        const currentIndex = exercises.findIndex((exercise) => exercise.exerciseId === this.activeTab());
        return exercises.slice(currentIndex + 1).find((exercise) => exercise.submissionId !== undefined && exercise.exerciseType !== ExerciseType.QUIZ);
    });

    /**
     * The mounted assessment editor, so a tab switch can ask it whether it holds unsaved feedback. One query per editor
     * type, since the panel mounts whichever the active exercise needs; at most one of them is ever set.
     */
    private readonly programmingEditor = viewChild(CodeEditorTutorAssessmentContainerComponent);
    private readonly textEditor = viewChild(TextSubmissionAssessmentComponent);
    private readonly modelingEditor = viewChild(ModelingAssessmentEditorComponent);
    private readonly fileUploadEditor = viewChild(FileUploadAssessmentComponent);
    private readonly assessmentEditor = computed<{ hasPendingChanges: boolean } | undefined>(
        () => this.programmingEditor() ?? this.textEditor() ?? this.modelingEditor() ?? this.fileUploadEditor(),
    );

    constructor() {
        this.route.params.pipe(takeUntilDestroyed(this.destroyRef)).subscribe((params) => {
            this.courseId.set(Number(params['courseId']));
            this.groupId.set(Number(params['groupId']));
            this.studentLogin.set(params['studentLogin']);
            this.titleService.setTitle(this.studentLogin());
            this.load();
        });
    }

    private load(): void {
        this.isLoading.set(true);
        this.milestoneAssessmentService
            .getAssessmentForStudent(this.courseId(), this.groupId(), this.studentLogin())
            .pipe(takeUntilDestroyed(this.destroyRef))
            .subscribe({
                next: (assessment) => {
                    this.assessment.set(assessment);
                    this.isLoading.set(false);
                },
                error: () => {
                    this.isLoading.set(false);
                    this.alertService.error('artemisApp.milestoneAssessment.loadFailed');
                },
            });
    }

    /**
     * Switches tabs, refusing while the mounted editor holds feedback the tutor has not saved.
     * <p>
     * The panel is destroyed on switch (see the class comment), so leaving without asking would discard that feedback
     * silently. Reuses the confirmation the routed assessment page's deactivate guard shows for the same reason.
     */
    protected onTabChange(value: TumAetUiTabValue): void {
        if (value === undefined || value === this.activeTab()) {
            return;
        }
        if (this.assessmentEditor()?.hasPendingChanges && !window.confirm(this.translateService.instant('artemisApp.programmingAssessment.confirmLeave'))) {
            return;
        }
        this.activeTab.set(value);
    }

    /**
     * Moves to the next exercise instead of fetching another student's submission, which is what an assessment editor's
     * "Assess next" button would otherwise do. Bound into the editor through its {@code overrideNextSubmission} input;
     * the button is hidden once {@link nextExercise} is undefined.
     */
    protected readonly assessNextExercise = (): void => {
        const next = this.nextExercise();
        if (next) {
            // The tab is bound to the numeric id, and tabs match by strict equality: a stringified id selects no tab,
            // and the tab list then falls back to the first one, the overview.
            this.onTabChange(next.exerciseId);
        }
    };
}
