package com.ruoyi.fashion.application.customer.port;

import java.util.List;
import java.util.Optional;

import com.ruoyi.fashion.domain.customer.FashionCustomer;

public interface FashionCustomerRepository {
    default java.util.Map<Long,String> salespersonNames(java.util.Set<Long> ids) { return java.util.Map.of(); }
    List<FashionCustomer> search(long userId, boolean administrator, String status, String keyword, int offset, int limit);

    long count(long userId, boolean administrator, String status, String keyword);

    Optional<FashionCustomer> findById(long id);

    Optional<FashionCustomer> findByCode(String code);

    void insert(FashionCustomer customer);

    boolean update(FashionCustomer customer, long expectedRowVersion);

    boolean updateStatus(long id, String status, long expectedRowVersion, long operatorId, java.time.Instant now);
}
