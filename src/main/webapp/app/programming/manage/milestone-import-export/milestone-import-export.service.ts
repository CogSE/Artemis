import { HttpClient, HttpResponse } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { ExerciseVariantGroupDTO } from 'app/course/manage/exercises/exercise-variant-group.service';

/** The name of the manifest at the root of a milestone archive (mirrors the server-side constant). */
export const MILESTONE_DETAILS_FILE_NAME = 'Milestone-Details.json';

/** Options for importing a milestone archive (mirrors the server-side {@code MilestoneImportOptionsDTO}). */
export interface MilestoneImportOptions {
    /** Title of the imported milestone; left out to keep the archive's one (made unique if it is taken). */
    title?: string;
    /** Short name of the imported milestone; left out to keep the archive's one (made unique if it is taken). */
    shortName?: string;
    /** Whether the exported timeline is kept; otherwise the imported group starts without dates. */
    keepDates: boolean;
}

/** The part of the archive manifest the import dialog reads to prefill its form. */
export interface MilestoneExportDetails {
    formatVersion?: number;
    groupTitle?: string;
    userStories?: { title?: string }[];
}

/**
 * Exports a milestone exercise group (its milestone exercise with the shared repositories plus all user stories) into
 * one archive, and imports such an archive into a course.
 */
@Injectable({ providedIn: 'root' })
export class MilestoneImportExportService {
    private readonly http = inject(HttpClient);

    private resourceUrl(courseId: number): string {
        return `api/programming/courses/${courseId}/milestone-exercise-groups`;
    }

    /** Downloads the archive of the given milestone group. */
    exportMilestoneGroup(courseId: number, groupId: number): Observable<HttpResponse<Blob>> {
        return this.http.get(`${this.resourceUrl(courseId)}/${groupId}/export`, { observe: 'response', responseType: 'blob' });
    }

    /** Uploads a milestone archive and creates the milestone group it describes in the course. */
    importMilestoneGroup(courseId: number, file: File, options: MilestoneImportOptions): Observable<ExerciseVariantGroupDTO> {
        const formData = new FormData();
        formData.append('file', file, file.name);
        formData.append('options', new Blob([JSON.stringify(options)], { type: 'application/json' }));
        return this.http.post<ExerciseVariantGroupDTO>(`${this.resourceUrl(courseId)}/import-from-file`, formData);
    }
}
