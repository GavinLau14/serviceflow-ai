package com.serviceflow.operations;

import com.serviceflow.auth.User;
import com.serviceflow.common.ApiException;
import com.serviceflow.ticket.TicketService;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class OperationsController {
  private final OperationsService operations;
  private final TicketService tickets;
  public OperationsController(OperationsService operations, TicketService tickets) {
    this.operations=operations;this.tickets=tickets;
  }
  private void staff(User u) { if(u.is("CUSTOMER"))throw ApiException.forbidden(); }
  @GetMapping("/tickets/{id}/sla")
  public OperationsService.SlaView sla(@AuthenticationPrincipal User u,@PathVariable long id) {
    staff(u);return operations.view(tickets.require(u,id));
  }
  @GetMapping("/tickets/{id}/audit")
  public List<OperationsService.AuditView> audit(@AuthenticationPrincipal User u,@PathVariable long id) {
    staff(u);tickets.require(u,id);return operations.audit(id);
  }
  @GetMapping("/notifications")
  public List<OperationsService.Notice> notices(@AuthenticationPrincipal User u) {
    return operations.notifications(u);
  }
  @PostMapping("/notifications/{id}/read")
  public OperationsService.Notice read(@AuthenticationPrincipal User u,@PathVariable long id) {
    return operations.read(u,id);
  }
  public record ScanRequest(boolean confirmed) {}
  @PostMapping("/operations/scan")
  public OperationsService.ScanResult scan(@AuthenticationPrincipal User u,@RequestBody ScanRequest req) {
    if(!u.is("SUPERVISOR")&&!u.is("ADMIN"))throw ApiException.forbidden();
    if(u.is("SUPERVISOR")&&u.teamId==null)throw ApiException.forbidden();
    if(!req.confirmed())throw ApiException.bad("请显式确认扫描操作");
    return operations.scan(u.is("ADMIN")?null:u.teamId);
  }
}
