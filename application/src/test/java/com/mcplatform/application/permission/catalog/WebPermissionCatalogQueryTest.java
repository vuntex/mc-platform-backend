package com.mcplatform.application.permission.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import com.mcplatform.application.economy.EconomyPermissionCatalog;
import com.mcplatform.application.economy.EconomyPermissions;
import com.mcplatform.application.permission.PermissionAdminCatalog;
import com.mcplatform.application.permission.PermissionAdminService;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/** Catalog structure + the drift guard: the catalog and the actually-gated web permissions must agree. */
class WebPermissionCatalogQueryTest {

    private final WebPermissionCatalogQuery query = new WebPermissionCatalogQuery(
            List.of(new PermissionAdminCatalog(), new EconomyPermissionCatalog()));

    @Test
    void groupsAreSortedByDisplayNameAndFullyDescribed() {
        List<PermissionCatalogGroup> groups = query.groups();
        assertThat(groups).extracting(PermissionCatalogGroup::displayName)
                .containsExactly("Economy", "Rollen & Permissions"); // deterministic, alphabetical
        assertThat(groups).allSatisfy(g -> {
            assertThat(g.key()).isNotBlank();
            assertThat(g.description()).isNotBlank();
            assertThat(g.permissions()).isNotEmpty();
            assertThat(g.permissions()).allSatisfy(p -> {
                assertThat(p.key()).isNotBlank();
                assertThat(p.description()).isNotBlank();
            });
        });
    }

    @Test
    void catalogMatchesExactlyTheGatedWebPermissionConstants() {
        Set<String> catalogKeys = query.groups().stream()
                .flatMap(g -> g.permissions().stream())
                .map(PermissionCatalogEntry::key)
                .collect(Collectors.toSet());

        Set<String> gated = Set.of(
                PermissionAdminService.READ,
                PermissionAdminService.ROLE_CREATE,
                PermissionAdminService.ROLE_EDIT,
                PermissionAdminService.ROLE_EDIT_INHERIT,
                PermissionAdminService.ROLE_DELETE,
                PermissionAdminService.GRANT_ROLE,
                PermissionAdminService.GRANT_PERMISSION,
                EconomyPermissions.READ);

        assertThat(catalogKeys).as("catalog must list exactly the gated web permissions (no drift)")
                .isEqualTo(gated);
    }

    @Test
    void catalogHasNoDuplicatesAndNoWildcard() {
        List<String> keys = query.groups().stream()
                .flatMap(g -> g.permissions().stream())
                .map(PermissionCatalogEntry::key)
                .toList();
        assertThat(keys).doesNotHaveDuplicates();
        assertThat(keys).doesNotContain("*");
    }
}
