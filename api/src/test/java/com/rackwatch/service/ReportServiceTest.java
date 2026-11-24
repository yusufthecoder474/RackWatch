package com.rackwatch.service;

import com.rackwatch.model.Reading;
import com.rackwatch.model.Room;
import com.rackwatch.model.Sensor;
import com.rackwatch.model.SensorKind;
import com.rackwatch.repository.ReadingRepository;
import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.CellValue;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Reads the generated workbook back with POI and checks it: headers,
 * data rows, the real min/avg/max formulas, and the values those
 * formulas evaluate to. A workbook whose formulas are wrong strings
 * (or missing) fails here — writing the file is not enough. */
class ReportServiceTest {

    private static final Instant T0 = Instant.parse("2026-11-23T12:00:00Z");

    private static Reading reading(Sensor sensor, Instant ts, Double tempC, Double rhPct) {
        return new Reading(sensor, ts, tempC, rhPct);
    }

    private static List<Reading> sampleRows() {
        Room room = new Room("ROOM-A", "Room A", null);
        Sensor temp = new Sensor("ROOM-A-TEMP-01", room, SensorKind.TEMP, "°C");
        Sensor rh = new Sensor("ROOM-A-RH-01", room, SensorKind.HUMIDITY, "%RH");
        return List.of(
                reading(temp, T0, 20.0, null),
                reading(temp, T0.plusSeconds(60), 22.5, null),
                reading(temp, T0.plusSeconds(120), 25.0, null),
                reading(rh, T0, null, 40.0),
                reading(rh, T0.plusSeconds(60), null, 60.0));
    }

    private static Workbook readBack(byte[] bytes) throws Exception {
        return new XSSFWorkbook(new ByteArrayInputStream(bytes));
    }

    @Test
    void workbookHasHeadersDataRowsAndFormulas() throws Exception {
        ReportService service = new ReportService(mock(ReadingRepository.class));
        try (Workbook workbook = readBack(service.buildWorkbook(sampleRows()))) {
            Sheet sheet = workbook.getSheet("Readings");
            assertTrue(sheet != null, "sheet 'Readings' must exist");
            assertEquals("Timestamp (UTC)", sheet.getRow(0).getCell(0).getStringCellValue());
            assertEquals("Sensor", sheet.getRow(0).getCell(1).getStringCellValue());
            assertEquals("Temp (°C)", sheet.getRow(0).getCell(2).getStringCellValue());
            assertEquals("Humidity (%RH)", sheet.getRow(0).getCell(3).getStringCellValue());

            // 5 data rows, Excel rows 2..6.
            assertEquals(Date.from(T0), sheet.getRow(1).getCell(0).getDateCellValue());
            assertEquals("ROOM-A-TEMP-01", sheet.getRow(1).getCell(1).getStringCellValue());
            assertEquals(20.0, sheet.getRow(1).getCell(2).getNumericCellValue());
            assertEquals(CellType.BLANK, sheet.getRow(1).getCell(3).getCellType());
            assertEquals(60.0, sheet.getRow(5).getCell(3).getNumericCellValue());

            // Summary: header at 0-based row 7, formulas at rows 8..13.
            assertEquals("Summary", sheet.getRow(7).getCell(0).getStringCellValue());
            assertEquals("Min temp (°C)", sheet.getRow(8).getCell(0).getStringCellValue());
            assertEquals("IF(COUNT(C2:C6)=0,\"n/a\",MIN(C2:C6))",
                    sheet.getRow(8).getCell(1).getCellFormula());
            assertEquals("IF(COUNT(C2:C6)=0,\"n/a\",AVERAGE(C2:C6))",
                    sheet.getRow(9).getCell(1).getCellFormula());
            assertEquals("IF(COUNT(C2:C6)=0,\"n/a\",MAX(C2:C6))",
                    sheet.getRow(10).getCell(1).getCellFormula());
            assertEquals("IF(COUNT(D2:D6)=0,\"n/a\",MIN(D2:D6))",
                    sheet.getRow(11).getCell(1).getCellFormula());
            assertEquals("IF(COUNT(D2:D6)=0,\"n/a\",AVERAGE(D2:D6))",
                    sheet.getRow(12).getCell(1).getCellFormula());
            assertEquals("IF(COUNT(D2:D6)=0,\"n/a\",MAX(D2:D6))",
                    sheet.getRow(13).getCell(1).getCellFormula());
        }
    }

    @Test
    void formulasEvaluateToTheRightNumbers() throws Exception {
        ReportService service = new ReportService(mock(ReadingRepository.class));
        try (Workbook workbook = readBack(service.buildWorkbook(sampleRows()))) {
            var evaluator = workbook.getCreationHelper().createFormulaEvaluator();
            Sheet sheet = workbook.getSheet("Readings");
            // Rows 8..13 are min/avg/max temp then min/avg/max humidity.
            double[] expected = {20.0, 22.5, 25.0, 40.0, 50.0, 60.0};
            for (int i = 0; i < expected.length; i++) {
                CellValue value = evaluator.evaluate(sheet.getRow(8 + i).getCell(1));
                assertEquals(CellType.NUMERIC, value.getCellType());
                assertEquals(expected[i], value.getNumberValue(), 1e-9);
            }
        }
    }

    @Test
    void emptyRangeReportsNAInsteadOfZeroOrDiv0() throws Exception {
        ReportService service = new ReportService(mock(ReadingRepository.class));
        try (Workbook workbook = readBack(service.buildWorkbook(List.of()))) {
            var evaluator = workbook.getCreationHelper().createFormulaEvaluator();
            Sheet sheet = workbook.getSheet("Readings");
            // No data rows: summary header at row 2, formulas at rows 3..8.
            assertEquals("Summary", sheet.getRow(2).getCell(0).getStringCellValue());
            for (int i = 0; i < 6; i++) {
                CellValue value = evaluator.evaluate(sheet.getRow(3 + i).getCell(1));
                assertEquals(CellType.STRING, value.getCellType());
                assertEquals("n/a", value.getStringValue());
            }
        }
    }

    @Test
    void invertedRangeIsRejected() {
        ReadingRepository repo = mock(ReadingRepository.class);
        ReportService service = new ReportService(repo);
        assertThrows(IllegalArgumentException.class,
                () -> service.buildReport(T0.plusSeconds(60), T0));
    }

    @Test
    void rangeWiderThan31DaysIsRejected() {
        ReadingRepository repo = mock(ReadingRepository.class);
        ReportService service = new ReportService(repo);
        assertThrows(IllegalArgumentException.class,
                () -> service.buildReport(T0, T0.plusSeconds(31L * 24 * 3600 + 1)));
    }

    @Test
    void exactly31DaysIsAccepted() {
        ReadingRepository repo = mock(ReadingRepository.class);
        when(repo.findByTsBetweenOrderByTsAsc(any(), any(), any(Pageable.class)))
                .thenReturn(List.of());
        ReportService service = new ReportService(repo);
        byte[] bytes = service.buildReport(T0, T0.plusSeconds(31L * 24 * 3600));
        assertTrue(bytes.length > 0);
    }

    @Test
    void moreThan10000ReadingsIsRejected() {
        ReadingRepository repo = mock(ReadingRepository.class);
        List<Reading> tooMany = new ArrayList<>();
        Room room = new Room("ROOM-A", "Room A", null);
        Sensor temp = new Sensor("ROOM-A-TEMP-01", room, SensorKind.TEMP, "°C");
        for (int i = 0; i <= ReportService.MAX_ROWS; i++) {
            tooMany.add(reading(temp, T0.plusSeconds(i), 22.0, null));
        }
        when(repo.findByTsBetweenOrderByTsAsc(any(), any(), any(Pageable.class)))
                .thenReturn(tooMany);
        ReportService service = new ReportService(repo);
        assertThrows(IllegalArgumentException.class,
                () -> service.buildReport(T0, T0.plusSeconds(3600)));
    }
}
