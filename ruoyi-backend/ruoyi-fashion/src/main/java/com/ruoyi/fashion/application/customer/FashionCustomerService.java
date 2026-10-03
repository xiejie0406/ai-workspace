package com.ruoyi.fashion.application.customer;

import com.ruoyi.fashion.configuration.FashionModuleEnabled;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.fashion.application.customer.port.FashionCustomerRepository;
import com.ruoyi.fashion.application.security.FashionCustomerAccessGuard;
import com.ruoyi.fashion.domain.customer.FashionCustomer;
import com.ruoyi.fashion.domain.shared.FashionId;
import com.ruoyi.fashion.domain.shared.FashionIdGenerator;
import com.ruoyi.fashion.domain.shared.FashionTimeSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@FashionModuleEnabled
@Service
public class FashionCustomerService {
    private static final Set<String> TYPES = Set.of("group_purchase", "wholesale");
    private static final Set<String> STATUSES = Set.of("active", "archived");

    private final FashionCustomerRepository repository;
    private final FashionCustomerAccessGuard accessGuard;
    private final FashionIdGenerator ids;
    private final FashionTimeSource time;
    private final TransactionTemplate transaction;

    public FashionCustomerService(
            FashionCustomerRepository repository,
            FashionCustomerAccessGuard accessGuard,
            FashionIdGenerator ids,
            FashionTimeSource time,
            @Qualifier("fashionTransactionTemplate") TransactionTemplate transaction) {
        this.repository = repository;
        this.accessGuard = accessGuard;
        this.ids = ids;
        this.time = time;
        this.transaction = transaction;
    }

    public CustomerPage search(String status, String keyword, int page, int pageSize) {
        if (status != null && !status.isBlank() && !STATUSES.contains(status)) {
            throw new ServiceException("客户状态无效");
        }
        int normalizedPage = Math.max(1, page);
        int normalizedSize = Math.min(100, Math.max(1, pageSize));
        long userId = SecurityUtils.getUserId();
        boolean administrator = SecurityUtils.isAdmin();
        var customers = repository.search(userId, administrator, trim(status), trim(keyword),
                (normalizedPage - 1) * normalizedSize, normalizedSize);
        var names = repository.salespersonNames(customers.stream().map(FashionCustomer::salespersonId).collect(java.util.stream.Collectors.toSet()));
        return new CustomerPage(
                customers.stream().map(customer -> CustomerView.from(customer,names.get(customer.salespersonId()))).toList(),
                repository.count(userId, administrator, trim(status), trim(keyword)),
                normalizedPage, normalizedSize);
    }

    public CustomerView get(String id) {
        return CustomerView.from(requireAccessible(FashionId.parse(id).value()));
    }

    public CustomerView create(CustomerCreate command, long operatorId) {
        validate(command);
        long salespersonId = command.salespersonId() == null ? operatorId : command.salespersonId();
        requireCanAssignSalesperson(operatorId, salespersonId);
        List<Long> collaborators = collaborators(command.collaboratorIds(), salespersonId);
        return transaction.execute(status -> {
            String code = command.code().trim();
            if (repository.findByCode(code).isPresent()) {
                throw new ServiceException("客户编码已存在：" + code);
            }
            Instant now = time.now();
            FashionCustomer customer = new FashionCustomer(ids.nextId(), code, command.name().trim(),
                    command.customerType().trim(), trim(command.contactName()), trim(command.contactPhone()),
                    trim(command.region()), salespersonId, collaborators, trim(command.internalNote()), "active",
                    operatorId, now, operatorId, now, 1L);
            repository.insert(customer);
            return CustomerView.from(customer);
        });
    }

    public CustomerView update(String id, CustomerUpdate command, long operatorId) {
        if (command == null) {
            throw new ServiceException("客户修改内容不能为空");
        }
        CustomerCreate validation = new CustomerCreate("VALID", command.name(), command.customerType(),
                command.contactName(), command.contactPhone(), command.region(), command.salespersonId(),
                command.collaboratorIds(), command.internalNote());
        validate(validation);
        long customerId = FashionId.parse(id).value();
        FashionCustomer current = requireAccessible(customerId);
        long salespersonId = command.salespersonId() == null ? current.salespersonId() : command.salespersonId();
        requireCanAssignSalesperson(operatorId, salespersonId);
        FashionCustomer target = new FashionCustomer(current.id(), current.code(), command.name().trim(),
                command.customerType().trim(), trim(command.contactName()), trim(command.contactPhone()),
                trim(command.region()), salespersonId, collaborators(command.collaboratorIds(), salespersonId),
                trim(command.internalNote()), current.status(), current.createBy(), current.createTime(), operatorId,
                time.now(), current.rowVersion() + 1);
        return transaction.execute(status -> {
            if (!repository.update(target, command.rowVersion())) {
                throw conflict();
            }
            return CustomerView.from(requireAccessible(customerId));
        });
    }

    public CustomerView archive(String id, long rowVersion, long operatorId) {
        long customerId = FashionId.parse(id).value();
        requireAccessible(customerId);
        return transaction.execute(status -> {
            if (!repository.updateStatus(customerId, "archived", rowVersion, operatorId, time.now())) {
                throw conflict();
            }
            return CustomerView.from(requireAccessible(customerId));
        });
    }

    public FashionCustomer requireAccessible(long id) {
        FashionCustomer customer = repository.findById(id)
                .orElseThrow(() -> new ServiceException("客户不存在或已不可见"));
        accessGuard.requireCurrentUser(customer.salespersonId(), customer.collaboratorIds());
        return customer;
    }

    private static void validate(CustomerCreate command) {
        if (command == null) {
            throw new ServiceException("客户数据不能为空");
        }
        requireCode(command.code());
        requireText("客户名称", command.name(), 80);
        if (!TYPES.contains(command.customerType())) {
            throw new ServiceException("客户类型必须是 group_purchase 或 wholesale");
        }
        requireOptional("联系人", command.contactName(), 80);
        requireOptional("联系电话", command.contactPhone(), 512);
        requireOptional("地区", command.region(), 120);
        requireOptional("内部备注", command.internalNote(), 2000);
    }

    private static List<Long> collaborators(List<Long> values, long salespersonId) {
        LinkedHashSet<Long> result = new LinkedHashSet<>();
        if (values != null) {
            if (values.size() > 20) {
                throw new ServiceException("客户协作者不能超过 20 人");
            }
            values.forEach(value -> {
                if (value == null || value <= 0) {
                    throw new ServiceException("客户协作者 ID 无效");
                }
                if (value != salespersonId) {
                    result.add(value);
                }
            });
        }
        return List.copyOf(result);
    }

    private static void requireCanAssignSalesperson(long operatorId, long salespersonId) {
        if (salespersonId <= 0) {
            throw new ServiceException("负责销售 ID 无效");
        }
        if (!SecurityUtils.isAdmin() && salespersonId != operatorId) {
            throw new ServiceException("只有管理员可以把客户分配给其他销售", 403);
        }
    }

    private static void requireCode(String value) {
        requireText("客户编码", value, 64);
        if (!value.matches("[A-Za-z0-9][A-Za-z0-9._-]*")) {
            throw new ServiceException("客户编码只能包含字母、数字、点、下划线和连字符");
        }
    }

    private static void requireText(String field, String value, int maximum) {
        if (value == null || value.isBlank() || value.trim().length() > maximum) {
            throw new ServiceException(field + "必须为 1～" + maximum + " 个字符");
        }
    }

    private static void requireOptional(String field, String value, int maximum) {
        if (value != null && value.trim().length() > maximum) {
            throw new ServiceException(field + "长度不能超过 " + maximum);
        }
    }

    private static String trim(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static ServiceException conflict() {
        return new ServiceException("客户已被其他操作修改，请刷新后重试", 409);
    }
}
