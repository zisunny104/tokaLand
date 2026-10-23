package dev.toka.land;

import cn.nukkit.Player;
import cn.nukkit.command.CommandSender;
import dev.toka.i18n.Messages;
import dev.toka.land.model.ClaimAccess;
import dev.toka.land.model.ClaimKind;
import dev.toka.land.model.LandClaim;
import dev.toka.land.protection.SelectionToolListener;
import java.util.HashMap;
import java.util.Map;

/** Player-facing text: language choice, message lookup and owner labels. Authorization never uses these names. */
public final class Texts {
    private final Main plugin;
    private final PlayerNames names;
    private final Map<String, Messages> languages = new HashMap<>();

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

    public void sendSelectionPoint(Player player, boolean first, SelectionToolListener.Point point) {
        player.sendMessage(text(player, first ? "selection.first" : "selection.second", point.world(), point.x(), point.y(), point.z()));
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
