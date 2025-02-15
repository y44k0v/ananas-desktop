package org.ananas.runner.steprunner.files.excel;

import java.io.FileInputStream;
import java.io.IOException;
import java.util.*;
import org.ananas.runner.kernel.errors.AnanasException;
import org.ananas.runner.kernel.errors.ErrorHandler;
import org.ananas.runner.kernel.errors.ExceptionHandler;
import org.ananas.runner.kernel.paginate.AutoDetectedSchemaPaginator;
import org.apache.beam.sdk.schemas.Schema;
import org.apache.beam.sdk.values.Row;
import org.apache.commons.lang3.tuple.MutablePair;
import org.apache.poi.openxml4j.exceptions.InvalidFormatException;
import org.apache.poi.ss.usermodel.*;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import spark.utils.StringUtils;

public class ExcelPaginator extends AutoDetectedSchemaPaginator {

  private static final Logger LOG = LoggerFactory.getLogger(ExcelPaginator.class);

  protected static int MAX_EMPTY_LEFTMOST_COLUMNS = 100;

  protected ExcelStepConfig excelConfig;
  protected ErrorHandler errors;

  public ExcelPaginator(String id, String type, Map<String, Object> config, Schema schema) {
    super(id, type, config, schema);
    this.errors = new ErrorHandler();
  }

  @Override
  public void parseConfig(Map<String, Object> config) {
    this.excelConfig = new ExcelStepConfig(config);
  }

  @Override
  public Schema autodetect() {
    return null; // saving some IO here. Excel Schema is built while iterating rows.
  }

  @Override
  public Iterable<Row> iterateRows(Integer page, Integer pageSize) {
    MutablePair<Schema, Iterable<Row>> rs =
        extractWorkbook(
            new ErrorHandler(),
            this.excelConfig,
            page * pageSize,
            page * pageSize + pageSize,
            e -> e);
    this.schema = rs.getLeft();
    return rs.getRight();
  }

  interface ParDoExcelRow<T> {
    T rowTo(Row r);
  }

  public static <T> MutablePair<Schema, Iterable<T>> extractWorkbook(
      ErrorHandler errorHandler,
      ExcelStepConfig config,
      int offset,
      int limit,
      ParDoExcelRow<T> lambdaFunction) {
    Schema schema = null;
    List<T> rows = new LinkedList<>();
    Workbook workbook = null;
    try {
      // use file input stream here to avoid writing to the excel file when close
      // see:
      // https://stackoverflow.com/questions/49073251/why-does-apache-pois-workbook-close-method-write-content-to-input-file
      workbook = WorkbookFactory.create(new FileInputStream(config.path));
      // Retrieving the number of sheets in the Workbook
      LOG.info("Workbook has " + workbook.getNumberOfSheets() + " sheets");

      Sheet sheet = workbook.getSheetAt(0);

      if (StringUtils.isNotEmpty(config.sheetName)) {
        sheet = workbook.getSheet(config.sheetName);
        if (sheet == null) {
          throw new RuntimeException("No sheet with name " + config.sheetName);
        }
      }

      LOG.info("Sheetname selected :" + sheet.getSheetName());

      int realFirstRow = sheet.getFirstRowNum();

      LOG.info("\n\nIterating over Rows and Columns with lambda\n");
      LOG.info("\n\nFirst row " + realFirstRow);
      LOG.info("\n\nLast row " + sheet.getLastRowNum());
      Schema.Builder schemaBuilder = Schema.builder();
      int headerIndex = 0;
      for (int i = sheet.getFirstRowNum(); i <= sheet.getLastRowNum(); i++) {
        LOG.info("searching header in row " + i);
        Iterator<Cell> header = sheet.getRow(i).iterator();
        org.apache.poi.ss.usermodel.Row a = sheet.getRow(Math.min(i + 1, sheet.getLastRowNum()));
        if (a != null) {

          Iterator<Cell> firstRow = a.iterator();
          schemaBuilder = Schema.builder();
          Map<String, Schema.FieldType> fields = new HashMap<>();
          headerIndex = 0;
          while (header.hasNext()) {
            Cell cellHeader = header.next();
            if (!firstRow.hasNext()) {
              throw new RuntimeException(
                  "No data exist after header line "
                      + i
                      + ". Expected a cell value for each header cells");
            }

            Cell firstRowCell = firstRow.next();
            MutablePair<Schema.FieldType, Object> firstRowCol = toRowField(firstRowCell);
            MutablePair<Schema.FieldType, Object> headerCol = toRowField(cellHeader);

            boolean isCellEmpty =
                (cellHeader == null
                    || "".equals(cellHeader.toString())
                    || headerCol.getRight() == null
                    || headerCol.getLeft() != Schema.FieldType.STRING);
            if (isCellEmpty) {
              if (!fields.isEmpty()) {
                LOG.debug(
                    "header cell {}, row {}, cell value {} end of row",
                    headerIndex,
                    i,
                    cellHeader.toString());
                break;
              }
              headerIndex++;
              LOG.debug(
                  "header cell {}, row {}, cell value {} not text",
                  headerIndex,
                  i,
                  cellHeader.toString());
            } else {
              LOG.debug(
                  "header cell {}, col {}, cell value {} is text",
                  headerIndex,
                  i,
                  cellHeader.toString());
              fields.put((String) headerCol.getRight(), headerCol.getLeft());
              schemaBuilder.addNullableField((String) headerCol.getRight(), firstRowCol.getLeft());
            }
            if (headerIndex > MAX_EMPTY_LEFTMOST_COLUMNS && fields.isEmpty()) {
              LOG.debug(
                  "header cell {}, row {}, cell value {} skipped lined",
                  headerIndex,
                  i,
                  cellHeader.toString());
              break;
            }
          }
          if (!fields.isEmpty()) {
            LOG.info("header line found at " + i);
            realFirstRow = Math.min(i + 1, sheet.getLastRowNum());
            break;
          }
          LOG.debug("no header line found at " + i);
        }
      }
      schema = schemaBuilder.build();

      LOG.debug("Col0 " + headerIndex);
      LOG.info("Limit " + limit);
      LOG.info("Offset " + offset);
      int firstRow = Math.min(sheet.getLastRowNum(), realFirstRow + offset);
      int lastRow = Math.min(sheet.getLastRowNum(), Math.abs(firstRow + limit));
      LOG.info("First row " + firstRow);
      LOG.info("Last row " + lastRow);

      for (int j = firstRow; j <= lastRow && sheet.getRow(j) != null; j++) {
        org.apache.beam.sdk.values.Row.Builder r =
            org.apache.beam.sdk.values.Row.withSchema(schema);
        Iterator<Cell> row = sheet.getRow(j).iterator();
        int rowCellIndex = 0;
        int k = 0;
        while (row.hasNext() && rowCellIndex < schema.getFields().size()) {
          Cell c = row.next();
          if (k++ >= headerIndex) {
            rowCellIndex++;
            LOG.debug("row {}, cell value {} ", j, c.toString());
            r.addValue(toRowField(c).getRight());
          }
        }
        rows.add(lambdaFunction.rowTo(r.build()));
      }

    } catch (IOException e) {
      throw new AnanasException(
          MutablePair.of(ExceptionHandler.ErrorCode.GENERAL, "Can't read your Excel file. "));
    } catch (InvalidFormatException e) {
      throw new AnanasException(
          MutablePair.of(ExceptionHandler.ErrorCode.GENERAL, "Oops. Invalid format? "));
    } catch (Exception e) {
      errorHandler.addError(ExceptionHandler.ErrorCode.GENERAL, e.getMessage());
    } finally {
      try {
        workbook.close();
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    }
    LOG.info("excel - " + schema.toString());
    LOG.info("excel - number of rows : " + rows.size());
    return MutablePair.of(schema, rows);
  }

  /**
   * Return a Row field tuple [fieldType,fieldValue]
   *
   * @param cell
   * @return
   */
  private static MutablePair<Schema.FieldType, Object> toRowField(Cell cell) {
    switch (cell.getCellTypeEnum()) {
      case BOOLEAN:
        return MutablePair.of(Schema.FieldType.BOOLEAN, cell.getBooleanCellValue());
      case STRING:
        return MutablePair.of(Schema.FieldType.STRING, cell.getRichStringCellValue().getString());
      case NUMERIC:
        if (DateUtil.isCellDateFormatted(cell)) {
          Date d = cell.getDateCellValue();
          return MutablePair.of(
              Schema.FieldType.DATETIME, new DateTime(d.getTime()).toDateTime(DateTimeZone.UTC));
        } else {
          return MutablePair.of(Schema.FieldType.DOUBLE, cell.getNumericCellValue());
        }
      case FORMULA:
        return MutablePair.of(Schema.FieldType.DOUBLE, cell.getNumericCellValue());
      case ERROR:
        return MutablePair.of(Schema.FieldType.STRING, "");
      case BLANK:
        return MutablePair.of(Schema.FieldType.STRING, "");
      default:
        return MutablePair.of(Schema.FieldType.STRING, "");
    }
  }
}
