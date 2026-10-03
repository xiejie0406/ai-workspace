-- 设计1.2：新选品提案交付记录及池级试衣费用；历史报价表保留。
-- 由Fashion Flyway在已授权目标环境按V1→V4顺序执行；本次编码不执行迁移。
alter table fq_proposal_image_task
    add column provider_code varchar(80) null,
    add column model varchar(120) null,
    add column estimated_cost decimal(18,4) null,
    add column actual_cost decimal(18,4) null,
    add column cost_currency varchar(10) null,
    add column billing_status varchar(30) not null default 'unknown',
    add column submitted_at datetime(3) null,
    add column finished_at datetime(3) null,
    add column cancel_requested_at datetime(3) null,
    add column next_query_at datetime(3) null,
    add column retry_count int not null default 0,
    add constraint ck_fq_proposal_image_cost check ((estimated_cost is null or estimated_cost >= 0) and (actual_cost is null or actual_cost >= 0)),
    add constraint ck_fq_proposal_image_retry check (retry_count >= 0);

-- V3存量在途任务以已有创建时间作为恢复期限起点，不补造费用或受理信息。
update fq_proposal_image_task set submitted_at=create_time
where submitted_at is null and status in ('submitting','queued','running','cancel_requested','unknown');

create table fq_proposal_delivery (
    id varchar(36) not null,
    owner_id bigint not null,
    pool_id varchar(36) not null,
    draft_version bigint not null,
    request_key varchar(100) not null,
    title varchar(200) not null,
    status varchar(30) not null,
    snapshot_json json not null,
    file_key varchar(512) null,
    file_hash char(64) null,
    file_size bigint null,
    page_count int not null default 0,
    download_count bigint not null default 0,
    create_time datetime(3) not null,
    update_time datetime(3) not null,
    error_message varchar(1000) null,
    primary key (id),
    unique key uk_proposal_delivery_request (owner_id, request_key),
    key idx_proposal_delivery_owner_time (owner_id, create_time),
    key idx_proposal_delivery_pool (pool_id, draft_version),
    constraint fk_fq_proposal_delivery_pool foreign key (pool_id) references fq_selection_pool(id) on delete restrict,
    constraint ck_fq_proposal_delivery_counts check (draft_version >= 1 and page_count >= 0 and download_count >= 0 and (file_size is null or file_size >= 0))
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci comment='选品提案交付记录；Java唯一维护';
