ALTER TABLE payments
    ADD COLUMN psp_reference VARCHAR(255) NULL;

-- nullable: a payment starts without a PSP reference (CREATED/PENDING_PSP before the PSP responds);
-- the callback/reconciler is what fills it in once the PSP has assigned one
CREATE UNIQUE INDEX idx_payments_psp_reference ON payments (psp_reference) WHERE psp_reference IS NOT NULL;
