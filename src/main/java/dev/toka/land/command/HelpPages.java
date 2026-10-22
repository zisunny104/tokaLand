package dev.toka.land.command;

import cn.nukkit.command.CommandSender;
import java.util.List;

/**
 * Help split into sections, each paged: "help" lists the sections the sender may see, "help <section> [page]" shows one.
 * Sections whose permission the sender lacks are hidden, so nobody reads about commands they cannot use.
 */
public final class HelpPages {
    public interface Text { String of(CommandSender sender, String key, Object... args); }

    /** @param permission null when everyone may see the section */
    public record Section(String id, String permission, List<String> keys) {}

    private static final int PER_PAGE = 8;
    private final String command;
    private final List<Section> sections;
    private final Text text;

    public HelpPages(String command, List<Section> sections, Text text) {
        this.command = command;
        this.sections = List.copyOf(sections);
        this.text = text;
    }

    private List<Section> visible(CommandSender sender) {
        return sections.stream().filter(s -> s.permission() == null || sender.hasPermission(s.permission())).toList();
    }

    /** @param args what follows the word "help" */
    public void show(CommandSender sender, String[] args) {
        List<Section> visible = visible(sender);
        if (args.length == 0) {
            sender.sendMessage(text.of(sender, "help.title", command));
            for (Section section : visible) sender.sendMessage(text.of(sender, "help.index", command, section.id(), text.of(sender, "help.sec." + section.id()), section.keys().size()));
            return;
        }
        Section found = visible.stream().filter(s -> s.id().equalsIgnoreCase(args[0])).findFirst().orElse(null);
        if (found == null) { sender.sendMessage(text.of(sender, "help.unknown", args[0], command)); return; }
        int pages = (found.keys().size() + PER_PAGE - 1) / PER_PAGE;
        int page = 1;
        if (args.length > 1) try { page = Math.min(Math.max(Integer.parseInt(args[1]), 1), pages); } catch (NumberFormatException ignored) { /* first page */ }
        sender.sendMessage(text.of(sender, "help.section", text.of(sender, "help.sec." + found.id()), page, pages));
        for (String key : found.keys().subList((page - 1) * PER_PAGE, Math.min(found.keys().size(), page * PER_PAGE))) sender.sendMessage(text.of(sender, "help." + key));
        if (page < pages) sender.sendMessage(text.of(sender, "help.next", command, found.id(), page + 1));
    }

    /** Section ids the sender can open, for tab completion. */
    public List<String> ids(CommandSender sender) { return visible(sender).stream().map(Section::id).toList(); }
}
