package vn.signa.sign.internal.feedback;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import vn.signa.sign.internal.review.ReviewRequests.LocationInput;

/** Thân yêu cầu chấp nhận báo cáo (FR-32). */
public record ReportAcceptance(
    @NotNull Action action, @NotNull @Min(0) Integer version, @Valid Changes changes) {

  @JsonIgnore
  @AssertTrue(message = "EDIT cần signTypeCode hoặc location, REMOVE không nhận changes")
  public boolean isChangesValid() {
    if (action == null) return true;
    return switch (action) {
      case EDIT ->
          changes != null && (changes.signTypeCode() != null || changes.location() != null);
      case REMOVE -> changes == null;
    };
  }

  public enum Action {
    EDIT,
    REMOVE;

    @JsonCreator
    public static Action fromJson(String name) {
      return valueOf(name);
    }
  }

  public record Changes(
      @Size(min = 1, max = 30) String signTypeCode, @Valid LocationInput location) {
    public Changes {
      if (signTypeCode != null) signTypeCode = signTypeCode.strip();
    }
  }
}
