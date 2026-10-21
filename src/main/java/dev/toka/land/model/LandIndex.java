package dev.toka.land.model;

import java.util.*;

/** Correctness-first spatial lookup. It deduplicates multiple matching pieces but returns every claim hit. */
public final class LandIndex {
    private static final long MAX_INDEX_CELLS_PER_SELECTION = 4096;
    private final Map<UUID, LandClaim> claims;
    private final Map<String, Map<Cell, Set<UUID>>> cells = new HashMap<>();
    private final Map<String, Set<UUID>> broadClaims = new HashMap<>();

    private record Cell(int x, int z) { }

    public LandIndex(Collection<LandClaim> claims) {
        Map<UUID, LandClaim> indexed = new HashMap<>();
        Set<UUID> selectionIds = new HashSet<>();
        for (LandClaim claim : claims) {
            if (indexed.putIfAbsent(claim.id(), claim) != null)
                throw new IllegalArgumentException("duplicate claim ID: " + claim.id());
            for (LandSelection selection : claim.selections())
                if (!selectionIds.add(selection.id()))
                    throw new IllegalArgumentException("duplicate selection ID: " + selection.id());
        }
        this.claims = Map.copyOf(indexed);
        validateParents();
        buildSpatialIndex();
    }

    public List<LandClaim> findAllAt(String world, int x, int y, int z) {
        Set<UUID> candidates = new HashSet<>(broadClaims.getOrDefault(world, Set.of()));
        candidates.addAll(cells.getOrDefault(world, Map.of())
                .getOrDefault(new Cell(Math.floorDiv(x, 16), Math.floorDiv(z, 16)), Set.of()));
        return candidates.stream().map(claims::get).filter(Objects::nonNull)
                .filter(claim -> claim.contains(world, x, y, z))
                .sorted(Comparator.comparing(claim -> claim.id().toString()))
                .toList();
    }

    public Optional<LandClaim> get(UUID id) { return Optional.ofNullable(claims.get(id)); }
    public Collection<LandClaim> all() { return claims.values(); }

    private void buildSpatialIndex() {
        for (LandClaim claim : claims.values()) for (LandSelection part : claim.selections()) {
            int minCellX = Math.floorDiv(part.minX(), 16), maxCellX = Math.floorDiv(part.maxX(), 16);
            int minCellZ = Math.floorDiv(part.minZ(), 16), maxCellZ = Math.floorDiv(part.maxZ(), 16);
            long columns = (long) maxCellX - minCellX + 1;
            long rows = (long) maxCellZ - minCellZ + 1;
            if (columns * rows > MAX_INDEX_CELLS_PER_SELECTION) {
                broadClaims.computeIfAbsent(part.world(), ignored -> new HashSet<>()).add(claim.id());
                continue;
            }
            Map<Cell, Set<UUID>> worldCells = cells.computeIfAbsent(part.world(), ignored -> new HashMap<>());
            for (int cellX = minCellX; cellX <= maxCellX; cellX++)
                for (int cellZ = minCellZ; cellZ <= maxCellZ; cellZ++)
                    worldCells.computeIfAbsent(new Cell(cellX, cellZ), ignored -> new HashSet<>()).add(claim.id());
        }
    }

    private void validateParents() {
        for (LandClaim claim : claims.values()) {
            Set<UUID> path = new HashSet<>();
            LandClaim current = claim;
            while (current.parentClaimId() != null) {
                if (!path.add(current.id())) throw new IllegalArgumentException("claim parent cycle at " + current.id());
                LandClaim parent = claims.get(current.parentClaimId());
                if (parent == null) throw new IllegalArgumentException("missing parent claim for " + claim.id());
                for (LandSelection childPart : current.selections()) {
                    if (!coveredByUnion(childPart, parent.selections()))
                        throw new IllegalArgumentException("child claim selection is outside parent: " + current.id());
                }
                current = parent;
            }
        }
    }

    /** Integer block volumes use half-open long bounds, including MAX_VALUE + 1. */
    private record Box(long x1,long y1,long z1,long x2,long y2,long z2) {
        static Box of(LandSelection s) {
            return new Box(s.minX(),s.ignoreHeight()?Integer.MIN_VALUE:s.minY(),s.minZ(),
                    (long)s.maxX()+1,s.ignoreHeight()?(long)Integer.MAX_VALUE+1:(long)s.maxY()+1,(long)s.maxZ()+1);
        }
        void subtract(Box cover,List<Box> remaining) {
            long a=Math.max(x1,cover.x1),b=Math.max(y1,cover.y1),c=Math.max(z1,cover.z1);
            long d=Math.min(x2,cover.x2),e=Math.min(y2,cover.y2),f=Math.min(z2,cover.z2);
            if(a>=d||b>=e||c>=f){remaining.add(this);return;}
            if(x1<a)remaining.add(new Box(x1,y1,z1,a,y2,z2));
            if(d<x2)remaining.add(new Box(d,y1,z1,x2,y2,z2));
            if(y1<b)remaining.add(new Box(a,y1,z1,d,b,z2));
            if(e<y2)remaining.add(new Box(a,e,z1,d,y2,z2));
            if(z1<c)remaining.add(new Box(a,b,z1,d,e,c));
            if(f<z2)remaining.add(new Box(a,b,f,d,e,z2));
        }
    }

    private static boolean coveredByUnion(LandSelection child,List<LandSelection> parents) {
        List<Box> uncovered=List.of(Box.of(child));
        for(LandSelection parent:parents) {
            if(!child.world().equals(parent.world()))continue;
            List<Box> next=new ArrayList<>();
            Box cover=Box.of(parent);
            for(Box piece:uncovered)piece.subtract(cover,next);
            if(next.isEmpty())return true;
            uncovered=next;
        }
        return false;
    }
}
