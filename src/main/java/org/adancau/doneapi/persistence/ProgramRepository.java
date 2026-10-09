package org.adancau.doneapi.persistence;

import java.util.*;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.*;

public interface ProgramRepository extends JpaRepository<ProgramEntity, UUID> {
  List<ProgramEntity> findByApplianceIdAndUserIdOrderByPositionAscIdAsc(
      UUID applianceId, UUID userId);

  Optional<ProgramEntity> findByIdAndApplianceIdAndUserId(UUID id, UUID applianceId, UUID userId);

  List<ProgramEntity> findByApplianceIdAndNameIgnoreCase(UUID applianceId, String name);
  Optional<ProgramEntity> findByApplianceIdAndNameIgnoreCaseAndMinutes(UUID applianceId, String name, int minutes);

  List<ProgramEntity> findByApplianceIdAndNameAndMinutes(UUID applianceId, String name, int minutes);

  long countByApplianceId(UUID applianceId);
}
