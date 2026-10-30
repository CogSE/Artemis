import { Feedback, STATIC_CODE_ANALYSIS_FEEDBACK_IDENTIFIER } from 'app/assessment/shared/entities/feedback.model';
import { FeedbackGroup } from 'app/exercise/feedback/group/feedback-group';
import { FeedbackItem } from 'app/exercise/feedback/item/feedback-item';
import { Result } from 'app/exercise/shared/entities/result/result.model';
import { StaticCodeAnalysisIssue } from 'app/programming/shared/entities/static-code-analysis-issue.model';

/** The Artemis category a static code analysis feedback item belongs to, which is what carries the penalty. */
function scaCategory(item: FeedbackItem): string {
    return item.feedbackReference.text!.substring(STATIC_CODE_ANALYSIS_FEEDBACK_IDENTIFIER.length);
}

/**
 * The issues of one Artemis category, most expensive first. Unlike the fixed groups of the result dialog, which put all
 * static code analysis feedback into one, the milestone shows what each category costs.
 */
export class MilestoneCodeQualityGroup extends FeedbackGroup {
    constructor(category: string, items: FeedbackItem[]) {
        super();
        this.name = category;
        this.label = category;
        this.color = items.some((item) => (item.credits ?? 0) < 0) ? 'danger' : 'warning';
        this.open = true;
        this.addAllItems([...items].sort((a, b) => (a.credits ?? 0) - (b.credits ?? 0)));
    }

    shouldContain(feedbackItem: FeedbackItem): boolean {
        return scaCategory(feedbackItem) === this.name;
    }
}

/** Groups static code analysis feedback items by Artemis category, most expensive category first. */
export function groupByScaCategory(items: FeedbackItem[]): MilestoneCodeQualityGroup[] {
    const byCategory = new Map<string, FeedbackItem[]>();
    for (const item of items) {
        const category = scaCategory(item);
        byCategory.set(category, [...(byCategory.get(category) ?? []), item]);
    }
    return [...byCategory.entries()]
        .map(([category, members]) => new MilestoneCodeQualityGroup(category, members))
        .sort((a, b) => a.credits - b.credits || a.name.localeCompare(b.name));
}

/**
 * Parses the `StaticCodeAnalysisIssue` a static code analysis feedback carries in its detail text.
 * <p>
 * The server falls back to the plain message when the issue does not fit the column limit, so parsing is not guaranteed
 * to succeed - a single unreadable issue must not take a whole view down, and yields undefined instead.
 */
export function parseScaIssue(feedback: Feedback): StaticCodeAnalysisIssue | undefined {
    if (!feedback.detailText) {
        return undefined;
    }
    try {
        return StaticCodeAnalysisIssue.fromFeedback(feedback);
    } catch {
        return undefined;
    }
}

/**
 * The points one static code analysis feedback costs, as a non-negative number.
 * <p>
 * The same fallback the result dialog applies: the issue's own penalty when the server wrote one, otherwise the
 * feedback's credits, which are negative for a deduction.
 */
export function scaFeedbackPenalty(feedback: Feedback, issue: StaticCodeAnalysisIssue | undefined = parseScaIssue(feedback)): number {
    return Math.max(0, issue?.penalty ?? -(feedback.credits ?? 0));
}

/** The points a milestone result loses to static code analysis in total, as a non-negative number; 0 without a result. */
export function milestoneScaPenalty(result: Result | undefined): number {
    return (result?.feedbacks ?? []).filter((feedback) => Feedback.isStaticCodeAnalysisFeedback(feedback)).reduce((sum, feedback) => sum + scaFeedbackPenalty(feedback), 0);
}
