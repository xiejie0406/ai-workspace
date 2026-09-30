-- v0.3 用户批准的方案池增量。旧销售价保持原义，双价缺失保持 NULL。
alter table fq_product
    add column group_price decimal(16,2) null comment '团购价，与商品币种税口径一致',
    add column vip_price decimal(16,2) null comment 'VIP价，与商品币种税口径一致',
    add constraint ck_fq_product_group_price check (group_price is null or group_price >= 0),
    add constraint ck_fq_product_vip_price check (vip_price is null or vip_price >= 0);

create table fq_selection_pool (
    id varchar(36) not null primary key,
    owner_id bigint not null,
    title varchar(200) not null,
    request_json longtext not null,
    snapshot_json longtext not null,
    create_time datetime(3) not null,
    key idx_fq_pool_owner_time (owner_id, create_time),
    constraint ck_fq_pool_request_json check (json_valid(request_json)),
    constraint ck_fq_pool_snapshot_json check (json_valid(snapshot_json))
) engine=innodb default charset=utf8mb4 collate=utf8mb4_unicode_ci comment='方案池筛选及主SKU候选快照，不落全量组合';

create table fq_selection_draft (
    pool_id varchar(36) not null primary key,
    owner_id bigint not null,
    row_version bigint not null default 1,
    selection_json longtext not null,
    update_time datetime(3) not null,
    key idx_fq_selection_draft_owner (owner_id),
    constraint fk_fq_selection_draft_pool foreign key (pool_id) references fq_selection_pool(id) on delete restrict,
    constraint ck_fq_selection_draft_json check (json_valid(selection_json)),
    constraint ck_fq_selection_draft_version check (row_version >= 1)
) engine=innodb default charset=utf8mb4 collate=utf8mb4_unicode_ci comment='方案池已选草稿，独立于正式报价';

create table fq_proposal_image_task (
    id varchar(36) not null primary key,
    pool_id varchar(36) not null,
    owner_id bigint not null,
    draft_version bigint not null,
    combo_id varchar(512) not null,
    input_hash varchar(64) not null,
    input_json longtext not null,
    request_key varchar(100) not null,
    status varchar(24) not null,
    provider_task_id varchar(200) null,
    result_json longtext null,
    error_message varchar(1000) null,
    review_json longtext null,
    row_version bigint not null default 1,
    create_time datetime(3) not null,
    update_time datetime(3) not null,
    unique key uk_fq_proposal_image_request (owner_id, request_key),
    key idx_fq_proposal_image_pool_owner (pool_id, owner_id),
    constraint fk_fq_proposal_image_pool foreign key (pool_id) references fq_selection_pool(id) on delete restrict,
    constraint ck_fq_proposal_image_input check (json_valid(input_json)),
    constraint ck_fq_proposal_image_result check (result_json is null or json_valid(result_json)),
    constraint ck_fq_proposal_image_review check (review_json is null or json_valid(review_json)),
    constraint ck_fq_proposal_image_version check (row_version >= 1 and draft_version >= 1)
) engine=innodb default charset=utf8mb4 collate=utf8mb4_unicode_ci comment='主SKU选品提案试衣任务，不伪造已确认报价';
