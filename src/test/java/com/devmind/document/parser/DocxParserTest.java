package com.devmind.document.parser;

import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

class DocxParserTest {

    private byte[] docx(String... paragraphs) throws Exception {
        try (XWPFDocument doc = new XWPFDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            for (String p : paragraphs) {
                XWPFParagraph para = doc.createParagraph();
                XWPFRun run = para.createRun();
                run.setText(p);
            }
            doc.write(out);
            return out.toByteArray();
        }
    }

    @Test
    void docxTextExtracted() throws Exception {
        byte[] bytes = docx("这是第一段", "这是第二段");

        String text = new DocxParser().parse(bytes, "报告.docx");

        assertThat(text).contains("这是第一段");
        assertThat(text).contains("这是第二段");
    }
}
