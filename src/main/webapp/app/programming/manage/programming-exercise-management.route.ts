import {
    programmingExerciseBuildPlanRoute,
    programmingExerciseDetailRoute,
    programmingExerciseGradingRoute,
    programmingExerciseUpdateRoute,
    programmingExerciseVersionHistoryRoute,
} from 'app/programming/manage/programming-exercise-shared.route';
import { Routes } from '@angular/router';
import { UserRouteAccessService } from 'app/core/auth/user-route-access-service';

import { IS_AT_LEAST_EDITOR, IS_AT_LEAST_TUTOR } from 'app/foundation/constants/authority.constants';

import { ProgrammingExerciseResolve } from 'app/programming/manage/services/programming-exercise-resolve.service';
import { repositorySubRoutes } from 'app/programming/shared/routes/programming-exercise-repository.route';
import {
    CodeEditorTutorAssessmentContainerComponent,
    canLeaveCodeEditorTutorAssessmentContainer,
} from 'app/programming/manage/assess/code-editor-tutor-assessment-container/code-editor-tutor-assessment-container.component';

export const routes: Routes = [
    // Milestone create/edit reuses the same programming-exercise update page as a normal exercise (see
    // ProgrammingExerciseUpdateComponent.isMilestoneMode), so the milestone's settings (language, package,
    // build config, static code analysis, ...) can be configured exactly like a programming exercise, minus
    // Problem Statement/Points/Assessment - those stay independently configured per UserStoryExercise.
    programmingExerciseUpdateRoute('milestone-exercise-groups/new', 'artemisApp.exerciseManagement.addModal.milestoneGroup.title'),
    programmingExerciseUpdateRoute('milestone-exercise-groups/:exerciseId/edit', 'artemisApp.exerciseManagement.addModal.milestoneGroup.title'),
    {
        // The anchor MilestoneExercise owns the group's shared template/solution/test repositories and is the only
        // exercise of the group that ever runs a build, so it gets the same detail page a normal programming exercise
        // has (repository URIs, build plan links, template/solution build status). Declared after the 'new' and 'edit'
        // routes above, which would otherwise be swallowed by this route's ':exerciseId' segment.
        path: 'milestone-exercise-groups/:exerciseId',
        loadComponent: () => import('app/programming/manage/detail/programming-exercise-detail.component').then((m) => m.ProgrammingExerciseDetailComponent),
        resolve: {
            programmingExercise: ProgrammingExerciseResolve,
        },
        data: {
            authorities: IS_AT_LEAST_TUTOR,
            pageTitle: 'artemisApp.exerciseVariantGroup.milestoneDetail.title',
        },
        canActivate: [UserRouteAccessService],
    },
    {
        // jhi-code-button navigates to the repository view relatively (['.', 'repository', type], see
        // programming-repository-buttons-detail.component.html), so the milestone detail page needs its own copy of
        // the repository sub-routes below the programming-exercise ones.
        path: 'milestone-exercise-groups/:exerciseId/repository/:repositoryType',
        children: repositorySubRoutes,
    },
    {
        path: 'milestone-exercise-groups/:exerciseId/repository/:repositoryType/:repositoryId',
        children: repositorySubRoutes,
    },
    {
        // Keyed on the group id, not on the anchor exercise id the sibling milestone routes use: both endpoints behind
        // this page address the group, and neither page ever loads the anchor as an exercise.
        path: 'milestone-exercise-groups/:groupId/assessment-dashboard',
        loadComponent: () =>
            import('app/programming/manage/assess/milestone-assessment/milestone-assessment-dashboard.component').then((m) => m.MilestoneAssessmentDashboardComponent),
        data: {
            authorities: IS_AT_LEAST_TUTOR,
            pageTitle: 'artemisApp.milestoneAssessment.dashboard.title',
        },
        canActivate: [UserRouteAccessService],
    },
    {
        path: 'milestone-exercise-groups/:groupId/assessment/:studentLogin',
        loadComponent: () => import('app/programming/manage/assess/milestone-assessment/milestone-assessment.component').then((m) => m.MilestoneAssessmentComponent),
        data: {
            authorities: IS_AT_LEAST_TUTOR,
            pageTitle: 'artemisApp.milestoneAssessment.title',
        },
        canActivate: [UserRouteAccessService],
    },
    // UserStory create reuses the same programming-exercise update page as a normal exercise (see
    // ProgrammingExerciseUpdateComponent.isUserStoryMode), minus everything the milestone group already owns
    // (language, package, build config, static code analysis, timeline, ...) - only title/short name/categories/
    // difficulty and Problem Statement/Points/Assessment are configured here.
    programmingExerciseUpdateRoute('user-story-exercises/new', 'artemisApp.exerciseManagement.addModal.userStory.title'),
    programmingExerciseUpdateRoute('programming-exercises/new'),
    programmingExerciseUpdateRoute('programming-exercises/:exerciseId/edit'),
    programmingExerciseUpdateRoute('programming-exercises/import/:exerciseId', 'artemisApp.programmingExercise.home.importLabel'),
    programmingExerciseUpdateRoute('programming-exercises/import-from-file', 'artemisApp.programmingExercise.home.importLabel'),
    programmingExerciseUpdateRoute('programming-exercises/import-from-sharing', 'artemisApp.programmingExercise.home.importLabel'),
    programmingExerciseDetailRoute('programming-exercises/:exerciseId'),
    programmingExerciseVersionHistoryRoute('programming-exercises/:exerciseId/version-history'),
    {
        path: 'programming-exercises/:exerciseId/plagiarism',
        loadComponent: () => import('app/plagiarism/manage/plagiarism-inspector/plagiarism-inspector.component').then((m) => m.PlagiarismInspectorComponent),
        resolve: {
            exercise: ProgrammingExerciseResolve,
        },
        data: {
            authorities: IS_AT_LEAST_EDITOR,
            pageTitle: 'artemisApp.plagiarism.plagiarismDetection',
        },
        canActivate: [UserRouteAccessService],
    },
    programmingExerciseGradingRoute('programming-exercises/:exerciseId/grading/:tab'),
    {
        path: 'programming-exercises/:exerciseId/exercise-statistics',
        loadComponent: () => import('app/exercise/statistics/exercise-statistics.component').then((m) => m.ExerciseStatisticsComponent),
        resolve: {
            exercise: ProgrammingExerciseResolve,
        },
        data: {
            authorities: IS_AT_LEAST_TUTOR,
            pageTitle: 'exercise-statistics.title',
        },
        canActivate: [UserRouteAccessService],
    },
    programmingExerciseBuildPlanRoute('programming-exercises/:exerciseId/edit-build-plan'),
    {
        path: 'programming-exercises/:exerciseId/repository/:repositoryType',
        children: repositorySubRoutes,
    },
    {
        path: 'programming-exercises/:exerciseId/repository/:repositoryType/:repositoryId',
        children: repositorySubRoutes,
    },
    {
        path: 'programming-exercises/:exerciseId/submissions/:submissionId/assessment',
        component: CodeEditorTutorAssessmentContainerComponent,
        data: {
            authorities: IS_AT_LEAST_TUTOR,
            pageTitle: 'artemisApp.programmingExercise.home.title',
        },
        canActivate: [UserRouteAccessService],
        canDeactivate: [canLeaveCodeEditorTutorAssessmentContainer],
    },
];
