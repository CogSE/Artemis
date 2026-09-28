import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { HttpHeaders, HttpResponse } from '@angular/common/http';
import { TranslateService } from '@ngx-translate/core';
import { MockProvider } from 'ng-mocks';
import { of, throwError } from 'rxjs';
import { MockTranslateService } from 'test/helpers/mocks/service/mock-translate.service';
import { AlertService } from 'app/foundation/service/alert.service';
import * as downloadUtil from 'app/foundation/util/download.util';
import { ExerciseVariantGroupService } from 'app/course/manage/exercises/exercise-variant-group.service';
import { MilestoneExportDialogComponent } from 'app/programming/manage/milestone-import-export/milestone-export-dialog.component';
import { MilestoneImportExportService } from 'app/programming/manage/milestone-import-export/milestone-import-export.service';

describe('MilestoneExportDialogComponent', () => {
    let fixture: ComponentFixture<MilestoneExportDialogComponent>;
    let component: MilestoneExportDialogComponent;
    let groupService: ExerciseVariantGroupService;
    let importExportService: MilestoneImportExportService;
    let alertService: AlertService;

    beforeEach(async () => {
        await TestBed.configureTestingModule({
            imports: [MilestoneExportDialogComponent],
            providers: [
                MockProvider(ExerciseVariantGroupService),
                MockProvider(MilestoneImportExportService),
                MockProvider(AlertService),
                { provide: TranslateService, useClass: MockTranslateService },
            ],
        }).compileComponents();

        fixture = TestBed.createComponent(MilestoneExportDialogComponent);
        component = fixture.componentInstance;
        fixture.componentRef.setInput('courseId', 42);
        groupService = TestBed.inject(ExerciseVariantGroupService);
        importExportService = TestBed.inject(MilestoneImportExportService);
        alertService = TestBed.inject(AlertService);
    });

    it('loads the milestone groups of the course when opened', () => {
        const loadSpy = vi.spyOn(groupService, 'getMilestoneGroupsForCourse').mockReturnValue(of([{ id: 1, title: 'Sprint 1', type: 'milestone' }]));

        component.visible.set(true);
        fixture.detectChanges();

        expect(loadSpy).toHaveBeenCalledWith(42);
        expect(component.milestoneGroups()).toHaveLength(1);
    });

    it('downloads the archive of the chosen group', () => {
        const response = new HttpResponse({ body: new Blob(['zip']), headers: new HttpHeaders({ filename: 'Milestone.zip' }) });
        const exportSpy = vi.spyOn(importExportService, 'exportMilestoneGroup').mockReturnValue(of(response));
        const downloadSpy = vi.spyOn(downloadUtil, 'downloadZipFileFromResponse').mockImplementation(() => {});
        const successSpy = vi.spyOn(alertService, 'success');

        component.exportGroup({ id: 1, title: 'Sprint 1' });

        expect(exportSpy).toHaveBeenCalledWith(42, 1);
        expect(downloadSpy).toHaveBeenCalledWith(response);
        expect(successSpy).toHaveBeenCalledOnce();
        expect(component.exportingGroupId()).toBeUndefined();
    });

    it('reports a failed export', () => {
        vi.spyOn(importExportService, 'exportMilestoneGroup').mockReturnValue(throwError(() => new Error('failed')));
        const errorSpy = vi.spyOn(alertService, 'error');

        component.exportGroup({ id: 1, title: 'Sprint 1' });

        expect(errorSpy).toHaveBeenCalledWith('artemisApp.milestoneImportExport.export.failed', { title: 'Sprint 1' });
        expect(component.exportingGroupId()).toBeUndefined();
    });
});
