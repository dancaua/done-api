package org.adancau.doneapi.auth;

import org.adancau.doneapi.site.SiteProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration
@EnableConfigurationProperties(RecoveryProperties.class)
public class RecoveryConfiguration {
  public RecoveryConfiguration(RecoveryProperties p, SiteProperties site, Environment env) {
    if (p.tokenTtl()==null || p.tokenTtl().compareTo(java.time.Duration.ofMinutes(1))<0 || p.tokenTtl().getSeconds()%60!=0 || p.tokenTtl().compareTo(java.time.Duration.ofMinutes(30))>0 || p.queueCapacity()<1)
      throw new IllegalStateException("Invalid recovery token TTL or queue capacity.");
    if (p.enabled() && (p.from()==null || !p.from().matches("[^\\s@<>]+@[^\\s@<>]+\\.[^\\s@<>]+")))
      throw new IllegalStateException("Configure a valid RECOVERY_FROM email address.");
    if (env.matchesProfiles("prod")) {
      if (!p.enabled() || !p.deliveryEnabled() || !env.getProperty("app.rate.enabled",Boolean.class,true))
        throw new IllegalStateException("Production requires request limits and password recovery delivery.");
      boolean tls=env.getProperty("spring.mail.properties.mail.smtp.starttls.enable",Boolean.class,false)
          && env.getProperty("spring.mail.properties.mail.smtp.starttls.required",Boolean.class,false);
      boolean ssl=env.getProperty("spring.mail.properties.mail.smtp.ssl.enable",Boolean.class,false);
      if ((!tls && !ssl) || !env.getProperty("spring.mail.properties.mail.smtp.auth",Boolean.class,false) || !env.getProperty("spring.mail.properties.mail.smtp.ssl.checkserveridentity",Boolean.class,false)
          || env.getProperty("spring.mail.host", "").isBlank() || env.getProperty("spring.mail.username", "").isBlank()
          || env.getProperty("spring.mail.password", "").isBlank())
        throw new IllegalStateException("Production recovery needs authenticated SMTP with verified TLS.");
    }
  }
  @Bean
  ThreadPoolTaskScheduler taskScheduler() {
    var scheduler=new ThreadPoolTaskScheduler();scheduler.setPoolSize(2);
    scheduler.setThreadNamePrefix("done-jobs-");return scheduler;
  }
  @Bean
  ThreadPoolTaskScheduler recoveryScheduler() {
    var scheduler=new ThreadPoolTaskScheduler();scheduler.setPoolSize(1);
    scheduler.setThreadNamePrefix("recovery-mail-");scheduler.setWaitForTasksToCompleteOnShutdown(true);
    scheduler.setAwaitTerminationSeconds(20);return scheduler;
  }
}
