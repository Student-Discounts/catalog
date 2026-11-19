package org.studentdiscounts.catalog.api;

import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/catalog")
public class PublicCatalogController {
  private final PublicCatalogQueries queries;

  public PublicCatalogController(PublicCatalogQueries queries) {
    this.queries = queries;
  }

  @GetMapping("/places/{id}")
  public PublicCatalogQueries.PlaceCard place(@PathVariable UUID id) {
    return queries.place(id);
  }
}
