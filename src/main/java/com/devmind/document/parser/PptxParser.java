package com.devmind.document.parser;

import org.springframework.stereotype.Component;

/** PowerPoint 演示文稿（.pptx）解析：Tika 提取文本 */
@Component
public class PptxParser extends TikaOfficeParser {

    @Override
    public String supportedType() {
        return "pptx";
    }
}
