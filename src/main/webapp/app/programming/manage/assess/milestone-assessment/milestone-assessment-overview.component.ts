import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { TranslateDirective } from 'app/foundation/language/translate.directive';
import { hydrate } from 'app/foundation/util/deep-clone.util';
import { ExerciseType } from 'app/exercise/shared/entities/exercise/exercise.model';
import { ProgrammingExerciseStudentParticipation } from 'app/exercise/shared/entities/participation/programming-exercise-student-participation.model';
import { ProgrammingExercise } from 'app/programming/shared/entities/programming-exercise.model';
import { ProgrammingSubmission } from 'app/programming/shared/entities/programming-submission.model';
import { ProgrammingExerciseInstructionComponent } from 'app/programming/shared/instructions-render/programming-exercise-instruction.component';
import { MilestoneCodeQualityComponent } from 'app/programming/shared/milestone-code-quality/milestone-code-quality.component';
import { MilestoneAssessment } from './milestone-assessment.service';

/**
 * The milestone assessment page's first tab: what a tutor needs to read the group's shared codebase before grading any
 * one story against it.
 * <p>
 * Both halves are group-level and exist nowhere else on the page. The problem statement is the brief the whole
 * repository was written against - the milestone itself is never rendered to anyone, so this is the only surface a
 * tutor can reach it from. It is rendered exactly as the student's group page renders it, so each task shows the
 * outcome of its tests in the student's latest milestone build. The static code analysis issues are charged once for
 * the entire group, so they belong here rather than on any single story's tab.
 */
@Component({
    selector: 'jhi-milestone-assessment-overview',
    templateUrl: './milestone-assessment-overview.component.html',
    styleUrl: './milestone-assessment-overview.component.scss',
    imports: [TranslateDirective, MilestoneCodeQualityComponent, ProgrammingExerciseInstructionComponent],
    changeDetection: ChangeDetectionStrategy.OnPush,
})
export class MilestoneAssessmentOverviewComponent {
    readonly assessment = input.required<MilestoneAssessment>();

    /**
     * The milestone rebuilt as an exercise, which is the shape both the instructions renderer and
     * {@link MilestoneCodeQualityComponent} take. Only the fields they read are carried; sending the whole exercise would
     * mean shipping the problem statement and the course twice.
     */
    protected readonly milestoneExercise = computed<ProgrammingExercise>(() => {
        const assessment = this.assessment();
        return hydrate(new ProgrammingExercise(undefined, undefined), {
            id: assessment.milestoneExerciseId,
            type: ExerciseType.MILESTONE,
            problemStatement: assessment.problemStatement,
            staticCodeAnalysisEnabled: assessment.staticCodeAnalysisEnabled === true,
            maxStaticCodeAnalysisPenalty: assessment.maxStaticCodeAnalysisPenalty,
            maxPoints: assessment.milestoneMaxPoints,
        });
    });

    /**
     * The student's milestone participation, carrying the latest milestone result the page already has, so the renderer
     * judges the tasks against it without loading it a second time. Undefined if the student never started the
     * milestone, in which case the statement renders without any task outcome.
     */
    protected readonly milestoneParticipation = computed<ProgrammingExerciseStudentParticipation | undefined>(() => {
        const { milestoneParticipationId, milestoneResult } = this.assessment();
        if (milestoneParticipationId === undefined) {
            return undefined;
        }
        const submissions = milestoneResult ? [hydrate(new ProgrammingSubmission(), { id: milestoneResult.submission?.id, results: [milestoneResult] })] : [];
        return hydrate(new ProgrammingExerciseStudentParticipation(), { id: milestoneParticipationId, submissions });
    });
}
