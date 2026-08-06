package com.devmind.agent;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ChatFileStoreTest {

    private ChatFileStore store;

    @BeforeEach
    void setUp() {
        store = new ChatFileStore();
    }

    @Test
    void putAndGetRoundTrip() {
        String id = store.put(1L, "a.md", "内容");

        ChatFileStore.ChatFile file = store.get(id, 1L);

        assertThat(file.fileName()).isEqualTo("a.md");
        assertThat(file.text()).isEqualTo("内容");
    }

    @Test
    void getRejectsOtherUsersFile() {
        String id = store.put(1L, "a.md", "内容");

        assertThat(store.get(id, 2L)).isNull();
    }

    @Test
    void getReturnsNullForUnknownId() {
        assertThat(store.get("nope", 1L)).isNull();
    }

    @Test
    void longTextIsTruncated() {
        String big = "x".repeat(ChatFileStore.MAX_TEXT_CHARS + 5000);
        String id = store.put(1L, "big.txt", big);

        ChatFileStore.ChatFile file = store.get(id, 1L);

        assertThat(file.text().length()).isEqualTo(ChatFileStore.MAX_TEXT_CHARS);
    }

    @Test
    void perUserLimitEvictsOldest() {
        String firstId = store.put(1L, "1.txt", "a");
        for (int i = 2; i <= ChatFileStore.MAX_FILES_PER_USER + 5; i++) {
            store.put(1L, i + ".txt", "a");
        }

        // 最旧的被挤出，最新的还在
        assertThat(store.get(firstId, 1L)).isNull();
        // 其他用户的文件不受影响
        String otherId = store.put(2L, "other.txt", "b");
        assertThat(store.get(otherId, 2L)).isNotNull();
    }
}
