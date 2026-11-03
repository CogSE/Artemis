package de.tum.cit.aet.artemis.tutorialgroup.api;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Controller;

import de.tum.cit.aet.artemis.tutorialgroup.config.TutorialGroupEnabled;
import de.tum.cit.aet.artemis.tutorialgroup.domain.TutorialGroup;
import de.tum.cit.aet.artemis.tutorialgroup.domain.TutorialGroupRegistration;
import de.tum.cit.aet.artemis.tutorialgroup.domain.TutorialGroupRegistrationType;
import de.tum.cit.aet.artemis.tutorialgroup.dto.StudentTutorialGroupDTO;
import de.tum.cit.aet.artemis.tutorialgroup.repository.TutorialGroupRegistrationRepository;

@Conditional(TutorialGroupEnabled.class)
@Controller
@Lazy
public class TutorialGroupRegistrationApi extends AbstractTutorialGroupApi {

    private final TutorialGroupRegistrationRepository tutorialGroupRegistrationRepository;

    public TutorialGroupRegistrationApi(TutorialGroupRegistrationRepository tutorialGroupRegistrationRepository) {
        this.tutorialGroupRegistrationRepository = tutorialGroupRegistrationRepository;
    }

    public Set<TutorialGroupRegistration> findAllByTutorialGroupAndType(TutorialGroup tutorialGroup, TutorialGroupRegistrationType type) {
        return tutorialGroupRegistrationRepository.findAllByTutorialGroupAndType(tutorialGroup, type);
    }

    /**
     * Finds all tutorial group registrations for a user for GDPR data export.
     *
     * @param userId the ID of the user
     * @return list of all registrations for the user with tutorial group information
     */
    public List<TutorialGroupRegistration> findAllByStudentIdForExport(long userId) {
        return tutorialGroupRegistrationRepository.findAllByStudentIdWithTutorialGroupAndCourse(userId);
    }

    /**
     * Finds the titles of the tutorial groups every registered student of a course belongs to, in one query.
     *
     * @param courseId the ID of the course
     * @return the tutorial group titles keyed by student id, each list ordered by title; students without a registration are absent
     */
    public Map<Long, List<String>> findTutorialGroupTitlesByStudentInCourse(long courseId) {
        return tutorialGroupRegistrationRepository.findAllStudentTutorialGroupsByCourseId(courseId).stream()
                .collect(Collectors.groupingBy(StudentTutorialGroupDTO::studentId, Collectors.mapping(StudentTutorialGroupDTO::title, Collectors.toCollection(ArrayList::new))));
    }
}
