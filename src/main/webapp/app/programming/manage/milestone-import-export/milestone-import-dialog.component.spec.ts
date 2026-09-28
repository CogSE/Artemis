import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { TranslateService } from '@ngx-translate/core';
import { MockProvider } from 'ng-mocks';
import { of, throwError } from 'rxjs';
import { MockTranslateService } from 'test/helpers/mocks/service/mock-translate.service';
import { AlertService } from 'app/foundation/service/alert.service';
import { ZipBuilder } from 'app/foundation/util/zip.util';
import { MilestoneImportDialogComponent } from 'app/programming/manage/milestone-import-export/milestone-import-dialog.component';
import {
    MAX_MILESTONE_IMPORT_FILE_SIZE,
    MILESTONE_DETAILS_FILE_NAME,
    MilestoneImportExportService,
} from 'app/programming/manage/milestone-import-export/milestone-import-export.service';

describe('MilestoneImportDialogComponent', () => {
    let fixture: ComponentFixture<MilestoneImportDialogComponent>;
    let component: MilestoneImportDialogComponent;
    let importExportService: MilestoneImportExportService;
    let alertService: AlertService;

    const buildArchive = async (entries: Record<string, string>, name = 'milestone.zip'): Promise<File> => {
        const zip = new ZipBuilder();
        Object.entries(entries).forEach(([entryName, content]) => zip.file(entryName, content));
        return new File([await zip.generateBlob()], name);
    };

    const selectFile = async (file: File) => {
        await component.onFileSelected({ target: { files: [file] } } as unknown as Event);
    };

    beforeEach(async () => {
        await TestBed.configureTestingModule({
            imports: [MilestoneImportDialogComponent],
            providers: [MockProvider(MilestoneImportExportService), MockProvider(AlertService), { provide: TranslateService, useClass: MockTranslateService }],
        }).compileComponents();

        fixture = TestBed.createComponent(MilestoneImportDialogComponent);
        component = fixture.componentInstance;
        fixture.componentRef.setInput('courseId', 42);
        importExportService = TestBed.inject(MilestoneImportExportService);
        alertService = TestBed.inject(AlertService);
    });

    it('reads the manifest of a milestone archive and prefills the title', async () => {
        const details = { formatVersion: 1, groupTitle: 'Sprint 1', userStories: [{ title: 'Login' }, { title: 'Logout' }] };
        await selectFile(await buildArchive({ [MILESTONE_DETAILS_FILE_NAME]: JSON.stringify(details), 'Milestone-Exercise.zip': 'nested' }));

        expect(component.title()).toBe('Sprint 1');
        expect(component.userStoryCount()).toBe(2);
        expect(component.canImport()).toBe(true);
    });

    it('counts the user stories and the other exercises of the archive', async () => {
        const details = {
            groupTitle: 'Sprint 1',
            userStories: [{ title: 'Login' }],
            otherExercises: [
                { title: 'Quiz', type: 'quiz' },
                { title: 'Essay', type: 'text' },
            ],
        };
        await selectFile(await buildArchive({ [MILESTONE_DETAILS_FILE_NAME]: JSON.stringify(details) }));

        expect(component.userStoryCount()).toBe(1);
        expect(component.otherExerciseCount()).toBe(2);
    });

    it('accepts archives above the regular upload limit but rejects those above 100 MB', async () => {
        const errorSpy = vi.spyOn(alertService, 'error');
        const tooLarge = new File(['zip'], 'milestone.zip');
        Object.defineProperty(tooLarge, 'size', { value: MAX_MILESTONE_IMPORT_FILE_SIZE + 1 });
        await selectFile(tooLarge);

        expect(errorSpy).toHaveBeenCalledWith('artemisApp.milestoneImportExport.import.fileTooBig', { fileName: 'milestone.zip', maxSize: 100 });
        expect(component.file()).toBeUndefined();
    });

    it('rejects an archive without a milestone manifest', async () => {
        const errorSpy = vi.spyOn(alertService, 'error');
        await selectFile(await buildArchive({ 'Exercise-Details-Something.json': '{}' }));

        expect(errorSpy).toHaveBeenCalledWith('artemisApp.milestoneImportExport.import.notAMilestoneArchive');
        expect(component.canImport()).toBe(false);
    });

    it('rejects a file that is not a zip archive', async () => {
        const errorSpy = vi.spyOn(alertService, 'error');
        await selectFile(new File(['text'], 'notes.txt'));

        expect(errorSpy).toHaveBeenCalledWith('artemisApp.programmingExercise.importFromFile.fileExtensionError');
        expect(component.file()).toBeUndefined();
    });

    it('blocks the import for an invalid short name', async () => {
        await selectFile(await buildArchive({ [MILESTONE_DETAILS_FILE_NAME]: JSON.stringify({ groupTitle: 'Sprint 1' }) }));
        component.shortName.set('1-invalid');

        expect(component.isShortNameValid()).toBe(false);
        expect(component.canImport()).toBe(false);
    });

    it('imports the archive, sending only the overrides the author changed', async () => {
        await selectFile(await buildArchive({ [MILESTONE_DETAILS_FILE_NAME]: JSON.stringify({ groupTitle: 'Sprint 1' }) }));
        const importSpy = vi.spyOn(importExportService, 'importMilestoneGroup').mockReturnValue(of({ id: 3, title: 'Sprint 1' }));
        const imported = vi.fn();
        component.imported.subscribe(imported);
        component.visible.set(true);
        component.shortName.set('sprint2');
        component.keepDates.set(true);

        component.importArchive();

        expect(importSpy).toHaveBeenCalledWith(42, component.file(), { title: undefined, shortName: 'sprint2', keepDates: true });
        expect(imported).toHaveBeenCalledWith({ id: 3, title: 'Sprint 1' });
        expect(component.visible()).toBe(false);
    });

    it('stays open when the import fails', async () => {
        await selectFile(await buildArchive({ [MILESTONE_DETAILS_FILE_NAME]: JSON.stringify({ groupTitle: 'Sprint 1' }) }));
        vi.spyOn(importExportService, 'importMilestoneGroup').mockReturnValue(throwError(() => new Error('failed')));
        component.visible.set(true);

        component.importArchive();

        expect(component.importing()).toBe(false);
        expect(component.visible()).toBe(true);
    });
});
