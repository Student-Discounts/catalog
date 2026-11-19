package org.studentdiscounts.catalog.lifecycle;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.studentdiscounts.catalog.events.CatalogEvents;
import org.studentdiscounts.catalog.model.Category;
import org.studentdiscounts.catalog.persistence.CatalogRepository;

@Service
public class CatalogCategories {
  private final CatalogRepository repository;
  private final NamedParameterJdbcTemplate jdbc;
  private final CatalogEvents events;

  public CatalogCategories(
      CatalogRepository repository, NamedParameterJdbcTemplate jdbc, CatalogEvents events) {
    this.repository = repository;
    this.jdbc = jdbc;
    this.events = events;
  }

  @Transactional
  public void save(Category category) {
    var previous =
        jdbc.query(
            "SELECT name FROM categories WHERE code=:code FOR UPDATE",
            Map.of("code", category.code()),
            (rs, n) -> rs.getString(1));
    repository.saveCategory(category);
    if (!previous.isEmpty() && previous.getFirst().equals(category.name())) return;
    var places =
        jdbc.query(
            "SELECT id FROM places WHERE category_code=:code ORDER BY id FOR UPDATE",
            Map.of("code", category.code()),
            (rs, n) -> rs.getObject(1, UUID.class));
    places.forEach(id -> events.record(id, List.of()));
  }
}
