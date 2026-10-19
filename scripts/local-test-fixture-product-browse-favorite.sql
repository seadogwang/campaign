-- ==============================================================================
-- Local-test PostgreSQL fixture: product browse & favorite event storage tables.
--
-- Opt-in — this script is never run automatically in production. It must be
-- executed explicitly by an operator against a local or development database.
--
-- The storage catalog (core-loyalty-storage-v16-supplement.json) maps these
-- physical tables to the metadata definitions product.browse_count and
-- product.favorite_count through member.product_browses / member.product_favorites
-- relations, joined on member.id = <table>.member_id.
--
-- Every table carries tenant_id/program_id columns so the read-only preview
-- respects the same multi-tenancy boundary as other seg_ event tables.
-- ==============================================================================

-- 1. Product browse event storage ------------------------------------------------

CREATE TABLE IF NOT EXISTS public.seg_product_browse (
    product_browse_id   BIGSERIAL                   NOT NULL,
    tenant_id           VARCHAR(128)                NOT NULL,
    program_id          VARCHAR(128)                NOT NULL,
    member_id           VARCHAR(128)                NOT NULL,
    product_name        VARCHAR(512),
    occurred_at         TIMESTAMP WITH TIME ZONE    NOT NULL,
    created_at          TIMESTAMP WITH TIME ZONE    NOT NULL DEFAULT now(),

    CONSTRAINT pk_seg_product_browse
        PRIMARY KEY (tenant_id, program_id, product_browse_id)
);

-- Indexes for read-only preview: query by member + time window.
CREATE INDEX IF NOT EXISTS idx_seg_product_browse_member_time
    ON public.seg_product_browse (tenant_id, program_id, member_id, occurred_at);

CREATE INDEX IF NOT EXISTS idx_seg_product_browse_product
    ON public.seg_product_browse (tenant_id, program_id, product_name);


-- 2. Product favorite event storage ----------------------------------------------

CREATE TABLE IF NOT EXISTS public.seg_product_favorite (
    product_favorite_id BIGSERIAL                   NOT NULL,
    tenant_id           VARCHAR(128)                NOT NULL,
    program_id          VARCHAR(128)                NOT NULL,
    member_id           VARCHAR(128)                NOT NULL,
    product_name        VARCHAR(512),
    occurred_at         TIMESTAMP WITH TIME ZONE    NOT NULL,
    created_at          TIMESTAMP WITH TIME ZONE    NOT NULL DEFAULT now(),

    CONSTRAINT pk_seg_product_favorite
        PRIMARY KEY (tenant_id, program_id, product_favorite_id)
);

-- Indexes for read-only preview: query by member + time window.
CREATE INDEX IF NOT EXISTS idx_seg_product_favorite_member_time
    ON public.seg_product_favorite (tenant_id, program_id, member_id, occurred_at);

CREATE INDEX IF NOT EXISTS idx_seg_product_favorite_product
    ON public.seg_product_favorite (tenant_id, program_id, product_name);


-- 3. Optional minimal seed rows for local validation ----------------------------
-- Uncomment and adjust tenant/program to your local environment.
-- These rows exercise the member join path without carrying any sports-shoe
-- scenario vocabulary in production code.

/*
INSERT INTO public.seg_product_browse
    (tenant_id, program_id, member_id, product_name, occurred_at)
VALUES
    ('tenant-dev', 'program-dev', 'membership-dev', 'generic-product-alpha', now() - interval '1 day');

INSERT INTO public.seg_product_favorite
    (tenant_id, program_id, member_id, product_name, occurred_at)
VALUES
    ('tenant-dev', 'program-dev', 'membership-dev', 'generic-product-alpha', now() - interval '2 days');
*/
