package com.mcplatform.domain.permission;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/**
 * Pure unit tests for the transitive inheritance logic (no DB). Covers the union (A→B→C), multiple
 * inheritance, the diamond (a permission appears once with the full source set), cycle detection and the
 * defensive visited-set termination (FR-003/FR-004/FR-010/FR-010a/FR-022a).
 */
class RoleHierarchyTest {

    private static RoleId r(long id) {
        return RoleId.of(id);
    }

    @Test
    void transitiveUnionAcrossAChain() {
        // A(1) -> B(2) -> C(3)
        Function<RoleId, List<RoleId>> parents = Map.of(
                r(1), List.of(r(2)),
                r(2), List.of(r(3)),
                r(3), List.<RoleId>of())::get;
        Set<RoleId> reachable = RoleHierarchy.reachable(Set.of(r(1)), id -> nullToEmpty(parents, id));
        assertThat(reachable).containsExactlyInAnyOrder(r(1), r(2), r(3));
    }

    @Test
    void multipleInheritance() {
        // A(1) inherits B(2) and C(3)
        Function<RoleId, List<RoleId>> parents = id -> id.equals(r(1)) ? List.of(r(2), r(3)) : List.of();
        assertThat(RoleHierarchy.reachable(Set.of(r(1)), parents))
                .containsExactlyInAnyOrder(r(1), r(2), r(3));
    }

    @Test
    void diamondYieldsEachPermissionOnceWithFullProvenance() {
        // Z(1) -> X(2), Y(3); X(2) -> D(4); Y(3) -> D(4). D has "feature.d".
        Function<RoleId, List<RoleId>> parents = id -> switch ((int) id.value()) {
            case 1 -> List.of(r(2), r(3));
            case 2, 3 -> List.of(r(4));
            default -> List.of();
        };
        Function<RoleId, List<String>> perms = id -> id.equals(r(4)) ? List.of("feature.d") : List.of();

        Map<String, RoleHierarchy.Provenance> prov =
                RoleHierarchy.resolveWithProvenance(Set.of(r(1)), parents, perms, List.of());

        assertThat(prov).containsOnlyKeys("feature.d");
        assertThat(prov.get("feature.d").own()).isFalse();
        assertThat(prov.get("feature.d").sources()).containsExactly(r(4)); // once, full set = {D}
    }

    @Test
    void ownPermissionsAreFlaggedAndMergeWithInherited() {
        Function<RoleId, List<RoleId>> parents = id -> id.equals(r(1)) ? List.of(r(2)) : List.of();
        Function<RoleId, List<String>> perms = id -> id.equals(r(2)) ? List.of("base.perm") : List.of();

        Map<String, RoleHierarchy.Provenance> prov =
                RoleHierarchy.resolveWithProvenance(Set.of(r(1)), parents, perms, List.of("direct.perm"));

        assertThat(prov.get("direct.perm").own()).isTrue();
        assertThat(prov.get("direct.perm").sources()).isEmpty();
        assertThat(prov.get("base.perm").own()).isFalse();
        assertThat(prov.get("base.perm").sources()).containsExactly(r(2));
    }

    @Test
    void detectsDirectAndTransitiveCycles() {
        // existing edges: A(1) -> B(2) -> C(3)
        Function<RoleId, List<RoleId>> parents = id -> switch ((int) id.value()) {
            case 1 -> List.of(r(2));
            case 2 -> List.of(r(3));
            default -> List.of();
        };
        assertThat(RoleHierarchy.wouldCreateCycle(r(1), r(1), parents)).isTrue();   // self
        assertThat(RoleHierarchy.wouldCreateCycle(r(2), r(1), parents)).isTrue();   // B->A closes A->B
        assertThat(RoleHierarchy.wouldCreateCycle(r(3), r(1), parents)).isTrue();   // C->A closes A->B->C
        assertThat(RoleHierarchy.wouldCreateCycle(r(3), r(2), parents)).isTrue();   // C->B closes B->C
        assertThat(RoleHierarchy.wouldCreateCycle(r(1), r(3), parents)).isFalse();  // A->C is fine (no cycle)
    }

    @Test
    void resolutionTerminatesOnAResidualCycle() {
        // Malformed data: A(1) -> B(2) -> A(1). reachable() must terminate (visited-set, FR-010a).
        Function<RoleId, List<RoleId>> parents = id -> id.equals(r(1)) ? List.of(r(2)) : List.of(r(1));
        assertThat(RoleHierarchy.reachable(Set.of(r(1)), parents)).containsExactlyInAnyOrder(r(1), r(2));
    }

    private static List<RoleId> nullToEmpty(Function<RoleId, List<RoleId>> f, RoleId id) {
        List<RoleId> v = f.apply(id);
        return v == null ? List.of() : v;
    }
}
