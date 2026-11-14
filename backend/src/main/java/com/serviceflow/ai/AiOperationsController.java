package com.serviceflow.ai;

import com.serviceflow.auth.User;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/operations")
public class AiOperationsController {
  private final AiOperationsService service;
  public AiOperationsController(AiOperationsService service){this.service=service;}
  @GetMapping("/ai-runs")
  public AiOperationsService.Page runs(@AuthenticationPrincipal User user,@RequestParam(defaultValue="24") int hours,
      @RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size,
      @RequestParam(defaultValue="") String mode,@RequestParam(defaultValue="false") boolean linkedOnly){return service.page(user,hours,page,size,mode,linkedOnly);}
  @GetMapping("/ai-health")
  public AiOperationsService.Health health(@AuthenticationPrincipal User user){return service.health(user);}
}
