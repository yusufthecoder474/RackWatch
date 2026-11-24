package com.rackwatch.service;

import com.rackwatch.model.Reading;
import com.rackwatch.repository.ReadingRepository;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Builds the readings .xlsx report. The workbook is built by
 * buildWorkbook(List) — a pure function of the rows, so the POI test
 * can exercise it without a database. buildReport(Instant, Instant)
 * adds the bounded-range rules and loads the rows. */
@Service
public class ReportService {

    /** The report range must be at most 31 days. An unbounded export
     * would let one request dump months of 5-second readings. */
    public static final int MAX_RANGE_DAYS = 31;

    /** At most this many readings in one report. Even a 31-day range at
     * 5-second sampling holds over 2 million rows; the row cap is the
     * second bound, so the workbook stays small. */
    public static final int MAX_ROWS = 10_000;

    // Column 0 is labelled with its zone: the API container runs on
    // UTC (TZ=UTC, see docker-compose.yml and the Dockerfile), so the
    // timestamps in the file are UTC.
    private static final String[] HEADERS = {"Timestamp (UTC)", "Sensor", "Temp (°C)", "Humidity (%RH)"};

    private final ReadingRepository readings;

    public ReportService(ReadingRepository readings) {
        this.readings = readings;
    }

    /** Builds the report bytes for the readings in [from, to].
     * Throws IllegalArgumentException (mapped to 400 by the global
     * handler) when the range is empty, inverted, wider than 31 days,
     * or holds more than 10,000 readings. */
    @Transactional(readOnly = true)
    public byte[] buildReport(Instant from, Instant to) {
        if (from == null || to == null) {
            throw new IllegalArgumentException("Both from and to are required");
        }
        if (!to.isAfter(from)) {
            throw new IllegalArgumentException("to must be after from");
        }
        if (Duration.between(from, to).compareTo(Duration.ofDays(MAX_RANGE_DAYS)) > 0) {
            throw new IllegalArgumentException(
                    "Date range must be at most " + MAX_RANGE_DAYS + " days");
        }
        // One more than the cap: if the query returns MAX_ROWS + 1
        // rows, the range holds too many readings to report.
        List<Reading> rows = readings.findByTsBetweenOrderByTsAsc(
                from, to, PageRequest.of(0, MAX_ROWS + 1));
        if (rows.size() > MAX_ROWS) {
            throw new IllegalArgumentException(
                    "More than " + MAX_ROWS + " readings in the range; narrow the dates");
        }
        return buildWorkbook(rows);
    }

    /** Builds the workbook for the given readings (already ordered by
     * ts ascending). Columns: Timestamp (UTC), Sensor, Temp (°C),
     * Humidity (%RH). The timestamp is a real Excel date, so the sheet
     * sorts and filters on it. Below the data, a Summary section holds REAL Excel
     * formulas for min / average / max of each value column — the
     * numbers are computed by the spreadsheet, not baked in by this
     * code. When a column has no values at all, its formulas return
     * "n/a" instead of Excel's misleading MIN-of-empty-range 0 or
     * AVERAGE's #DIV/0!. */
    public byte[] buildWorkbook(List<Reading> rows) {
        try (Workbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("Readings");
            sheet.createFreezePane(0, 1);
            sheet.setAutoFilter(new CellRangeAddress(0, 0, 0, HEADERS.length - 1));

            CellStyle dateStyle = workbook.createCellStyle();
            dateStyle.setDataFormat(
                    workbook.createDataFormat().getFormat("yyyy-mm-dd hh:mm:ss"));
            CellStyle boldStyle = workbook.createCellStyle();
            org.apache.poi.ss.usermodel.Font bold = workbook.createFont();
            bold.setBold(true);
            boldStyle.setFont(bold);

            Row header = sheet.createRow(0);
            for (int i = 0; i < HEADERS.length; i++) {
                Cell cell = header.createCell(i);
                cell.setCellValue(HEADERS[i]);
                cell.setCellStyle(boldStyle);
            }
            int rowIndex = 1;
            for (Reading reading : rows) {
                Row row = sheet.createRow(rowIndex++);
                Cell tsCell = row.createCell(0);
                tsCell.setCellValue(Date.from(reading.getTs()));
                tsCell.setCellStyle(dateStyle);
                row.createCell(1).setCellValue(reading.getSensor().getId());
                Cell tempCell = row.createCell(2);
                if (reading.getTempC() != null) {
                    tempCell.setCellValue(reading.getTempC());
                }

                Cell humidityCell = row.createCell(3);
                if (reading.getRhPct() != null) {
                    humidityCell.setCellValue(reading.getRhPct());
                }
            }

            // Summary: data rows are Excel rows 2..rows.size()+1. With
            // no data rows the range is a single empty cell (C2:C2), so
            // the COUNT guard below returns "n/a" instead of a
            // reversed range (C2:C1), which Excel and POI disagree on.
            int firstDataRow = 2;
            int lastDataRow = Math.max(firstDataRow, rows.size() + 1);
            int summaryRow = rows.size() + 3; // one blank row after the data
            Row summaryHeader = sheet.createRow(summaryRow - 1);
            Cell summaryTitle = summaryHeader.createCell(0);
            summaryTitle.setCellValue("Summary");
            summaryTitle.setCellStyle(boldStyle);
            String[][] summary = {
                    {"Min temp (°C)", formula("MIN", "C", firstDataRow, lastDataRow)},
                    {"Avg temp (°C)", formula("AVERAGE", "C", firstDataRow, lastDataRow)},
                    {"Max temp (°C)", formula("MAX", "C", firstDataRow, lastDataRow)},
                    {"Min humidity (%RH)", formula("MIN", "D", firstDataRow, lastDataRow)},
                    {"Avg humidity (%RH)", formula("AVERAGE", "D", firstDataRow, lastDataRow)},
                    {"Max humidity (%RH)", formula("MAX", "D", firstDataRow, lastDataRow)},
            };
            for (int i = 0; i < summary.length; i++) {
                Row row = sheet.createRow(summaryRow + i);
                row.createCell(0).setCellValue(summary[i][0]);
                row.createCell(1).setCellFormula(summary[i][1]);
            }

            sheet.setColumnWidth(0, 20 * 256);
            sheet.setColumnWidth(1, 18 * 256);
            sheet.setColumnWidth(2, 12 * 256);
            sheet.setColumnWidth(3, 14 * 256);

            workbook.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            // Writing to a byte array cannot fail in practice; if it
            // does, it is a server error, not a client error.
            throw new IllegalStateException("Failed to build the Excel report", e);
        }
    }

    /** =IF(COUNT(range)=0,"n/a",FN(range)): the IF guard is what makes
     * an empty value column report "n/a" instead of 0 or #DIV/0!. */
    private static String formula(String fn, String column, int firstRow, int lastRow) {
        String range = column + firstRow + ":" + column + lastRow;
        return "IF(COUNT(" + range + ")=0,\"n/a\"," + fn + "(" + range + "))";
    }
}
