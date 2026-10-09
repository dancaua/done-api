package org.adancau.doneapi.config;

import java.util.Arrays;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.*;

@Configuration
public class JdbcConfiguration {
  @Bean
  JdbcTemplate jdbcTemplate(DataSource dataSource) {
    // Connector/J otherwise serializes setObject(UUID) as binary. Match Hibernate's CHAR UUID mapping.
    var template = new JdbcTemplate(dataSource) {
      @Override
      protected PreparedStatementSetter newArgPreparedStatementSetter(Object[] args) {
        return super.newArgPreparedStatementSetter(args == null ? null : Arrays.stream(args)
            .map(value -> value instanceof UUID id ? id.toString() : value).toArray());
      }
    };
    template.setExceptionTranslator(new org.springframework.jdbc.support.SQLExceptionSubclassTranslator() {
      @Override
      protected org.springframework.dao.DataAccessException doTranslate(String task,String sql,java.sql.SQLException exception) {
        // MySQL CHECK violations use generic HY000 instead of the integrity SQLSTATE class.
        if(exception.getErrorCode()==3819)
          return new org.springframework.dao.DataIntegrityViolationException("MySQL CHECK constraint violated",exception);
        return super.doTranslate(task,sql,exception);
      }
    });
    return template;
  }
}
