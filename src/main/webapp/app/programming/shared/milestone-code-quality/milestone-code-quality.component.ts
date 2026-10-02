import { ChangeDetectionStrategy, Component, computed, input, signal } from '@angular/core';
import { DecimalPipe, NgTemplateOutlet } from '@angular/common';
import { FaIconComponent } from '@fortawesome/angular-fontawesome';
import { faCircleCheck, faCircleNotch, faCircleXmark, faTriangleExclamation } from '@fortawesome/free-solid-svg-icons';
import { Feedback, STATIC_CODE_ANALYSIS_FEEDBACK_IDENTIFIER } from 'app/assessment/shared/entities/feedback.model';
import { Result } from 'app/exercise/shared/entities/result/result.model';
import { ProgrammingExercise } from 'app/programming/shared/entities/programming-exercise.model';
import { StaticCodeAnalysisIssue } from 'app/programming/shared/entities/static-code-analysis-issue.model';
import { ArtemisTranslatePipe } from 'app/foundation/pipes/artemis-translate.pipe';
import { TranslateDirective } from 'app/foundation/language/translate.directive';
import { InformationBox, InformationBoxComponent } from 'app/shared-ui/information-box/information-box.component';
import { TumAetUiDialogComponent, TumAetUiTableDirective } from '@tumaet/ui-angular';
import { parseScaIssue, scaFeedbackPenalty } from './milestone-code-quality.util';

/** One static code analysis issue of the milestone's build, flattened for display. */
interface CodeQualityIssue {
    /** The Artemis category the issue was mapped to, which is what carries the penalty. */
    category: string;
    /** `path/to/File.java:42` or `path/to/File.java:42-47`, or just the path when the tool reported no line. */
    location: string;
    rule?: string;
    message?: string;
    /** Points this issue costs, as a non-negative number. */
    penalty: number;
}

/** The issues of one Artemis category, with the points that category costs in total. */
interface CodeQualityCategory {
    category: string;
    penalty: number;
    issues: CodeQualityIssue[];
}

/**
 * How the group's code quality reads at a glance, in the order the box escalates through them.
 * <p>
 * `deducting` covers both a {@code GRADED} category charging its per-issue price and a {@code BLOCKING} one zeroing the
 * group: the server writes both into the same {@code ScaFeedback#penalty} field
 * ({@code ProgrammingExerciseGradingService.calculateStaticCodeAnalysisPenalty} and
 * {@code applyBlockingStaticCodeAnalysisDeduction}), and the wire format carries no category state, so the client
 * cannot tell the two apart - nor does it need to, since both mean "this cost you points".
 */
type CodeQualityStatus = 'building' | 'clean' | 'informational' | 'deducting';

/**
 * The group's static code analysis standing, as an information box in the milestone group header that opens the full
 * issue table on click.
 * <p>
 * A milestone group's user stories share one repository and one build, so a violation belongs to the group rather than
 * to any one story: the build's SCA feedback stays on the `MilestoneExercise`'s own result and is priced there exactly
 * once (server-side: `ProgrammingExerciseGradingService.fanOutResultToUserStoryExercise` deliberately copies only test
 * case feedback, and `MilestoneScoreService` subtracts the penalty from the group's aggregate). The milestone itself is
 * never rendered as an exercise (`MilestoneExercise.isVisibleToStudents()` is always false), so without this box the
 * student sees the points the penalty cost them but never which issues caused it.
 * <p>
 * The feedback is parsed exactly the way `ProgrammingFeedbackItemService.createScaFeedbackItem` parses it for the
 * ordinary result dialog, so both surfaces read the same wire format.
 */
@Component({
    selector: 'jhi-milestone-code-quality',
    templateUrl: './milestone-code-quality.component.html',
    styleUrl: './milestone-code-quality.component.scss',
    imports: [FaIconComponent, DecimalPipe, NgTemplateOutlet, ArtemisTranslatePipe, TranslateDirective, InformationBoxComponent, TumAetUiDialogComponent, TumAetUiTableDirective],
    /* preserveWhitespaces: false is required here because the global tsconfig sets preserveWhitespaces: true,
     * which inserts whitespace text nodes that break [contentComponent] slot matching in jhi-information-box. */
    preserveWhitespaces: false,
    changeDetection: ChangeDetectionStrategy.OnPush,
})
export class MilestoneCodeQualityComponent {
    /** The group's anchor milestone exercise, which is where static code analysis is configured. */
    readonly exercise = input.required<ProgrammingExercise | undefined>();
    /** The latest result of the student's milestone participation, or undefined while it loads / before the first build. */
    readonly result = input<Result | undefined>(undefined);
    /** Whether a build for the milestone's shared repository is currently running. */
    readonly isBuilding = input(false);
    /** Whether a build for the milestone's shared repository is queued but has not started yet. */
    readonly isQueued = input(false);
    /**
     * Renders the issue table directly instead of behind the information box that opens it in a dialog.
     * <p>
     * The student group page shows this among a row of header boxes, where the issue count is the whole point and the
     * detail belongs in a dialog. The tutor assessment page gives it a tab of its own, where there is nothing to open
     * and a trigger the tutor has to click first would only be in the way.
     */
    readonly inline = input(false);

    protected readonly faTriangleExclamation = faTriangleExclamation;
    protected readonly faCircleCheck = faCircleCheck;
    protected readonly faCircleXmark = faCircleXmark;
    protected readonly faCircleNotch = faCircleNotch;

    /** Whether the issue table is open. */
    protected readonly detailsVisible = signal(false);

    /** Whether a build is on its way, so the box shows that rather than a count that is already known to be stale. */
    protected readonly isPending = computed<boolean>(() => this.isBuilding() || this.isQueued());

    /** What the box says while a build is on its way; a build that has already started outranks a queued one. */
    protected readonly pendingLabel = computed<string>(() =>
        this.isBuilding() ? 'artemisApp.exerciseVariantGroup.detail.codeQuality.building' : 'artemisApp.exerciseVariantGroup.detail.codeQuality.queued',
    );

    /**
     * Whether the box has anything to say at all: the milestone must have static code analysis on, and either have a
     * result or a build on its way. Without the pending case a student who pushes before their first build ever
     * finished would see nothing at all between the push and the result.
     */
    protected readonly isApplicable = computed<boolean>(() => this.exercise()?.staticCodeAnalysisEnabled === true && (this.result() !== undefined || this.isPending()));

    protected readonly issues = computed<CodeQualityIssue[]>(() => {
        const feedbacks = this.result()?.feedbacks ?? [];
        return feedbacks.filter((feedback) => Feedback.isStaticCodeAnalysisFeedback(feedback)).map((feedback) => this.toIssue(feedback));
    });

    protected readonly issueCount = computed<number>(() => this.issues().length);

    /** The points the group loses to static code analysis, as a non-negative number. */
    protected readonly totalPenalty = computed<number>(() => this.issues().reduce((sum, issue) => sum + issue.penalty, 0));

    protected readonly status = computed<CodeQualityStatus>(() => {
        if (this.isPending()) {
            return 'building';
        }
        if (this.issues().some((issue) => issue.penalty > 0)) {
            return 'deducting';
        }
        return this.issueCount() === 0 ? 'clean' : 'informational';
    });

    protected readonly infoBoxData = computed<InformationBox>(() => ({
        // The label the standard static code analysis box uses, so the figure reads the same wherever it is shown
        // (see ExerciseHeadersInformationComponent.getStaticCodeAnalysisItemIfEnabled).
        title: 'artemisApp.courseOverview.exerciseDetails.codeIssues',
        content: { type: 'string', value: this.issueCount() },
        isContentComponent: true,
        tooltip: 'artemisApp.exerciseVariantGroup.detail.codeQuality.boxTooltip',
    }));

    /**
     * The most the group can lose to static code analysis, or undefined when the instructor set no cap.
     * <p>
     * {@code maxStaticCodeAnalysisPenalty} is a percentage of {@code maxPoints}, which is how the server prices it
     * (see {@code ProgrammingExerciseGradingService.calculateStaticCodeAnalysisPenalty}). Note that
     * {@code ProgrammingFeedbackGroupWarning} multiplies the two without dividing by 100, so the result dialog's
     * warning-group cap is a hundred times too large - not copied here.
     */
    protected readonly penaltyCap = computed<number | undefined>(() => {
        const exercise = this.exercise();
        const maxPenaltyRatio = exercise?.maxStaticCodeAnalysisPenalty;
        return maxPenaltyRatio === undefined || exercise?.maxPoints === undefined ? undefined : (maxPenaltyRatio / 100) * exercise.maxPoints;
    });

    /** Whether the cap is actually what limits the loss, i.e. the raw penalties would have cost more. */
    protected readonly isCapped = computed<boolean>(() => {
        const cap = this.penaltyCap();
        return cap !== undefined && this.totalPenalty() >= cap;
    });

    /** The issues grouped by Artemis category, most expensive category first, so the costly ones are read first. */
    protected readonly categories = computed<CodeQualityCategory[]>(() => {
        const byCategory = new Map<string, CodeQualityIssue[]>();
        for (const issue of this.issues()) {
            const existing = byCategory.get(issue.category);
            if (existing) {
                existing.push(issue);
            } else {
                byCategory.set(issue.category, [issue]);
            }
        }
        return [...byCategory.entries()]
            .map(([category, issues]) => ({
                category,
                issues: [...issues].sort((a, b) => b.penalty - a.penalty),
                penalty: issues.reduce((sum, issue) => sum + issue.penalty, 0),
            }))
            .sort((a, b) => b.penalty - a.penalty || a.category.localeCompare(b.category));
    });

    protected openDetails(): void {
        this.detailsVisible.set(true);
    }

    /**
     * Flattens one synthesized SCA feedback into a display row. An issue whose detail text cannot be parsed (see
     * {@link parseScaIssue}) is shown with whatever the server did write.
     */
    private toIssue(feedback: Feedback): CodeQualityIssue {
        const category = feedback.text!.substring(STATIC_CODE_ANALYSIS_FEEDBACK_IDENTIFIER.length);
        const issue = parseScaIssue(feedback);
        return {
            category,
            location: this.buildLocation(issue),
            rule: issue?.rule,
            message: issue?.message ?? feedback.detailText,
            penalty: scaFeedbackPenalty(feedback, issue),
        };
    }

    private buildLocation(issue: StaticCodeAnalysisIssue | undefined): string {
        if (!issue?.filePath) {
            return '';
        }
        if (!issue.startLine) {
            return issue.filePath;
        }
        const lines = !issue.endLine || issue.endLine === issue.startLine ? `${issue.startLine}` : `${issue.startLine}-${issue.endLine}`;
        return `${issue.filePath}:${lines}`;
    }
}
