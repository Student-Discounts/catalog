package org.studentdiscounts.catalog.persistence;

import com.fasterxml.uuid.Generators;
import com.fasterxml.uuid.impl.TimeBasedEpochRandomGenerator;
import java.security.SecureRandom;
import java.util.UUID;

public final class CatalogIds {
  private static final TimeBasedEpochRandomGenerator GENERATOR =
      Generators.timeBasedEpochRandomGenerator(new SecureRandom());

  private CatalogIds() {}

  public static UUID newId() {
    return GENERATOR.generate();
  }
}
