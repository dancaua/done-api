package org.adancau.doneapi.common;

import java.time.Clock;
import java.util.UUID;
import java.util.function.Function;
import org.adancau.doneapi.persistence.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

@Service
public class MutationService {
  private final TransactionTemplate tx;
  private final UserRepository users;
  private final MutationReceiptRepository receipts;
  private final JsonMapper json;
  private final Clock clock;

  public MutationService(
      PlatformTransactionManager manager,
      UserRepository users,
      MutationReceiptRepository receipts,
      JsonMapper json,
      Clock clock) {
    this.tx = new TransactionTemplate(manager);
    this.users = users;
    this.receipts = receipts;
    this.json = json;
    this.clock = clock;
  }

  public <T> T execute(
      UUID owner,
      UUID requestId,
      String operation,
      Object payload,
      Class<T> resultType,
      Function<UserEntity, T> work) {
    if (requestId == null) throw ApiException.invalid("Idempotency-Key este obligatoriu.");
    String hash = Crypto.hash(operation + ":" + json.writeValueAsString(payload));
    return tx.execute(
        status -> {
          var user = users.lockById(owner).orElseThrow(ApiException::unauthorized);
          var cached = receipts.findByUserIdAndRequestId(owner, requestId);
          if (cached.isPresent()) {
            if (!cached.get().getRequestHash().equals(hash))
              throw ApiException.conflict(
                  "idempotency_conflict", "Această comandă a fost folosită cu alte date.");
            return json.readValue(cached.get().getResponseJson(), resultType);
          }
          user.setRevision(user.getRevision() + 1);
          T result = work.apply(user);
          var receipt = new MutationReceiptEntity();
          receipt.setUserId(owner);
          receipt.setRequestId(requestId);
          receipt.setRequestHash(hash);
          receipt.setResponseJson(json.writeValueAsString(result));
          receipt.setCreatedAt(clock.instant());
          receipts.save(receipt);
          return result;
        });
  }
}
