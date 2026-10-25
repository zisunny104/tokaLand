package dev.toka.land;

import cn.nukkit.Player;
import cn.nukkit.command.CommandSender;
import dev.toka.i18n.Messages;
import dev.toka.land.model.ClaimAccess;
import dev.toka.land.model.ClaimKind;
import dev.toka.land.model.LandClaim;
import dev.toka.land.protection.SelectionToolListener;
import java.util.Map;

/** Player-facing text: language choice, message lookup and owner labels. Authorization never uses these names. */
public final class Texts {
    private final Main plugin;
    private final PlayerNames names;
    private final Map<String, Messages> languages = new java.util.concurrent.ConcurrentHashMap<>();

    Texts(Main plugin, PlayerNames names) {
        this.plugin = plugin;
        this.names = names;
    }

    public String language(CommandSender sender) {
        return sender instanceof Player player && player.getLocale() != null ? player.getLocale().toLanguageTag() : plugin.getConfig().getString("language", "zh-TW");
    }

    public String message(String language, String key, Object... args) {
        String selected = language != null && language.startsWith("en") ? "en-US" : "zh-TW";
        return languages.computeIfAbsent(selected, tag -> new Messages(plugin.getClass().getClassLoader(),
                plugin.getDataFolder().toPath(), tag, line -> plugin.getLogger().warning(line))).text(key, args);
    }

    public String text(CommandSender sender, String key, Object... args) { return message(language(sender), key, args); }

    /** Drops the loaded languages so the next message re-reads the files (used by /land reload). */
    public void clear() { languages.clear(); }

    private String console(String key, Object... args) { return message(plugin.getConfig().getString("language", "zh-TW"), key, args); }

    public void info(String key, Object... args) { plugin.getLogger().info(console(key, args)); }

    public void warn(String key, Object... args) { plugin.getLogger().warning(console(key, args)); }

    public void error(String key, Throwable cause, Object... args) { plugin.getLogger().error(console(key, args), cause); }

    public void sendSelectionPoint(Player player, boolean first, SelectionToolListener.Point point) {
        player.sendMessage(text(player, first ? "selection.first" : "selection.second", point.world(), point.x(), point.y(), point.z()));
    }

    /** One chat line for a finished selection: blocks as [footprint|volume], then the price of each; the one that is not charged is dark grey. */
    public void sendSelectionSummary(Player player, dev.toka.land.Trading.Preview preview) {
        String flat = format(preview.flatPrice(), !preview.flat()), volume = format(preview.volumePrice(), preview.flat());
        player.sendMessage(text(player, "selection.summary", format(preview.area(), false), format(preview.volume(), false), flat, volume));
    }

    private static String format(long number, boolean dim) {
        String shown = String.format(java.util.Locale.ROOT, "%,d", number);
        return dim ? "§8" + shown + "§b" : shown;
    }

    /** Everything that identifies a claim, with what each id belongs to; one line per fact. */
    public String claimInfo(LandClaim claim, String language) {
        String none = message(language, "tools.i.none");
        var lines = new java.util.ArrayList<String>();
        lines.add(message(language, "tools.i.id", claim.id()));
        lines.add(message(language, "tools.i.name", claim.displayName(language)));
        if (!claim.localizedNames().isEmpty())
            lines.add(message(language, "tools.i.names", String.join(", ", new java.util.TreeMap<>(claim.localizedNames()).entrySet().stream().map(e -> e.getKey() + " = " + e.getValue()).toList())));
        lines.add(message(language, "tools.i.owner", ownerText(claim, language), ClaimAccess.effectiveOwner(plugin.index(), claim.id()).map(Object::toString).orElse(none)));
        var details = claim.details();
        if (details.creatorId() != null)
            lines.add(message(language, "tools.i.creator", names.find(details.creatorId()).orElse(details.creatorName() == null ? none : details.creatorName()), details.creatorId()));
        if (claim.parentClaimId() != null)
            lines.add(message(language, "tools.i.parent", plugin.getClaim(claim.parentClaimId()).map(parent -> parent.displayName(language)).orElse(none), claim.parentClaimId()));
        var lease = plugin.leases() == null ? null : plugin.leases().leaseOf(claim.id());
        if (lease != null && lease.tenant != null) lines.add(message(language, "tools.i.tenant", lease.tenantName == null ? none : lease.tenantName, lease.tenant));
        lines.add(message(language, "tools.i.type", claim.type(), claim.accessPolicy().mode() + (claim.accessPolicy().locked() ? "+LOCK" : "")));
        lines.add(message(language, "tools.i.place", claim.world(), claim.selections().size()));
        var point = details.teleport();
        if (point != null) lines.add(message(language, "tools.i.teleport", point.world(), Math.round(point.x()), Math.round(point.y()), Math.round(point.z())));
        return String.join("\n", lines);
    }

    /** Current name if known, else the stored snapshot or the UUID; unresolved legacy owners are labelled as pending. */
    public String ownerText(LandClaim claim, String language) {
        var owner = ClaimAccess.effectiveOwner(plugin.index(), claim.id());
        if (owner.isPresent()) {
            String fallback = claim.ownerNameSnapshot() == null ? owner.get().toString() : claim.ownerNameSnapshot();
            return names.find(owner.get()).orElse(fallback);
        }
        if (claim.legacyOwnerName() != null) return message(language, "ui.pending", claim.legacyOwnerName());
        return message(language, claim.kind() == ClaimKind.PLAYER ? "ui.inheritedOwner" : "ui.publicOwner");
    }
}
