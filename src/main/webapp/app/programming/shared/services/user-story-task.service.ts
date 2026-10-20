import { HttpClient } from '@angular/common/http';
import { Service, WritableSignal, inject, signal } from '@angular/core';
import { Observable, tap } from 'rxjs';
import { UserStoryTask } from 'app/exercise/shared/entities/participation/programming-exercise-student-participation.model';

/**
 * Reads and writes the tasks the current user creates for themself while working on a user story exercise (mirrors
 * the server {@code UserStoryTaskResource}).
 *
 * Every endpoint acts on the caller's own participation, or a task on it - there is nothing here for reading or
 * writing someone else's board.
 */
@Service()
export class UserStoryTaskService {
    private readonly http = inject(HttpClient);

    /**
     * Bumped whenever a task is created, updated, reordered, advanced or deleted, so anything showing effort summed
     * from the board (e.g. the exercise header's "Estimated effort" box) can react without reloading the page - the
     * server derives that sum from the task list rather than storing it, so nothing else notifies of a change to it.
     */
    readonly boardVersion: WritableSignal<number> = signal(0);

    private bumpBoardVersion(): void {
        this.boardVersion.update((version) => version + 1);
    }

    /** The current user's tasks for the story, in creation order. */
    getTasks(exerciseId: number): Observable<UserStoryTask[]> {
        return this.http.get<UserStoryTask[]>(`api/programming/user-story-exercises/${exerciseId}/tasks`);
    }

    /** Creates a new task on the current user's board for the story. */
    createTask(exerciseId: number, task: UserStoryTask): Observable<UserStoryTask> {
        return this.http.post<UserStoryTask>(`api/programming/user-story-exercises/${exerciseId}/tasks`, task).pipe(tap(() => this.bumpBoardVersion()));
    }

    /** Reorders the current user's board (drag-and-drop) to match the given sequence of task ids. */
    reorderTasks(exerciseId: number, orderedTaskIds: number[]): Observable<UserStoryTask[]> {
        return this.http.put<UserStoryTask[]>(`api/programming/user-story-exercises/${exerciseId}/tasks/reorder`, orderedTaskIds);
    }

    /** Updates a task the current user owns. */
    updateTask(taskId: number, task: UserStoryTask): Observable<UserStoryTask> {
        return this.http.put<UserStoryTask>(`api/programming/user-story-tasks/${taskId}`, task).pipe(tap(() => this.bumpBoardVersion()));
    }

    /** Shifts a task the current user owns one step forward: NEW to IN_PROGRESS, or IN_PROGRESS to DONE. */
    advanceState(taskId: number): Observable<UserStoryTask> {
        return this.http.put<UserStoryTask>(`api/programming/user-story-tasks/${taskId}/advance-state`, undefined).pipe(tap(() => this.bumpBoardVersion()));
    }

    /** Deletes a task the current user owns. */
    deleteTask(taskId: number): Observable<void> {
        return this.http.delete<void>(`api/programming/user-story-tasks/${taskId}`).pipe(tap(() => this.bumpBoardVersion()));
    }
}
