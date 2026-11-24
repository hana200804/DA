package vn.signa.recognition.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import vn.signa.recognition.internal.DetectResult;
import vn.signa.recognition.internal.ModelList;
import vn.signa.recognition.internal.RecognitionService;

/** Nhận diện ảnh trên server, công khai, có giới hạn tần suất (docs/07 §5, FR-02). */
@RestController
@RequestMapping("/api/v1/recognition")
class RecognitionController {

  private final RecognitionService recognition;

  RecognitionController(RecognitionService recognition) {
    this.recognition = recognition;
  }

  @GetMapping("/models")
  ModelList models() {
    return recognition.models();
  }

  @PostMapping(path = "/detect", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  DetectResult detect(
      @RequestPart("image") MultipartFile image,
      @RequestParam(name = "model", required = false) String model,
      HttpServletRequest request) {
    return recognition.detect(image, model, request.getRemoteAddr());
  }
}
