import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, provideRouter } from '@angular/router';
import { Observable, of, throwError } from 'rxjs';
import { MockProvider } from 'ng-mocks';
import { TranslateService } from '@ngx-translate/core';
import dayjs from 'dayjs/esm';
import { MockTranslateService } from 'test/helpers/mocks/service/mock-translate.service';
import { AlertService } from 'app/foundation/service/alert.service';
import { ExerciseVariantGroupDTO, ExerciseVariantGroupService } from 'app/course/manage/exercises/exercise-variant-group.service';
import { MilestoneOverviewComponent } from 'app/programming/manage/milestone-overview/milestone-overview.component';

describe('MilestoneOverviewComponent', () => {
    let fixture: ComponentFixture<MilestoneOverviewComponent>;

    function groups(): ExerciseVariantGroupDTO[] {
        return [
            { id: 3, title: 'Sprint 2', type: 'milestone', milestoneExerciseId: 30, releaseDate: dayjs('2026-11-15T10:00:00Z'), maxPoints: 20, exerciseIds: [31, 32] },
            { id: 4, title: 'Unplanned', type: 'milestone', exerciseIds: [] },
            {
                id: 2,
                title: 'Sprint 1',
                type: 'milestone',
                milestoneExerciseId: 20,
                releaseDate: dayjs('2026-11-01T10:00:00Z'),
                dueDate: dayjs('2026-11-14T22:00:00Z'),
                maxPoints: 15,
                exerciseIds: [21, 22, 23],
            },
        ];
    }

    async function setup(getGroups: () => Observable<ExerciseVariantGroupDTO[]> = () => of(groups())): Promise<void> {
        await TestBed.configureTestingModule({
            imports: [MilestoneOverviewComponent],
            providers: [
                provideRouter([]),
                { provide: ActivatedRoute, useValue: { params: of({ courseId: '1' }) } },
                { provide: TranslateService, useClass: MockTranslateService },
                MockProvider(ExerciseVariantGroupService, { getMilestoneGroupsForCourse: getGroups as never }),
                MockProvider(AlertService, { error: vi.fn() }),
            ],
        }).compileComponents();

        fixture = TestBed.createComponent(MilestoneOverviewComponent);
        fixture.detectChanges();
    }

    const panels = (): HTMLElement[] => Array.from(fixture.nativeElement.querySelectorAll('[data-testid="milestone-overview-group"]'));

    beforeEach(() => TestBed.resetTestingModule());

    it('lists the milestones in release order, those without a release date last', async () => {
        await setup();

        expect(panels().map((panel) => panel.textContent)).toEqual([
            expect.stringContaining('Sprint 1'),
            expect.stringContaining('Sprint 2'),
            expect.stringContaining('Unplanned'),
        ]);
    });

    it('shows how many exercises each milestone holds', async () => {
        await setup();

        expect(panels().map((panel) => panel.querySelector('[data-testid="milestone-overview-exercise-count"]')?.textContent?.trim())).toEqual(['3', '2', '0']);
    });

    it('marks the dates a milestone does not set', async () => {
        await setup();

        // Sprint 1 sets release and due date, so start and assessment due remain.
        expect(panels()[0].querySelectorAll('[jhiTranslate="artemisApp.exerciseManagement.groupMeta.none"]')).toHaveLength(2);
    });

    it('links each milestone to its assessment dashboard', async () => {
        await setup();

        const link: HTMLAnchorElement = panels()[0].querySelector('[data-testid="milestone-overview-assessment-dashboard"]')!;
        expect(link.getAttribute('href')).toBe('/course-management/1/milestone-exercise-groups/2/assessment-dashboard');
    });

    it('links to the milestone details only when the group has its milestone exercise', async () => {
        await setup();

        expect(panels()[0].querySelector('[data-testid="milestone-overview-details"]')?.getAttribute('href')).toBe('/course-management/1/milestone-exercise-groups/20');
        expect(panels()[2].querySelector('[data-testid="milestone-overview-details"]')).toBeNull();
    });

    it('links to the participations of the milestone exercise only when the group has one', async () => {
        await setup();

        expect(panels()[0].querySelector('[data-testid="milestone-overview-participations"]')?.getAttribute('href')).toBe(
            '/course-management/1/programming-exercises/20/participations',
        );
        expect(panels()[2].querySelector('[data-testid="milestone-overview-participations"]')).toBeNull();
    });

    it('says so when the course has no milestones', async () => {
        await setup(() => of([]));

        expect(fixture.nativeElement.querySelector('[data-testid="milestone-overview-empty"]')).not.toBeNull();
    });

    it('reports a failed load', async () => {
        await setup(() => throwError(() => new Error('failed')));

        expect(TestBed.inject(AlertService).error).toHaveBeenCalledWith('artemisApp.milestoneOverview.loadFailed');
        expect(fixture.nativeElement.querySelector('[data-testid="milestone-overview-empty"]')).not.toBeNull();
    });
});
