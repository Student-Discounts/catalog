package org.studentdiscounts.catalog.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.net.URI;
import java.util.Objects;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record Source(SourceType type, String url, String note) {
  public Source {
    Objects.requireNonNull(type, "source.type");
    url = Fields.optionalText(url, "source.url", 2048);
    note = Fields.optionalText(note, "source.note", 1000);
    if (url == null && note == null) throw new IllegalArgumentException("source");
    if (url != null) {
      URI uri = URI.create(url);
      if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
          || uri.getHost() == null
          || uri.getUserInfo() != null) {
        throw new IllegalArgumentException("source.url");
      }
      url =
          uri.getScheme().toLowerCase(java.util.Locale.ROOT)
              + url.substring(uri.getScheme().length());
    }
  }
}
