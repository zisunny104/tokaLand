package dev.toka.land.command;

import cn.nukkit.command.CommandSender;
import java.util.ArrayList;
import java.util.List;

/**
 * Chat help as numbered pages only: page 1 is the basics, later pages are topics, each introduced by one line.
 * Topics whose permission the sender lacks are left out, so nobody reads about commands they cannot use.
 */
public final class HelpPages {
    public interface Text { String of(CommandSender sender, String key, Object... args); }

    /** @param intro true when the topic opens with a line naming it (help.intro.&lt;id&gt;); the basics need none */
    public record Section(String id, String permission, boolean intro, List<String> keys) {}

    private record Page(Section section, List<String> keys) {}

    private static final int PER_PAGE = 8;
    private final String command;
    private final List<Section> sections;
    private final Text text;

    public HelpPages(String command, List<Section> sections, Text text) {
        this.command = command;
        this.sections = List.copyOf(sections);
        this.text = text;
    }

    private List<Page> pages(CommandSender sender) {
        List<Page> pages = new ArrayList<>();
        for (Section section : sections) {
            if (section.permission() != null && !sender.hasPermission(section.permission())) continue;
            int body = PER_PAGE - (section.intro() ? 1 : 0);
            for (int from = 0; from < section.keys().size(); from += body)
                pages.add(new Page(section, section.keys().subList(from, Math.min(section.keys().size(), from + body))));
        }
        return pages;
    }

    /** @param args what follows the word "help"; anything but a number shows the first page */
    public void show(CommandSender sender, String[] args) {
        List<Page> pages = pages(sender);
        int page = 1;
        if (args.length > 0) try { page = Integer.parseInt(args[0]); } catch (NumberFormatException ignored) { /* first page */ }
        show(sender, pages, Math.min(Math.max(page, 1), pages.size()));
    }

    /** The first page of one topic, for commands that point at their own help (for example facility upkeep). */
    public void showTopic(CommandSender sender, String id) {
        List<Page> pages = pages(sender);
        for (int i = 0; i < pages.size(); i++) if (pages.get(i).section().id().equals(id)) { show(sender, pages, i + 1); return; }
        show(sender, pages, 1);
    }

    private void show(CommandSender sender, List<Page> pages, int page) {
        Page shown = pages.get(page - 1);
        sender.sendMessage(text.of(sender, "help.section", command, page, pages.size()));
        if (shown.section().intro()) sender.sendMessage(text.of(sender, "help.intro." + shown.section().id()));
        for (String key : shown.keys()) sender.sendMessage(text.of(sender, "help." + key));
        if (page < pages.size()) sender.sendMessage(text.of(sender, "help.next", command, page + 1));
    }
}
