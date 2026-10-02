import { ChangeDetectionStrategy, Component, computed, effect, inject, input, model, output, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { strFromU8 } from 'fflate';
import { TumAetUiButtonComponent, TumAetUiCheckboxComponent, TumAetUiDialogComponent, TumAetUiInputDirective, TumAetUiMessageComponent } from '@tumaet/ui-angular';
import { AlertService } from 'app/foundation/service/alert.service';
import { readZipEntries } from 'app/foundation/util/zip.util';
import { parseJson } from 'app/foundation/util/json.util';
import { ArtemisTranslatePipe } from 'app/foundation/pipes/artemis-translate.pipe';
import { TranslateDirective } from 'app/foundation/language/translate.directive';
import { ExerciseVariantGroupDTO } from 'app/course/manage/exercises/exercise-variant-group.service';
import { MAX_MILESTONE_IMPORT_FILE_SIZE, MILESTONE_DETAILS_FILE_NAME, MilestoneExportDetails, MilestoneImportExportService } from './milestone-import-export.service';

/** Mirrors the server's short name rule for programming exercises. */
const SHORT_NAME_PATTERN = /^[a-zA-Z][a-zA-Z0-9]{2,}$/;
const SHORT_NAME_MAX_LENGTH = 36;

/**
 * Imports a milestone archive (created by the milestone export) into the course: the milestone exercise with its
 * repositories, the group, and all of its user stories. The archive's manifest is read in the browser to prefill the
 * title and to show what the import is going to create.
 */
@Component({
    selector: 'jhi-milestone-import-dialog',
    templateUrl: './milestone-import-dialog.component.html',
    imports: [
        FormsModule,
        TumAetUiDialogComponent,
        TumAetUiButtonComponent,
        TumAetUiCheckboxComponent,
        TumAetUiInputDirective,
        TumAetUiMessageComponent,
        ArtemisTranslatePipe,
        TranslateDirective,
    ],
    changeDetection: ChangeDetectionStrategy.OnPush,
})
export class MilestoneImportDialogComponent {
    private readonly milestoneImportExportService = inject(MilestoneImportExportService);
    private readonly alertService = inject(AlertService);

    readonly visible = model<boolean>(false);
    readonly courseId = input.required<number>();
    /** Emits the imported group once the import has finished. */
    readonly imported = output<ExerciseVariantGroupDTO>();

    readonly file = signal<File | undefined>(undefined);
    readonly details = signal<MilestoneExportDetails | undefined>(undefined);
    readonly title = signal('');
    readonly shortName = signal('');
    readonly keepDates = signal(false);
    readonly importing = signal(false);

    readonly userStoryCount = computed(() => this.details()?.userStories?.length ?? 0);
    readonly otherExerciseCount = computed(() => this.details()?.otherExercises?.length ?? 0);
    readonly isShortNameValid = computed(() => {
        const shortName = this.shortName().trim();
        return shortName === '' || (SHORT_NAME_PATTERN.test(shortName) && shortName.length <= SHORT_NAME_MAX_LENGTH);
    });
    readonly canImport = computed(() => this.file() !== undefined && this.details() !== undefined && this.isShortNameValid() && !this.importing());

    constructor() {
        // Every opening starts from a clean form, so a previous archive does not linger.
        effect(() => {
            if (this.visible()) {
                this.reset();
            }
        });
    }

    private reset(): void {
        this.file.set(undefined);
        this.details.set(undefined);
        this.title.set('');
        this.shortName.set('');
        this.keepDates.set(false);
        this.importing.set(false);
    }

    /** Takes the selected archive and reads its manifest, rejecting anything that is not a milestone export. */
    async onFileSelected(event: Event): Promise<void> {
        const fileInput = event.target as HTMLInputElement;
        const selected = fileInput.files?.[0];
        this.file.set(undefined);
        this.details.set(undefined);
        if (!selected) {
            return;
        }
        if (!selected.name.toLowerCase().endsWith('.zip')) {
            this.alertService.error('artemisApp.programmingExercise.importFromFile.fileExtensionError');
            return;
        }
        if (selected.size > MAX_MILESTONE_IMPORT_FILE_SIZE) {
            this.alertService.error('artemisApp.milestoneImportExport.import.fileTooBig', { fileName: selected.name, maxSize: MAX_MILESTONE_IMPORT_FILE_SIZE / (1024 * 1024) });
            return;
        }
        try {
            const entries = await readZipEntries(selected);
            const detailsFile = Object.keys(entries).find((name) => name === MILESTONE_DETAILS_FILE_NAME || name.endsWith('/' + MILESTONE_DETAILS_FILE_NAME));
            if (!detailsFile) {
                this.alertService.error('artemisApp.milestoneImportExport.import.notAMilestoneArchive');
                return;
            }
            const details = parseJson<MilestoneExportDetails>(strFromU8(entries[detailsFile]));
            this.details.set(details);
            this.title.set(details.groupTitle ?? '');
            this.file.set(selected);
        } catch {
            this.alertService.error('artemisApp.milestoneImportExport.import.notAMilestoneArchive');
        }
    }

    importArchive(): void {
        const file = this.file();
        if (!file || !this.canImport()) {
            return;
        }
        this.importing.set(true);
        const title = this.title().trim();
        const shortName = this.shortName().trim();
        this.milestoneImportExportService
            .importMilestoneGroup(this.courseId(), file, {
                title: title === '' || title === this.details()?.groupTitle ? undefined : title,
                shortName: shortName === '' ? undefined : shortName,
                keepDates: this.keepDates(),
            })
            .subscribe({
                // Success and error alerts come from the response headers through the global HTTP interceptors.
                next: (group) => {
                    this.importing.set(false);
                    this.imported.emit(group);
                    this.visible.set(false);
                },
                error: () => this.importing.set(false),
            });
    }
}
