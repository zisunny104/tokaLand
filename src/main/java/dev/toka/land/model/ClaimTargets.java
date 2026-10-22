package dev.toka.land.model;

import java.util.*;

/** Selection among every matching claim. Ambiguity never grants permission or selects a first row. */
public final class ClaimTargets {
    private ClaimTargets(){}
    public static final class Ambiguous extends IllegalArgumentException {
        private final List<LandClaim> matches;
        public Ambiguous(List<LandClaim> matches){super("AMBIGUOUS_CLAIM");this.matches=List.copyOf(matches);}
        public List<LandClaim> matches(){return matches;}
    }
    public static LandClaim resolve(Collection<LandClaim> candidates,String query){
        List<LandClaim> hits=List.copyOf(candidates);
        if(query!=null&&!query.isBlank()){
            UUID parsed=null;try{UUID id=UUID.fromString(query);if(id.toString().equalsIgnoreCase(query))parsed=id;}catch(IllegalArgumentException ignored){}
            if(parsed!=null){UUID id=parsed;hits=hits.stream().filter(c->c.id().equals(id)).toList();}
            else hits=hits.stream().filter(c->c.name().equalsIgnoreCase(query)).toList();
        }
        if(hits.isEmpty())throw new IllegalArgumentException("CLAIM_NOT_FOUND");
        if(hits.size()!=1)throw new Ambiguous(hits);
        return hits.getFirst();
    }
}
