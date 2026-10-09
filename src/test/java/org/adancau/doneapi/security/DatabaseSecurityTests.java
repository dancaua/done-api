package org.adancau.doneapi.security;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import org.junit.jupiter.api.Test;

class DatabaseSecurityTests {
  @Test void acceptsOnlyScopedDomainDml() {
    assertTrue(DatabaseSecurityConfiguration.safeGrants(List.of("GRANT USAGE ON *.* TO `done_runtime`@`%`", "GRANT SELECT, INSERT, UPDATE, DELETE ON `done_db`.`app_users` TO `done_runtime`@`%`"),"done_db"));
  }
  @Test void rejectsGlobalSchemaHistoryAndDelegatedPermissions() {
    for(String grant:List.of("GRANT ALL PRIVILEGES ON *.* TO `u`@`%`","GRANT SELECT, INSERT ON `done_db`.* TO `u`@`%`", "GRANT SELECT ON `done_db`.`flyway_schema_history` TO `u`@`%`", "GRANT SELECT ON `other`.`app_users` TO `u`@`%`", "GRANT CREATE ON `done_db`.`app_users` TO `u`@`%`", "GRANT SELECT ON `done_db`.`app_users` TO `u`@`%` WITH GRANT OPTION", "GRANT `admin`@`%` TO `u`@`%`"))
      assertFalse(DatabaseSecurityConfiguration.safeGrants(List.of(grant),"done_db"),grant);
  }
}
