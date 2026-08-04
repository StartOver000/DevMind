package com.devmind.document.parser;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

@Component
public class MarkdownParser implements DocumentParser {

    @Override
    public String supportedType() {
        return "markdown";
    }

    @Override
    public String parse(byte[] bytes, String fileName) {
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
