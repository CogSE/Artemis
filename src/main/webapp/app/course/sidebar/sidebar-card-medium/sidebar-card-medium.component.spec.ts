import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { SidebarCardMediumComponent } from 'app/course/sidebar/sidebar-card-medium/sidebar-card-medium.component';
import { SidebarCardItemComponent } from 'app/course/sidebar/sidebar-card-item/sidebar-card-item.component';
import { MockModule } from 'ng-mocks';
import { ActivatedRoute, DefaultUrlSerializer, Router, RouterModule } from '@angular/router';
import { MockRouterLinkDirective } from 'test/helpers/mocks/directive/mock-router-link.directive';
import { MockRouter } from 'test/helpers/mocks/mock-router';
import { DifficultyLevel, ExerciseType } from 'app/exercise/shared/entities/exercise/exercise.model';
import { MockActivatedRoute } from 'test/helpers/mocks/activated-route/mock-activated-route';

describe('SidebarCardMediumComponent', () => {
    let component: SidebarCardMediumComponent;
    let fixture: ComponentFixture<SidebarCardMediumComponent>;
    let router: MockRouter;

    beforeEach(() => {
        router = new MockRouter();
        TestBed.configureTestingModule({
            imports: [MockModule(RouterModule), SidebarCardMediumComponent, SidebarCardItemComponent, MockRouterLinkDirective],
            providers: [
                { provide: Router, useValue: router },
                { provide: ActivatedRoute, useValue: new MockActivatedRoute() },
            ],
        }).compileComponents();
    });

    beforeEach(() => {
        fixture = TestBed.createComponent(SidebarCardMediumComponent);
        component = fixture.componentInstance;
        fixture.componentRef.setInput('sidebarItem', {
            title: 'testTitle',
            id: 'testId',
            size: 'M',
        });
        fixture.componentRef.setInput('itemSelected', true);
        fixture.detectChanges();
    });

    it('should create', () => {
        expect(component).toBeTruthy();
    });

    it('should keep the neutral stripe for an exercise without difficulty', () => {
        const element: HTMLElement = fixture.nativeElement.querySelector('#test-sidebar-card-medium');
        expect(element.className).toContain('border-module');
        expect(element.className).not.toContain('border-variant-group');
    });

    it('should have success border class for easy difficulty', () => {
        fixture.componentRef.setInput('sidebarItem', { ...component.sidebarItem(), difficulty: DifficultyLevel.EASY });
        fixture.changeDetectorRef.detectChanges();
        const element: HTMLElement = fixture.nativeElement.querySelector('#test-sidebar-card-medium');
        const classes = element.className;
        expect(classes).toContain('border-success');
    });

    it('should have success border class for medium difficulty', () => {
        fixture.componentRef.setInput('sidebarItem', { ...component.sidebarItem(), difficulty: DifficultyLevel.MEDIUM });
        fixture.changeDetectorRef.detectChanges();
        const element: HTMLElement = fixture.nativeElement.querySelector('#test-sidebar-card-medium');
        const classes = element.className;
        expect(classes).toContain('border-warning');
    });

    it('should have success border class for hard difficulty', () => {
        fixture.componentRef.setInput('sidebarItem', { ...component.sidebarItem(), difficulty: DifficultyLevel.HARD });
        fixture.changeDetectorRef.detectChanges();
        const element: HTMLElement = fixture.nativeElement.querySelector('#test-sidebar-card-medium');
        const classes = element.className;
        expect(classes).toContain('border-danger');
    });

    describe('variant group card', () => {
        /** A variant group is a single card; its members have no card of their own, so the group card carries their selection. */
        beforeEach(() => {
            fixture.componentRef.setInput('sidebarItem', {
                title: 'Sorting variants',
                id: 10,
                size: 'M',
                groupedItems: [
                    { title: 'Variant A', id: 11, size: 'M' },
                    { title: 'Variant B', id: 12, size: 'M' },
                ],
            });
        });

        const cardClasses = (): string => {
            fixture.changeDetectorRef.detectChanges();
            return (fixture.nativeElement.querySelector('#test-sidebar-card-medium') as HTMLElement).className;
        };

        it('should carry the primary stripe in place of a difficulty colour', () => {
            const classes = cardClasses();
            expect(classes).toContain('border-variant-group');
            expect(classes).not.toContain('border-module');
        });

        it('should mark the group card as selected while one of its members is open', () => {
            fixture.componentRef.setInput('activeItemId', 12);
            expect(cardClasses()).toContain('bg-group-selected');
        });

        it('should not mark the group card as selected for an unrelated open item', () => {
            fixture.componentRef.setInput('activeItemId', 99);
            expect(cardClasses()).not.toContain('bg-group-selected');
        });

        it('should not mark the group card as selected when no item is open', () => {
            expect(cardClasses()).not.toContain('bg-group-selected');
        });
    });

    it('should store target subroute and refresh on click when previously an item was selected', async () => {
        vi.spyOn(component, 'storeTargetComponentSubRoute');
        vi.spyOn(component, 'refreshChildComponent');
        fixture.componentRef.setInput('itemSelected', true);
        fixture.changeDetectorRef.detectChanges();
        await fixture.whenStable();

        const itemElement = fixture.nativeElement.querySelector('#test-sidebar-card-medium a');
        itemElement.click();
        fixture.changeDetectorRef.detectChanges();
        await fixture.whenStable();

        expect(component.storeTargetComponentSubRoute).toHaveBeenCalled();
        expect(component.refreshChildComponent).toHaveBeenCalled();
    });

    it('should store target subroute on click when previously no item was selected', async () => {
        vi.spyOn(component, 'storeTargetComponentSubRoute');
        vi.spyOn(component, 'refreshChildComponent');
        fixture.componentRef.setInput('itemSelected', false);
        fixture.changeDetectorRef.detectChanges();
        await fixture.whenStable();

        const itemElement = fixture.nativeElement.querySelector('#test-sidebar-card-medium a');
        itemElement.click();
        fixture.changeDetectorRef.detectChanges();
        await fixture.whenStable();

        expect(component.storeTargetComponentSubRoute).toHaveBeenCalled();
        expect(component.refreshChildComponent).not.toHaveBeenCalled();
    });

    describe('selected highlight', () => {
        const urlSerializer = new DefaultUrlSerializer();

        /**
         * Stands in for the router's relative-URL resolution: `['./', 'programming-exercises', 5]` resolves against the
         * sidebar's own `courses/1/exercises` route. Real `UrlTree`s are handed out on both sides so the component's
         * `isActive` call performs the router's genuine subset match rather than a stubbed one.
         */
        function navigateTo(currentUrl: string) {
            router.createUrlTree.mockImplementation((commands: (string | number)[]) =>
                urlSerializer.parse(`/courses/1/exercises/${commands.filter((command) => command !== './').join('/')}`),
            );
            router.lastSuccessfulNavigation.mockReturnValue({ finalUrl: urlSerializer.parse(currentUrl) });
            router.setUrl(currentUrl);
        }

        function renderExerciseCard(item: object) {
            fixture.componentRef.setInput('sidebarItem', { title: 'testTitle', size: 'M', ...item });
            fixture.changeDetectorRef.detectChanges();
            return fixture.nativeElement.querySelector('#test-sidebar-card-medium') as HTMLElement;
        }

        it('should highlight the open exercise on its plain route', () => {
            navigateTo('/courses/1/exercises/5');
            const element = renderExerciseCard({ id: 5, exercise: { id: 5, type: ExerciseType.PROGRAMMING } });
            expect(element.className).toContain('bg-selected');
        });

        it('should keep the highlight once the exercise is started and its details page redirected into the code editor', () => {
            navigateTo('/courses/1/exercises/programming-exercises/5/code-editor/42');
            const element = renderExerciseCard({ id: 5, exercise: { id: 5, type: ExerciseType.USER_STORY } });
            expect(element.className).toContain('bg-selected');
        });

        it('should not highlight an unrelated exercise that shares its id with the open variant group', () => {
            navigateTo('/courses/1/exercises/group/5');
            const element = renderExerciseCard({ id: 5, exercise: { id: 5, type: ExerciseType.PROGRAMMING } });
            expect(element.className).not.toContain('bg-selected');
        });

        it('should highlight the open variant group header', () => {
            navigateTo('/courses/1/exercises/group/5');
            const element = renderExerciseCard({ id: 5, targetComponentSubRoute: 'group', groupConnected: true, groupedItems: [{ id: 7, size: 'M' }] });
            expect(element.className).toContain('bg-selected');
        });

        it('should match only its own link for an item without an exercise', () => {
            navigateTo('/courses/1/exercises/programming-exercises/5/code-editor/42');
            expect(renderExerciseCard({ id: 5, targetComponentSubRoute: 'tutorial-lectures' }).className).not.toContain('bg-selected');

            navigateTo('/courses/1/exercises/tutorial-lectures/5');
            expect(renderExerciseCard({ id: 5, targetComponentSubRoute: 'tutorial-lectures' }).className).toContain('bg-selected');
        });
    });
});
