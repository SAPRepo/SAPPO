package com.equalize.xpi.af.modules.excel;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.FormulaEvaluator;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.ss.formula.*;

import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.hssf.usermodel.HSSFEvaluationWorkbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xssf.usermodel.XSSFEvaluationWorkbook;

import org.w3c.dom.Document;
import org.w3c.dom.Node;

import com.equalize.xpi.af.modules.util.AbstractModuleConverter;
import com.equalize.xpi.af.modules.util.AuditLogHelper;
import com.equalize.xpi.af.modules.util.DynamicConfigurationHelper;
import com.equalize.xpi.af.modules.util.ParameterHelper;
import com.equalize.xpi.util.converter.XMLChar;
import com.sap.aii.af.lib.mp.module.ModuleException;
import com.sap.engine.interfaces.messaging.api.Message;
import com.sap.engine.interfaces.messaging.api.auditlog.AuditLogStatus;

public class Excel2XMLTransformer_ext extends AbstractModuleConverter {

	// Module parameters
	private String sheetName;
	private int sheetIndex;
	private String processFieldNames;
	private int headerRow = 0;
	private boolean onlyValidCharsInXMLName;
	private String fieldNames;
	private int columnCount = 0;
	private String recordName;
	private String documentName;
	private String documentNamespace;
	private String formatting;
	private boolean evaluateFormulas;
	private boolean evaluateFormulasExt;
	private String emptyCellOutput;
	private String emptyCellDefaultValue;
	private int rowOffset;
	private int columnOffset;
	private boolean skipEmptyRows;
	private String skipRowsWithEmptyCol = "";
	private int skipRowsWithEmptyColHits;
	private int indentFactor;
	private String isOneLiner = "";
	private String oneLinerFieldName = "";
	private String[] columnNames;
	private int noOfRows = 0;
	private ArrayList<String[]> sheetContents;
	private FormulaEvaluator evaluator;
	private DataFormatter formatter = new DataFormatter(true);
	private EvaluationWorkbook evalWorkbook = null;
	private EvaluationSheet esheet;
	private EvaluationCell ecell;

	// Constructor
	public Excel2XMLTransformer_ext(Message msg, ParameterHelper param, AuditLogHelper audit,
			DynamicConfigurationHelper dyncfg, Boolean debug) {
		super(msg, param, audit, dyncfg, debug);
	}

	@Override
	public void retrieveModuleParameters() throws ModuleException {
		// Active sheet
		this.sheetName = this.param.getParameter("sheetName");
		this.isOneLiner = this.param.getParameter("isOneLiner");
		this.oneLinerFieldName = this.param.getParameter("oneLinerFieldName");
		String sheetIndexString = this.param.getParameter("sheetIndex");
		if (this.sheetName == null && sheetIndexString == null) {
			throw new ModuleException("Parameter sheetName or sheetIndex is missing");
		} else if (this.sheetName != null && sheetIndexString != null) {
			throw new ModuleException("Use only parameter sheetName or sheetIndex, not both");
		} else if (sheetIndexString != null) {
			this.sheetIndex = this.param.getIntMandatoryParameter("sheetIndex");
		}

		// Output XML document properties
		this.recordName = this.param.getParameter("recordName", "Record", true);
		this.documentName = this.param.getMandatoryParameter("documentName");
		this.documentNamespace = this.param.getMandatoryParameter("documentNamespace");

		// Row & Column processing options
		this.skipEmptyRows = this.param.getBoolParameter("skipEmptyRows", "Y", false);
		if (!this.skipEmptyRows) {
			this.audit.addLog(AuditLogStatus.SUCCESS, "Empty rows will be included");
		}

		this.skipRowsWithEmptyCol = this.param.getParameter("skipRowsWithEmptyCol");
		this.skipRowsWithEmptyColHits = this.param.getIntParameter("skipRowsWithEmptyColHits");
		if (this.skipRowsWithEmptyCol == null) {
			this.skipRowsWithEmptyCol = "";
		}

		if (!this.skipRowsWithEmptyCol.equals("")
				&& (String.valueOf(this.skipRowsWithEmptyColHits).equals("") || this.skipRowsWithEmptyColHits == 0)) {
			throw new ModuleException("Parameter skipRowsWithEmptyColHits is missing for skipRowsWithEmptyCol");
		} else {
			this.audit.addLog(AuditLogStatus.SUCCESS, "Rows with " + this.skipRowsWithEmptyColHits + " empty columns "
					+ this.skipRowsWithEmptyCol + " will be skipped");
		}

		this.rowOffset = this.param.getIntParameter("rowOffset");
		this.columnOffset = this.param.getIntParameter("columnOffset");

		// Determine number of columns and field names if any
		this.processFieldNames = this.param.getMandatoryParameter("processFieldNames");
		this.param.checkParamValidValues("processFieldNames", "fromFile,fromConfiguration,notAvailable");
		if (this.processFieldNames.equalsIgnoreCase("fromFile")) {
			this.onlyValidCharsInXMLName = this.param.getBoolParameter("onlyValidCharsInXMLName", "N", false);
			this.headerRow = this.param.getIntParameter("headerRow");
			// this.columnCount remains 0
			if (this.rowOffset == 0) {
				this.rowOffset = this.headerRow + 1;
				this.audit.addLog(AuditLogStatus.ERROR, "Processing automatically skipped to row after header row");
			}
			if (!isOneLiner.equals("")) {
				if (String.valueOf(this.param.getIntParameter("rowOffset")).equals("")) {
					this.rowOffset = 0;
				} else {
					this.rowOffset = this.param.getIntParameter("rowOffset");
				}
				this.audit.addLog(AuditLogStatus.SUCCESS, "One Line, FromFile Offset set to " + this.rowOffset);
			} else {
				// throw an exception if headerRow is equal to or larger than
				// rowOffset.
				if (this.headerRow >= this.rowOffset) {
					throw new ModuleException("Parameter 'rowOffset' must be larger than parameter 'headerRow'");
				}
			}
		} else if (this.processFieldNames.equalsIgnoreCase("fromConfiguration")) {
			this.fieldNames = this.param.getParameter("fieldNames");
			if (this.fieldNames == null || this.fieldNames.replaceAll("\\s+", "").isEmpty()) {
				throw new ModuleException(
						"Parameter 'fieldNames' required when 'processFieldNames' = fromConfiguration");
			} else {
				this.columnNames = this.fieldNames.split(",");
				this.columnCount = this.columnNames.length;
			}
		} else if (this.processFieldNames.equalsIgnoreCase("notAvailable")) {
			this.param.getConditionallyMandatoryParameter("columnCount", "processFieldNames", "notAvailable");
			this.columnCount = this.param.getIntParameter("columnCount");
			if (this.columnCount <= 0) {
				throw new ModuleException("Only positive integers allowed for columnCount");
			}
		}

		// Output options
		this.formatting = this.param.getParameter("formatting", "excel", false);
		this.param.checkParamValidValues("formatting", "excel,raw");
		if (this.formatting.equalsIgnoreCase("raw")) {
			this.audit.addLog(AuditLogStatus.SUCCESS,
					"Cell contents will not be formatted, raw values displayed instead");
		}
		this.evaluateFormulas = this.param.getBoolParameter("evaluateFormulas", "Y", false);
		if (!this.evaluateFormulas) {
			this.audit.addLog(AuditLogStatus.SUCCESS,
					"Formulas will not be evaluated, formula logic displayed instead");
		}

		this.evaluateFormulasExt = this.param.getBoolParameter("evaluateFormulasExt", "N", false);
		if (this.evaluateFormulasExt) {
			this.audit.addLog(AuditLogStatus.SUCCESS, "Ext Formulas will be evaluated");
		}

		this.emptyCellOutput = this.param.getParameter("emptyCellOutput", "suppress", false);
		this.param.checkParamValidValues("emptyCellOutput", "suppress,defaultValue");
		if (this.emptyCellOutput.equalsIgnoreCase("defaultValue")) {
			this.emptyCellDefaultValue = this.param.getParameter("emptyCellDefaultValue", "", false);
			this.audit.addLog(AuditLogStatus.SUCCESS,
					"Empty cells will be filled with default value: '" + this.emptyCellDefaultValue + "'");
		}
		this.indentFactor = this.param.getIntParameter("indentFactor");
		if (this.indentFactor > 0) {
			this.audit.addLog(AuditLogStatus.SUCCESS, "XML output will be indented");
		}
	}

	@Override
	public void parseInput() throws ModuleException {
		// Get workbook
		Workbook wb;
		try {
			wb = WorkbookFactory.create(this.payload.getInputStream());
		} catch (Exception e) {
			throw new ModuleException(e.getMessage(), e);
		}

		if (wb instanceof HSSFWorkbook) {
			evalWorkbook = HSSFEvaluationWorkbook.create((HSSFWorkbook) wb);
		} else if (wb instanceof XSSFWorkbook) {
			evalWorkbook = XSSFEvaluationWorkbook.create((XSSFWorkbook) wb);
		} else {
			throw new IllegalStateException();
		}

		// Get the sheet
		Sheet sheet = retrieveSheet(wb, this.sheetName, this.sheetIndex);
		// Get the number of rows and columns
		if (this.columnCount == 0) { // this only happens if processFieldNames =
										// fromFile
			this.columnCount = retrieveHeaderColumnCount(sheet);
		}

		this.noOfRows = sheet.getLastRowNum() + 1;
		this.audit.addLog(AuditLogStatus.SUCCESS, "Total Rows to be processed " + this.noOfRows);
		// Get the column names from header
		if (this.processFieldNames.equalsIgnoreCase("fromFile")) {
			this.columnNames = retrieveColumnNamesFromFileHeader(sheet, this.columnCount);
		}

		// Get the cell contents of the sheet
		this.sheetContents = extractSheetContents(sheet, wb, this.rowOffset, this.noOfRows, this.columnOffset,
				this.columnCount, this.skipEmptyRows, this.evaluateFormulas, this.formatting, this.debug);
	}

	@Override
	public byte[] generateOutput() throws ModuleException {
		try {
			String lv_rowContent = "";
			DocumentBuilder docBuilder;
			docBuilder = DocumentBuilderFactory.newInstance().newDocumentBuilder();
			Document outDoc = docBuilder.newDocument();

			Node outRoot = outDoc.createElementNS(this.documentNamespace, "ns:" + this.documentName);
			outDoc.appendChild(outRoot);

			this.audit.addLog(AuditLogStatus.SUCCESS, "Constructing output XML");
			// Loop through the 2D array of saved contents
			if (!isOneLiner.equals("")) {
				this.audit.addLog(AuditLogStatus.SUCCESS, "One Liner Content Processing Starts");
				for (int row = 0; row < this.sheetContents.size(); row++) {
					lv_rowContent = "";
					String[] rowContent = this.sheetContents.get(row);
					// Add new row
					Node outRecord = addElementToNode(outDoc, outRoot, this.recordName);

					for (int col = 0; col < rowContent.length; col++) {
						if (rowContent[col] == null && this.emptyCellDefaultValue != null) {
							rowContent[col] = this.emptyCellDefaultValue;
						}

						if (rowContent[col] != null) {

							if (lv_rowContent.equals("")) {
								lv_rowContent = rowContent[col];
							} else {
								lv_rowContent = lv_rowContent + "," + rowContent[col];
							}

						}

					}
					lv_rowContent = lv_rowContent.replaceAll("[\\r\\n]", "");
					// this.audit.addLog(AuditLogStatus.SUCCESS, "X= " +
					// lv_rowContent);
					addElementToNode(outDoc, outRecord, this.oneLinerFieldName, lv_rowContent);
				}
			} else {
				for (int row = 0; row < this.sheetContents.size(); row++) {
					String[] rowContent = this.sheetContents.get(row);
					// Add new row
					Node outRecord = addElementToNode(outDoc, outRoot, this.recordName);
					for (int col = 0; col < rowContent.length; col++) {
						if (rowContent[col] == null && this.emptyCellDefaultValue != null) {
							rowContent[col] = this.emptyCellDefaultValue;
						}
						if (rowContent[col] != null) {
							String fieldName;
							if (this.columnNames != null) {
								fieldName = this.columnNames[col];
							} else {
								fieldName = "Column" + Integer.toString(col + 1);
							}

							// Add fields of the row
							addElementToNode(outDoc, outRecord, fieldName, rowContent[col]);
						}
					}
				}
			}
			// Transform the DOM to OutputStream
			javax.xml.transform.Transformer transformer = TransformerFactory.newInstance().newTransformer();
			if (this.indentFactor > 0) {
				transformer.setOutputProperty(OutputKeys.INDENT, "yes");
				transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount",
						Integer.toString(this.indentFactor));
			}
			ByteArrayOutputStream baos = new ByteArrayOutputStream();
			transformer.transform(new DOMSource(outDoc), new StreamResult(baos));

			this.audit.addLog(AuditLogStatus.SUCCESS, "Conversion complete");
			return baos.toByteArray();
		} catch (Exception e) {
			throw new ModuleException(e.getMessage(), e);
		}
	}

	private Sheet retrieveSheet(Workbook wb, String name, int sheetIndex) throws ModuleException {
		Sheet sheet = null;
		int sheetIndex2;
		if (name != null) {
			this.audit.addLog(AuditLogStatus.SUCCESS, "Accessing sheet " + name);
			sheet = wb.getSheet(name);
			sheetIndex2 = wb.getSheetIndex(name);
			esheet = evalWorkbook.getSheet(sheetIndex2);
			if (sheet == null) {
				throw new ModuleException("Sheet " + name + " not found");
			}
		} else {
			sheet = wb.getSheetAt(sheetIndex);
			esheet = evalWorkbook.getSheet(sheetIndex);
			this.audit.addLog(AuditLogStatus.SUCCESS,
					"Accessing sheet " + sheet.getSheetName() + " at index " + sheetIndex);
		}
		return sheet;
	}

	private int retrieveHeaderColumnCount(Sheet sheet) throws ModuleException {
		Row header = sheet.getRow(this.headerRow);
		int lastCellNum = 0;
		if (header != null) {
			lastCellNum = header.getLastCellNum();
		}
		if (lastCellNum != 0) {
			this.audit.addLog(AuditLogStatus.SUCCESS,
					"No. of columns dynamically set to " + lastCellNum + " based on row " + this.headerRow);
			return lastCellNum;
		} else {
			throw new ModuleException("No. of columns in row " + this.headerRow + " is zero.");
		}
	}

	private String[] retrieveColumnNamesFromFileHeader(Sheet sheet, int columnNo) throws ModuleException {
		Row row = sheet.getRow(this.headerRow);
		this.audit.addLog(AuditLogStatus.SUCCESS, "Retrieving column names from row " + this.headerRow);
		String[] headerColumns = new String[columnNo];
		for (int col = 0; col < columnNo; col++) {
			Cell cell = row.getCell(col);
			if (isOneLiner.equals("")) {
				if (cell == null) {
					throw new ModuleException("Empty column name found");
				}
			}

			if (!isOneLiner.equals("")) {
				if (cell == null) {
					headerColumns[col] = "";
				} else {
					headerColumns[col] = cell.getStringCellValue();
				}

			} else {
				headerColumns[col] = cell.getStringCellValue();
			}

			String fieldName = headerColumns[col].replaceAll("\\s+", "");

			// ensure only valid chars are included in the XML element name
			if (this.onlyValidCharsInXMLName) {
				fieldName = XMLChar.stripInvalidCharsFromName(fieldName);
			}

			if (isOneLiner.equals("")) {
				if (fieldName.isEmpty()) {
					throw new ModuleException("Empty column name found");
				}

				if (!fieldName.equals(headerColumns[col])) {
					this.audit.addLog(AuditLogStatus.SUCCESS,
							"Renaming field '" + headerColumns[col] + "' to " + fieldName);
					headerColumns[col] = fieldName;
				}
			} else {
				headerColumns[col] = fieldName;
				// this.audit.addLog(AuditLogStatus.SUCCESS, "Column " + col + "
				// is " + headerColumns[col]);
				// if (headerColumns[col].equals("") || headerColumns[col] ==
				// null)
				// {
				// headerColumns[col] = "aaa";
				// }
				// this.audit.addLog(AuditLogStatus.SUCCESS, "xxx Column " + col
				// + " is " + headerColumns[col]);

			}
		}
		return headerColumns;
	}

	private ArrayList<String[]> extractSheetContents(Sheet sheet, Workbook wb, int startRow, int noOfRows, int startCol,
			int noOfColumns, boolean skipEmptyRows, boolean evaluateFormulas, String formatting, boolean debug)
			throws ModuleException {
		if (startRow >= noOfRows) {
			throw new ModuleException("Starting row is greater than last row of sheet");
		}
		this.audit.addLog(AuditLogStatus.SUCCESS, "Extracting Excel sheet contents");
		this.audit.addLog(AuditLogStatus.SUCCESS, "Start processing from row " + Integer.toString(startRow + 1));
		this.audit.addLog(AuditLogStatus.SUCCESS, "Start processing from column " + Integer.toString(startCol + 1));
		ArrayList<String[]> contents = new ArrayList<String[]>();
		Cell cell;
		int lastColumn = startCol + noOfColumns;
		String[] rowContent;
		String[] targetColumns;
		Row row;
		int emptyCol = 0;
		boolean contentFound = false;
		// Go through each row
		for (int rowNo = startRow; rowNo < noOfRows; rowNo++) {
			// this.audit.addLog(AuditLogStatus.SUCCESS, "Curr Row " + rowNo);
			row = sheet.getRow(rowNo);
			contentFound = false;
			if (row != null) {
				rowContent = new String[noOfColumns];
				// Go through each column cell of the current row
				emptyCol = 0;
				for (int colNo = startCol; colNo < lastColumn; colNo++) {
					// this.audit.addLog(AuditLogStatus.SUCCESS, "at col "
					// + colNo);
					cell = row.getCell(colNo);
					
					
					
					//if (cell != null) {

						rowContent[colNo - startCol] = retrieveCellContent(cell, wb, evaluateFormulas, formatting);

						// this.audit.addLog(AuditLogStatus.SUCCESS, "Curr col "
						// + colNo + " content " + rowContent[colNo -
						// startCol]);

						if (!this.skipRowsWithEmptyCol.equals("")) {
							targetColumns = this.skipRowsWithEmptyCol.split(",");
							for (int tg = 0; tg < targetColumns.length; tg++) {

								if (colNo == Integer.parseInt(targetColumns[tg])
										&& (rowContent[colNo - startCol] == null || rowContent[colNo - startCol] == "")) {
									emptyCol++;
									
										//this.audit.addLog(AuditLogStatus.SUCCESS,
										//		"empty Col" + colNo + " " + "total empty " + emptyCol);
									
								}
							}
						}
						if (rowContent[colNo - startCol] == null) {
							rowContent[colNo - startCol] = " ";
						} else {
							if (rowContent[colNo - startCol].indexOf(',') > -1) {
								rowContent[colNo - startCol] = '"' + rowContent[colNo - startCol] + '"';
							}
						}
						// this.audit.addLog(AuditLogStatus.SUCCESS, "Curr col
						// end" + colNo);
						// if (rowContent[colNo - startCol] != null) {
						contentFound = true;
						// }

						if (emptyCol == this.skipRowsWithEmptyColHits && !this.skipRowsWithEmptyCol.equals("")) {
							if (debug) {
								this.audit.addLog(AuditLogStatus.SUCCESS, "no further process due to empty Cols");
							}
							contentFound = false;
							break;
						}

					//}
					if (debug) {
						this.audit.addLog(AuditLogStatus.SUCCESS, "DEBUG Cell " + Integer.toString(rowNo + 1) + ":"
								+ Integer.toString(colNo + 1) + " - " + rowContent[colNo]);
					}
				}
				if (contentFound) {
					contents.add(rowContent);
				}
				rowContent = null;
			} else if (debug) {
				this.audit.addLog(AuditLogStatus.SUCCESS, "DEBUG Row " + Integer.toString(rowNo + 1) + " empty");
			}
			// Add empty rows if skip parameter set to NO
			if (!skipEmptyRows && !contentFound) {
				contents.add(new String[noOfColumns]);
			}
			// this.audit.addLog(AuditLogStatus.SUCCESS, "Curr Row End" +
			// rowNo);

		}
		if (contents.size() == 0)

		{
			throw new ModuleException("No rows with valid contents found");
		} else {
			return contents;
		}
	}

	private String retrieveCellContent(Cell cell, Workbook wb, boolean evaluateFormulas, String formatting) {
		evaluator = wb.getCreationHelper().createFormulaEvaluator();
		String cellContent = null;
		String cellContentTmp = null;
		
		if (cell == null)
		{
			return "";
		}
		
		int cellType = cell.getCellType();
		//this.audit.addLog(AuditLogStatus.SUCCESS,
		//		"cell pos " + cell.getRowIndex() + " " + cell.getColumnIndex() + " type " + cellType);
		switch (cellType) {
		case Cell.CELL_TYPE_BLANK:
			break;
		case Cell.CELL_TYPE_FORMULA:
			if (evaluateFormulas) {
				if (!this.evaluateFormulasExt) {
					cellContent = formatter.formatCellValue(cell, evaluator);
				} else {

					ecell = esheet.getCell(cell.getRowIndex(), cell.getColumnIndex());
					// cellContent = formatter.formatCellValue(ecell,
					// evaluator);
					//this.audit.addLog(AuditLogStatus.SUCCESS, "Eval Ext " + ecell.getCellType());
					try {
						cellContentTmp = Double.toString(ecell.getNumericCellValue());
						cellContent = cellContentTmp;
					} catch (Exception e) {

					}

					
					if (cellContent == null) {
						try {
							cellContentTmp = ecell.getStringCellValue();
							cellContent = cellContentTmp;
						} catch (Exception e) {

						}
					}
					
					if (cellContent == null) {
						try {
							cellContentTmp = Boolean.toString(ecell.getBooleanCellValue());
							cellContent = cellContentTmp;
						} catch (Exception e) {

						}
					}

					

				}
			} else {
				// Display the formula instead
				cellContent = cell.getCellFormula();
			}
			break;
		default:
			if (formatting.equalsIgnoreCase("excel")) {
				cellContent = formatter.formatCellValue(cell);
			} else if (formatting.equalsIgnoreCase("raw")) {
				// Display the raw cell contents
				switch (cellType) {
				case Cell.CELL_TYPE_NUMERIC:
					cellContent = Double.toString(cell.getNumericCellValue());
					break;
				case Cell.CELL_TYPE_STRING:
					cellContent = cell.getStringCellValue();
					break;
				case Cell.CELL_TYPE_BOOLEAN:
					cellContent = Boolean.toString(cell.getBooleanCellValue());
					break;
				}
			}
			break;
		}
		return cellContent;
	}

	private Node addElementToNode(Document doc, Node parentNode, String elementName) {
		Node element = doc.createElement(elementName);
		parentNode.appendChild(element);
		return element;
	}

	private Node addElementToNode(Document doc, Node parentNode, String elementName, String elementTextValue) {
		Node element = addElementToNode(doc, parentNode, elementName);
		if (elementTextValue != null) {
			element.appendChild(doc.createTextNode(elementTextValue));
		}
		return element;
	}
}