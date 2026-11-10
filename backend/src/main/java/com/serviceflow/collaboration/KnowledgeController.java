package com.serviceflow.collaboration;

import com.serviceflow.auth.User;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class KnowledgeController {
  private final KnowledgeService knowledge;
  public KnowledgeController(KnowledgeService knowledge) { this.knowledge=knowledge; }

  public record Draft(@NotBlank @Size(max=160) String title,@NotBlank @Size(max=10000) String content,boolean confirmed) {}
  public record Review(@NotNull Boolean approved,boolean confirmed) {}

  @GetMapping("/knowledge")
  public List<Knowledge> list(@AuthenticationPrincipal User u) { return knowledge.list(u); }

  @PostMapping("/tickets/{id}/knowledge")
  @ResponseStatus(HttpStatus.CREATED)
  public Knowledge draft(@AuthenticationPrincipal User u,@PathVariable long id,@Valid @RequestBody Draft request) {
    return knowledge.draft(u,id,request);
  }

  @PostMapping("/knowledge/{id}/review")
  public Knowledge review(@AuthenticationPrincipal User u,@PathVariable long id,@Valid @RequestBody Review request) {
    return knowledge.review(u,id,request);
  }
}
