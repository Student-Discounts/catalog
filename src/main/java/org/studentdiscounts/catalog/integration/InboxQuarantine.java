package org.studentdiscounts.catalog.integration;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class InboxQuarantine {
  private final NamedParameterJdbcTemplate jdbc;

  public InboxQuarantine(NamedParameterJdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Transactional
  public void save(ConsumerRecord<String, String> record, RuntimeException failure) {
    var params =
        new MapSqlParameterSource()
            .addValue("topic", record.topic())
            .addValue("partition", record.partition())
            .addValue("offset", record.offset())
            .addValue("key", record.key())
            .addValue("body", record.value())
            .addValue("failure", failure.getClass().getSimpleName());
    jdbc.update(
        """
        INSERT INTO catalog_inbox_quarantine(topic,partition_no,record_offset,message_key,payload,failure_type)
        VALUES(:topic,:partition,:offset,:key,:body,:failure) ON CONFLICT DO NOTHING
        """,
        params);
  }
}
