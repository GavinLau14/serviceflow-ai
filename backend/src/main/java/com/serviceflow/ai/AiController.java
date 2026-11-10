package com.serviceflow.ai;

import com.serviceflow.auth.User;
import com.serviceflow.ticket.Ticket;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/ai")
public class AiController {
  private final AiService service;

  public AiController(AiService service) {
    this.service = service;
  }

  public record Ask(@NotBlank @Size(max = 5000) String message) {}

  public record Consent(boolean confirmed) {}

  public record HandoffRequest(boolean confirmed, @NotNull com.serviceflow.ticket.TicketRequests.Category category) {}

  @PostMapping("/assist")
  public AiModels.RunView assist(@AuthenticationPrincipal User u, @Valid @RequestBody Ask ask) {
    return service.assist(u, ask.message());
  }

  @GetMapping("/runs")
  public java.util.List<AiModels.RunView> history(@AuthenticationPrincipal User u) {
    return service.history(u);
  }

  @PostMapping("/runs/{id}/execute")
  public Ticket execute(
      @AuthenticationPrincipal User u, @PathVariable String id, @Valid @RequestBody Consent req) {
    return service.execute(u, id, req.confirmed());
  }

  @PostMapping("/runs/{id}/handoff")
  public Ticket handoff(
      @AuthenticationPrincipal User u, @PathVariable String id, @Valid @RequestBody HandoffRequest req) {
    return service.handoff(u, id, req.confirmed(), req.category());
  }

  @PostMapping("/index")
  public Object index(@AuthenticationPrincipal User u) {
    return service.index(u);
  }
}
