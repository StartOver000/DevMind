package com.devmind.document.parser;

import com.devmind.common.ApiException;
import com.devmind.common.ErrorCode;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Excel（.xlsx）解析：保留表格结构转 Markdown 表格。
 * AI 分析表格时需要知道行列关系（哪列是什么指标），纯文本拼接会丢结构。
 * 行/列超限截断，防超大表格撑爆上下文。
 */
@Component
@SuppressWarnings("null")
public class XlsxParser implements DocumentParser {

    /** 最大解析行数（含表头） */
    private static final int MAX_ROWS = 500;
    /** 最大解析列数 */
    private static final int MAX_COLS = 50;

    @Override
    public String supportedType() {
        return "xlsx";
    }

    @Override
    public String parse(byte[] bytes, String fileName) {
        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            StringBuilder sb = new StringBuilder();
            for (int s = 0; s < workbook.getNumberOfSheets(); s++) {
                Sheet sheet = workbook.getSheetAt(s);
                sb.append("## Sheet: ").append(sheet.getSheetName()).append('\n');
                int lastRow = sheet.getLastRowNum();
                if (lastRow < 0) {
                    sb.append("（空表格）\n\n");
                    continue;
                }
                int rows = Math.min(lastRow + 1, MAX_ROWS);
                for (int r = 0; r < rows; r++) {
                    Row row = sheet.getRow(r);
                    if (row == null) {
                        continue;
                    }
                    int lastCell = Math.max(row.getLastCellNum(), 0);
                    int cols = Math.min(lastCell, MAX_COLS);
                    List<String> cells = new ArrayList<>(cols);
                    for (int c = 0; c < cols; c++) {
                        cells.add(cellText(row.getCell(c)));
                    }
                    // 空行跳过
                    if (cells.stream().allMatch(String::isEmpty)) {
                        continue;
                    }
                    sb.append("| ").append(String.join(" | ", cells)).append(" |\n");
                    if (r == 0) {
                        sb.append('|').append(" --- |".repeat(Math.max(1, cells.size()))).append('\n');
                    }
                }
                if (rows > lastRow + 1) {
                    sb.append("…（表格超过 ").append(MAX_ROWS).append(" 行，已截断）\n");
                }
                sb.append('\n');
            }
            String result = sb.toString().trim();
            return result.isEmpty() ? "（空表格）" : result;
        } catch (ApiException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new ApiException(ErrorCode.DOCUMENT_PROCESS_FAILED, "Excel 解析失败: " + ex.getMessage());
        }
    }

    private String cellText(Cell cell) {
        if (cell == null) {
            return "";
        }
        String text;
        try {
            switch (cell.getCellType()) {
                case STRING -> text = cell.getStringCellValue();
                case NUMERIC -> {
                    if (DateUtil.isCellDateFormatted(cell)) {
                        text = cell.getLocalDateTimeCellValue().toLocalDate().toString();
                    } else {
                        double d = cell.getNumericCellValue();
                        text = d == Math.floor(d) && !Double.isInfinite(d)
                                ? String.valueOf((long) d) : String.valueOf(d);
                    }
                }
                case BOOLEAN -> text = String.valueOf(cell.getBooleanCellValue());
                case FORMULA -> {
                    CellType cached = cell.getCachedFormulaResultType();
                    text = cached == CellType.NUMERIC
                            ? String.valueOf(cell.getNumericCellValue())
                            : cell.getStringCellValue();
                }
                default -> text = "";
            }
        } catch (Exception ex) {
            text = "";
        }
        // 转义 Markdown 表格特殊字符
        return text.replace("|", "\\|").replace("\n", " ").replace("\r", " ").trim();
    }
}
