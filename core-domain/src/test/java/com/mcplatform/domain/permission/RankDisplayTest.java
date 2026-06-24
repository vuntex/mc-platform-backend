package com.mcplatform.domain.permission;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class RankDisplayTest {

    private Role role(long id, int weight, boolean teamRank, boolean active) {
        return new Role(RoleId.of(id), "role" + id, "Role " + id, null, null, null, null, null, null,
                weight, teamRank, active, false);
    }

    @Test
    void teamFlagBeatsHigherWeight() {
        Role team = role(1, 5, true, true);
        Role heavy = role(2, 100, false, true);
        assertThat(RankDisplay.choose(List.of(heavy, team))).contains(team);
    }

    @Test
    void higherWeightWinsAmongSameTeamFlag() {
        Role low = role(1, 5, false, true);
        Role high = role(2, 50, false, true);
        assertThat(RankDisplay.choose(List.of(low, high))).contains(high);
    }

    @Test
    void smallestIdWinsOnEqualWeight() {
        Role a = role(7, 10, false, true);
        Role b = role(3, 10, false, true);
        assertThat(RankDisplay.choose(List.of(a, b))).contains(b);
    }

    @Test
    void deactivatedRolesAreIgnored() {
        Role inactive = role(1, 100, true, false);
        Role active = role(2, 1, false, true);
        assertThat(RankDisplay.choose(List.of(inactive, active))).contains(active);
    }

    @Test
    void emptyWhenNoActiveRole() {
        assertThat(RankDisplay.choose(List.of())).isEmpty();
        assertThat(RankDisplay.choose(List.of(role(1, 1, false, false)))).isEmpty();
    }
}
