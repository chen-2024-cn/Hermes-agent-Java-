package com.cyk.ui;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SlashMenuTest {
    private SlashMenu menu() {
        var menu = new SlashMenu();
        menu.configure(List.of(new UserInput.CommandChoice("/help", "帮助", false),
                new UserInput.CommandChoice("/sessions", "会话列表", false),
                new UserInput.CommandChoice("/resume", "恢复会话", true),
                new UserInput.CommandChoice("/debug tools-json", "工具定义", true)));
        return menu;
    }

    @Test void slashOpensAllChoicesAndTypingFiltersWithoutSubmission() {
        var menu = menu(); menu.update("/", 1, false);
        assertThat(menu.render(80, 10)).contains("/help", "/sessions", "/resume", "/debug tools-json", "> /help");
        menu.update("/se", 3, false);
        assertThat(menu.render(80, 10)).contains("> /sessions").doesNotContain("/help");
        assertThat(menu.accept(false)).isEqualTo("/sessions");
        menu.update("/sessions", 9, false);
        assertThat(menu.visible()).isFalse();
    }

    @Test void directionsSelectAndArgumentsAreNotInsertedAsExampleText() {
        var menu = menu(); menu.update("/", 1, false); menu.move(1); menu.move(1);
        assertThat(menu.accept(false)).isEqualTo("/resume ");
        menu.update("/resume id", 10, false);
        assertThat(menu.visible()).isFalse();
    }

    @Test void exactHandTypedCommandUsesNormalEnterButTabStillCompletes() {
        var menu = menu(); menu.update("/resume", 7, false);
        assertThat(menu.accept(false)).isNull();
        menu.reset(); menu.update("/resume", 7, false);
        assertThat(menu.accept(true)).isEqualTo("/resume ");
    }

    @Test void escapeDismissesSamePrefixAndEditingReopensIt() {
        var menu = menu(); menu.update("/", 1, false); menu.dismiss(); menu.update("/", 1, false);
        assertThat(menu.visible()).isFalse();
        menu.update("/r", 2, false); assertThat(menu.visible()).isTrue();
    }

    @Test void pasteMultilineMiddleCursorAndOrdinarySlashDoNotOpenMenu() {
        for (String text : List.of("text /", "/sessions\nbody", "/unknown")) {
            var menu = menu(); menu.update(text, text.length(), false); assertThat(menu.visible()).isFalse();
        }
        var menu = menu(); menu.update("/", 1, true); assertThat(menu.visible()).isFalse();
        menu.update("/se", 1, false); assertThat(menu.visible()).isFalse();
    }

    @Test void constrainedScreenCanScrollToEveryChoice() {
        var menu = menu(); menu.update("/", 1, false); menu.move(-1);
        String result = menu.render(30, 3);
        assertThat(result).contains("> /debug tools-json");
        assertThat(result.lines().count()).isLessThanOrEqualTo(3);
        assertThat(menu.render(30, 1)).isEmpty();
    }
}
