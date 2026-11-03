import { Renderer2, RendererFactory2, Service, inject } from '@angular/core';
import { Title } from '@angular/platform-browser';
import { ActivatedRouteSnapshot, Router } from '@angular/router';
import { TranslateService } from '@ngx-translate/core';
import { SessionStorageService } from 'app/foundation/service/session-storage.service';

import { LANGUAGES } from './language.constants';
import { BehaviorSubject, Observable } from 'rxjs';
import { captureException } from '@sentry/angular';
import { LocaleConversionService } from 'app/foundation/service/locale-conversion.service';

@Service()
export class JhiLanguageHelper {
    private translateService = inject(TranslateService);
    private localeConversionService = inject(LocaleConversionService);
    private titleService = inject(Title);
    private router = inject(Router);
    private sessionStorageService = inject(SessionStorageService);

    private renderer: Renderer2;
    private _language: BehaviorSubject<string>;

    constructor() {
        const rootRenderer = inject(RendererFactory2);

        this._language = new BehaviorSubject<string>(this.translateService.getCurrentLang() ?? 'en');
        this.renderer = rootRenderer.createRenderer(document.querySelector('html'), null);
        this.init();
    }

    /**
     * Get all supported ISO_639-1 language codes.
     */
    getAll(): string[] {
        return LANGUAGES;
    }

    get language(): Observable<string> {
        return this._language.asObservable();
    }

    /**
     * Update the window title using a value from the following order:
     * 1. The function's titleKey parameter
     * 2. The return value of {@link getPageTitle}, extracting it from the router state or a fallback value
     * The current route's parameters are interpolated into the translation, so a title can name what the page is about,
     * for example `{{ studentLogin }}`. A component setting the title itself would be overwritten on the next navigation.
     * If the translation doesn't exist, a Sentry exception is thrown.
     */
    updateTitle(titleKey?: string) {
        const root = this.router.routerState?.snapshot.root;
        if (!titleKey) {
            titleKey = this.getPageTitle(root);
        }

        this.translateService.get(titleKey, root ? this.getRouteParams(root) : {}).subscribe((title) => {
            if (title) {
                this.titleService.setTitle(title);
            } else {
                captureException(new Error(`Translation key '${titleKey}' for page title not found`));
            }
        });
    }

    private init() {
        this.translateService.onLangChange.subscribe(() => {
            const languageKey = this.translateService.getCurrentLang() ?? 'en';
            this._language.next(languageKey);
            this.localeConversionService.locale = languageKey;
            this.sessionStorageService.store('locale', languageKey);
            this.renderer.setAttribute(document.querySelector('html'), 'lang', this.translateService.getCurrentLang() ?? 'en');
            this.updateTitle();
        });
    }

    /**
     * Get the current page's title key based on the router state.
     * Fallback to 'global.title' when no key is found.
     * @param routeSnapshot The snapshot of the current route
     */
    getPageTitle(routeSnapshot: ActivatedRouteSnapshot) {
        let title: string = routeSnapshot.data?.['pageTitle'] || 'global.title';
        if (routeSnapshot.firstChild) {
            title = this.getPageTitle(routeSnapshot.firstChild) || title;
        }
        return title;
    }

    /**
     * Collects the parameters of every route from the given snapshot down to the deepest active child. A child's own
     * parameter wins over a parent's of the same name.
     * @param routeSnapshot The snapshot to start from
     */
    private getRouteParams(routeSnapshot: ActivatedRouteSnapshot): Record<string, string> {
        const params: Record<string, string> = {};
        let current: ActivatedRouteSnapshot | null = routeSnapshot;
        while (current) {
            for (const [key, value] of Object.entries(current.params ?? {})) {
                params[key] = value;
            }
            current = current.firstChild;
        }
        return params;
    }

    public determinePreferredLanguage(): string {
        const navigator = this.getNavigatorReference();
        // In the languages array the languages are ordered by preference with the most preferred language first.
        for (let i = 0; i < navigator.languages.length; i++) {
            // return the language with the highest preference
            if (navigator.languages[i].startsWith('en')) {
                return 'en';
            }
            if (navigator.languages[i].startsWith('de')) {
                return 'de';
            }
        }
        // english as fallback
        return 'en';
    }

    public getNavigatorReference(): Navigator {
        return navigator;
    }
}
