package org.adancau.doneapi.security;

import org.springframework.context.annotation.*;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration
public class DatabaseSecurityConfiguration {
  @Bean
  @Profile("prod")
  ApplicationRunner requireRestrictedRuntimeRole(JdbcTemplate jdbc) {
    return args -> {
      boolean excessive=Boolean.TRUE.equals(jdbc.queryForObject(
          "SELECT rolsuper OR rolcreatedb OR rolcreaterole OR rolbypassrls "
          + "OR has_schema_privilege(current_user,'public','CREATE') "
          + "OR has_table_privilege(current_user,'public.flyway_schema_history','INSERT,UPDATE,DELETE') "
          + "FROM pg_roles WHERE rolname=current_user",Boolean.class));
      if(excessive)throw new IllegalStateException("Production requires a restricted runtime database role, separate from the Flyway owner.");
    };
  }
}
