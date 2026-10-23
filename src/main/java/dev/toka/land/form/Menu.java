package dev.toka.land.form;

import cn.nukkit.Player;
import cn.nukkit.form.element.ElementButton;
import cn.nukkit.form.element.ElementButtonImageData;
import cn.nukkit.form.response.FormResponseSimple;
import cn.nukkit.form.window.FormWindowSimple;
import java.util.ArrayList;
import java.util.List;

/** A button window whose buttons carry their own action, so nothing is matched by text. */
final class Menu {
    private final LandWindow host;
    private final Player player;
    private final FormWindowSimple window;
    private final List<Runnable> actions = new ArrayList<>();

    Menu(LandWindow host, Player player, String title, String content) {
        this.host = host;
        this.player = player;
        this.window = new FormWindowSimple(title, content);
    }

    Menu button(String text, String icon, Runnable action) {
        window.addButton(icon == null ? new ElementButton(text) : new ElementButton(text, new ElementButtonImageData("path", icon)));
        actions.add(action);
        return this;
    }

    void show() {
        host.show(player, window, response -> {
            if (response instanceof FormResponseSimple simple) {
                int index = simple.getClickedButtonId();
                if (index >= 0 && index < actions.size()) actions.get(index).run();
            }
        });
    }
}
