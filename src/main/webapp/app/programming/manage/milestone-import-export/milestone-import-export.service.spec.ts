import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideHttpClient } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { MilestoneImportExportService } from 'app/programming/manage/milestone-import-export/milestone-import-export.service';

describe('MilestoneImportExportService', () => {
    let service: MilestoneImportExportService;
    let httpMock: HttpTestingController;
    const baseUrl = 'api/programming/courses/42/milestone-exercise-groups';

    beforeEach(() => {
        TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
        service = TestBed.inject(MilestoneImportExportService);
        httpMock = TestBed.inject(HttpTestingController);
    });

    afterEach(() => httpMock.verify());

    it('downloads the archive of a milestone group as a blob', () => {
        let status: number | undefined;
        service.exportMilestoneGroup(42, 7).subscribe((response) => (status = response.status));

        const req = httpMock.expectOne({ method: 'GET', url: `${baseUrl}/7/export` });
        expect(req.request.responseType).toBe('blob');
        req.flush(new Blob(['zip']));
        expect(status).toBe(200);
    });

    it('uploads the archive together with the import options', async () => {
        const file = new File(['zip'], 'milestone.zip');
        let importedTitle: string | undefined;
        service.importMilestoneGroup(42, file, { title: 'Sprint 1', keepDates: true }).subscribe((group) => (importedTitle = group.title));

        const req = httpMock.expectOne({ method: 'POST', url: `${baseUrl}/import-from-file` });
        const body = req.request.body as FormData;
        expect((body.get('file') as File).name).toBe('milestone.zip');
        const options = JSON.parse(await (body.get('options') as Blob).text());
        expect(options).toEqual({ title: 'Sprint 1', keepDates: true });
        req.flush({ id: 3, title: 'Sprint 1' });
        expect(importedTitle).toBe('Sprint 1');
    });
});
