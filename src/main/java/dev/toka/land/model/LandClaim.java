package dev.toka.land.model;

import java.util.*;

/** One unique land right. Geometry and sparse permission overrides are stored once under its ID. */
public record LandClaim(UUID id, String name, ClaimKind kind, String type,
                        UUID ownerId, UUID parentClaimId,
                        boolean inheritOwner, boolean inheritPermissions,
                        List<LandSelection> selections,
                        List<AccessGrant> accessGrants, AccessPolicy accessPolicy,
                        Map<String,String> localizedNames, String legacyOwnerName, String ownerNameSnapshot,
                        ClaimDetails details) {
    public LandClaim {
        Objects.requireNonNull(id, "id");
        if (name == null || name.isBlank()) throw new IllegalArgumentException("name is required");
        Objects.requireNonNull(kind, "kind");
        details=details==null?ClaimDetails.privateDefaults():details;
        accessPolicy=accessPolicy==null?AccessPolicy.privateDefault():accessPolicy;
        if(details.teleport()!=null && selections!=null && !selections.isEmpty()) {
            var point=details.teleport();
            if(selections.stream().noneMatch(part->part.contains(point.world(),(int)Math.floor(point.x()),(int)Math.floor(point.y()),(int)Math.floor(point.z()))))
                throw new IllegalArgumentException("teleport point must lie inside the claim");
        }
        legacyOwnerName = legacyOwnerName == null || legacyOwnerName.isBlank() ? null : legacyOwnerName.strip();
        ownerNameSnapshot = ownerNameSnapshot == null || ownerNameSnapshot.isBlank() ? null : ownerNameSnapshot.strip();
        if(legacyOwnerName != null && (ownerId != null || inheritOwner))
            throw new IllegalArgumentException("legacy owner cannot also have a UUID or inherited owner");
        Map<String,String> names = new HashMap<>();
        if(localizedNames != null) localizedNames.forEach((locale,value)->{
            String tag=Locale.forLanguageTag(Objects.requireNonNull(locale).replace('_','-')).toLanguageTag();
            if(tag.equals("und") || value == null || value.isBlank()) throw new IllegalArgumentException("invalid localized name");
            if(names.putIfAbsent(tag,value)!=null)throw new IllegalArgumentException("duplicate normalized locale: "+tag);
        });
        localizedNames = Map.copyOf(names);
        type = type == null ? "" : type.strip();
        selections = List.copyOf(Objects.requireNonNull(selections, "selections"));
        if (selections.isEmpty()) throw new IllegalArgumentException("a claim needs at least one selection");
        String world = selections.getFirst().world();
        if (selections.stream().anyMatch(part -> !world.equals(part.world())))
            throw new IllegalArgumentException("all selections in one claim must use the same world");
        if (id.equals(parentClaimId)) throw new IllegalArgumentException("a claim cannot parent itself");
        accessGrants=List.copyOf(Objects.requireNonNull(accessGrants,"accessGrants"));
        Set<String> grantKeys=new HashSet<>();
        for(AccessGrant grant:accessGrants){
            Objects.requireNonNull(grant,"access grant");
            if(!grantKeys.add(grant.subject()+"/"+grant.permission()))
                throw new IllegalArgumentException("duplicate subject/action grant");
        }
    }

    /** The same claim with another owner and permission set, as when a sub-claim is let to a tenant or given back. */
    public LandClaim withTenancy(UUID owner, String ownerName, boolean inheritOwner, boolean inheritPermissions, List<AccessGrant> grants) {
        return new LandClaim(id, name, kind, type, owner, parentClaimId, inheritOwner, inheritPermissions, selections, grants, accessPolicy,
                localizedNames, null, ownerName, details);
    }

    public String world() { return selections.getFirst().world(); }

    public String displayName(String language) {
        if(language == null || language.isBlank())return name;
        Locale locale=Locale.forLanguageTag(language.replace('_','-'));
        return localizedNames.getOrDefault(locale.toLanguageTag(),localizedNames.getOrDefault(locale.getLanguage(),name));
    }

    /** A display-only fallback; it never supplies an identity for authorization. */
    public String displayOwner(java.util.function.Function<UUID,Optional<String>> names) {
        if(ownerId != null) return names.apply(ownerId).filter(value->!value.isBlank())
                .orElse(ownerNameSnapshot != null ? ownerNameSnapshot : ownerId.toString());
        return legacyOwnerName; // Caller localizes the pending/inherited/public label using tokaI18n.
    }

    public boolean contains(String world, int x, int y, int z) {
        return selections.stream().anyMatch(part -> part.contains(world, x, y, z));
    }

    /** Only player-owned claims are eligible for the personal top-bar hint. */
    public boolean showsPersonalTopbar() { return kind == ClaimKind.PLAYER; }
}
