import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { TranslateService } from '@ngx-translate/core';
import { MockTranslateService } from 'test/helpers/mocks/service/mock-translate.service';
import { MilestoneCodeQualityComponent } from 'app/programming/shared/milestone-code-quality/milestone-code-quality.component';
import { Feedback, FeedbackType, STATIC_CODE_ANALYSIS_FEEDBACK_IDENTIFIER } from 'app/assessment/shared/entities/feedback.model';
import { Result } from 'app/exercise/shared/entities/result/result.model';
import { ProgrammingExercise } from 'app/programming/shared/entities/programming-exercise.model';
import { StaticCodeAnalysisIssue } from 'app/programming/shared/entities/static-code-analysis-issue.model';

describe('MilestoneCodeQualityComponent', () => {
    let fixture: ComponentFixture<MilestoneCodeQualityComponent>;

    /** The wire format the server synthesizes from an `ScaFeedback` row (see `ProgrammingFeedbackSynthesizerService`). */
    function scaFeedback(category: string, issue: Partial<StaticCodeAnalysisIssue>, credits = 0): Feedback {
        return {
            type: FeedbackType.AUTOMATIC,
            positive: false,
            text: STATIC_CODE_ANALYSIS_FEEDBACK_IDENTIFIER + category,
            detailText: JSON.stringify(issue),
            credits,
        } as Feedback;
    }

    function milestone(overrides: Partial<ProgrammingExercise> = {}): ProgrammingExercise {
        return { id: 99, staticCodeAnalysisEnabled: true, maxPoints: 20, ...overrides } as ProgrammingExercise;
    }

    /** Renders the real template, so a broken binding fails here rather than only in the browser. */
    async function setup(
        exercise: ProgrammingExercise | undefined,
        result: Result | undefined,
        buildState: { isBuilding?: boolean; isQueued?: boolean; inline?: boolean } = {},
    ): Promise<MilestoneCodeQualityComponent> {
        await TestBed.configureTestingModule({
            imports: [MilestoneCodeQualityComponent],
            providers: [{ provide: TranslateService, useClass: MockTranslateService }],
        }).compileComponents();

        fixture = TestBed.createComponent(MilestoneCodeQualityComponent);
        fixture.componentRef.setInput('exercise', exercise);
        fixture.componentRef.setInput('result', result);
        fixture.componentRef.setInput('isBuilding', buildState.isBuilding ?? false);
        fixture.componentRef.setInput('isQueued', buildState.isQueued ?? false);
        fixture.componentRef.setInput('inline', buildState.inline ?? false);
        fixture.detectChanges();
        return fixture.componentInstance;
    }

    /** Access to the protected computeds under test. */
    function state(component: MilestoneCodeQualityComponent): {
        isApplicable: () => boolean;
        issueCount: () => number;
        totalPenalty: () => number;
        penaltyCap: () => number | undefined;
        isCapped: () => boolean;
        status: () => 'building' | 'clean' | 'informational' | 'deducting';
        isPending: () => boolean;
        pendingLabel: () => string;
        detailsVisible: { (): boolean; set: (value: boolean) => void };
        categories: () => { category: string; penalty: number; issues: { location: string; rule?: string; message?: string; penalty: number }[] }[];
        infoBoxData: () => { title: string };
    } {
        return component as never;
    }

    /** Opens the issue table. The dialog is portaled by the CDK, so its content lives on `document.body`. */
    function openDetails(): void {
        (fixture.nativeElement.querySelector('.code-quality__trigger') as HTMLButtonElement).click();
        fixture.detectChanges();
    }

    beforeEach(() => TestBed.resetTestingModule());

    // The dialog portals into document.body and outlives the fixture, so it has to be torn down explicitly
    // or the next test's document query finds the previous test's rows.
    afterEach(() => fixture?.destroy());

    it('groups the issues by category, most expensive category first', async () => {
        const result = {
            id: 1,
            feedbacks: [
                scaFeedback('Style', { filePath: 'src/Main.java', startLine: 42, endLine: 42, rule: 'LineLength', message: 'line too long', penalty: 0.5 }),
                scaFeedback('Bad Practice', { filePath: 'src/Foo.java', startLine: 8, endLine: 8, rule: 'UnusedVariable', message: 'unused variable', penalty: 1 }),
                scaFeedback('Style', { filePath: 'src/Bar.java', startLine: 3, endLine: 5, rule: 'Indentation', message: 'wrong indentation', penalty: 0.25 }),
            ],
        } as Result;
        const component = state(await setup(milestone(), result));

        expect(component.issueCount()).toBe(3);
        expect(component.totalPenalty()).toBe(1.75);
        expect(component.categories().map((category) => category.category)).toEqual(['Bad Practice', 'Style']);
        expect(component.categories()[1].penalty).toBe(0.75);
        // Within a category the costliest issue is listed first.
        expect(component.categories()[1].issues.map((issue) => issue.location)).toEqual(['src/Main.java:42', 'src/Bar.java:3-5']);

        // The rows actually reach the DOM in that order, so a broken template does not pass on the computeds alone.
        openDetails();
        const locations = [...document.body.querySelectorAll('.code-quality__location')].map((cell) => cell.textContent?.trim());
        expect(locations).toEqual(['src/Foo.java:8', 'src/Main.java:42', 'src/Bar.java:3-5']);
    });

    it('falls back to the feedback credits when the issue carries no penalty of its own', async () => {
        const result = { id: 1, feedbacks: [scaFeedback('Style', { filePath: 'src/Main.java', startLine: 1, endLine: 1, message: 'x' }, -2)] } as Result;
        const component = state(await setup(milestone(), result));

        expect(component.totalPenalty()).toBe(2);
    });

    it('keeps the remaining issues when one issue payload cannot be parsed', async () => {
        // The server falls back to the plain message when the serialized issue would exceed the column limit.
        const unparseable = { type: FeedbackType.AUTOMATIC, text: STATIC_CODE_ANALYSIS_FEEDBACK_IDENTIFIER + 'Style', detailText: 'not json', credits: -1 } as Feedback;
        const result = {
            id: 1,
            feedbacks: [unparseable, scaFeedback('Style', { filePath: 'src/Main.java', startLine: 1, endLine: 1, message: 'x', penalty: 0.5 })],
        } as Result;
        const component = state(await setup(milestone(), result));

        expect(component.issueCount()).toBe(2);
        expect(component.totalPenalty()).toBe(1.5);
        expect(component.categories()[0].issues.map((issue) => issue.message)).toContain('not json');
    });

    it('ignores feedback that is not static code analysis feedback', async () => {
        const testCaseFeedback = { type: FeedbackType.AUTOMATIC, text: 'testSort', detailText: 'failed', credits: 0 } as Feedback;
        const result = { id: 1, feedbacks: [testCaseFeedback] } as Result;
        const component = state(await setup(milestone(), result));

        expect(component.isApplicable()).toBe(true);
        expect(component.issueCount()).toBe(0);
    });

    it('reports the cap in points, not in percent', async () => {
        // maxStaticCodeAnalysisPenalty is a percentage of maxPoints server-side (ProgrammingExerciseGradingService).
        const result = { id: 1, feedbacks: [scaFeedback('Style', { filePath: 'a', startLine: 1, endLine: 1, penalty: 6 })] } as Result;
        const component = state(await setup(milestone({ maxStaticCodeAnalysisPenalty: 20 }), result));

        expect(component.penaltyCap()).toBe(4);
        expect(component.isCapped()).toBe(true);
    });

    it('reports no cap when the instructor configured none', async () => {
        const result = { id: 1, feedbacks: [] } as unknown as Result;
        const component = state(await setup(milestone(), result));

        expect(component.penaltyCap()).toBeUndefined();
        expect(component.isCapped()).toBe(false);
    });

    describe('information box', () => {
        function boxText(): string {
            return (fixture.nativeElement.querySelector('.code-quality__summary') as HTMLElement).textContent?.replace(/\s+/g, ' ').trim() ?? '';
        }

        function iconState(): string | undefined {
            const icon = fixture.nativeElement.querySelector('.code-quality__summary fa-icon') as HTMLElement | null;
            return icon?.className;
        }

        // The box sits in the page's title bar, where every box is one row of the bar's own height, and the figure is
        // the same one the exercise header states - so it carries the same label.
        it('renders as a single-row box labelled like the standard code issues box', async () => {
            const component = state(await setup(milestone(), { id: 1, feedbacks: [] } as unknown as Result));

            expect(component.infoBoxData().title).toBe('artemisApp.courseOverview.exerciseDetails.codeIssues');
            expect((fixture.nativeElement.querySelector('#test-information-box') as HTMLElement).className).toContain('information-box-inline');
        });

        it('reads clean when the build found no issues', async () => {
            const component = state(await setup(milestone(), { id: 1, feedbacks: [] } as unknown as Result));

            expect(component.status()).toBe('clean');
            expect(iconState()).toContain('text-state-success');
            expect(boxText()).toBe('0');
        });

        it('warns without alarming when the issues cost no points', async () => {
            // A FEEDBACK-state category is shown to the student but priced at nothing, so the server leaves
            // ScaFeedback#penalty null and the synthesized credits come through as 0.
            const result = { id: 1, feedbacks: [scaFeedback('Style', { filePath: 'a', startLine: 1, endLine: 1, message: 'x' })] } as Result;
            const component = state(await setup(milestone(), result));

            expect(component.status()).toBe('informational');
            expect(iconState()).toContain('text-state-warning');
            expect(boxText()).toBe('1');
        });

        it('escalates and shows the deduction when points are lost', async () => {
            const result = {
                id: 1,
                feedbacks: [
                    scaFeedback('Style', { filePath: 'a', startLine: 1, endLine: 1, penalty: 0.5 }),
                    scaFeedback('Style', { filePath: 'b', startLine: 2, endLine: 2, message: 'free' }),
                ],
            } as Result;
            const component = state(await setup(milestone(), result));

            expect(component.status()).toBe('deducting');
            expect(iconState()).toContain('text-state-danger');
            // MockTranslateService echoes the key, so the deduction is asserted through the computed instead.
            expect(component.totalPenalty()).toBe(0.5);
            expect(boxText()).toContain('2');
            expect(fixture.nativeElement.querySelector('.code-quality__summary .text-state-danger')).not.toBeNull();
        });

        it('hides the deduction entirely when nothing was deducted', async () => {
            await setup(milestone(), { id: 1, feedbacks: [] } as unknown as Result);

            expect(fixture.nativeElement.querySelector('.code-quality__summary .text-state-danger')).toBeNull();
        });

        it('opens the issue table on click and still opens it with no issues', async () => {
            const component = state(await setup(milestone(), { id: 1, feedbacks: [] } as unknown as Result));
            expect(component.detailsVisible()).toBe(false);
            expect(document.body.querySelector('.tumaet-ui-dialog')).toBeNull();

            openDetails();

            expect(component.detailsVisible()).toBe(true);
            expect(document.body.querySelector('.tumaet-ui-dialog')).not.toBeNull();
            // With nothing to list the dialog still explains itself rather than opening empty.
            expect(document.body.querySelector('table')).toBeNull();
        });
    });

    it('renders nothing when static code analysis is off for the milestone', async () => {
        const result = { id: 1, feedbacks: [] } as unknown as Result;
        expect(state(await setup(milestone({ staticCodeAnalysisEnabled: false }), result)).isApplicable()).toBe(false);
        expect(fixture.nativeElement.querySelector('.code-quality__trigger')).toBeNull();
    });

    it('renders nothing before the milestone has its first result', async () => {
        expect(state(await setup(milestone(), undefined)).isApplicable()).toBe(false);
    });

    it('renders nothing while the milestone exercise is still loading', async () => {
        const result = { id: 1, feedbacks: [] } as unknown as Result;
        expect(state(await setup(undefined, result)).isApplicable()).toBe(false);
    });

    describe('inline', () => {
        it('renders the issue table straight away, with no trigger to click', async () => {
            const result = {
                id: 1,
                feedbacks: [scaFeedback('Bad Practice', { filePath: 'src/Main.java', startLine: 4, rule: 'DM_EXIT', message: 'Avoid System.exit' }, -2)],
            } as unknown as Result;

            await setup(milestone(), result, { inline: true });

            // No information box to open: the tutor page gives this a tab of its own, so a trigger would only be in the way.
            expect(fixture.nativeElement.querySelector('.code-quality__trigger')).toBeNull();
            const table = fixture.nativeElement.querySelector('table');
            expect(table).not.toBeNull();
            expect(table.textContent).toContain('Bad Practice');
            expect(table.textContent).toContain('src/Main.java:4');
        });

        it('still explains itself when the codebase is clean', async () => {
            await setup(milestone(), { id: 1, feedbacks: [] } as unknown as Result, { inline: true });

            expect(fixture.nativeElement.querySelector('table')).toBeNull();
            expect(fixture.nativeElement.textContent).toContain('artemisApp.exerciseVariantGroup.detail.codeQuality.noIssues');
        });
    });

    describe('while a build is on its way', () => {
        it('renders the box before the first result ever arrived, so a push is visibly acknowledged', async () => {
            const component = state(await setup(milestone(), undefined, { isBuilding: true }));

            expect(component.isApplicable()).toBe(true);
            expect(component.status()).toBe('building');
            expect(fixture.nativeElement.querySelector('.code-quality__pending')).not.toBeNull();
            // The issue count would be a lie while the build that decides it is still running.
            expect(fixture.nativeElement.querySelector('.code-quality__summary .text-state-success')).toBeNull();
        });

        it('distinguishes a queued build from a running one', async () => {
            const component = state(await setup(milestone(), undefined, { isQueued: true }));
            expect(component.pendingLabel()).toBe('artemisApp.exerciseVariantGroup.detail.codeQuality.queued');

            // A build that has already started outranks the queue state, which may still be reported alongside it.
            fixture.componentRef.setInput('isBuilding', true);
            fixture.detectChanges();

            expect(component.pendingLabel()).toBe('artemisApp.exerciseVariantGroup.detail.codeQuality.building');
        });

        it('does not claim a clean codebase in the dialog while the build is still running', async () => {
            await setup(milestone(), { id: 1, feedbacks: [] } as unknown as Result, { isBuilding: true });

            openDetails();

            const dialogText = document.body.querySelector('.tumaet-ui-dialog')?.textContent ?? '';
            expect(dialogText).toContain('artemisApp.exerciseVariantGroup.detail.codeQuality.building');
            expect(dialogText).not.toContain('artemisApp.exerciseVariantGroup.detail.codeQuality.noIssues');
        });

        it('falls back to the issue count once the build finished', async () => {
            const component = state(await setup(milestone(), { id: 1, feedbacks: [] } as unknown as Result, { isBuilding: false }));

            expect(component.status()).toBe('clean');
            expect(fixture.nativeElement.querySelector('.code-quality__pending')).toBeNull();
        });
    });
});
