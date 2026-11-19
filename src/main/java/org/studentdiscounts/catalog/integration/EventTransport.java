package org.studentdiscounts.catalog.integration;

import java.util.Set;

public interface EventTransport {
  Set<String> topics();

  void send(String topic, String key, String payload);
}
