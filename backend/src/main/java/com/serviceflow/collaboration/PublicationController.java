package com.serviceflow.collaboration;

import com.serviceflow.auth.User;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/knowledge")
public class PublicationController {
  private final PublicationService service;
  public PublicationController(PublicationService service){this.service=service;}
  public record Publish(@Min(0) int expectedVersion,@NotBlank @Size(max=160) String title,
      @NotBlank @Size(max=1500) String text,boolean confirmed,boolean redacted) {}
  public record Action(@NotBlank @Size(max=36) String publicationId,boolean confirmed) {}
  @GetMapping("/{id}/publication") public PublicationService.History history(@AuthenticationPrincipal User u,@PathVariable long id){return service.history(u,id);}
  @PostMapping("/{id}/publication") public PublicationService.Publication publish(@AuthenticationPrincipal User u,@PathVariable long id,@Valid @RequestBody Publish req){return service.publish(u,id,req);}
  @PostMapping("/{id}/publication/sync") public PublicationService.Publication sync(@AuthenticationPrincipal User u,@PathVariable long id,@Valid @RequestBody Action req){return service.retry(u,id,req);}
  @PostMapping("/{id}/publication/withdraw") public PublicationService.Publication withdraw(@AuthenticationPrincipal User u,@PathVariable long id,@Valid @RequestBody Action req){return service.withdraw(u,id,req);}
  @GetMapping("/publications/{id}") public PublicationService.Document publicSource(@PathVariable String id){return service.publicSource(id);}
}
