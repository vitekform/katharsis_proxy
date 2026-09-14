package me.ganamaga.katharsis_paper.protocol;

import java.util.LinkedHashMap;
import java.util.Optional;

/**
 * A decoded reply from the proxy.
 *
 * @param operation the operation being answered
 * @param requestId echoed back, used to match this to its request
 * @param uuid      the player the operation concerned
 * @param success   whether the operation was applied
 * @param language  resulting language on success, empty on failure
 * @param error     error code on failure, empty on success
 * @param menu      selector payload, present only on {@code get_language_menu}
 * @param serverName the name the proxy knows this server by, present only on
 *                   {@code announce}; empty when the proxy did not send one
 */
public record ProxyResponse(String operation, String requestId, String uuid,
                            boolean success, String language, String error,
                            Menu menu, String serverName) {

    public ProxyResponse {
        serverName = serverName == null ? "" : serverName;
    }

    /**
     * The selector payload.
     *
     * @param title       menu title, as a MiniMessage template
     * @param selectHint  lore for a language the player may pick
     * @param currentHint lore for the language the player already uses
     * @param languages   language code to its own display name, in menu order
     */
    public record Menu(String title, String selectHint, String currentHint,
                       LinkedHashMap<String, String> languages) {

        public Menu {
            languages = new LinkedHashMap<>(languages);
        }

        @Override
        public LinkedHashMap<String, String> languages() {
            return new LinkedHashMap<>(languages);
        }
    }

    public Optional<Menu> menuData() {
        return Optional.ofNullable(menu);
    }

    /** The menu payload, or a failure if this response did not carry one. */
    public Menu requireMenu() {
        if (menu == null) {
            throw new IllegalStateException("response to " + operation + " carries no menu");
        }
        return menu;
    }
}
