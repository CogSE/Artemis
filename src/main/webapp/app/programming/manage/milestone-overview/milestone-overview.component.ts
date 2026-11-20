import dayjs from 'dayjs/esm';
import { ChangeDetectionStrategy, Component, DestroyRef, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { TumAetUiButtonDirective, TumAetUiPanelComponent } from '@tumaet/ui-angular';
import { FaIconComponent } from '@fortawesome/angular-fontawesome';
import { faEye, faListAlt, faListCheck } from '@fortawesome/free-solid-svg-icons';
import { TranslateDirective } from 'app/foundation/language/translate.directive';
import { ArtemisDatePipe } from 'app/foundation/pipes/artemis-date.pipe';
import { AlertService } from 'app/foundation/service/alert.service';
import { getMilestoneAssessmentDashboardLink } from 'app/foundation/util/navigation.utils';
import { CourseTitleBarTitleDirective } from 'app/course/shared/directives/course-title-bar-title.directive';
import { ExerciseVariantGroupDTO, ExerciseVariantGroupService } from 'app/course/manage/exercises/exercise-variant-group.service';

/**
 * Every milestone group of a course at a glance: its timeline, its points and how many exercises it holds, with the way
 * into its assessment dashboard. Unlike the group view on the exercise page it leaves the members out - a tutor coming
 * here wants to grade a milestone, not manage its user stories.
 */
@Component({
    selector: 'jhi-milestone-overview',
    templateUrl: './milestone-overview.component.html',
    imports: [RouterLink, FaIconComponent, TranslateDirective, ArtemisDatePipe, TumAetUiButtonDirective, TumAetUiPanelComponent, CourseTitleBarTitleDirective],
    changeDetection: ChangeDetectionStrategy.OnPush,
})
export class MilestoneOverviewComponent {
    private readonly route = inject(ActivatedRoute);
    private readonly destroyRef = inject(DestroyRef);
    private readonly exerciseVariantGroupService = inject(ExerciseVariantGroupService);
    private readonly alertService = inject(AlertService);

    protected readonly courseId = signal<number>(0);
    protected readonly groups = signal<ExerciseVariantGroupDTO[]>([]);
    protected readonly isLoading = signal(true);

    /** In the order the milestones run: by release date, the ones without one last, then by title in natural order. */
    protected readonly sortedGroups = computed(() =>
        [...this.groups()].sort((first, second) => {
            const firstRelease = first.releaseDate?.valueOf() ?? Number.POSITIVE_INFINITY;
            const secondRelease = second.releaseDate?.valueOf() ?? Number.POSITIVE_INFINITY;
            if (firstRelease !== secondRelease) {
                return firstRelease < secondRelease ? -1 : 1;
            }
            return (first.title ?? '').localeCompare(second.title ?? '', undefined, { numeric: true });
        }),
    );

    protected readonly faEye = faEye;
    protected readonly faListCheck = faListCheck;
    protected readonly faListAlt = faListAlt;

    constructor() {
        this.route.params.pipe(takeUntilDestroyed(this.destroyRef)).subscribe((params) => {
            this.courseId.set(Number(params['courseId']));
            this.load();
        });
    }

    private load(): void {
        this.isLoading.set(true);
        this.exerciseVariantGroupService
            .getMilestoneGroupsForCourse(this.courseId())
            .pipe(takeUntilDestroyed(this.destroyRef))
            .subscribe({
                next: (groups) => {
                    this.groups.set(groups);
                    this.isLoading.set(false);
                },
                error: () => {
                    this.isLoading.set(false);
                    this.alertService.error('artemisApp.milestoneOverview.loadFailed');
                },
            });
    }

    /** The group's dates in the order they happen, each with the label the exercise page uses for it. */
    protected timeline(group: ExerciseVariantGroupDTO): { label: string; value?: dayjs.Dayjs }[] {
        return [
            { label: 'artemisApp.exercise.release', value: group.releaseDate },
            { label: 'artemisApp.exercise.start', value: group.startDate },
            { label: 'artemisApp.exercise.due', value: group.dueDate },
            { label: 'artemisApp.exercise.assessmentDue', value: group.assessmentDueDate },
        ];
    }

    protected assessmentDashboardLink(group: ExerciseVariantGroupDTO): string[] {
        return getMilestoneAssessmentDashboardLink(this.courseId(), group.id!);
    }

    /** The anchor milestone exercise's detail page, where the group's shared repositories and build status live. */
    protected detailsLink(milestoneExerciseId: number): (string | number)[] {
        return ['/course-management', this.courseId(), 'milestone-exercise-groups', milestoneExerciseId];
    }

    /**
     * The participations of the anchor milestone exercise: one per student who started the milestone, with their builds.
     * Managed under the programming routes, like the milestone's own detail page links them.
     */
    protected participationsLink(milestoneExerciseId: number): (string | number)[] {
        return ['/course-management', this.courseId(), 'programming-exercises', milestoneExerciseId, 'participations'];
    }
}
