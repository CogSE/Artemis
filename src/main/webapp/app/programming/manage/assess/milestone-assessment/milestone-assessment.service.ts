import { HttpClient } from '@angular/common/http';
import { Service, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { Result } from 'app/exercise/shared/entities/result/result.model';
import { ExerciseType } from 'app/exercise/shared/entities/exercise/exercise.model';

/**
 * One exercise of a milestone group as it stands for a single student (mirrors the server
 * {@code MilestoneAssessmentExerciseDTO}). Most are user stories; a group may also hold text, modeling, file upload and
 * quiz exercises, which the server lists after the stories.
 * <p>
 * Every field but the exercise itself is optional, because an exercise the student never started still produces an
 * entry: "not started" is what a tutor needs to see, not a row to hide.
 */
export interface MilestoneAssessmentExercise {
    exerciseId: number;
    title: string;
    exerciseType: ExerciseType;
    /** Whether this is a user story. Needed on top of the type, since a user story is a programming exercise. */
    userStory?: boolean;
    maxPoints?: number;
    participationId?: number;
    /** The submission the assessment editor is opened on; unset before the student's first build. */
    submissionId?: number;
    latestScore?: number;
    assessorLogin?: string;
    /**
     * Whether a manual assessment has been submitted. Optional rather than required because the server serializes with
     * `NON_EMPTY`, which drops a `false` on the wire - so absent means "not assessed".
     */
    assessed?: boolean;
}

/** One student's standing across a whole milestone group (mirrors the server {@code MilestoneAssessmentStudentDTO}). */
export interface MilestoneAssessmentStudent {
    studentLogin: string;
    studentName?: string;
    milestoneParticipationId: number;
    exercises: MilestoneAssessmentExercise[];
}

/**
 * Everything the milestone assessment page needs for one student (mirrors the server {@code MilestoneAssessmentDTO}).
 * <p>
 * {@link milestoneResult} is the milestone's own result, which is the only place the group's static code analysis
 * feedback exists: the fan-out copies only test case feedback down to the stories, and every story has static code
 * analysis switched off.
 */
export interface MilestoneAssessment {
    milestoneExerciseId: number;
    milestoneTitle: string;
    problemStatement?: string;
    staticCodeAnalysisEnabled?: boolean;
    maxStaticCodeAnalysisPenalty?: number;
    milestoneMaxPoints?: number;
    /** The student's milestone participation, which the problem statement's tasks are judged against. */
    milestoneParticipationId?: number;
    milestoneResult?: Result;
    exercises: MilestoneAssessmentExercise[];
}

/** Reads the two tutor-facing views of a milestone exercise group. */
@Service()
export class MilestoneAssessmentService {
    private readonly http = inject(HttpClient);

    private resourceUrl(courseId: number, groupId: number): string {
        return `api/exercise/courses/${courseId}/milestone-exercise-groups/${groupId}/assessment/students`;
    }

    /** Every student who has started the group's milestone, with the standing of each of their exercises in the group. */
    getAssessmentDashboard(courseId: number, groupId: number): Observable<MilestoneAssessmentStudent[]> {
        return this.http.get<MilestoneAssessmentStudent[]>(this.resourceUrl(courseId, groupId));
    }

    /** The group-level information and the ordered exercises the assessment page renders as tabs for one student. */
    getAssessmentForStudent(courseId: number, groupId: number, studentLogin: string): Observable<MilestoneAssessment> {
        return this.http.get<MilestoneAssessment>(`${this.resourceUrl(courseId, groupId)}/${studentLogin}`);
    }
}
