package com.serviceflow.operations;

import com.serviceflow.auth.User;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/operations/sla-inbox")
public class SlaInboxController {
  private final SlaInboxService service;
  public SlaInboxController(SlaInboxService service){this.service=service;}
  @GetMapping
  public SlaInboxService.Page page(@AuthenticationPrincipal User user,@RequestParam(defaultValue="1") int page,
      @RequestParam(defaultValue="20") int size,@RequestParam(defaultValue="BREACHED") SlaInboxService.Filter filter,
      @RequestParam(required=false) String q){return service.page(user,page,size,filter,q);}
}
