package org.adancau.doneapi.auth;

import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.*;

/** Diagnostic events contain no recipients, tokens, credentials or exception messages. */
final class RecoveryDeliveryLog {
  private static final Logger log=LoggerFactory.getLogger(RecoveryDeliveryLog.class);
  private enum Failure { AUTHENTICATION, TLS, NETWORK_TIMEOUT, CONNECTION, DATABASE, SMTP, UNEXPECTED }

  static void requested() {
    log.info("Password recovery event=queued");
  }
  static void limited(boolean daily) {
    log.warn("Password recovery event=rate_limited window={}",daily ? "daily" : "five_minutes");
  }
  static void accepted(String kind) {
    log.info("Password recovery event=smtp_accepted kind={}",safeKind(kind));
  }
  static void skipped(String kind) {
    log.info("Password recovery event=skipped kind={} reason=ineligible_or_cancelled",safeKind(kind));
  }
  static void failed(String kind,int attempt,Throwable error) {
    log.warn("Password recovery event=delivery_failed kind={} attempt={} category={} retry={}",
        safeKind(kind),attempt,category(error),attempt<3);
  }
  private static String safeKind(String kind) {
    return Set.of("reset","changed").contains(kind) ? kind : "unknown";
  }
  private static Failure category(Throwable error) {
    if(error==null)return Failure.UNEXPECTED;
    var queue=new ArrayDeque<Throwable>();queue.add(error);
    var seen=Collections.newSetFromMap(new IdentityHashMap<Throwable,Boolean>());
    var failures=EnumSet.noneOf(Failure.class);
    while(!queue.isEmpty() && seen.size()<32) {
      var current=queue.removeFirst();if(!seen.add(current))continue;
      if(current instanceof MailAuthenticationException || current instanceof jakarta.mail.AuthenticationFailedException)
        failures.add(Failure.AUTHENTICATION);
      if(current instanceof javax.net.ssl.SSLException)failures.add(Failure.TLS);
      if(current instanceof java.net.SocketTimeoutException)failures.add(Failure.NETWORK_TIMEOUT);
      if(current instanceof java.net.ConnectException || current instanceof java.net.UnknownHostException
          || current instanceof java.net.SocketException)failures.add(Failure.CONNECTION);
      if(current instanceof org.springframework.dao.DataAccessException || current instanceof java.sql.SQLException)
        failures.add(Failure.DATABASE);
      if(current instanceof MailException || current instanceof jakarta.mail.MessagingException)failures.add(Failure.SMTP);
      if(current.getCause()!=null)queue.addLast(current.getCause());
      if(current instanceof MailSendException sending)
        for(var nested:sending.getMessageExceptions())if(nested!=null)queue.addLast(nested);
      if(current instanceof jakarta.mail.MessagingException messaging && messaging.getNextException()!=null)
        queue.addLast(messaging.getNextException());
    }
    for(var category:Failure.values())if(failures.contains(category))return category;
    return Failure.UNEXPECTED;
  }
}
