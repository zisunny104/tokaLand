package dev.toka.land.model;

import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public class LandIdentityTest {
    private LandClaim claim(UUID owner,String legacy) {
        return new LandClaim(UUID.randomUUID(),"預設名稱",ClaimKind.PLAYER,"land",owner,null,false,true,
                List.of(new LandSelection("world",0,0,0,5,5,5,false)),List.of(),AccessPolicy.privateDefault(),
                Map.of("zh-TW","小屋","en-US","Cabin"),legacy,"舊顯示名稱",ClaimDetails.privateDefaults());
    }
    @Test public void displayNamesDoNotChangeTheOwnerIdentity() {
        UUID owner=UUID.randomUUID();LandClaim claim=claim(owner,null);
        assertEquals("New name",claim.displayOwner(id->Optional.of("New name")));
        assertEquals("舊顯示名稱",claim.displayOwner(id->Optional.empty()));
        assertEquals(owner,claim.ownerId());
        assertEquals("小屋",claim.displayName("zh_TW"));
        assertEquals("Cabin",claim.displayName("en-US"));
        assertEquals("預設名稱",claim.displayName("ja-JP"));
    }
    @Test public void unresolvedLegacyNameDoesNotAcquirePermissions() {
        LandClaim claim=claim(null,"Legacy name");LandIndex index=new LandIndex(List.of(claim));
        assertEquals("Legacy name",claim.displayOwner(id->Optional.of("Same name")));
        assertFalse(ClaimAccess.allows(index,claim.id(),UUID.randomUUID(),ClaimPermission.MANAGE,false));
    }
    @Test(expected=IllegalArgumentException.class) public void uuidAndLegacyOwnerCannotBothAuthorize() {
        claim(UUID.randomUUID(),"Legacy name");
    }
}
