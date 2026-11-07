-- SMTP delivery through MailHog (box1-docs#16). Receipts from an SMTP provider record the Message-ID and the operator
-- mailbox, so a receipt can be matched to the message in MailHog. Both stay NULL for the stub.
-- Operator mailboxes only (ops-*@<domain>); no customer address can be stored.
ALTER TABLE delivery_receipt
    ADD COLUMN message_id text NULL CHECK (message_id IS NULL OR message_id ~ '^<ntf_[0-9a-f]{26}@[a-z0-9.-]{1,200}>$'),
    ADD COLUMN recipient  text NULL CHECK (recipient IS NULL OR recipient ~ '^ops-[a-z0-9-]{1,40}@[a-z0-9.-]{1,200}$'),
    ADD CONSTRAINT delivery_receipt_provider_check CHECK (provider IN ('stub', 'mailhog'));
