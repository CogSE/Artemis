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

    it('uploads the archive as the raw request body with the options as parameters', () => {
        const file = new File(['zip'], 'milestone.zip');
        let importedTitle: string | undefined;
        service.importMilestoneGroup(42, file, { title: 'Sprint 1', keepDates: true }).subscribe((group) => (importedTitle = group.title));

        const req = httpMock.expectOne((request) => request.method === 'POST' && request.url === `${baseUrl}/import-from-file`);
        expect(req.request.body).toBe(file);
        expect(req.request.headers.get('Content-Type')).toBe('application/zip');
        expect(req.request.params.get('title')).toBe('Sprint 1');
        expect(req.request.params.get('keepDates')).toBe('true');
        expect(req.request.params.has('shortName')).toBe(false);
        req.flush({ id: 3, title: 'Sprint 1' });
        expect(importedTitle).toBe('Sprint 1');
    });
});
