import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { TestBed, inject } from '@angular/core/testing';
import { JhiLanguageHelper } from 'app/core/language/shared/language.helper';
import { SessionStorageService } from 'app/foundation/service/session-storage.service';
import { MockTranslateService } from 'test/helpers/mocks/service/mock-translate.service';
import { MockRouter } from 'test/helpers/mocks/mock-router';
import { TranslateService } from '@ngx-translate/core';
import { Router, RouterState } from '@angular/router';
import { Title } from '@angular/platform-browser';
import { LocaleConversionService } from 'app/foundation/service/locale-conversion.service';
import { MockProvider } from 'ng-mocks';
import { Renderer2, RendererFactory2 } from '@angular/core';

describe('Language Helper', () => {
    const renderer2: Renderer2 = {
        setAttribute: () => {},
    } as unknown as Renderer2;

    beforeEach(() => {
        TestBed.configureTestingModule({
            providers: [
                JhiLanguageHelper,
                { provide: TranslateService, useClass: MockTranslateService },
                SessionStorageService,
                { provide: Router, useClass: MockRouter },
                MockProvider(LocaleConversionService),
                MockProvider(Title),
                MockProvider(RendererFactory2, {
                    createRenderer: () => renderer2,
                }),
            ],
        });
    });

    afterEach(() => {
        vi.restoreAllMocks();
    });

    it('determinePreferredLanguages should respect user preference', inject([JhiLanguageHelper], (service: JhiLanguageHelper) => {
        const navigator = {
            languages: ['de', 'en'],
        } as unknown as Navigator;

        const languageChangeSpy = vi.spyOn(service, 'getNavigatorReference').mockReturnValue(navigator);
        expect(service.determinePreferredLanguage()).toBe('de');
        expect(languageChangeSpy).toHaveBeenCalledOnce();
    }));

    it('determinePreferredLanguage should return english if no other language matches', inject([JhiLanguageHelper], (service: JhiLanguageHelper) => {
        const navigator = {
            languages: ['elvish', 'orcish'],
        } as unknown as Navigator;

        const languageChangeSpy = vi.spyOn(service, 'getNavigatorReference').mockReturnValue(navigator);
        expect(service.determinePreferredLanguage()).toBe('en');
        expect(languageChangeSpy).toHaveBeenCalledOnce();
    }));

    it('interpolates the parameters of the whole active route into the page title', inject([JhiLanguageHelper], (service: JhiLanguageHelper) => {
        const child = { params: { courseId: '7', studentLogin: 'student1' }, data: { pageTitle: 'artemisApp.milestoneAssessment.pageTitle' }, firstChild: null };
        const root = { params: { courseId: '1', other: 'x' }, data: {}, firstChild: child };
        (TestBed.inject(Router) as unknown as MockRouter).setRouterState({ snapshot: { root } } as unknown as RouterState);
        const translateService = TestBed.inject(TranslateService);
        const getSpy = vi.spyOn(translateService, 'get');
        const setTitleSpy = vi.spyOn(TestBed.inject(Title), 'setTitle');

        service.updateTitle();

        // The deepest route's parameter wins over its parent's of the same name.
        expect(getSpy).toHaveBeenCalledWith('artemisApp.milestoneAssessment.pageTitle', { courseId: '7', other: 'x', studentLogin: 'student1' });
        expect(setTitleSpy).toHaveBeenCalledOnce();
    }));
});
