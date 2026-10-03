package com.ruoyi.fashion.application.customer;

import java.time.Instant;
import java.util.List;

import com.ruoyi.fashion.domain.customer.FashionCustomer;

public record CustomerView(
        String id,
        String code,
        String name,
        String customerType,
        String contactName,
        String contactPhone,
        String region,
        String salespersonId,
        List<String> collaboratorIds,
        String internalNote,
        String status,
        Instant updateTime,
        long rowVersion,
        String salespersonName) {

    public static CustomerView from(FashionCustomer customer) {
        return from(customer,null);
    }
    public static CustomerView from(FashionCustomer customer,String salespersonName) {
        return new CustomerView(
                Long.toString(customer.id()), customer.code(), customer.name(), customer.customerType(),
                customer.contactName(), customer.contactPhone(), customer.region(),
                Long.toString(customer.salespersonId()),
                customer.collaboratorIds().stream().map(String::valueOf).toList(), customer.internalNote(),
                customer.status(), customer.updateTime(), customer.rowVersion(),salespersonName);
    }
}
