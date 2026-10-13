import { TaskPriority, TaskState } from 'app/exercise/shared/entities/participation/programming-exercise-student-participation.model';

/**
 * How a task's priority and state are labelled and coloured. Shared by the student's own board and the tutor's
 * read-only view of it in the milestone assessment, so both read the same.
 */

export function priorityLabelKey(priority: TaskPriority): string {
    switch (priority) {
        case 'LOW':
            return 'artemisApp.courseOverview.exerciseDetails.tasks.priorityLow';
        case 'MEDIUM':
            return 'artemisApp.courseOverview.exerciseDetails.tasks.priorityMedium';
        case 'HIGH':
            return 'artemisApp.courseOverview.exerciseDetails.tasks.priorityHigh';
    }
}

export function prioritySeverity(priority: TaskPriority): 'success' | 'warn' | 'danger' {
    switch (priority) {
        case 'LOW':
            return 'success';
        case 'MEDIUM':
            return 'warn';
        case 'HIGH':
            return 'danger';
    }
}

export function stateLabelKey(state: TaskState): string {
    switch (state) {
        case 'NEW':
            return 'artemisApp.courseOverview.exerciseDetails.tasks.stateNew';
        case 'IN_PROGRESS':
            return 'artemisApp.courseOverview.exerciseDetails.tasks.stateInProgress';
        case 'DONE':
            return 'artemisApp.courseOverview.exerciseDetails.tasks.stateDone';
    }
}

export function stateSeverity(state: TaskState): 'contrast' | 'info' | 'success' {
    switch (state) {
        case 'NEW':
            return 'contrast';
        case 'IN_PROGRESS':
            return 'info';
        case 'DONE':
            return 'success';
    }
}
