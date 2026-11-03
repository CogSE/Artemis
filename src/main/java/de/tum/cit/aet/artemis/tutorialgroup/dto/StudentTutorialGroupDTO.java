package de.tum.cit.aet.artemis.tutorialgroup.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * One student's registration in one tutorial group, reduced to what another module needs to label the student with
 * their group.
 *
 * @param studentId the id of the registered student
 * @param title     the title of the tutorial group the student is registered in
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record StudentTutorialGroupDTO(long studentId, String title) {
}
