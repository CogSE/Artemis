import { HttpClient } from '@angular/common/http';
import { Service, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { UserStoryEffort } from 'app/exercise/shared/entities/participation/programming-exercise-student-participation.model';

/** One started user story with the effort reported for it (mirrors the server {@code UserStoryEffortStatusDTO}). */
export interface UserStoryEffortStatus extends UserStoryEffort {
    exerciseId: number;
}

/**
 * Reads the effort the current user has reported for a user story exercise - summed from their task board rather
 * than entered by hand (mirrors the server {@code UserStoryEffortResource}).
 *
 * Every endpoint acts on the caller's own participation only, except reading one specific participation for the
 * tutor assessing it.
 */
@Service()
export class UserStoryEffortService {
    private readonly http = inject(HttpClient);

    private resourceUrl(exerciseId: number): string {
        return `api/programming/user-story-exercises/${exerciseId}/effort`;
    }

    /** The pair the current user has reported, with unset values omitted by the server. */
    getEffort(exerciseId: number): Observable<UserStoryEffort> {
        return this.http.get<UserStoryEffort>(this.resourceUrl(exerciseId));
    }

    /**
     * Every user story in the course the current user has started, with whatever effort they reported. One request for
     * the whole overview - the pair is not serialized with each participation, because an inverse association there cost
     * a query per participation and broke the dashboard payload.
     */
    getEffortsForCourse(courseId: number): Observable<UserStoryEffortStatus[]> {
        return this.http.get<UserStoryEffortStatus[]>(`api/programming/courses/${courseId}/user-story-efforts`);
    }
}
