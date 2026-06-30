-- role_inheritance — directed many-to-many "role (child) inherits the PERMISSIONS of role (parent)".
-- State-stored CRUD (Constitution VI); the change history lives in the existing role_audit trail.
-- ONLY permissions are inherited — never display/meta fields (resolved transitively in
-- JooqPermissionResolver / RoleHierarchy). The default role is a leaf (it never appears as role_id).
CREATE TABLE role_inheritance (
    role_id            BIGINT       NOT NULL REFERENCES role(id) ON DELETE CASCADE,   -- inheriting (child)
    inherited_role_id  BIGINT       NOT NULL REFERENCES role(id) ON DELETE RESTRICT,  -- inherited (parent)
    created_by         UUID,
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (role_id, inherited_role_id),
    CONSTRAINT chk_no_self_inherit CHECK (role_id <> inherited_role_id)
);
-- Reverse lookup (dependents / fan-out): "which roles inherit this parent?".
CREATE INDEX idx_role_inheritance_parent ON role_inheritance (inherited_role_id);
