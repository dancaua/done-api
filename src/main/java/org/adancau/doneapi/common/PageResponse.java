package org.adancau.doneapi.common;

import java.util.List;
import org.springframework.data.domain.*;

public record PageResponse<T>(
    List<T> items, int page, int size, long totalElements, int totalPages) {

  public static Pageable page(int page, int size) {
    if (page < 0 || size < 1 || size > 100)
      throw ApiException.invalid(
          "Paginarea permite page între 0 și 100000 și size între 1 și 100.");
    return PageRequest.of(page, size);
  }

  public static <T> PageResponse<T> from(Page<T> p) {
    return new PageResponse<>(
        p.getContent(), p.getNumber(), p.getSize(), p.getTotalElements(), p.getTotalPages());
  }
}
