import { ChangeDetectionStrategy, Component, effect, inject, input, model, signal } from '@angular/core';
import { FaIconComponent } from '@fortawesome/angular-fontawesome';
import { faFileExport, faFlagCheckered } from '@fortawesome/free-solid-svg-icons';
import { TumAetUiButtonComponent, TumAetUiDialogComponent, TumAetUiEmptyStateComponent } from '@tumaet/ui-angular';
import { ArtemisTranslatePipe } from 'app/foundation/pipes/artemis-translate.pipe';
import { TranslateDirective } from 'app/foundation/language/translate.directive';
import { downloadZipFileFromResponse } from 'app/foundation/util/download.util';
import { AlertService } from 'app/foundation/service/alert.service';
import { ExerciseVariantGroupDTO, ExerciseVariantGroupService } from 'app/course/manage/exercises/exercise-variant-group.service';
import { MilestoneImportExportService } from './milestone-import-export.service';

/**
 * Lists the course's milestone groups and downloads the archive of the chosen one: its milestone exercise with the
 * shared repositories and all of its user stories, ready to be imported into another course or instance.
 */
@Component({
    selector: 'jhi-milestone-export-dialog',
    templateUrl: './milestone-export-dialog.component.html',
    imports: [TumAetUiDialogComponent, TumAetUiButtonComponent, TumAetUiEmptyStateComponent, FaIconComponent, ArtemisTranslatePipe, TranslateDirective],
    changeDetection: ChangeDetectionStrategy.OnPush,
})
export class MilestoneExportDialogComponent {
    private readonly exerciseVariantGroupService = inject(ExerciseVariantGroupService);
    private readonly milestoneImportExportService = inject(MilestoneImportExportService);
    private readonly alertService = inject(AlertService);

    protected readonly faFileExport = faFileExport;
    protected readonly faFlagCheckered = faFlagCheckered;

    readonly visible = model<boolean>(false);
    readonly courseId = input.required<number>();

    readonly milestoneGroups = signal<ExerciseVariantGroupDTO[]>([]);
    readonly loading = signal(false);
    /** The id of the group whose archive is being built; the server clones every repository, which takes a moment. */
    readonly exportingGroupId = signal<number | undefined>(undefined);

    constructor() {
        effect(() => {
            if (this.visible()) {
                this.loadMilestoneGroups(this.courseId());
            }
        });
    }

    private loadMilestoneGroups(courseId: number): void {
        this.loading.set(true);
        this.exerciseVariantGroupService.getMilestoneGroupsForCourse(courseId).subscribe({
            next: (groups) => {
                this.milestoneGroups.set(groups);
                this.loading.set(false);
            },
            error: () => this.loading.set(false),
        });
    }

    exportGroup(group: ExerciseVariantGroupDTO): void {
        if (group.id === undefined || this.exportingGroupId() !== undefined) {
            return;
        }
        this.exportingGroupId.set(group.id);
        this.milestoneImportExportService.exportMilestoneGroup(this.courseId(), group.id).subscribe({
            next: (response) => {
                downloadZipFileFromResponse(response);
                this.alertService.success('artemisApp.milestoneImportExport.export.success', { title: group.title });
                this.exportingGroupId.set(undefined);
            },
            error: () => {
                this.alertService.error('artemisApp.milestoneImportExport.export.failed', { title: group.title });
                this.exportingGroupId.set(undefined);
            },
        });
    }
}
