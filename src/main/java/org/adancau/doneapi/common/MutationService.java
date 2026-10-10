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
  private final org.adancau.doneapi.household.HouseholdAccess access;

  public MutationService(
      PlatformTransactionManager manager,
      UserRepository users,
      MutationReceiptRepository receipts,
      JsonMapper json,
      Clock clock,
      org.adancau.doneapi.household.HouseholdAccess access) {
    this.tx = new TransactionTemplate(manager);
    this.users = users;
    this.receipts = receipts;
    this.json = json;
    this.clock = clock;
    this.access = access;
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

  public UUID receiptHousehold(UUID actor, UUID requestId) {
    return receipts
        .findByUserIdAndRequestId(actor, requestId)
        .map(MutationReceiptEntity::getHouseholdId)
        .orElseThrow(ApiException::missing);
  }

  public <T> T executeShared(
      UUID actor,
      UUID household,
      UUID requestId,
      String operation,
      Object payload,
      Class<T> resultType,
      Function<UserEntity, T> work) {
    return executeSharedChecked(
        actor,
        household,
        requestId,
        operation,
        payload,
        resultType,
        () -> access.home(actor, household),
        work);
  }

  public <T> T executeSharedChecked(
      UUID actor,
      UUID household,
      UUID requestId,
      String operation,
      Object payload,
      Class<T> resultType,
      Runnable permission,
      Function<UserEntity, T> work) {
    if (requestId == null) throw ApiException.invalid("Idempotency-Key is required.");
    String hash = Crypto.hash(operation + ":" + json.writeValueAsString(payload));
    return tx.execute(
        status -> {
          var ids = new java.util.TreeSet<>(access.participants(household));
          ids.add(actor);
          var locked = new java.util.HashMap<UUID, UserEntity>();
          for (var id : ids)
            locked.put(id, users.lockById(id).orElseThrow(ApiException::unauthorized));
          permission.run(); // Check even on idempotent replay after removal.
          var ownerId = access.ownerId(household);
          var cached = receipts.findByUserIdAndRequestId(actor, requestId);
          if (cached.isPresent()) {
            if (!cached.get().getRequestHash().equals(hash))
              throw ApiException.conflict(
                  "idempotency_conflict", "Request key has different data.");
            return json.readValue(cached.get().getResponseJson(), resultType);
          }
          for (var user : locked.values()) user.setRevision(user.getRevision() + 1);
          T result = work.apply(locked.get(ownerId));
          var receipt = new MutationReceiptEntity();
          receipt.setUserId(actor);
          receipt.setHouseholdId(household);
          receipt.setRequestId(requestId);
          receipt.setRequestHash(hash);
          receipt.setResponseJson(json.writeValueAsString(result));
          receipt.setCreatedAt(clock.instant());
          receipts.save(receipt);
          return result;
        });
  }
}
