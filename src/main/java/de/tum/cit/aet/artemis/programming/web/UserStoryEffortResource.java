package de.tum.cit.aet.artemis.programming.web;

import static de.tum.cit.aet.artemis.core.config.Constants.PROFILE_CORE;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import de.tum.cit.aet.artemis.account.domain.User;
import de.tum.cit.aet.artemis.account.repository.UserRepository;
import de.tum.cit.aet.artemis.core.security.annotations.EnforceAtLeastStudent;
import de.tum.cit.aet.artemis.programming.domain.UserStoryExercise;
import de.tum.cit.aet.artemis.programming.dto.UserStoryEffortDTO;
import de.tum.cit.aet.artemis.programming.dto.UserStoryEffortStatusDTO;
import de.tum.cit.aet.artemis.programming.service.UserStoryEffortService;

/**
 * REST controller for the effort a participant has reported on a {@link UserStoryExercise}: what they estimated the
 * story would take, and what it actually took - both summed from the participant's task board, and read-only.
 * <p>
 * Every endpoint acts on the requesting user's own participation only - there is no way to read someone else's
 * through here.
 */
@Profile(PROFILE_CORE)
@Lazy
@RestController
@RequestMapping("api/programming/")
public class UserStoryEffortResource {

    private static final Logger log = LoggerFactory.getLogger(UserStoryEffortResource.class);

    private final UserStoryEffortService userStoryEffortService;

    private final UserRepository userRepository;

    public UserStoryEffortResource(UserStoryEffortService userStoryEffortService, UserRepository userRepository) {
        this.userStoryEffortService = userStoryEffortService;
        this.userRepository = userRepository;
    }

    /**
     * GET /user-story-exercises/:exerciseId/effort : Get the effort summed from the requesting user's board for the
     * story.
     *
     * @param exerciseId the id of the user story exercise
     * @return the ResponseEntity with status 200 (OK) and the summed pair in the body
     */
    @GetMapping("user-story-exercises/{exerciseId}/effort")
    @EnforceAtLeastStudent
    public ResponseEntity<UserStoryEffortDTO> getUserStoryEffort(@PathVariable long exerciseId) {
        log.debug("REST request to get the reported effort for user story exercise {}", exerciseId);
        User user = userRepository.getUser();
        return ResponseEntity.ok(userStoryEffortService.findForUser(exerciseId, user));
    }

    /**
     * GET /courses/:courseId/user-story-efforts : Every user story in the course the requesting user has started,
     * with the effort summed from its board.
     * <p>
     * One request for the whole exercise overview, which marks the stories still without any tasks.
     *
     * @param courseId the id of the course
     * @return the ResponseEntity with status 200 (OK) and one entry per started story
     */
    @GetMapping("courses/{courseId}/user-story-efforts")
    @EnforceAtLeastStudent
    public ResponseEntity<List<UserStoryEffortStatusDTO>> getUserStoryEffortsForCourse(@PathVariable long courseId) {
        log.debug("REST request to get the reported user story efforts in course {}", courseId);
        User user = userRepository.getUser();
        return ResponseEntity.ok(userStoryEffortService.findAllForCourse(courseId, user));
    }
}
