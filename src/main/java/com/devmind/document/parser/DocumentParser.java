package com.devmind.document.parser;

public interface DocumentParser {

    String supportedType();

    String parse(byte[] bytes, String fileName);
}
