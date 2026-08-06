package com.devmind.document.parser;

import com.devmind.common.ApiException;
import com.devmind.common.ErrorCode;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.BodyContentHandler;

import java.io.ByteArrayInputStream;
import java.io.InputStream;

/**
 * 基于 Tika 的通用 Office 文本解析（docx/pptx 等）。
 * Tika 的 AutoDetectParser 按内容自动识别格式，提取纯文本。
 */
public abstract class TikaOfficeParser implements DocumentParser {

    @Override
    public String parse(byte[] bytes, String fileName) {
        try (InputStream input = new ByteArrayInputStream(bytes)) {
            BodyContentHandler handler = new BodyContentHandler(-1);
            Metadata metadata = new Metadata();
            ParseContext context = new ParseContext();
            new AutoDetectParser().parse(input, handler, metadata, context);
            return handler.toString().trim();
        } catch (Exception ex) {
            throw new ApiException(ErrorCode.DOCUMENT_PROCESS_FAILED, "文档解析失败: " + ex.getMessage());
        }
    }
}
