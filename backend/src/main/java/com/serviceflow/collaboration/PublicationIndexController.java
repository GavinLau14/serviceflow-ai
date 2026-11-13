package com.serviceflow.collaboration;

import com.serviceflow.auth.User;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/knowledge/index-operations")
public class PublicationIndexController {
  private final PublicationIndexOperations service;
  public PublicationIndexController(PublicationIndexOperations service){this.service=service;}
  @GetMapping
  public PublicationIndexOperations.Page page(@AuthenticationPrincipal User user,
      @RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size,
      @RequestParam(defaultValue="false") boolean problemsOnly){return service.page(user,page,size,problemsOnly);}
}
