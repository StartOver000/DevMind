package com.devmind.document.parser;

import com.devmind.common.ApiException;
import com.devmind.common.ErrorCode;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
public class DocumentParserRegistry {

    private final Map<String, DocumentParser> parsers;

    public DocumentParserRegistry(List<DocumentParser> parsers) {
        this.parsers = parsers.stream()
                .collect(Collectors.toUnmodifiableMap(DocumentParser::supportedType, Function.identity()));
    }

    public String parse(String fileName, String fileType, byte[] bytes) {
        DocumentParser parser = parsers.get(fileType);
        if (parser == null) {
            throw new ApiException(ErrorCode.FILE_TYPE_NOT_SUPPORTED, "不支持的文件类型: " + fileType);
        }
        return parser.parse(bytes, fileName);
    }
}
