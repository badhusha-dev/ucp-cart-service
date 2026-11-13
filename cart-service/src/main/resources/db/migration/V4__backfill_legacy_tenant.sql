-- Signed-in carts created before V3 have no tenant. A signed-in shopper is scoped to the legacy tenant, so backfill them
-- to keep existing carts reachable. Guest (session) carts stay tenant-less.
UPDATE carts SET tenant_id = '00000000-0000-4000-8000-000000000001' WHERE tenant_id IS NULL AND user_id IS NOT NULL;
