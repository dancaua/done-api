package org.adancau.doneapi.security;

import java.sql.*;
import java.util.*;
import org.flywaydb.core.api.callback.*;
import org.springframework.context.annotation.*;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration
public class DatabaseSecurityConfiguration {
  @Bean
  @Profile("prod")
  Callback runtimeTableGrants() {
    return new Callback() {
      public boolean supports(Event event, Context context) { return event==Event.AFTER_MIGRATE; }
      public boolean canHandleInTransaction(Event event, Context context) { return false; }
      public String getCallbackName() { return "mysql-runtime-table-grants"; }
      public void handle(Event event, Context context) {
        // The migration principal has GRANT OPTION only on this database. Runtime has table DML only.
        try {
          var connection=context.getConnection();String db=connection.getCatalog();
          var tables=new ArrayList<String>();
          try(var query=connection.prepareStatement("SELECT TABLE_NAME FROM information_schema.TABLES WHERE TABLE_SCHEMA=? AND TABLE_TYPE='BASE TABLE' AND TABLE_NAME<>'flyway_schema_history'")) {
            query.setString(1,db);
            try(var rows=query.executeQuery()) { while(rows.next())tables.add(rows.getString(1)); }
          }
          try(var statement=connection.createStatement()) {
            for(String table:tables)statement.execute("GRANT SELECT,INSERT,UPDATE,DELETE ON "+quote(db)+"."+quote(table)+" TO 'done_runtime'@'%'");
          }
        } catch(SQLException e) { throw new IllegalStateException("Cannot provision MySQL runtime table privileges",e); }
      }
    };
  }
  private static String quote(String value) { return "`"+value.replace("`","``")+"`"; }

  @Bean
  @Profile("prod")
  ApplicationRunner requireRestrictedRuntimeRole(JdbcTemplate jdbc) {
    return args -> {
      String db=jdbc.queryForObject("SELECT DATABASE()",String.class);
      String roles=jdbc.queryForObject("SELECT CURRENT_ROLE()",String.class);
      if (!"NONE".equals(roles) || !safeGrants(jdbc.queryForList("SHOW GRANTS",String.class),db))
        throw new IllegalStateException("Production requires table-level DML privileges, no roles, DDL or Flyway history access; use the separate migration account.");
    };
  }

  static boolean safeGrants(List<String> grants,String database) {
    String scope=quote(database)+".";
    if(grants.isEmpty())return false;
    for(String grant:grants) {
      if(grant.startsWith("GRANT USAGE ON *.* TO ") && !grant.contains(" WITH GRANT OPTION"))continue;
      int on=grant.indexOf(" ON "),to=grant.indexOf(" TO ");
      if(!grant.startsWith("GRANT ") || on<0 || to<on || grant.contains(" WITH GRANT OPTION"))return false;
      String target=grant.substring(on+4,to);
      if(!target.startsWith(scope+"`") || !target.endsWith("`") || target.substring(scope.length()).contains("flyway_schema_history"))return false;
      for(String privilege:grant.substring(6,on).split(", "))
        if(!Set.of("SELECT","INSERT","UPDATE","DELETE").contains(privilege))return false;
    }
    return true;
  }
}
