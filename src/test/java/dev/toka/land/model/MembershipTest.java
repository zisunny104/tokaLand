package dev.toka.land.model;

import com.google.gson.JsonObject;
import dev.toka.land.storage.DatasetRepository;
import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public class MembershipTest {
    private static final UUID OWNER = UUID.randomUUID(), ALICE = UUID.randomUUID(), BOB = UUID.randomUUID();
    private static final ClaimDetails.TeleportPoint POINT = new ClaimDetails.TeleportPoint("world", 1, 2, 1, 0, 0);

    private static LandClaim claim(String name, UUID owner, UUID parent, boolean inheritPermissions, boolean publicTeleport, int x) {
        return new LandClaim(UUID.randomUUID(), name, ClaimKind.PLAYER, "land", owner, parent, false, inheritPermissions,
                List.of(new LandSelection("world", x, 0, 0, x + 3, 20, 3, false)), List.of(), AccessPolicy.privateDefault(), Map.of(), null, null,
                new ClaimDetails(owner, "o", new ClaimDetails.TeleportPoint("world", x + 1, 2, 1, 0, 0), publicTeleport));
    }
    private static LandClaim with(LandClaim claim, UUID player, MemberLevel level) { return ClaimEdits.member(claim, AccessSubject.player(player), level); }

    @Test public void visitorIsInteractOnlyAndMemberIsWhatMembersAlwaysGot() {
        LandClaim base = claim("a", OWNER, null, true, false, 0);
        LandClaim member = ClaimEdits.member(base, AccessSubject.player(ALICE), true);
        assertEquals(MemberLevel.MEMBER, MemberLevel.of(member, AccessSubject.player(ALICE)));
        assertEquals(Set.of(ClaimPermission.BUILD, ClaimPermission.INTERACT, ClaimPermission.CONTAINERS),
                EnumSet.copyOf(member.accessGrants().stream().map(AccessGrant::permission).toList()));
        LandClaim visitor = with(member, ALICE, MemberLevel.VISITOR);
        assertEquals(MemberLevel.VISITOR, MemberLevel.of(visitor, AccessSubject.player(ALICE)));
        assertEquals(1, visitor.accessGrants().size());
        assertNull(MemberLevel.of(ClaimEdits.member(visitor, AccessSubject.player(ALICE), false), AccessSubject.player(ALICE)));
        assertNull(MemberLevel.of(base, AccessSubject.player(BOB)));
    }

    @Test public void memberListHasMembersNotOwnersAndFollowsInheritance() {
        LandClaim parent = with(with(claim("p", OWNER, null, true, false, 0), ALICE, MemberLevel.MEMBER), BOB, MemberLevel.VISITOR);
        LandClaim child = claim("c", UUID.randomUUID(), parent.id(), true, false, 0);
        LandClaim lone = claim("l", ALICE, null, true, false, 100);
        LandClaim ownedAndGranted = with(claim("g", BOB, null, true, false, 200), BOB, MemberLevel.MEMBER);
        LandIndex index = new LandIndex(List.of(parent, child, lone, ownedAndGranted));
        assertEquals(Set.of(parent.id(), child.id()), new HashSet<>(index.memberOf(ALICE).stream().map(LandClaim::id).toList()));
        assertEquals(Set.of(parent.id(), child.id()), new HashSet<>(index.memberOf(BOB).stream().map(LandClaim::id).toList()));
        assertTrue(index.memberOf(OWNER).isEmpty());
        assertTrue(ClaimAccess.isMember(index, child.id(), ALICE));
    }

    @Test public void aChildThatDoesNotInheritOrDeniesHasNoMember() {
        LandClaim parent = with(claim("p", OWNER, null, true, false, 0), ALICE, MemberLevel.MEMBER);
        LandClaim apart = claim("c", UUID.randomUUID(), parent.id(), false, false, 0);
        LandClaim denied = claim("d", UUID.randomUUID(), parent.id(), true, false, 0);
        denied = new LandClaim(denied.id(), denied.name(), denied.kind(), denied.type(), denied.ownerId(), denied.parentClaimId(), false, true, denied.selections(),
                List.of(new AccessGrant(AccessSubject.player(ALICE), ClaimPermission.INTERACT, AccessDecision.DENY)), denied.accessPolicy(), Map.of(), null, null, denied.details());
        LandIndex index = new LandIndex(List.of(parent, apart, denied));
        assertEquals(List.of(parent.id()), index.memberOf(ALICE).stream().map(LandClaim::id).toList());
    }

    @Test public void teleportAccessKeepsTheOldMeaningUntilSet() {
        assertEquals(TeleportAccess.ALL, TeleportAccess.of(claim("a", OWNER, null, true, true, 0).details()));
        assertEquals(TeleportAccess.MEMBERS, TeleportAccess.of(claim("a", OWNER, null, true, false, 0).details()));
        assertEquals(TeleportAccess.OWNER, TeleportAccess.of(new ClaimDetails(OWNER, "o", POINT, true, null, null, "bogus")));
        assertEquals(Optional.of(TeleportAccess.MEMBERS), TeleportAccess.parse("Member"));
        assertEquals(Optional.empty(), TeleportAccess.parse("everyone"));
        assertEquals("member", TeleportAccess.MEMBERS.word());
    }

    @Test public void teleportAccessDecidesWhoMayGo() {
        LandClaim base = with(claim("a", OWNER, null, true, false, 0), ALICE, MemberLevel.VISITOR);
        UUID stranger = UUID.randomUUID();
        for (TeleportAccess access : TeleportAccess.values()) {
            LandClaim set = ClaimEdits.teleportAccess(base, access);
            LandIndex index = new LandIndex(List.of(set));
            assertTrue(TeleportAccess.permits(index, set, OWNER, false));
            assertEquals(access != TeleportAccess.OWNER, TeleportAccess.permits(index, set, ALICE, false));
            assertEquals(access == TeleportAccess.ALL, TeleportAccess.permits(index, set, stranger, false));
            assertTrue(TeleportAccess.permits(index, set, stranger, true));
        }
    }

    @Test public void oldStoredClaimsLoadWithoutTheNewField() {
        LandClaim old = claim("a", OWNER, null, true, true, 0);
        JsonObject data = DatasetRepository.encode(old);
        data.getAsJsonObject("details").remove("teleportAccess");
        LandClaim loaded = DatasetRepository.decode(data);
        assertEquals(old, loaded);
        assertEquals(TeleportAccess.ALL, TeleportAccess.of(loaded.details()));
        LandClaim set = ClaimEdits.teleportAccess(old, TeleportAccess.OWNER);
        assertEquals(TeleportAccess.OWNER, TeleportAccess.of(DatasetRepository.decode(DatasetRepository.encode(set)).details()));
    }
}
