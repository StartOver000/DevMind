package com.devmind.document.parser;

import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

class XlsxParserTest {

    private byte[] xlsx(String sheetName, Object[][] rows) throws Exception {
        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet(sheetName);
            for (int r = 0; r < rows.length; r++) {
                Row row = sheet.createRow(r);
                for (int c = 0; c < rows[r].length; c++) {
                    Object v = rows[r][c];
                    if (v instanceof Number n) {
                        row.createCell(c).setCellValue(n.doubleValue());
                    } else {
                        row.createCell(c).setCellValue(String.valueOf(v));
                    }
                }
            }
            wb.write(out);
            return out.toByteArray();
        }
    }

    @Test
    void xlsxConvertedToMarkdownTableWithStructure() throws Exception {
        byte[] bytes = xlsx("销售", new Object[][]{
                {"月份", "销售额"},
                {"1月", 12000},
                {"2月", 15000.5}
        });

        String text = new XlsxParser().parse(bytes, "销售.xlsx");

        assertThat(text).contains("## Sheet: 销售");
        assertThat(text).contains("| 月份 | 销售额 |");
        assertThat(text).contains("| --- | --- |");
        assertThat(text).contains("| 1月 | 12000 |");
        assertThat(text).contains("| 2月 | 15000.5 |");
    }

    @Test
    void emptySheetMarkedAsEmpty() throws Exception {
        byte[] bytes = xlsx("空表", new Object[][]{});

        String text = new XlsxParser().parse(bytes, "空.xlsx");

        assertThat(text).contains("空表格");
    }
}
