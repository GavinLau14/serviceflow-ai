package com.serviceflow.ticket;

import static com.serviceflow.ticket.TicketRequests.*;

import com.serviceflow.auth.User;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/tickets")
public class TicketController {
  private final TicketService service;

  public TicketController(TicketService service) {
    this.service = service;
  }

  @GetMapping
  public TicketService.Page list(
      @AuthenticationPrincipal User u,
      @RequestParam(required = false) Status status,
      @RequestParam(required = false) String q,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "20") int size) {
    return service.list(u, status, q, page, size);
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public Ticket create(@AuthenticationPrincipal User u, @Valid @RequestBody Create req) {
    return service.create(u, req);
  }

  @GetMapping("/{id}")
  public TicketService.Detail detail(@AuthenticationPrincipal User u, @PathVariable long id) {
    return service.detail(u, id);
  }

  @PostMapping("/{id}/assign")
  public Ticket assign(
      @AuthenticationPrincipal User u, @PathVariable long id, @Valid @RequestBody Assign req) {
    return service.assign(u, id, req);
  }

  @PostMapping("/{id}/transition")
  public Ticket transition(
      @AuthenticationPrincipal User u, @PathVariable long id, @Valid @RequestBody Transition req) {
    return service.transition(u, id, req);
  }

  @PostMapping("/{id}/comments")
  @ResponseStatus(HttpStatus.CREATED)
  public Ticket comment(
      @AuthenticationPrincipal User u, @PathVariable long id, @Valid @RequestBody Comment req) {
    return service.comment(u, id, req);
  }
}
