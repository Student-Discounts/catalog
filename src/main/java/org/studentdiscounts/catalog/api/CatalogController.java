package org.studentdiscounts.catalog.api;

import java.util.UUID;
import org.springframework.web.bind.annotation.*;
import org.studentdiscounts.catalog.model.Category;

@RestController
@RequestMapping("/catalog/v1")
public class CatalogController {
  private final CatalogQueries queries;

  public CatalogController(CatalogQueries queries) {
    this.queries = queries;
  }

  @GetMapping("/categories/{code}")
  public Category category(@PathVariable String code) {
    return queries.category(code);
  }

  @GetMapping("/places/{id}")
  public CatalogQueries.PlaceView place(@PathVariable UUID id) {
    return queries.place(id);
  }

  @GetMapping("/offers/{id}")
  public CatalogQueries.OfferView offer(@PathVariable UUID id) {
    return queries.offer(id);
  }
}
