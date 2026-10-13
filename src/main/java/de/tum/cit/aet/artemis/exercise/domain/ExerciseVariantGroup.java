package de.tum.cit.aet.artemis.exercise.domain;

import static de.tum.cit.aet.artemis.core.util.DateUtil.validateStrictDateSequence;

import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import jakarta.persistence.Column;
import jakarta.persistence.DiscriminatorColumn;
import jakarta.persistence.DiscriminatorType;
import jakarta.persistence.DiscriminatorValue;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Inheritance;
import jakarta.persistence.InheritanceType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;

import org.hibernate.annotations.ConcreteProxy;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.JsonTypeName;

import de.tum.cit.aet.artemis.core.domain.DomainObject;
import de.tum.cit.aet.artemis.core.domain.Parent;
import de.tum.cit.aet.artemis.core.exception.BadRequestAlertException;
import de.tum.cit.aet.artemis.course.domain.Course;

/**
 * An {@code ExerciseVariantGroup} bundles a set of {@link Exercise}s that are interchangeable variants of one another
 * (e.g. several versions of the same task that a student may choose between).
 * <p>
 * The group holds the settings that are shared across all of its variants:
 * <ul>
 * <li>{@link #maxPoints} – the cap on the points the group's variants contribute to the course score,</li>
 * <li>the date fields – a common timeline applied to every variant in the group,</li>
 * <li>{@link #descriptionHeading} and {@link #descriptionBody} – the text students see above the variants.</li>
 * </ul>
 * Owned directly by a {@code Course} (unidirectional, {@code course_id} FK on this table); the aggregated {@link Exercise}s
 * are non-owning and outlive the group's removal.
 * <p>
 * A {@link de.tum.cit.aet.artemis.programming.domain.ProgrammingExercise}'s "build and test student submissions after due
 * date" is <em>not</em> shared: it is derived per exercise from the due date and build plan, so each member keeps its own.
 */
@Entity
@Table(name = "exercise_variant_group")
@Inheritance(strategy = InheritanceType.SINGLE_TABLE)
@DiscriminatorColumn(name = "discriminator", discriminatorType = DiscriminatorType.STRING)
@DiscriminatorValue("V")
@ConcreteProxy
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonTypeName("variant")
@JsonSubTypes({ @JsonSubTypes.Type(value = MilestoneExerciseGroup.class, name = "milestone") })
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public class ExerciseVariantGroup extends DomainObject {

    @Column(name = "title", nullable = false)
    private String title;

    /** Cap on the group's contribution to the course score: {@code min(sum(points of variants), maxPoints)}. */
    @Nullable
    @Column(name = "max_points")
    private Double maxPoints;

    /**
     * Heading of the callout students see above the variants; {@code null} shows the default "Choose a variant" heading.
     * A milestone group never sets it: its milestone's problem statement takes the callout's place.
     */
    @Nullable
    @Column(name = "description_heading")
    private String descriptionHeading;

    /** Text under {@link #descriptionHeading}; {@code null} shows the default text naming the number of variants. */
    @Nullable
    @Column(name = "description_body")
    private String descriptionBody;

    @Nullable
    @Column(name = "release_date")
    private ZonedDateTime releaseDate;

    @Nullable
    @Column(name = "start_date")
    private ZonedDateTime startDate;

    @Nullable
    @Column(name = "due_date")
    private ZonedDateTime dueDate;

    @Nullable
    @Column(name = "assessment_due_date")
    private ZonedDateTime assessmentDueDate;

    @Nullable
    @Column(name = "example_solution_publication_date")
    private ZonedDateTime exampleSolutionPublicationDate;

    /**
     * The course the group belongs to. The key lives here rather than on the course so that a group cannot exist
     * without one: a course-less group is invisible to every course query and would linger forever.
     */
    @ManyToOne
    @JoinColumn(name = "course_id", nullable = false)
    @JsonIgnore
    @Parent
    private Course course;

    // Ignore "course" as well to break the Course -> exerciseVariantGroups -> group -> exercises -> exercise.course cycle,
    // mirroring the guard on Course.exercises.
    @OneToMany(mappedBy = "exerciseVariantGroup", fetch = FetchType.LAZY)
    @JsonIgnoreProperties(value = { "exerciseVariantGroup", "course" }, allowSetters = true)
    private Set<Exercise> exercises = new HashSet<>();

    public Course getCourse() {
        return course;
    }

    public void setCourse(Course course) {
        this.course = course;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(@NonNull String title) {
        this.title = title.strip();
    }

    @Nullable
    public Double getMaxPoints() {
        return maxPoints;
    }

    public void setMaxPoints(@Nullable Double maxPoints) {
        this.maxPoints = maxPoints;
    }

    @Nullable
    public String getDescriptionHeading() {
        return descriptionHeading;
    }

    /** Stores the stripped heading, or {@code null} for a blank one so the client falls back to its default. */
    public void setDescriptionHeading(@Nullable String descriptionHeading) {
        this.descriptionHeading = blankToNull(descriptionHeading);
    }

    @Nullable
    public String getDescriptionBody() {
        return descriptionBody;
    }

    /** Stores the stripped body, or {@code null} for a blank one so the client falls back to its default. */
    public void setDescriptionBody(@Nullable String descriptionBody) {
        this.descriptionBody = blankToNull(descriptionBody);
    }

    private static @Nullable String blankToNull(@Nullable String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    @Nullable
    public ZonedDateTime getReleaseDate() {
        return releaseDate;
    }

    public void setReleaseDate(@Nullable ZonedDateTime releaseDate) {
        this.releaseDate = releaseDate;
    }

    @Nullable
    public ZonedDateTime getStartDate() {
        return startDate;
    }

    public void setStartDate(@Nullable ZonedDateTime startDate) {
        this.startDate = startDate;
    }

    @Nullable
    public ZonedDateTime getDueDate() {
        return dueDate;
    }

    public void setDueDate(@Nullable ZonedDateTime dueDate) {
        this.dueDate = dueDate;
    }

    @Nullable
    public ZonedDateTime getAssessmentDueDate() {
        return assessmentDueDate;
    }

    public void setAssessmentDueDate(@Nullable ZonedDateTime assessmentDueDate) {
        this.assessmentDueDate = assessmentDueDate;
    }

    @Nullable
    public ZonedDateTime getExampleSolutionPublicationDate() {
        return exampleSolutionPublicationDate;
    }

    public void setExampleSolutionPublicationDate(@Nullable ZonedDateTime exampleSolutionPublicationDate) {
        this.exampleSolutionPublicationDate = exampleSolutionPublicationDate;
    }

    public Set<Exercise> getExercises() {
        return exercises;
    }

    public void setExercises(Set<Exercise> exercises) {
        this.exercises = exercises;
    }

    public void addExercise(Exercise exercise) {
        this.exercises.add(exercise);
        exercise.setExerciseVariantGroup(this);
    }

    public void removeExercise(Exercise exercise) {
        this.exercises.remove(exercise);
        exercise.setExerciseVariantGroup(null);
    }

    /**
     * Whether this group's timeline fields are internally consistent, mirroring {@link Exercise#validateBaseDates()}. All
     * configured dates must follow the strict ordering used for course exercises, and an assessment due date requires a due
     * date.
     *
     * @return {@code true} if the set dates do not contradict each other
     */
    public boolean areDatesValid() {
        // Read through the getters (not the raw fields) so a subclass that overrides them to delegate elsewhere
        // (e.g. MilestoneExerciseGroup, whose dates live on its MilestoneExercise) is validated correctly.
        ZonedDateTime release = getReleaseDate();
        ZonedDateTime start = getStartDate();
        ZonedDateTime due = getDueDate();
        ZonedDateTime assessmentDue = getAssessmentDueDate();
        ZonedDateTime exampleSolutionPublication = getExampleSolutionPublicationDate();
        boolean releaseDateValid = validateStrictDateSequence(List.of(), release, Arrays.asList(start, due, assessmentDue, exampleSolutionPublication));
        boolean startDateValid = validateStrictDateSequence(Collections.singletonList(release), start, Arrays.asList(due, assessmentDue, exampleSolutionPublication));
        boolean dueDateValid = validateStrictDateSequence(Arrays.asList(release, start), due, Arrays.asList(assessmentDue, exampleSolutionPublication));
        boolean assessmentDueDateValid = validateAssessmentDueDate(release, start, due, assessmentDue, exampleSolutionPublication);
        boolean exampleSolutionPublicationDateValid = validateStrictDateSequence(Arrays.asList(release, start, due, assessmentDue), exampleSolutionPublication, List.of());

        return releaseDateValid && startDateValid && dueDateValid && assessmentDueDateValid && exampleSolutionPublicationDateValid;
    }

    private static boolean validateAssessmentDueDate(ZonedDateTime release, ZonedDateTime start, ZonedDateTime due, ZonedDateTime assessmentDue,
            ZonedDateTime exampleSolutionPublication) {
        if (assessmentDue == null) {
            return true;
        }
        if (due == null) {
            return false;
        }
        return validateStrictDateSequence(Arrays.asList(release, start, due), assessmentDue, Collections.singletonList(exampleSolutionPublication));
    }

    /** Like {@link #areDatesValid()}, but throws so create/update callers reject an inconsistent timeline instead of saving it. */
    public void validateDates() {
        if (!areDatesValid()) {
            throw new BadRequestAlertException("The group dates are not valid", "exerciseVariantGroup", "noValidDates");
        }
    }

    @Override
    public String toString() {
        return "ExerciseVariantGroup{" + "id=" + getId() + ", title='" + title + "'" + ", maxPoints=" + maxPoints + "}";
    }
}
