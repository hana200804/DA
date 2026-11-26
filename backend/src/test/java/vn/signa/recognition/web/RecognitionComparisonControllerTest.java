package vn.signa.recognition.web;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import vn.signa.identity.internal.RolePermissions;
import vn.signa.identity.internal.SessionService;
import vn.signa.identity.web.WithApiSecurity;
import vn.signa.recognition.internal.CompareResult;
import vn.signa.recognition.internal.DetectResult;
import vn.signa.recognition.internal.RecognitionService;

@WebMvcTest(RecognitionComparisonController.class)
@WithApiSecurity
class RecognitionComparisonControllerTest {

  private static final String PATH = "/api/v1/admin/recognition/compare";
  private static final String TOKEN = "t".repeat(43);
  private final MockMultipartFile image = new MockMultipartFile("image", new byte[] {1});

  @Autowired MockMvc mvc;
  @Autowired SessionService sessions;
  @Autowired RolePermissions permissions;
  @MockitoBean RecognitionService recognition;

  @BeforeEach
  void authenticate() {
    when(sessions.authenticate(TOKEN))
        .thenReturn(Optional.of(new SessionService.ActiveSession("s1", "u1", "MODERATOR")));
    when(permissions.of("MODERATOR")).thenReturn(Set.of("recognition:compare"));
  }

  @Test
  void returnsImageAndErrorForFailedModel() throws Exception {
    when(recognition.compare(image, "u1"))
        .thenReturn(
            new CompareResult(
                new DetectResult.ImageSize(32, 24),
                List.of(
                    new CompareResult.ModelResult(
                        new CompareResult.ModelRef("detr", null, null),
                        null,
                        null,
                        new CompareResult.ModelError("Dịch vụ bận")))));

    mvc.perform(multipart(PATH).file(image).header("Authorization", "Bearer " + TOKEN))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.image.width").value(32))
        .andExpect(jsonPath("$.results[0].model.name").value("detr"))
        .andExpect(jsonPath("$.results[0].model.version").doesNotHaveJsonPath())
        .andExpect(jsonPath("$.results[0].detections").doesNotHaveJsonPath())
        .andExpect(jsonPath("$.results[0].error.message").value("Dịch vụ bận"));
    verify(recognition).compare(image, "u1");
  }

  @Test
  void imageIsRequired() throws Exception {
    mvc.perform(multipart(PATH).header("Authorization", "Bearer " + TOKEN))
        .andExpect(status().isBadRequest());
    verifyNoInteractions(recognition);
  }
}
