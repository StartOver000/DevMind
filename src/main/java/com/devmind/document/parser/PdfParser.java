package com.devmind.document.parser;

import com.devmind.common.ApiException;
import com.devmind.common.ErrorCode;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.BodyContentHandler;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.InputStream;

@Component
public class PdfParser implements DocumentParser {

    @Override
    public String supportedType() {
        return "pdf";
    }

    @Override
    public String parse(byte[] bytes, String fileName) {
        try (InputStream input = new ByteArrayInputStream(bytes)) {
            BodyContentHandler handler = new BodyContentHandler(-1);
            Metadata metadata = new Metadata();
            ParseContext context = new ParseContext();
            new AutoDetectParser().parse(input, handler, metadata, context);
            return handler.toString();
        } catch (Exception ex) {
            throw new ApiException(ErrorCode.DOCUMENT_PROCESS_FAILED, "PDF 解析失败: " + ex.getMessage());
        }
    }
}
