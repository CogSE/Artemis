import { describe, expect, it } from 'vitest';
import { priorityLabelKey, prioritySeverity, stateLabelKey, stateSeverity } from 'app/course/overview/exercise-details/user-story-tasks/user-story-task-display.util';

describe('user-story-task-display.util', () => {
    it('should label and colour every priority', () => {
        expect(priorityLabelKey('LOW')).toBe('artemisApp.courseOverview.exerciseDetails.tasks.priorityLow');
        expect(priorityLabelKey('MEDIUM')).toBe('artemisApp.courseOverview.exerciseDetails.tasks.priorityMedium');
        expect(priorityLabelKey('HIGH')).toBe('artemisApp.courseOverview.exerciseDetails.tasks.priorityHigh');
        expect(prioritySeverity('LOW')).toBe('success');
        expect(prioritySeverity('MEDIUM')).toBe('warn');
        expect(prioritySeverity('HIGH')).toBe('danger');
    });

    it('should label and colour every state', () => {
        expect(stateLabelKey('NEW')).toBe('artemisApp.courseOverview.exerciseDetails.tasks.stateNew');
        expect(stateLabelKey('IN_PROGRESS')).toBe('artemisApp.courseOverview.exerciseDetails.tasks.stateInProgress');
        expect(stateLabelKey('DONE')).toBe('artemisApp.courseOverview.exerciseDetails.tasks.stateDone');
        expect(stateSeverity('NEW')).toBe('contrast');
        expect(stateSeverity('IN_PROGRESS')).toBe('info');
        expect(stateSeverity('DONE')).toBe('success');
    });
});
