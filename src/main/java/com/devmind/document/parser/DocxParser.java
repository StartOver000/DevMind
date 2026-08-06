package com.devmind.document.parser;

import org.springframework.stereotype.Component;

/** Word 文档（.docx）解析：Tika 提取文本 */
@Component
public class DocxParser extends TikaOfficeParser {

    @Override
    public String supportedType() {
        return "docx";
    }
}
