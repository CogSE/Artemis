package de.tum.cit.aet.artemis.programming.dto;

import org.jspecify.annotations.Nullable;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Options for importing a milestone exercise group from an archive.
 *
 * @param title     the title of the imported milestone, or {@code null} to keep the exported one (made unique if taken)
 * @param shortName the short name of the imported milestone, or {@code null} to keep the exported one (made unique if taken)
 * @param keepDates whether the exported timeline is kept; otherwise the imported group starts without any dates
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record MilestoneImportOptionsDTO(@Nullable String title, @Nullable String shortName, boolean keepDates) {
}
