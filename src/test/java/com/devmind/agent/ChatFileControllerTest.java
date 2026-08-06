package com.devmind.agent;

import com.devmind.common.ApiException;
import com.devmind.document.parser.DocumentParserRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@SuppressWarnings("null")
class ChatFileControllerTest {

    @Mock
    private DocumentParserRegistry parserRegistry;

    @Mock
    private ChatFileStore fileStore;

    private ChatFileController controller;

    @BeforeEach
    void setUp() {
        controller = new ChatFileController(parserRegistry, fileStore);
    }

    private MultipartFile file(String name, byte[] content) {
        return new MockMultipartFile("file", name, "text/plain", content);
    }

    @Test
    void uploadParsesAndReturnsFileId() throws Exception {
        when(parserRegistry.parse(anyString(), anyString(), org.mockito.ArgumentMatchers.any()))
                .thenReturn("解析出的文本");
        when(fileStore.put(1L, "report.md", "解析出的文本")).thenReturn("f1");

        Map<String, Object> res = controller.upload(file("report.md", "raw".getBytes()), 1L);

        assertThat(res.get("fileId")).isEqualTo("f1");
        assertThat(res.get("fileName")).isEqualTo("report.md");
        assertThat(res.get("textLength")).isEqualTo(6);
        assertThat(res.get("truncated")).isEqualTo(false);
    }

    @Test
    void emptyFileRejected() {
        assertThatThrownBy(() -> controller.upload(file("empty.txt", new byte[0]), 1L))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("为空");
    }

    @Test
    void oversizedFileRejected() {
        byte[] big = new byte[10 * 1024 * 1024 + 1];
        assertThatThrownBy(() -> controller.upload(file("big.txt", big), 1L))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("10MB");
    }

    @Test
    void parseFailureRejected() throws Exception {
        when(parserRegistry.parse(anyString(), anyString(), org.mockito.ArgumentMatchers.any()))
                .thenThrow(new RuntimeException("unsupported"));

        assertThatThrownBy(() -> controller.upload(file("x.xyz", "data".getBytes()), 1L))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("解析失败");
    }
}
