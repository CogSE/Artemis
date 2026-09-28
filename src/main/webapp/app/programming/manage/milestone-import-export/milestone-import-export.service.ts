import { HttpClient, HttpHeaders, HttpParams, HttpResponse } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { ExerciseVariantGroupDTO } from 'app/course/manage/exercises/exercise-variant-group.service';

/** The name of the manifest at the root of a milestone archive (mirrors the server-side constant). */
export const MILESTONE_DETAILS_FILE_NAME = 'Milestone-Details.json';

/**
 * The largest milestone archive the import accepts (mirrors the server-side {@code MAX_IMPORT_ARCHIVE_SIZE}). It is above
 * the limit of every other upload, since the archive carries the full history of the milestone's repositories.
 */
export const MAX_MILESTONE_IMPORT_FILE_SIZE = 100 * 1024 * 1024;

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
    /** The group's members that are not user stories (quiz, text, modeling and file upload exercises). */
    otherExercises?: { title?: string; type?: string }[];
}

/**
 * Exports a milestone exercise group (its milestone exercise with the shared repositories plus every exercise of the group) into
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

    /**
     * Uploads a milestone archive and creates the milestone group it describes in the course. The archive is sent as the
     * raw request body rather than as a multipart upload, so the multipart limit of the other uploads does not apply to it.
     */
    importMilestoneGroup(courseId: number, file: File, options: MilestoneImportOptions): Observable<ExerciseVariantGroupDTO> {
        let params = new HttpParams().set('keepDates', String(options.keepDates));
        if (options.title) {
            params = params.set('title', options.title);
        }
        if (options.shortName) {
            params = params.set('shortName', options.shortName);
        }
        return this.http.post<ExerciseVariantGroupDTO>(`${this.resourceUrl(courseId)}/import-from-file`, file, {
            params,
            headers: new HttpHeaders({ 'Content-Type': 'application/zip' }),
        });
    }
}
